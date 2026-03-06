package com.zerochat.app.domain.i2p

import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Encrypted Channel — Application-layer encryption over I2P stream.
 *
 * Uses session key held in Rust memory (via FFI session handle).
 * Session key NEVER touches the JVM heap.
 *
 * Wire format per message:
 * [4 bytes: length (big-endian)] [24 bytes: nonce] [N bytes: ciphertext + 16-byte tag]
 *
 * So total wire bytes = 4 + 24 + plaintext.size + 16
 */
class EncryptedChannel(
    private val sessionHandle: ULong,
    private val stream: I2PStream
) {
    companion object {
        private const val TAG = "EncryptedChannel"
        private const val NONCE_SIZE = 24   // XSalsa20 uses 24-byte nonce
        private const val TAG_SIZE = 16     // Poly1305 tag
        private const val LENGTH_SIZE = 4   // uint32 big-endian
    }

    // FIX #11: AtomicBoolean for thread-safe double-close prevention
    private val closed = AtomicBoolean(false)

    // FIX #9: Serialize send() and receive() to prevent interleaved wire frames
    private val sendLock = ReentrantLock()
    private val receiveLock = ReentrantLock()

    /**
     * Send an encrypted message over the I2P stream.
     * Encryption happens in Rust via FFI — session key never leaves Rust memory.
     *
     * @param plaintext The plaintext message bytes
     */
    fun send(plaintext: ByteArray) {
        if (closed.get()) throw IllegalStateException("Channel closed")

        // FIX #9: Only one coroutine can write a frame at a time
        sendLock.withLock {
            // Encrypt via Rust FFI — returns [nonce (24)] [ciphertext + tag (N+16)]
            val encrypted = uniffi.nym_transport.sessionEncryptWrapper(
                sessionHandle,
                plaintext.map { it.toUByte() }
            ).map { it.toByte() }.toByteArray()

            // Frame: [length (4B)] [encrypted payload]
            val frame = ByteBuffer.allocate(LENGTH_SIZE + encrypted.size)
            frame.putInt(encrypted.size)
            frame.put(encrypted, 0, encrypted.size)

            stream.write(frame.array())
            Log.v(TAG, "Sent ${plaintext.size} bytes (wire: ${frame.capacity()} bytes)")
        }
    }

    /**
     * Receive and decrypt one message from the I2P stream.
     * Decryption happens in Rust via FFI — session key never leaves Rust memory.
     * Blocks until a complete message is received.
     *
     * @return Decrypted plaintext, or null if stream closed/error
     */
    fun receive(): ByteArray? {
        if (closed.get()) return null

        // FIX #9: Only one coroutine can read a frame at a time
        return receiveLock.withLock {
            // Read length header (4 bytes)
            val lenBytes = stream.readFully(LENGTH_SIZE) ?: return@withLock null
            val payloadLen = ByteBuffer.wrap(lenBytes).int

            if (payloadLen < NONCE_SIZE + TAG_SIZE || payloadLen > 1_000_000) {
                Log.w(TAG, "Invalid payload length: $payloadLen")
                return@withLock null
            }

            // Read nonce + ciphertext
            val payload = stream.readFully(payloadLen) ?: return@withLock null

            // Decrypt via Rust FFI
            try {
                val plaintext = uniffi.nym_transport.sessionDecryptWrapper(
                    sessionHandle,
                    payload.map { it.toUByte() }
                ).map { it.toByte() }.toByteArray()

                Log.v(TAG, "Received ${plaintext.size} bytes")
                plaintext
            } catch (e: Exception) {
                Log.w(TAG, "Decryption failed: ${e.message}")
                null
            }
        }
    }

    /**
     * Check if the channel is still usable.
     */
    fun isConnected(): Boolean = !closed.get() && stream.isConnected()

    /**
     * Close the channel and underlying stream.
     * Destroys the session handle in Rust (zeroizes key).
     * FIX #11: compareAndSet guarantees exactly-once execution.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        stream.close()

        // Destroy session in Rust — key is zeroized there
        try {
            uniffi.nym_transport.sessionDestroyWrapper(sessionHandle)
        } catch (e: Exception) {
            // Session may already be destroyed by ConnectionManager teardown
            Log.d(TAG, "Session already destroyed: ${e.message}")
        }
        Log.d(TAG, "Channel closed, session destroyed")
    }
}
