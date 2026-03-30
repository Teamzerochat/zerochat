package com.zerochat.app.domain.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NetDB Encryption — AES-256-GCM via Android Keystore.
 *
 * Paper §7(b): "NetDB persistence key is derived via Android Keystore attestation,
 * preventing extraction by A_LFA."
 *
 * The key never leaves hardware-backed storage. It encrypts/decrypts the NetDB
 * directory contents so even a rooted device cannot extract persistent peer data.
 */
@Singleton
class NetDbEncryption @Inject constructor() {

    companion object {
        private const val TAG = "NetDbEncryption"
        private const val KEY_ALIAS = "zerochat_netdb_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_LENGTH = 128
        // GCM IV is prepended to ciphertext: [12-byte IV][ciphertext+tag]
        private const val GCM_IV_LENGTH = 12
    }

    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        ensureKeyExists()
    }

    private fun ensureKeyExists() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            Log.i(TAG, "Generating NetDB encryption key in Android Keystore")
            val keyGen = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            keyGen.generateKey()
            Log.i(TAG, "NetDB key generated successfully")
        }
    }

    private fun getKey(): SecretKey {
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * Encrypt data using AES-256-GCM.
     * Returns: [12-byte IV][ciphertext + 16-byte tag]
     */
    fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getKey())
        val iv = cipher.iv // GCM generates random IV
        val ciphertext = cipher.doFinal(plaintext)
        // Prepend IV
        return iv + ciphertext
    }

    /**
     * Decrypt data encrypted with [encrypt].
     * Input format: [12-byte IV][ciphertext + 16-byte tag]
     */
    fun decrypt(data: ByteArray): ByteArray {
        if (data.size < GCM_IV_LENGTH + GCM_TAG_LENGTH / 8) {
            throw IllegalArgumentException("Data too short for GCM decryption")
        }
        val iv = data.sliceArray(0 until GCM_IV_LENGTH)
        val ciphertext = data.sliceArray(GCM_IV_LENGTH until data.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Encrypt all files in a directory (non-recursive).
     * Adds ".enc" extension to encrypted files.
     */
    fun encryptDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".enc") }?.forEach { file ->
            try {
                val encrypted = encrypt(file.readBytes())
                File(file.parent, "${file.name}.enc").writeBytes(encrypted)
                file.delete()
                Log.d(TAG, "Encrypted: ${file.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt ${file.name}", e)
            }
        }
    }

    /**
     * Decrypt all ".enc" files in a directory back to original names.
     */
    fun decryptDirectory(dir: File) {
        if (!dir.exists() || !dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".enc") }?.forEach { file ->
            try {
                val decrypted = decrypt(file.readBytes())
                val originalName = file.name.removeSuffix(".enc")
                File(file.parent, originalName).writeBytes(decrypted)
                file.delete()
                Log.d(TAG, "Decrypted: $originalName")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt ${file.name}", e)
            }
        }
    }
}
