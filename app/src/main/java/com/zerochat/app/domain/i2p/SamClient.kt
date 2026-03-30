package com.zerochat.app.domain.i2p

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val HANDSHAKE_TIMEOUT_MS = 30_000 // FIX #6: 30s timeout for SAM protocol phase
        private const val SESSION_RETRY_TIMEOUT_MS = 60_000L // Total retry window for HELLO
        private const val INITIAL_BACKOFF_MS = 1_000L        // 1s → 2s → 4s exponential
        private const val MAX_BACKOFF_MS = 4_000L
        
        // BUG 5 FIX: Random suffix for session IDs to prevent conflicts between app restarts
        // Generated once at class load time, used for all sessions in this app instance
        private val SESSION_RANDOM_SUFFIX = UUID.randomUUID().toString().replace("-", "").take(8)
    }

    // FIX #5 / #7: Mutex guards session creation, access, and closure
    private val sessionMutex = Mutex()

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
     * BUG 5 FIX: Uses random session ID suffix and explicitly closes existing sessions.
     */
    suspend fun createSession(): String = sessionMutex.withLock {
        // FIX #12: If a stale session exists, clean it up first
        closeInternal()

        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + SESSION_RETRY_TIMEOUT_MS
            var backoffMs = INITIAL_BACKOFF_MS
            var lastException: Exception? = null

            // Retry with exponential backoff up to 60s
            while (System.currentTimeMillis() < deadline) {
                // BUG 5 FIX: Session ID with random suffix to prevent conflicts between app restarts
                // Format: zc-<random8>-<attempt4> e.g., zc-a1b2c3d4-0001
                val attemptNum = (SESSION_RETRY_TIMEOUT_MS - (deadline - System.currentTimeMillis())).toInt() / 1000
                val id = "zc-${SESSION_RANDOM_SUFFIX}-${attemptNum.toString().padStart(4, '0')}"
                var controlSocket: Socket? = null
                try {
                    controlSocket = Socket(SAM_HOST, SAM_PORT).apply {
                        soTimeout = HANDSHAKE_TIMEOUT_MS
                    }
                    val input = controlSocket.getInputStream()
                    val output = controlSocket.getOutputStream()

                    // Step 1: HELLO handshake
                    writeCommand(output, "HELLO VERSION MIN=$SAM_VERSION MAX=$SAM_VERSION")
                    val helloReply = readLine(input)
                    Log.d(TAG, "HELLO reply: $helloReply")

                    if (!helloReply.contains("RESULT=OK")) {
                        throw IOException("SAM HELLO failed: $helloReply")
                    }

                    // BUG 1 FIX: After successful HELLO, check i2pd router is tunnel-ready
                    // before attempting SESSION CREATE. HELLO only confirms SAM protocol
                    // version, not that router tunnels are ready to allocate sessions.
                    Log.d(TAG, "Checking router tunnel readiness...")
                    val routerReady = I2PRouterService.waitForRouterTunnelReady(timeoutMs = 30_000L)
                    if (!routerReady) {
                        Log.w(TAG, "Router not tunnel-ready yet, will retry...")
                        controlSocket.close()
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        continue
                    }
                    Log.d(TAG, "Router tunnel-ready, proceeding to SESSION CREATE")

                    // Step 2: SESSION CREATE
                    writeCommand(output, "SESSION CREATE STYLE=STREAM ID=$id DESTINATION=TRANSIENT SIGNATURE_TYPE=7")
                    val sessionReply = readLine(input)
                    Log.d(TAG, "SESSION reply: $sessionReply")

                    if (sessionReply.contains("DUPLICATED_ID")) {
                        // Stale session with this ID — close socket and retry immediately with new ID
                        Log.w(TAG, "SESSION DUPLICATED_ID for $id, retrying with new ID")
                        controlSocket.close()
                        delay(backoffMs)
                        backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                        continue
                    }

                    if (!sessionReply.contains("RESULT=OK")) {
                        throw IOException("SAM SESSION CREATE failed: $sessionReply")
                    }

                    val dest = parseValue(sessionReply, "DESTINATION")
                        ?: throw IOException("No DESTINATION in SESSION reply")

                    sessionId = id
                    localDestination = dest

                    Log.i(TAG, "✓ SAM session created: $id")
                    Log.i(TAG, "  Local destination: ${dest.take(32)}...")

                    controlSocket.soTimeout = 0
                    controlSocketRef = controlSocket

                    return@withContext dest

                } catch (e: Exception) {
                    lastException = e
                    controlSocket?.close()

                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break

                    Log.w(TAG, "Session attempt failed (${e.message}), retrying in ${backoffMs}ms (${remaining}ms left)")
                    delay(backoffMs.coerceAtMost(remaining))
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }

            throw IOException("SAM session creation failed after ${SESSION_RETRY_TIMEOUT_MS / 1000}s", lastException)
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
        // R6 FIX: Snapshot sessionId under mutex to prevent reading null if close() races
        val id = sessionMutex.withLock {
            sessionId ?: throw IllegalStateException("No active session")
        }

        // Self-connect check
        if (peerDestination == localDestination) {
            throw IllegalArgumentException("Cannot connect to own I2P destination")
        }

        Log.i(TAG, "STREAM CONNECT to ${peerDestination.take(32)}...")

        // Create a NEW socket for the stream (separate from control socket)
        val streamSocket = Socket(SAM_HOST, SAM_PORT).apply {
            keepAlive = true
            tcpNoDelay = true
            soTimeout = HANDSHAKE_TIMEOUT_MS // FIX #6: timeout during SAM command phase
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

            // FIX #6: Reset to infinite timeout for long-lived data stream
            streamSocket.soTimeout = 0

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
        // R6 FIX: Snapshot sessionId under mutex to prevent reading null if close() races
        val id = sessionMutex.withLock {
            sessionId ?: throw IllegalStateException("No active session")
        }

        Log.i(TAG, "STREAM ACCEPT waiting for incoming connection...")

        // Create a NEW socket for accepting
        val acceptSocket = Socket(SAM_HOST, SAM_PORT).apply {
            keepAlive = true
            tcpNoDelay = true
            soTimeout = HANDSHAKE_TIMEOUT_MS // FIX #6: timeout during SAM command phase
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

            // STREAM ACCEPT
            // R2 FIX: Keep handshake timeout through the ACCEPT command reply
            writeCommand(output, "STREAM ACCEPT ID=$id SILENT=false")
            val acceptReply = readLine(input)
            Log.d(TAG, "STREAM ACCEPT reply: $acceptReply")

            if (!acceptReply.contains("RESULT=OK")) {
                val reason = parseValue(acceptReply, "MESSAGE") ?: acceptReply
                throw IOException("STREAM ACCEPT failed: $reason")
            }

            // R2 FIX: Reset to infinite timeout ONLY for peer-wait (peer may take a while)
            acceptSocket.soTimeout = 0

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
     * FIX #7: Thread-safe via sessionMutex.
     */
    suspend fun close() {
        sessionMutex.withLock {
            closeInternal()
        }
    }

    /**
     * Non-suspend close for use from synchronized blocks (e.g. I2PRouterService reset).
     * Only closes the socket; does not acquire the coroutine mutex.
     */
    fun closeBlocking() {
        closeInternal()
    }

    /**
     * Internal close — caller MUST hold sessionMutex.
     */
    private fun closeInternal() {
        val sid = sessionId ?: return
        Log.i(TAG, "Closing SAM session: $sid")
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
