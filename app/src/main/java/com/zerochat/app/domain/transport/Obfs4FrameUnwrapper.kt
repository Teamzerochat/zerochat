package com.zerochat.app.domain.transport

import android.util.Log
import com.google.crypto.tink.Aead
import java.util.concurrent.atomic.AtomicLong
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * Per-Frame obfs4 Decryption using ChaCha20-Poly1305 (Paper §6)
 *
 * The paper's design:
 * 1. SPAKE2+ handshake produces a shared secret
 * 2. Both peers derive identical 64-byte obfs4_state using HKDF-SHA256(shared_secret, "zerochat-obfs4-state-v1")
 * 3. Each ~1452-byte Nym message is one atomic unit.
 * 4. Per-frame encryption/decryption:
 *    - Key: first 32 bytes of obfs4_state
 *    - Nonce: 12 bytes derived from (per-message counter || next 12 bytes of obfs4_state)
 *    - Cipher: ChaCha20-Poly1305-AEAD, produces 1452-byte ciphertext (includes 16-byte tag)
 * 5. No cross-message buffering. No stream obfuscation handshake.
 * 6. On MAC failure, discard the frame — do not attempt recovery.
 *
 * CRITICAL: Each Nym message is a complete frame, not part of a stream.
 * No obfs4 wire protocol bytes should ever appear on Nym.
 */
class Obfs4FrameUnwrapper(private val obfs4State: ByteArray) {
    companion object {
        private const val TAG = "Obfs4FrameUnwrapper"
        private const val NYM_MESSAGE_SIZE = 1452
        private const val AEAD_TAG_SIZE = 16  // ChaCha20-Poly1305 produces 16-byte tag
        
        // Extract key material from 64-byte obfs4_state
        private const val KEY_SIZE = 32
        private const val NONCE_SEED_SIZE = 12
        // Remaining 20 bytes (64 - 32 - 12) reserved for future use
    }

    // Diagnostic tracking for MAC failures
    private val obfs4StateFingerprint = obfs4State.sliceArray(0 until 8)
        .joinToString("") { "%02x".format(it) }
    private val macFailureCount = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        require(obfs4State.size == 64) { "obfs4_state must be exactly 64 bytes" }
        
        // CRITICAL: Check that obfs4_state is not all zeros
        // If this fails, the HKDF input (shared secret) was not set before key derivation
        check(obfs4State.any { it != 0.toByte() }) {
            "obfs4_state is all zeros — HKDF input (shared secret) was not set before key derivation. " +
            "This is a critical bug in SPAKE2+ key material handling."
        }
        
        // DEBUG: Log first 8 bytes of obfs4_state for verification
        // Both INITIATOR and RESPONDER should log the same value if HKDF derivation is correct
        Log.d(TAG, "Initialized with obfs4_state[0..7]=$obfs4StateFingerprint (full 64 bytes)")
        
        // Also log bytes 32-39 (nonce seed portion)
        val nonceSeedPreview = obfs4State.sliceArray(32 until 40)
        val nonceSeedHex = nonceSeedPreview.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "  nonce_seed[0..7]=$nonceSeedHex")
    }

    // Per-message counter for nonce derivation (atomically incremented)
    private val messageCounter = AtomicLong(0L)

    /**
     * Decrypt one complete Nym message using ChaCha20-Poly1305.
     *
     * Architecture (Paper §6 + Nym Protocol):
     * - Input: exactly 1452-byte Nym message
     * - Format: [4-byte big-endian length] [ciphertext] [zero padding]
     * - Key: first 32 bytes of obfs4_state
     * - Nonce: 12 bytes derived from atomically-incremented counter + seed from obfs4_state
     * - Output: plaintext payload or null on MAC failure
     *
     * CRITICAL: Each message must be processed atomically. If MAC fails, the frame is
     * discarded — do not buffer or attempt recovery.
     *
     * @param ciphertext Exactly 1452-byte message from Nym (with length prefix, padded with zeros)
     * @return Decrypted payload or null if MAC fails
     */
    fun decodeFrame(ciphertext: ByteArray): ByteArray? {
        if (ciphertext.size != NYM_MESSAGE_SIZE) {
            Log.w(TAG, "Invalid ciphertext size: expected $NYM_MESSAGE_SIZE, got ${ciphertext.size}")
            return null
        }

        try {
            // Extract key and nonce material from obfs4_state
            val key = obfs4State.sliceArray(0 until KEY_SIZE)
            val nonceSeed = obfs4State.sliceArray(KEY_SIZE until KEY_SIZE + NONCE_SEED_SIZE)

            // CRITICAL: Counter must ONLY increment on successful decryption!
            // If we increment before attempting decryption, the receiver's counter gets ahead
            // of the sender's counter on retransmissions, causing persistent MAC failures.
            // Try decryption with the next counter value without committing to it yet.
            val candidateCounter = messageCounter.get() + 1
            
            // Construct 12-byte nonce per Paper §6:
            // Bytes 0-3: 4-byte message counter as little-endian uint32
            // Bytes 4-11: obfs4_state[32..40] (8 fixed bytes from derived key material)
            val nonce = ByteArray(12)
            // Little-endian encoding of counter as uint32
            val counterVal = candidateCounter.toInt()  // only use lower 32 bits
            nonce[0] = (counterVal and 0xFF).toByte()
            nonce[1] = ((counterVal shr 8) and 0xFF).toByte()
            nonce[2] = ((counterVal shr 16) and 0xFF).toByte()
            nonce[3] = ((counterVal shr 24) and 0xFF).toByte()
            // Fixed 8-byte nonce seed from obfs4_state[32..40]
            for (i in 0 until 8) {
                nonce[4 + i] = nonceSeed[i]
            }

            // FIX: Detect and strip zero-padding from the 1452-byte frame
            // Message format: [4-byte length (big-endian)] [encrypted_plaintext || auth_tag] [zero padding]
            // Find where padding starts by searching backwards for non-zero byte.
            var actualCiphertextEnd = ciphertext.size
            for (i in (ciphertext.size - 1) downTo 0) {
                if (ciphertext[i] != 0.toByte()) {
                    actualCiphertextEnd = i + 1
                    break
                }
            }
            
            // Sanity check: must be at least 4 (length field) + 16 (auth tag)
            if (actualCiphertextEnd < 20) {
                Log.w(TAG, "Message too short after unpadding: $actualCiphertextEnd bytes (expected min 20)")
                return null
            }
            
            // Extract length field (first 4 bytes, big-endian)
            val lengthField = ((ciphertext[0].toInt() and 0xFF) shl 24) or
                              ((ciphertext[1].toInt() and 0xFF) shl 16) or
                              ((ciphertext[2].toInt() and 0xFF) shl 8) or
                               (ciphertext[3].toInt() and 0xFF)
            
            // Verify length field is plausible
            val expectedEnd = 4 + lengthField  // 4-byte field + declared length
            if (lengthField < AEAD_TAG_SIZE || lengthField > actualCiphertextEnd - 4) {
                Log.w(TAG, "Invalid length field: $lengthField (actual non-zero: ${actualCiphertextEnd - 4} bytes)")
                return null
            }
            
            // Extract only the actual ciphertext portion (skip 4-byte length field)
            // This is [ciphertext || auth_tag] without the 4-byte length prefix
            val actualCiphertext = ciphertext.sliceArray(4 until expectedEnd)
            val paddingBytes = ciphertext.size - expectedEnd
            
            if (paddingBytes > 0) {
                Log.d(TAG, "Unpadded frame: removed $paddingBytes zero bytes (length_field=$lengthField, total=$expectedEnd)")
            }

            // ChaCha20-Poly1305 format: [nonce (12 bytes) || encrypted_data || tag (16 bytes)]
            // Note: actualCiphertext is [plaintext_encrypted || tag(16)], no length prefix
            val ciphertextWithNonce = nonce + actualCiphertext
            
            // Attempt decryption (may fail with MAC error)
            val cipher = createChaCha20Poly1305Cipher(key)
            val plaintext = cipher.decrypt(ciphertextWithNonce, null)

            // Decryption succeeded! NOW we can advance the counter
            messageCounter.set(candidateCounter)

            Log.d(TAG, "✅ Decrypted frame: ${plaintext.size} bytes (counter=$candidateCounter, ciphertext_size=$lengthField)")
            return plaintext

        } catch (e: Exception) {
            val failureCount = macFailureCount.incrementAndGet()
            Log.w(TAG, "❌ MAC failure #$failureCount with obfs4_state[$obfs4StateFingerprint]: ${e.message}")
            
            // After 3+ failures with same obfs4_state, likely a state mismatch
            if (failureCount >= 3) {
                Log.e(TAG, "⚠️ CRITICAL: Persistent MAC failures ($failureCount total) suggest obfs4_state mismatch!")
                Log.e(TAG, "  Possible causes:")
                Log.e(TAG, "  1. INITIATOR switched to final obfs4_state before RESPONDER")
                Log.e(TAG, "  2. Deterministic obfs4_state derivation differs between devices")
                Log.e(TAG, "  3. SPAKE2+ shared secret not synchronized")
            }
            // Counter is NOT incremented on failure — allows retry with same counter
            return null
        }
    }

    /**
     * Create a ChaCha20-Poly1305 AEAD cipher from a 32-byte key.
     *
     * Implements a custom AEAD wrapper using ChaCha20Poly1305 algorithm.
     *
     * @param key 32-byte key material
     * @return CustomAead cipher wrapper
     */
    private fun createChaCha20Poly1305Cipher(key: ByteArray): Aead {
        require(key.size == 32) { "ChaCha20-Poly1305 key must be 32 bytes" }
        return CustomChaCha20Poly1305Cipher(key)
    }

    /**
     * Custom AEAD implementation wrapper for ChaCha20Poly1305.
     * This adapts the standard JCE Cipher interface to Tink's Aead interface.
     * Uses Android's built-in AndroidOpenSSL provider (available on API 28+).
     */
    private inner class CustomChaCha20Poly1305Cipher(private val keyMaterial: ByteArray) : Aead {
        
        override fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray {
            // ChaCha20Poly1305 format expected: [nonce (12 bytes) || plaintext || tag (16 bytes)]
            if (plaintext.size < 12) {
                throw IllegalArgumentException("Plaintext with nonce must be at least 12 bytes")
            }

            try {
                // Extract nonce from first 12 bytes  
                val nonce = plaintext.sliceArray(0 until 12)
                val actualPlaintext = plaintext.sliceArray(12 until plaintext.size)
                
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                val secretKey = SecretKeySpec(keyMaterial, 0, keyMaterial.size, "ChaCha20")
                val ivSpec = IvParameterSpec(nonce)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
                cipher.update(aad) // Add AAD if provided
                return cipher.doFinal(actualPlaintext)
            } catch (e: Exception) {
                Log.w(TAG, "ChaCha20-Poly1305 encryption failed: ${e.message}")
                throw e
            }
        }

        override fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray {
            try {
                // ChaCha20Poly1305 format expected: [nonce (12 bytes) || ciphertext || tag (16 bytes)]
                // Total size = 12 + plaintext_size + 16, so minimum is 28 bytes
                if (ciphertext.size < 28) {
                    throw IllegalArgumentException("Ciphertext too short: ${ciphertext.size} bytes")
                }

                // Extract nonce from the beginning
                val nonce = ciphertext.sliceArray(0 until 12)
                val encryptedData = ciphertext.sliceArray(12 until ciphertext.size)

                // Use Android's built-in AndroidOpenSSL provider via default JCE provider chain
                // (no explicit provider specification needed — JCE will find AndroidOpenSSL on API 28+)
                val cipher = Cipher.getInstance("ChaCha20-Poly1305")
                val secretKey = SecretKeySpec(keyMaterial, 0, keyMaterial.size, "ChaCha20")
                val ivSpec = IvParameterSpec(nonce)

                cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
                if (aad != null) {
                    cipher.updateAAD(aad)
                }

                return cipher.doFinal(encryptedData)

            } catch (e: Exception) {
                Log.w(TAG, "ChaCha20-Poly1305 decryption failed: ${e.message}")
                throw e
            }
        }
    }
}
