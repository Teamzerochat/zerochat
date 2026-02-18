package com.zerochat.app.domain.i2p

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAM v3.1 Bridge Client
 *
 * Communicates with the i2pd SAM bridge on localhost:7656 using the SAM protocol.
 * This is the standard way to create I2P streaming connections from any language.
 *
 * Protocol flow:
 * 1. HELLO VERSION    → Handshake with SAM bridge
 * 2. SESSION CREATE   → Create a streaming session, get our I2P Destination
 * 3. STREAM CONNECT   → Connect to a peer's I2P Destination (outbound)
 *    or STREAM ACCEPT  → Accept a connection from a peer (inbound)
 *
 * Reference: https://geti2p.net/en/docs/api/samv3
 */
@Singleton
class SamClient @Inject constructor() {

    companion object {
        private const val TAG = "SamClient"
        private const val SAM_HOST = "127.0.0.1"
        private const val SAM_PORT = 7656
        private const val SAM_VERSION = "3.1"
    }

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var localDestination: String? = null

    // References to keep control socket alive
    @Volatile
    private var controlSocketRef: Socket? = null

    /**
     * Create a new SAM streaming session.
     * Returns the local I2P destination (Base64 string).
     */
    suspend fun createSession(): String = withContext(Dispatchers.IO) {
        val id = "zerochat-${UUID.randomUUID().toString().take(8)}"

        // Connect control socket to SAM bridge
        val controlSocket = Socket(SAM_HOST, SAM_PORT)
        val input = controlSocket.getInputStream()
        val output = controlSocket.getOutputStream()

        try {
            // Step 1: HELLO handshake
            writeCommand(output, "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION")
            val helloReply = readLine(input)
            Log.d(TAG, "HELLO reply: $helloReply")

            if (!helloReply.contains("RESULT=OK")) {
                throw IOException("SAM HELLO failed: $helloReply")
            }

            // Step 2: Create session
            // TRANSIENT destination = ephemeral keys (new destination each session)
            // Use SIGNATURE_TYPE=7 (EdDSA) for modern crypto
            writeCommand(output, "SESSION CREATE STYLE=STREAM ID=$id DESTINATION=TRANSIENT SIGNATURE_TYPE=7")
            val sessionReply = readLine(input)
            Log.d(TAG, "SESSION reply: $sessionReply")

            if (!sessionReply.contains("RESULT=OK")) {
                throw IOException("SAM SESSION CREATE failed: $sessionReply")
            }

            // Parse DESTINATION from reply
            val dest = parseValue(sessionReply, "DESTINATION")
                ?: throw IOException("No DESTINATION in SESSION reply")

            sessionId = id
            localDestination = dest

            Log.i(TAG, "✓ SAM session created: $id")
            Log.i(TAG, "  Local destination: ${dest.take(32)}...")

            // Keep control socket alive (required by SAM protocol)
            // The session lives as long as this socket stays open
            controlSocketRef = controlSocket
            
            dest

        } catch (e: Exception) {
            controlSocket.close()
            throw e
        }
    }

    /**
     * Get the local I2P destination (Base64).
     * Must call createSession() first.
     */
    fun getLocalDestination(): String {
        return localDestination
            ?: throw IllegalStateException("No active SAM session. Call createSession() first.")
    }

    /**
     * Open an outbound stream to a peer's I2P destination.
     * Returns an I2PStream wrapper around the connection.
     *
     * Used by INITIATOR role.
     */
    suspend fun connectStream(peerDestination: String): I2PStream = withContext(Dispatchers.IO) {
        val id = sessionId ?: throw IllegalStateException("No active session")

        // Self-connect check
        if (peerDestination == localDestination) {
            throw IllegalArgumentException("Cannot connect to own I2P destination")
        }

        Log.i(TAG, "STREAM CONNECT to ${peerDestination.take(32)}...")

        // Create a NEW socket for the stream (separate from control socket)
        val streamSocket = Socket(SAM_HOST, SAM_PORT).apply {
            keepAlive = true
            tcpNoDelay = true
            soTimeout = 0 // Infinite timeout for long-lived streams
        }
        val input = streamSocket.getInputStream()
        val output = streamSocket.getOutputStream()

        try {
            // HELLO on the new stream socket
            writeCommand(output, "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION")
            val helloReply = readLine(input)
            if (!helloReply.contains("RESULT=OK")) {
                throw IOException("SAM HELLO (stream) failed: $helloReply")
            }

            // STREAM CONNECT
            writeCommand(output, "STREAM CONNECT ID=$id DESTINATION=$peerDestination SILENT=false")
            val connectReply = readLine(input)
            Log.d(TAG, "STREAM CONNECT reply: $connectReply")

            if (!connectReply.contains("RESULT=OK")) {
                val reason = parseValue(connectReply, "MESSAGE") ?: connectReply
                throw IOException("STREAM CONNECT failed: $reason")
            }

            Log.i(TAG, "✓ Stream connected to peer")

            // After STREAM CONNECT succeeds, the socket is now a raw data stream.
            // DO NOT use any buffered readers on this socket before this point!
            I2PStream(
                socket = streamSocket,
                inputStream = input,
                outputStream = output
            )

        } catch (e: Exception) {
            streamSocket.close()
            throw e
        }
    }

    /**
     * Accept an inbound stream from a peer.
     * Blocks until a peer connects.
     * Returns an I2PStream wrapper.
     *
     * Used by RESPONDER role.
     */
    suspend fun acceptStream(): I2PStream = withContext(Dispatchers.IO) {
        val id = sessionId ?: throw IllegalStateException("No active session")

        Log.i(TAG, "STREAM ACCEPT waiting for incoming connection...")

        // Create a NEW socket for accepting
        val acceptSocket = Socket(SAM_HOST, SAM_PORT).apply {
            keepAlive = true
            tcpNoDelay = true
            soTimeout = 0 // Infinite timeout for long-lived streams
        }
        val input = acceptSocket.getInputStream()
        val output = acceptSocket.getOutputStream()

        try {
            // HELLO on this socket
            writeCommand(output, "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION")
            val helloReply = readLine(input)
            if (!helloReply.contains("RESULT=OK")) {
                throw IOException("SAM HELLO (accept) failed: $helloReply")
            }

            // STREAM ACCEPT — blocks until peer connects
            writeCommand(output, "STREAM ACCEPT ID=$id SILENT=false")
            val acceptReply = readLine(input)
            Log.d(TAG, "STREAM ACCEPT reply: $acceptReply")

            if (!acceptReply.contains("RESULT=OK")) {
                val reason = parseValue(acceptReply, "MESSAGE") ?: acceptReply
                throw IOException("STREAM ACCEPT failed: $reason")
            }

            // Wait for incoming connection — SAM sends another line with peer's destination
            val peerLine = readLine(input)
            Log.i(TAG, "✓ Inbound stream accepted from: ${peerLine.take(32)}...")

            I2PStream(
                socket = acceptSocket,
                inputStream = input,
                outputStream = output
            )

        } catch (e: Exception) {
            acceptSocket.close()
            throw e
        }
    }

    /**
     * Close the SAM session and all streams.
     */
    fun close() {
        Log.i(TAG, "Closing SAM session: $sessionId")
        try {
            controlSocketRef?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing control socket", e)
        }
        controlSocketRef = null
        sessionId = null
        localDestination = null
    }

    // --- Protocol Helpers ---

    private fun writeCommand(output: OutputStream, command: String) {
        // Log.v(TAG, "SAM >>> $command")
        val bytes = (command + "\n").toByteArray(StandardCharsets.ISO_8859_1)
        output.write(bytes)
        output.flush()
    }

    /**
     * Read a line byte-by-byte to avoid buffering extra data.
     * This is critical because after the handshake, the stream becomes binary,
     * and a BufferedInputStream would eat the first bytes of the binary stream.
     */
    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) throw IOException("SAM connection closed unexpectedly")
            val c = b.toChar()
            if (c == '\n') break
            sb.append(c)
        }
        val line = sb.toString().trim()
        // Log.v(TAG, "SAM <<< $line")
        return line
    }

    /**
     * Parse a KEY=VALUE pair from a SAM reply string.
     * Handles both simple values and Base64 destinations (which may contain =).
     */
    private fun parseValue(reply: String, key: String): String? {
        val prefix = "$key="
        val idx = reply.indexOf(prefix)
        if (idx < 0) return null

        val start = idx + prefix.length
        if (start >= reply.length) return null

        // Find end: next space, or end of string
        val end = reply.indexOf(' ', start)
        return if (end < 0) reply.substring(start) else reply.substring(start, end)
    }
}
