package com.zerochat.app.domain.crypto

import android.util.Log
import uniffi.nym_transport.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handshake Manager - SPAKE2+ Password-Authenticated Key Exchange
 * 
 * Implements mutual authentication using shared secret.
 * Both parties derive the same session key if they have the same secret.
 * 
 * Security Properties:
 * - Password-authenticated: Prevents MITM without shared secret
 * - Forward secrecy: Session keys are ephemeral
 * - Mutual authentication: Both parties verify each other
 * - No plaintext password transmission
 */
@Singleton
class HandshakeManager @Inject constructor(
    private val keyManager: KeyManager
) {
    
    companion object {
        private const val TAG = "HandshakeManager"
    }
    
    // Volatile: Handshake handle exists only during handshake
    @Volatile
    private var handleId: ULong? = null
    
    /**
     * Start handshake as initiator (Alice)
     * 
     * @param sharedSecret The shared secret exchanged out-of-band
     * @return Commitment message to send to peer via rendezvous
     */
    fun startAsInitiator(sharedSecret: String): Result<ByteArray> {
        return try {
            Log.i(TAG, "Starting SPAKE2+ handshake as initiator")
            
            val password = sharedSecret.toByteArray(Charsets.UTF_8)
            val result = spake2StartInitiatorWrapper(password.map { it.toUByte() })
            
            // Save handle ID for finish call
            handleId = result.handleId
            
            val commitment = result.outboundMsg.map { it.toByte() }.toByteArray()
            Log.i(TAG, "Generated commitment (${commitment.size} bytes), handle=$handleId")
            
            Result.success(commitment)
        } catch (e: Spake2Exception) {
            Log.e(TAG, "Failed to start handshake: ${e.message}", e)
            cleanup()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in handshake: ${e.message}", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    /**
     * Finish handshake as initiator
     * 
     * @param peerResponse Response message from peer
     * @return Session key derived from handshake
     */
    fun finishAsInitiator(peerResponse: ByteArray): Result<ByteArray> {
        return try {
            val id = handleId ?: return Result.failure(
                IllegalStateException("No handshake in progress")
            )
            
            Log.i(TAG, "Finishing SPAKE2+ handshake as initiator with handle=$id")
            
            val responseList = peerResponse.map { it.toUByte() }
            
            val sharedSecret = spake2FinishInitiatorWrapper(id, responseList)
                .map { it.toByte() }
                .toByteArray()
            
            Log.i(TAG, "Handshake complete! Derived session key (${sharedSecret.size} bytes)")
            
            // Derive session keys from SPAKE2+ output
            val sessionKeys = keyManager.deriveSessionKeys(sharedSecret)
            
            // Clear handshake state
            handleId = null
            secureWipe(sharedSecret)
            
            Result.success(sessionKeys.encryptionKey)
        } catch (e: Spake2Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}", e)
            cleanup()
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error finishing handshake: ${e.message}", e)
            cleanup()
            Result.failure(e)
        }
    }
    
    /**
     * Process handshake as responder (Bob)
     * 
     * @param sharedSecret The shared secret exchanged out-of-band
     * @param peerCommitment Commitment message from peer
     * @return Pair of (response message, session key)
     */
    fun processAsResponder(sharedSecret: String, peerCommitment: ByteArray): Result<Pair<ByteArray, ByteArray>> {
        return try {
            Log.i(TAG, "Processing SPAKE2+ handshake as responder")
            
            val password = sharedSecret.toByteArray(Charsets.UTF_8)
            val passwordList = password.map { it.toUByte() }
            val commitmentList = peerCommitment.map { it.toUByte() }
            
            val result = spake2StartResponderWrapper(passwordList, commitmentList)
            
            val response = result.outboundMsg.map { it.toByte() }.toByteArray()
            val sharedSecretBytes = result.sharedSecret.map { it.toByte() }.toByteArray()
            
            Log.i(TAG, "Handshake complete! Derived session key (${sharedSecretBytes.size} bytes)")
            
            // Derive session keys from SPAKE2+ output
            val sessionKeys = keyManager.deriveSessionKeys(sharedSecretBytes)
            
            // Clear sensitive data
            secureWipe(sharedSecretBytes)
            
            Result.success(Pair(response, sessionKeys.encryptionKey))
        } catch (e: Spake2Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in handshake: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * Clear all handshake state and cleanup Rust-side handle
     */
    fun cleanup() {
        handleId?.let { id ->
            try {
                spake2CleanupStateWrapper(id)
                Log.d(TAG, "Cleaned up SPAKE2+ handle=$id")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to cleanup SPAKE2+ handle: ${e.message}")
            }
        }
        handleId = null
    }
    
    /**
     * Clear all handshake state
     */
    fun clearState() {
        cleanup()
    }
    
    /**
     * Secure wipe: Overwrite with zeros before deallocation
     */
    private fun secureWipe(data: ByteArray?) {
        data?.let { bytes ->
            for (i in bytes.indices) {
                bytes[i] = 0
            }
        }
    }
}

/**
 * Session keys derived from SPAKE2+ shared secret
 */
data class SessionKeys(
    val encryptionKey: ByteArray,
    val macKey: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SessionKeys
        return encryptionKey.contentEquals(other.encryptionKey) &&
                macKey.contentEquals(other.macKey)
    }

    override fun hashCode(): Int {
        var result = encryptionKey.contentHashCode()
        result = 31 * result + macKey.contentHashCode()
        return result
    }
}
