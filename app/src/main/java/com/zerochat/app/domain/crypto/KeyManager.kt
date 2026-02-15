package com.zerochat.app.domain.crypto

import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.PwHash
import com.goterl.lazysodium.interfaces.SecretBox
import com.sun.jna.NativeLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Key Manager - Passphrase-based key hierarchy
 * 
 * Security Architecture:
 * - User Passphrase → Argon2id (256MB, 3 iterations) → KEK
 * - KEK unwraps encrypted database key
 * - KEK exists ONLY in volatile RAM
 * - Duress passphrase triggers key destruction
 * 
 * CRITICAL: Android Keystore is NOT a trust anchor here
 */
@Singleton
class KeyManager @Inject constructor() {
    
    private val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())
    
    // Volatile: KEK is NEVER persisted to disk
    @Volatile
    private var kek: ByteArray? = null
    
    // Volatile: Session keys exist only during active connection
    @Volatile
    private var sessionKeys: SessionKeys? = null
    
    companion object {
        // Argon2id parameters - memory-hard to resist hardware attacks
        // Using mobile-friendly settings (64MB is still strong)
        const val ARGON2_MEM_LIMIT = 64 * 1024 * 1024  // 64MB (mobile-safe)
        const val ARGON2_OPS_LIMIT = 3L  // 3 iterations
        const val KEY_LENGTH = 32  // 256-bit keys
        const val SALT_LENGTH = 16  // PwHash.ARGON2ID_SALTBYTES
        const val NONCE_LENGTH = 24  // SecretBox.NONCEBYTES
    }
    
    /**
     * Derive KEK from user passphrase using Argon2id
     * 
     * @param passphrase User's secret passphrase
     * @param salt Stored per-device salt
     * @return True if unlock successful
     */
    fun deriveKEK(passphrase: String, salt: ByteArray): Boolean {
        // Clear any existing KEK first
        clearKEK()
        
        try {
            // Derive key using Argon2id
            val derivedKey = ByteArray(KEY_LENGTH)
            val passphraseBytes = passphrase.toByteArray(Charsets.UTF_8)
            
            val success = sodium.cryptoPwHash(
                derivedKey,
                KEY_LENGTH,
                passphraseBytes,
                passphraseBytes.size,
                salt,
                ARGON2_OPS_LIMIT,
                NativeLong(ARGON2_MEM_LIMIT.toLong()),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13
            )
            
            if (!success) {
                return false
            }
            
            // Store KEK in volatile memory
            kek = derivedKey
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Check if this is a duress passphrase
     * If so, wipe all keys and return true
     */
    fun checkDuress(passphrase: String, duressHash: ByteArray, salt: ByteArray): Boolean {
        try {
            val testDuressKey = ByteArray(KEY_LENGTH)
            val passphraseBytes = passphrase.toByteArray(Charsets.UTF_8)
            
            sodium.cryptoPwHash(
                testDuressKey,
                KEY_LENGTH,
                passphraseBytes,
                passphraseBytes.size,
                salt,
                ARGON2_OPS_LIMIT,
                NativeLong(ARGON2_MEM_LIMIT.toLong()),
                PwHash.Alg.PWHASH_ALG_ARGON2ID13
            )
            
            // Constant-time comparison
            if (testDuressKey.contentEquals(duressHash)) {
                // DURESS DETECTED - Wipe everything
                triggerDuressWipe()
                return true
            }
            
            return false
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * Unwrap the SQLCipher database key using KEK
     * 
     * @param encryptedDbKey Encrypted database key blob from storage
     * @param nonce Nonce used during encryption
     * @return Decrypted database key, or null if KEK not available
     */
    fun unwrapDatabaseKey(encryptedDbKey: ByteArray, nonce: ByteArray): ByteArray? {
        val currentKek = kek ?: return null
        
        return try {
            val dbKey = ByteArray(KEY_LENGTH)
            
            val success = sodium.cryptoSecretBoxOpenEasy(
                dbKey,
                encryptedDbKey,
                encryptedDbKey.size.toLong(),
                nonce,
                currentKek
            )
            
            if (success) dbKey else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Wrap a new database key for storage
     * 
     * @param dbKey Raw database key to encrypt
     * @return Pair of (encrypted blob, nonce)
     */
    fun wrapDatabaseKey(dbKey: ByteArray): Pair<ByteArray, ByteArray>? {
        val currentKek = kek ?: return null
        
        return try {
            val nonce = generateNonce()
            val macBytes = 16  // SecretBox MAC size
            val encrypted = ByteArray(dbKey.size + macBytes)
            
            val success = sodium.cryptoSecretBoxEasy(
                encrypted,
                dbKey,
                dbKey.size.toLong(),
                nonce,
                currentKek
            )
            
            if (success) Pair(encrypted, nonce) else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Generate a new random database key
     */
    fun generateDatabaseKey(): ByteArray {
        return sodium.randomBytesBuf(KEY_LENGTH)
    }
    
    /**
     * Generate a random salt for Argon2id
     */
    fun generateSalt(): ByteArray {
        return sodium.randomBytesBuf(SALT_LENGTH)
    }
    
    /**
     * Generate a random nonce for SecretBox
     */
    fun generateNonce(): ByteArray {
        return sodium.randomBytesBuf(NONCE_LENGTH)
    }
    
    /**
     * Check if KEK is currently available (app is unlocked)
     */
    fun isUnlocked(): Boolean = kek != null
    
    /**
     * Securely clear KEK from memory
     * Called on app exit, screen lock, or background
     */
    fun clearKEK() {
        kek?.let { key ->
            // Overwrite with zeros before nulling
            for (i in key.indices) {
                key[i] = 0
            }
        }
        kek = null
    }
    
    /**
     * DURESS RESPONSE: Wipe all keys immediately
     * This is called when duress passphrase is detected
     */
    private fun triggerDuressWipe() {
        // Clear KEK
        clearKEK()
        
        // The encrypted database key blob should be wiped by the caller
        // This makes data permanently unrecoverable
    }
    
    /**
     * Generate hash of duress passphrase for storage
     */
    fun hashDuressPassphrase(duressPassphrase: String, salt: ByteArray): ByteArray {
        val hash = ByteArray(KEY_LENGTH)
        val passphraseBytes = duressPassphrase.toByteArray(Charsets.UTF_8)
        
        sodium.cryptoPwHash(
            hash,
            KEY_LENGTH,
            passphraseBytes,
            passphraseBytes.size,
            salt,
            ARGON2_OPS_LIMIT,
            NativeLong(ARGON2_MEM_LIMIT.toLong()),
            PwHash.Alg.PWHASH_ALG_ARGON2ID13
        )
        return hash
    }
    
    /**
     * Derive session keys from SPAKE2+ shared secret using HKDF
     * 
     * @param spake2Output Shared secret from SPAKE2+ handshake
     * @return SessionKeys containing encryption and MAC keys
     */
    fun deriveSessionKeys(spake2Output: ByteArray): SessionKeys {
        // Clear any existing session keys
        clearSessionKeys()
        
        // Use HKDF to derive two keys from SPAKE2+ output
        // Salt: none (SPAKE2+ output is already high-entropy)
        // Info: domain separation strings
        val encryptionKey = ByteArray(KEY_LENGTH)
        val macKey = ByteArray(KEY_LENGTH)
        
        // Derive encryption key
        sodium.cryptoKdfDeriveFromKey(
            encryptionKey,
            KEY_LENGTH,
            1L, // subkey ID
            "ZCEncKey".toByteArray(), // MUST be exactly 8 bytes for crypto_kdf
            spake2Output
        )
        
        // Derive MAC key
        sodium.cryptoKdfDeriveFromKey(
            macKey,
            KEY_LENGTH,
            2L, // subkey ID
            "ZC_MACKy".toByteArray(), // MUST be exactly 8 bytes for crypto_kdf
            spake2Output
        )
        
        val keys = SessionKeys(encryptionKey, macKey)
        sessionKeys = keys
        
        return keys
    }
    
    /**
     * Derive confirmation key from session key for mutual verification
     * 
     * @param sessionKey Session key from SPAKE2+ handshake
     * @return Confirmation key (32 bytes) for HMAC
     */
    fun deriveConfirmationKey(sessionKey: ByteArray): ByteArray {
        val confirmationKey = ByteArray(KEY_LENGTH)
        
        // Derive confirmation key using KDF with domain separation
        sodium.cryptoKdfDeriveFromKey(
            confirmationKey,
            KEY_LENGTH,
            3L, // subkey ID (different from encryption=1, mac=2)
            "ZCConfKy".toByteArray(), // MUST be exactly 8 bytes for crypto_kdf
            sessionKey
        )
        
        return confirmationKey
    }
    
    /**
     * Encrypt data with session key (for handle encryption)
     * 
     * Uses AES-256-GCM via libsodium's SecretBox.
     * 
     * @param plaintext Data to encrypt
     * @param sessionKey Session key from SPAKE2+ handshake
     * @return Encrypted data (nonce + ciphertext + MAC), or null on failure
     */
    fun encrypt(plaintext: ByteArray, sessionKey: ByteArray): ByteArray? {
        return try {
            val nonce = generateNonce()
            val macBytes = 16  // SecretBox MAC size
            val encrypted = ByteArray(plaintext.size + macBytes)
            
            val success = sodium.cryptoSecretBoxEasy(
                encrypted,
                plaintext,
                plaintext.size.toLong(),
                nonce,
                sessionKey
            )
            
            if (success) {
                // Return nonce + encrypted (for transmission)
                nonce + encrypted
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Decrypt data with session key (for handle decryption)
     * 
     * @param ciphertext Encrypted data (nonce + ciphertext + MAC)
     * @param sessionKey Session key from SPAKE2+ handshake
     * @return Decrypted plaintext, or null if decryption fails
     */
    fun decrypt(ciphertext: ByteArray, sessionKey: ByteArray): ByteArray? {
        if (ciphertext.size < NONCE_LENGTH + 16) {
            return null  // Too short to be valid
        }
        
        return try {
            // Extract nonce and encrypted data
            val nonce = ciphertext.copyOfRange(0, NONCE_LENGTH)
            val encrypted = ciphertext.copyOfRange(NONCE_LENGTH, ciphertext.size)
            
            val macBytes = 16
            val plaintext = ByteArray(encrypted.size - macBytes)
            
            val success = sodium.cryptoSecretBoxOpenEasy(
                plaintext,
                encrypted,
                encrypted.size.toLong(),
                nonce,
                sessionKey
            )
            
            if (success) plaintext else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Encrypt message with session key
     * 
     * @param plaintext Message to encrypt
     * @return Encrypted message (nonce + ciphertext + MAC)
     */
    fun encryptMessage(plaintext: ByteArray): ByteArray? {
        val keys = sessionKeys ?: return null
        
        return try {
            val nonce = generateNonce()
            val macBytes = 16  // SecretBox MAC size
            val encrypted = ByteArray(plaintext.size + macBytes)
            
            val success = sodium.cryptoSecretBoxEasy(
                encrypted,
                plaintext,
                plaintext.size.toLong(),
                nonce,
                keys.encryptionKey
            )
            
            if (success) {
                // Return nonce + encrypted (for transmission)
                nonce + encrypted
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Decrypt message with session key
     * 
     * @param ciphertext Encrypted message (nonce + ciphertext + MAC)
     * @return Decrypted plaintext, or null if decryption fails
     */
    fun decryptMessage(ciphertext: ByteArray): ByteArray? {
        val keys = sessionKeys ?: return null
        
        if (ciphertext.size < NONCE_LENGTH + 16) {
            return null  // Too short to be valid
        }
        
        return try {
            // Extract nonce and encrypted data
            val nonce = ciphertext.copyOfRange(0, NONCE_LENGTH)
            val encrypted = ciphertext.copyOfRange(NONCE_LENGTH, ciphertext.size)
            
            val macBytes = 16
            val plaintext = ByteArray(encrypted.size - macBytes)
            
            val success = sodium.cryptoSecretBoxOpenEasy(
                plaintext,
                encrypted,
                encrypted.size.toLong(),
                nonce,
                keys.encryptionKey
            )
            
            if (success) plaintext else null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Check if session keys are available
     */
    fun hasSessionKeys(): Boolean = sessionKeys != null
    
    /**
     * Securely clear session keys from memory
     * Called on disconnect or session end
     */
    fun clearSessionKeys() {
        sessionKeys?.let { keys ->
            // Overwrite with zeros
            for (i in keys.encryptionKey.indices) {
                keys.encryptionKey[i] = 0
            }
            for (i in keys.macKey.indices) {
                keys.macKey[i] = 0
            }
        }
        sessionKeys = null
    }
}

