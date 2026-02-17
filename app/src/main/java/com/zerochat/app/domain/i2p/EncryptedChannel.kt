package com.zerochat.app.domain.i2p

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import java.nio.ByteBuffer

/**
 * Encrypted Channel — Application-layer encryption over I2P stream.
 *
 * Uses XChaCha20-Poly1305 (via lazysodium) for authenticated encryption.
 * This provides an independent layer of encryption on top of I2P's
 * tunnel-level encryption, using the session key derived from SPAKE2.
 *
 * Wire format per message:
 * [4 bytes: length (big-endian)] [24 bytes: nonce] [N bytes: ciphertext + 16-byte tag]
 *
 * So total wire bytes = 4 + 24 + plaintext.size + 16
 */
class EncryptedChannel(
    private val sessionKey: ByteArray,
    private val stream: I2PStream
) {
    companion object {
        private const val TAG = "EncryptedChannel"
        private const val NONCE_SIZE = 24   // XChaCha20 uses 24-byte nonce
        private const val TAG_SIZE = 16     // Poly1305 tag
        private const val LENGTH_SIZE = 4   // uint32 big-endian
    }

    private val sodium = SodiumAndroid()
    private val lazySodium = LazySodiumAndroid(sodium)

    @Volatile
    private var closed = false

    /**
     * Send an encrypted message over the I2P stream.
     *
     * @param plaintext The plaintext message bytes
     */
    fun send(plaintext: ByteArray) {
        if (closed) throw IllegalStateException("Channel closed")

        // Generate random nonce (24 bytes for XChaCha20)
        val nonce = ByteArray(NONCE_SIZE)
        sodium.randombytes_buf(nonce, NONCE_SIZE)

        // Encrypt: ciphertext includes 16-byte Poly1305 tag appended
        val ciphertext = ByteArray(plaintext.size + TAG_SIZE)
        val result = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
            ciphertext,
            null,   // ciphertext length output (not needed)
            plaintext,
            plaintext.size.toLong(),
            null,   // additional data
            0L,     // additional data length
            null,   // nsec (unused)
            nonce,
            sessionKey
        )

        if (result != 0) {
            throw SecurityException("Encryption failed")
        }

        // Frame: [length (4B)] [nonce (24B)] [ciphertext+tag]
        val totalPayload = NONCE_SIZE + ciphertext.size
        val frame = ByteBuffer.allocate(LENGTH_SIZE + totalPayload)
        frame.putInt(totalPayload)
        frame.put(nonce)
        frame.put(ciphertext)

        stream.write(frame.array())
        Log.v(TAG, "Sent ${plaintext.size} bytes (wire: ${frame.capacity()} bytes)")
    }

    /**
     * Receive and decrypt one message from the I2P stream.
     * Blocks until a complete message is received.
     *
     * @return Decrypted plaintext, or null if stream closed/error
     */
    fun receive(): ByteArray? {
        if (closed) return null

        // Read length header (4 bytes)
        val lenBytes = stream.readFully(LENGTH_SIZE) ?: return null
        val payloadLen = ByteBuffer.wrap(lenBytes).int

        if (payloadLen < NONCE_SIZE + TAG_SIZE || payloadLen > 1_000_000) {
            Log.w(TAG, "Invalid payload length: $payloadLen")
            return null
        }

        // Read nonce + ciphertext
        val payload = stream.readFully(payloadLen) ?: return null

        val nonce = payload.copyOfRange(0, NONCE_SIZE)
        val ciphertext = payload.copyOfRange(NONCE_SIZE, payloadLen)

        // Decrypt
        val plaintext = ByteArray(ciphertext.size - TAG_SIZE)
        val result = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
            plaintext,
            null,       // plaintext length output
            null,       // nsec (unused)
            ciphertext,
            ciphertext.size.toLong(),
            null,       // additional data
            0L,         // additional data length
            nonce,
            sessionKey
        )

        if (result != 0) {
            Log.w(TAG, "Decryption failed (auth tag mismatch)")
            return null
        }

        Log.v(TAG, "Received ${plaintext.size} bytes")
        return plaintext
    }

    /**
     * Check if the channel is still usable.
     */
    fun isConnected(): Boolean = !closed && stream.isConnected()

    /**
     * Close the channel and underlying stream.
     * Wipes the session key from memory.
     */
    fun close() {
        if (closed) return
        closed = true
        stream.close()

        // Secure-wipe session key
        for (i in sessionKey.indices) {
            sessionKey[i] = 0
        }
        Log.d(TAG, "Channel closed, session key wiped")
    }
}
