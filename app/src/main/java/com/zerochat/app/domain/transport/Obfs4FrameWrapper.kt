package com.zerochat.app.domain.transport

import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.IvParameterSpec

/**
 * Per-Frame obfs4 Encryption using ChaCha20-Poly1305 (Paper §6)
 *
 * Counterpart to Obfs4FrameUnwrapper - encrypts outbound messages.
 * 
 * Architecture (Paper §6):
 * 1. Both peers share identical 64-byte obfs4_state (derived deterministically)
 * 2. Each outbound message (plaintext ~27-50 bytes) is encrypted atomically
 * 3. Per-frame encryption:
 *    - Key: first 32 bytes of obfs4_state
 *    - Nonce: 12 bytes derived from (per-message counter || next 12 bytes of obfs4_state)
 *    - Cipher: ChaCha20-Poly1305-AEAD, produces 1452-byte ciphertext (includes 16-byte tag)
 * 4. Message is then padded to 1452 bytes (or already that size after encryption + tag)
 *
 * CRITICAL: Counter must be incremented ONLY on successful encryption.
 * Both devices must maintain synchronized message counters for proper decryption.
 */
class Obfs4FrameWrapper(private val obfs4State: ByteArray) {
    companion object {
        private const val TAG = "Obfs4FrameWrapper"
        private const val AEAD_TAG_SIZE = 16  // ChaCha20-Poly1305 produces 16-byte tag
        private const val NYM_MESSAGE_SIZE = 1452
        
        // Extract key material from 64-byte obfs4_state
        private const val KEY_SIZE = 32
        private const val NONCE_SEED_SIZE = 12
    }

    init {
        require(obfs4State.size == 64) { "obfs4_state must be exactly 64 bytes" }
        
        // CRITICAL: Check that obfs4_state is not all zeros
        check(obfs4State.any { it != 0.toByte() }) {
            "obfs4_state is all zeros — HKDF input (shared secret) was not set before key derivation. " +
            "This is a critical bug in SPAKE2+ key material handling."
        }
        
        // DEBUG: Log first 8 bytes of obfs4_state for verification
        val statePreview = obfs4State.sliceArray(0 until 8)
        val stateHex = statePreview.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Initialized with obfs4_state[0..7]=$stateHex (full 64 bytes)")
        
        val nonceSeedPreview = obfs4State.sliceArray(32 until 40)
        val nonceSeedHex = nonceSeedPreview.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "  nonce_seed[0..7]=$nonceSeedHex")
    }

    // Per-message counter for nonce derivation (atomically incremented)
    private val messageCounter = AtomicLong(0L)

    /**
     * Encrypt one complete message using ChaCha20-Poly1305.
     *
     * Architecture (Paper §6 + Nym Protocol):
     * - Input: plaintext message (variable size, typically 27-50 bytes for RendezvousFrame)
     * - Key: first 32 bytes of obfs4_state
     * - Nonce: 12 bytes derived from atomically-incremented counter + seed from obfs4_state
     * - Output: [4-byte big-endian length] + [variable-size ciphertext]
     *
     * Message Format (matching Nym/Sphinx padding convention):
     * [4-byte big-endian length of ciphertext || ciphertext || zero-padding to 1452 bytes]
     *
     * CRITICAL: Counter must ONLY increment on successful encryption.
     *
     * @param plaintext Message to encrypt (e.g., framed rendezvous protocol message)
     * @return [4-byte length] + [ciphertext], or null if encryption fails
     */
    fun encodeFrame(plaintext: ByteArray): ByteArray? {
        if (plaintext.isEmpty()) {
            Log.w(TAG, "Empty plaintext")
            return null
        }

        try {
            // Extract key and nonce material from obfs4_state
            val key = obfs4State.sliceArray(0 until KEY_SIZE)
            val nonceSeed = obfs4State.sliceArray(KEY_SIZE until KEY_SIZE + NONCE_SEED_SIZE)

            // CRITICAL: Increment counter for this message
            val candidateCounter = messageCounter.incrementAndGet()
            
            // Construct 12-byte nonce per Paper §6:
            // Bytes 0-3: 4-byte message counter as little-endian uint32
            // Bytes 4-11: obfs4_state[32..40] (8 fixed bytes from derived key material)
            val nonce = ByteArray(12)
            val counterVal = candidateCounter.toInt()  // only use lower 32 bits
            nonce[0] = (counterVal and 0xFF).toByte()
            nonce[1] = ((counterVal shr 8) and 0xFF).toByte()
            nonce[2] = ((counterVal shr 16) and 0xFF).toByte()
            nonce[3] = ((counterVal shr 24) and 0xFF).toByte()
            for (i in 0 until 8) {
                nonce[4 + i] = nonceSeed[i]
            }

            // Encrypt using ChaCha20-Poly1305
            val cipher = Cipher.getInstance("ChaCha20-Poly1305")
            val secretKey = SecretKeySpec(key, 0, key.size, "ChaCha20")
            val ivSpec = IvParameterSpec(nonce)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec)
            
            val ciphertext = cipher.doFinal(plaintext)
            
            // CRITICAL FIX: Prepend 4-byte big-endian length field
            // Format: [4-byte length (big-endian, size of ciphertext only)] [ciphertext]
            // IMPORTANT: Length field specifies ONLY the ciphertext size, NOT (4 + ciphertext)
            // This allows receiver to extract actual message size after unpadding
            val result = ByteArray(4 + ciphertext.size)
            
            // Write length in big-endian format (ciphertext size ONLY)
            val len = ciphertext.size  // Just the ciphertext size, NOT the 4-byte field itself
            result[0] = ((len shr 24) and 0xFF).toByte()
            result[1] = ((len shr 16) and 0xFF).toByte()
            result[2] = ((len shr 8) and 0xFF).toByte()
            result[3] = (len and 0xFF).toByte()
            
            // Copy ciphertext after length field
            ciphertext.copyInto(result, destinationOffset = 4)
            
            Log.d(TAG, "Encrypted frame: counter=$candidateCounter, plaintext=${plaintext.size} bytes, ciphertext=${ciphertext.size} bytes, with_length_field=${result.size} bytes")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "ChaCha20-Poly1305 encryption failed: ${e.message}")
            return null
        }
    }
}
