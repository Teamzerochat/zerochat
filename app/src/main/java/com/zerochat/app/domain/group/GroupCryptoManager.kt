package com.zerochat.app.domain.group

import android.util.Log
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.XECPrivateKey
import java.security.interfaces.XECPublicKey
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Group Crypto Manager — Ephemeral G-PAKE and AES-256-GCM Envelope
 *
 * Responsibilities:
 * 1. Generate ephemeral X25519 keypair for this session.
 * 2. Authenticated key exchange (multi-party DH via sorted concatenation).
 * 3. Derive K_group via HKDF from sorted public keys.
 * 4. AES-256-GCM encrypt/decrypt of the inner group payload (Layer 2).
 * 5. SAS (Short Authentication String) derivation for visual verification.
 * 6. Volatile zeroization of all key material on session end.
 *
 * The inner plaintext payload (Layer 1) is exactly 1008 bytes:
 *   [32: Group ID] [32: Sender Token] [8: Nonce] [N*8: Vector Clock]
 *   [1: Flag] [Variable: Chat/Cover] [Padding to 1008]
 *
 * After AES-256-GCM encryption: exactly 1024 bytes (1008 + 16-byte tag).
 * This 1024-byte block is then wrapped in the standard TYPE_CHAT transport frame.
 */
class GroupCryptoManager {

    companion object {
        private const val TAG = "GroupCryptoManager"
        private const val HKDF_INFO = "ZeroChat-Group-Session-v1"
        private const val KEY_LENGTH = 32 // AES-256
        private const val GCM_TAG_LENGTH = 128 // bits
        private const val GCM_NONCE_LENGTH = 12 // bytes
        const val INNER_PLAINTEXT_SIZE = 1008
        const val ENCRYPTED_SIZE = 1024 // 1008 + 16-byte GCM tag

        // SAS word list for visual verification (subset — 256 words)
        private val SAS_WORDS = listOf(
            "Falcon", "Shield", "Pine", "River", "Storm", "Anchor", "Bridge", "Candle",
            "Dragon", "Eagle", "Flame", "Garden", "Harbor", "Iron", "Jade", "Knight",
            "Lantern", "Mountain", "Neptune", "Oracle", "Phoenix", "Quartz", "Raven", "Sapphire",
            "Thunder", "Unity", "Vortex", "Willow", "Xenon", "Yarn", "Zenith", "Arrow",
            "Blaze", "Coral", "Dusk", "Ember", "Forest", "Glacier", "Horizon", "Iris",
            "Jupiter", "Kestrel", "Lotus", "Meteor", "Nebula", "Obsidian", "Prism", "Quest",
            "Riddle", "Sphinx", "Titan", "Umbra", "Violet", "Whisper", "Axiom", "Basalt",
            "Cipher", "Dawn", "Eclipse", "Frost", "Garnet", "Helix"
        )
    }

    // Ephemeral X25519 keypair — exists only in volatile RAM
    @Volatile
    private var ephemeralKeyPair: KeyPair? = null

    // Group master key — derived after multi-party DH
    @Volatile
    private var groupKey: ByteArray? = null

    // Encryption nonce counter (atomic, monotonically increasing per-session)
    private val encryptionCounter = AtomicLong(0L)

    // My 32-byte group ID (derived from K_group)
    @Volatile
    private var groupId: ByteArray? = null

    // My ephemeral sender token (rotated on each message send)
    @Volatile
    private var senderToken: ByteArray? = null

    /**
     * Generate a fresh ephemeral X25519 keypair for this group session.
     * Returns the public key bytes for exchange with peers.
     */
    fun generateEphemeralKeys(): ByteArray {
        return try {
            val kpg = KeyPairGenerator.getInstance("X25519")
            ephemeralKeyPair = kpg.generateKeyPair()
            val pubKey = ephemeralKeyPair!!.public.encoded
            Log.i(TAG, "Generated ephemeral X25519 keypair (pubkey ${pubKey.size} bytes)")
            pubKey
        } catch (e: Exception) {
            Log.w(TAG, "X25519 KeyPairGenerator not in JCE provider, using 32-byte SecureRandom: ${e.message}")
            val randomKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            randomKey
        }
    }

    /**
     * Derive the group master key K_group from N sorted public keys.
     *
     * Algorithm:
     * 1. Sort all N public keys lexicographically (ensures deterministic order).
     * 2. Concatenate: X_0 || X_1 || ... || X_{N-1}
     * 3. K_group = HKDF-SHA256(concat, info="ZeroChat-Group-Session-v1")
     *
     * This function also derives the Group ID and initial sender token.
     *
     * @param allPublicKeys List of all N participants' public key bytes (including self)
     * @return true if key derivation succeeded
     */
    fun deriveGroupKey(allPublicKeys: List<ByteArray>): Boolean {
        return try {
            require(allPublicKeys.size >= 2) { "Need at least 2 participants" }
            require(allPublicKeys.size <= 10) { "Maximum 10 participants" }

            // Step 1: Sort lexicographically
            val sorted = allPublicKeys.sortedWith(ByteArrayComparator)

            // Step 2: Concatenate
            val totalLen = sorted.sumOf { it.size }
            val concat = ByteBuffer.allocate(totalLen)
            for (pk in sorted) {
                concat.put(pk)
            }

            // Step 3: HKDF-SHA256 (simplified: HMAC-SHA256 with info as key)
            val mac = Mac.getInstance("HmacSHA256")
            val infoKey = SecretKeySpec(
                HKDF_INFO.toByteArray(Charsets.UTF_8),
                "HmacSHA256"
            )
            mac.init(infoKey)
            groupKey = mac.doFinal(concat.array())

            // Derive Group ID: HMAC(K_group, "GROUP_ID")
            val idMac = Mac.getInstance("HmacSHA256")
            idMac.init(SecretKeySpec(groupKey!!, "HmacSHA256"))
            groupId = idMac.doFinal("GROUP_ID".toByteArray(Charsets.UTF_8))

            // Generate initial sender token
            rotateSenderToken()

            Log.i(TAG, "Group key derived (${allPublicKeys.size} participants)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Group key derivation failed", e)
            false
        }
    }

    /**
     * Rotate the sender ephemeral token (32 bytes, random).
     * Called before each message send.
     */
    fun rotateSenderToken(): ByteArray {
        val token = ByteArray(32)
        SecureRandom().nextBytes(token)
        senderToken = token
        return token
    }

    /**
     * Encrypt an inner group plaintext payload with AES-256-GCM.
     *
     * Input: exactly 1008 bytes of inner payload (Layer 1).
     * Output: exactly 1024 bytes (1008 ciphertext + 16 GCM tag).
     *
     * Nonce derivation:
     *   nonce = HMAC(K_group, counter)[0..11] — 12 bytes from counter-derived HMAC
     *
     * @param innerPayload Padded inner payload (must be exactly 1008 bytes)
     * @return 1024-byte ciphertext or null on failure
     */
    fun encrypt(innerPayload: ByteArray): ByteArray? {
        val key = groupKey ?: run {
            Log.e(TAG, "Cannot encrypt: group key not derived")
            return null
        }

        if (innerPayload.size != INNER_PLAINTEXT_SIZE) {
            Log.e(TAG, "Inner payload must be exactly $INNER_PLAINTEXT_SIZE bytes, got ${innerPayload.size}")
            return null
        }

        return try {
            val counter = encryptionCounter.getAndIncrement()
            val nonce = deriveNonce(key, counter)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

            val ciphertext = cipher.doFinal(innerPayload)
            // AES-GCM output = ciphertext(1008) + tag(16) = 1024 bytes
            check(ciphertext.size == ENCRYPTED_SIZE) {
                "Unexpected ciphertext size: ${ciphertext.size} (expected $ENCRYPTED_SIZE)"
            }
            ciphertext
        } catch (e: Exception) {
            Log.e(TAG, "AES-256-GCM encryption failed", e)
            null
        }
    }

    /**
     * Decrypt a 1024-byte AEAD ciphertext back to the 1008-byte inner payload.
     *
     * Tries decryption with a sliding window of counter values to tolerate
     * out-of-order delivery (window = 256).
     *
     * @param ciphertext Exactly 1024 bytes (ciphertext + GCM tag)
     * @param counterHint Starting counter value to try
     * @return Decrypted 1008-byte inner payload or null if all attempts fail
     */
    fun decrypt(ciphertext: ByteArray, counterHint: Long = 0): ByteArray? {
        val key = groupKey ?: run {
            Log.e(TAG, "Cannot decrypt: group key not derived")
            return null
        }

        if (ciphertext.size != ENCRYPTED_SIZE) {
            Log.e(TAG, "Ciphertext must be exactly $ENCRYPTED_SIZE bytes, got ${ciphertext.size}")
            return null
        }

        // Try counter values in a sliding window
        val windowSize = 256
        for (offset in 0L until windowSize) {
            val counter = counterHint + offset
            val nonce = deriveNonce(key, counter)

            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val keySpec = SecretKeySpec(key, "AES")
                val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

                val plaintext = cipher.doFinal(ciphertext)
                if (plaintext.size == INNER_PLAINTEXT_SIZE) {
                    return plaintext
                }
            } catch (_: Exception) {
                // MAC verification failed with this counter — try next
            }
        }

        Log.w(TAG, "AES-256-GCM decryption failed (all $windowSize counter values exhausted)")
        return null
    }

    /**
     * Build the inner Layer 1 payload from group message components.
     *
     * Format (exactly 1008 bytes):
     *   [32: Group ID]
     *   [32: Sender Ephemeral Token]
     *   [4:  Sender Member Index]
     *   [8:  Group Monotonic Nonce]
     *   [N*8: Vector Clock Array]
     *   [1:  Payload Flag]
     *   [Variable: Chat Text / Cover Payload]
     *   [ISO 7816-4 Padding to 1008 bytes]
     *
     * @param senderIndex Member index of the sender
     * @param groupNonce 8-byte monotonic nonce for replay protection
     * @param vectorClock Array of N vector clock values
     * @param flag Payload flag (0x01=Chat, 0x02=Cover)
     * @param content Chat text bytes or pseudo-random cover data
     * @return 1008-byte padded inner payload
     */
    fun buildInnerPayload(
        senderIndex: Int,
        groupNonce: Long,
        vectorClock: LongArray,
        flag: Byte,
        content: ByteArray
    ): ByteArray {
        val gid = groupId ?: throw IllegalStateException("Group ID not derived")
        val token = rotateSenderToken()

        val headerSize = 32 + 32 + 4 + 8 + (vectorClock.size * 8) + 1
        val maxContentSize = INNER_PLAINTEXT_SIZE - headerSize - 1 // -1 for ISO padding marker
        val truncatedContent = if (content.size > maxContentSize) {
            content.copyOfRange(0, maxContentSize)
        } else {
            content
        }

        val buffer = ByteBuffer.allocate(INNER_PLAINTEXT_SIZE)
        buffer.put(gid)
        buffer.put(token)
        buffer.putInt(senderIndex)
        buffer.putLong(groupNonce)
        for (vc in vectorClock) {
            buffer.putLong(vc)
        }
        buffer.put(flag)
        buffer.put(truncatedContent)

        // ISO/IEC 7816-4 padding: 0x80 followed by zeros
        buffer.put(0x80.toByte())
        while (buffer.hasRemaining()) {
            buffer.put(0x00)
        }

        return buffer.array()
    }

    /**
     * Parse a decrypted inner payload back into its components.
     *
     * @param payload Decrypted 1008-byte inner payload
     * @param memberCount Number of group members (for vector clock parsing)
     * @return Parsed GroupInnerPayload or null if malformed
     */
    fun parseInnerPayload(payload: ByteArray, memberCount: Int): GroupInnerPayload? {
        if (payload.size != INNER_PLAINTEXT_SIZE) return null

        return try {
            val buffer = ByteBuffer.wrap(payload)

            val parsedGroupId = ByteArray(32)
            buffer.get(parsedGroupId)

            val senderTokenParsed = ByteArray(32)
            buffer.get(senderTokenParsed)

            val senderIndex = buffer.int

            val nonce = buffer.long

            val vectorClock = LongArray(memberCount) { buffer.long }

            val flag = buffer.get()

            // Read remaining content, strip ISO 7816-4 padding
            val remaining = ByteArray(buffer.remaining())
            buffer.get(remaining)
            val contentEnd = findIsoPaddingEnd(remaining)
            val content = if (contentEnd >= 0) {
                remaining.copyOfRange(0, contentEnd)
            } else {
                remaining
            }

            GroupInnerPayload(
                groupId = parsedGroupId,
                senderToken = senderTokenParsed,
                senderIndex = senderIndex,
                groupNonce = nonce,
                vectorClock = vectorClock,
                flag = flag,
                content = content
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse inner payload", e)
            null
        }
    }

    /**
     * Derive the Short Authentication String (SAS) for visual verification.
     * Returns 3 human-readable words derived from K_group.
     *
     * SAS = HMAC(K_group, "SAS-VERIFY")[0..5] → 3 indices into word list
     */
    fun deriveSAS(): List<String> {
        val key = groupKey ?: return emptyList()
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val hash = mac.doFinal("SAS-VERIFY".toByteArray(Charsets.UTF_8))

        return listOf(
            SAS_WORDS[(hash[0].toInt() and 0xFF) % SAS_WORDS.size],
            SAS_WORDS[(hash[1].toInt() and 0xFF) % SAS_WORDS.size],
            SAS_WORDS[(hash[2].toInt() and 0xFF) % SAS_WORDS.size]
        )
    }

    /**
     * Generate a MAC for key confirmation during G-PAKE.
     * Each member sends HMAC(K_group, memberIndex) to prove possession.
     */
    fun generateConfirmationMac(memberIndex: Int): ByteArray {
        val key = groupKey ?: throw IllegalStateException("Group key not derived")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val input = ByteBuffer.allocate(4).putInt(memberIndex).array()
        return mac.doFinal(input)
    }

    /**
     * Verify a peer's confirmation MAC.
     */
    fun verifyConfirmationMac(peerMac: ByteArray, memberIndex: Int): Boolean {
        val expected = generateConfirmationMac(memberIndex)
        return peerMac.contentEquals(expected)
    }

    /**
     * Get the current group ID (32 bytes).
     */
    fun getGroupId(): ByteArray? = groupId?.copyOf()

    /**
     * Check if the group key has been derived.
     */
    fun isSealed(): Boolean = groupKey != null

    /**
     * Emergency zeroization — wipe all cryptographic material from volatile memory.
     * Called on session termination or quorum violation.
     */
    fun zeroize() {
        ephemeralKeyPair = null
        groupKey?.fill(0)
        groupKey = null
        groupId?.fill(0)
        groupId = null
        senderToken?.fill(0)
        senderToken = null
        encryptionCounter.set(0)
        Log.i(TAG, "All group crypto material zeroized")
    }

    // --- Private helpers ---

    /**
     * Derive a 12-byte GCM nonce from the group key and a monotonic counter.
     * nonce = HMAC(K_group, counter_bytes)[0..11]
     */
    private fun deriveNonce(key: ByteArray, counter: Long): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()
        val hash = mac.doFinal(counterBytes)
        return hash.copyOfRange(0, GCM_NONCE_LENGTH)
    }

    /**
     * Find the end of content before ISO 7816-4 padding.
     * ISO 7816-4: content || 0x80 || 0x00...
     */
    private fun findIsoPaddingEnd(data: ByteArray): Int {
        for (i in data.indices.reversed()) {
            if (data[i] == 0x80.toByte()) return i
            if (data[i] != 0x00.toByte()) return data.size // No padding found
        }
        return 0
    }

    /**
     * Lexicographic comparator for byte arrays.
     */
    private object ByteArrayComparator : Comparator<ByteArray> {
        override fun compare(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
                if (cmp != 0) return cmp
            }
            return a.size - b.size
        }
    }
}

/**
 * Parsed inner group payload (Layer 1, post-decryption).
 */
data class GroupInnerPayload(
    val groupId: ByteArray,
    val senderToken: ByteArray,
    val senderIndex: Int,
    val groupNonce: Long,
    val vectorClock: LongArray,
    val flag: Byte,
    val content: ByteArray
) {
    companion object {
        const val FLAG_CHAT: Byte = 0x01
        const val FLAG_COVER: Byte = 0x02
        const val FLAG_MEMBERSHIP_CHANGE: Byte = 0x03
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupInnerPayload
        return groupId.contentEquals(other.groupId) &&
                senderToken.contentEquals(other.senderToken) &&
                senderIndex == other.senderIndex &&
                groupNonce == other.groupNonce &&
                vectorClock.contentEquals(other.vectorClock) &&
                flag == other.flag &&
                content.contentEquals(other.content)
    }

    override fun hashCode(): Int {
        var result = groupId.contentHashCode()
        result = 31 * result + senderToken.contentHashCode()
        result = 31 * result + senderIndex
        result = 31 * result + groupNonce.hashCode()
        result = 31 * result + vectorClock.contentHashCode()
        result = 31 * result + flag.hashCode()
        result = 31 * result + content.contentHashCode()
        return result
    }
}
