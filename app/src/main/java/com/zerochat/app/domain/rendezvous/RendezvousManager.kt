package com.zerochat.app.domain.rendezvous

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.goterl.lazysodium.interfaces.GenericHash
import com.zerochat.app.domain.transport.NymTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Rendezvous Manager - Derives meeting points from shared secrets
 * 
 * Security Invariants (see SECURITY_GUARDRAILS.md):
 * - RV-01: Rendezvous TTL ≤ 5 minutes
 * - RV-03: Rendezvous = HKDF(secret ‖ epoch)
 * - RV-04: No reuse after successful handshake
 * - PL-01: Constant-rate polling 10s ± 2s jitter
 * - UI-01: Never expose rendezvous to UI
 */
@Singleton
class RendezvousManager @Inject constructor(
    private val transport: NymTransport
) {
    
    private val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())
    private var nymConnected = false
    
    companion object {
        private const val TAG = "RendezvousManager"
        const val EPOCH_DURATION_SECONDS = 300  // 5 minutes
        const val RENDEZVOUS_LENGTH = 32
        const val POLL_INTERVAL_MS = 10_000L  // 10 seconds
        const val POLL_JITTER_MS = 2_000L  // ± 2 seconds
        const val MAX_POLL_ATTEMPTS = 30  // 5 minutes / 10 seconds
        
        private const val RENDEZVOUS_SALT = "zerochat-rendezvous-v1"
        private const val EPOCH_INFO = "epoch-meeting-point"
        private const val NYM_GATEWAY_URL = "https://mainnet-gateway.nymtech.net"  // Public mainnet
    }
    
    // Track consumed rendezvous points (in-memory only)
    private val consumedRendezvous = mutableSetOf<String>()
    
    /**
     * Ensure NYM connection is established before any operations
     */
    private suspend fun ensureConnected() {
        if (!nymConnected && !transport.isConnected()) {
            Log.i(TAG, "Connecting to NYM mixnet...")
            // Run on IO dispatcher to avoid blocking main thread (connection takes ~7 seconds)
            withContext(kotlinx.coroutines.Dispatchers.IO) {
                val result = transport.connect(NYM_GATEWAY_URL)
                if (result.isSuccess) {
                    nymConnected = true
                    Log.i(TAG, "NYM connection established")
                } else {
                    Log.e(TAG, "Failed to connect to NYM: ${result.exceptionOrNull()?.message}")
                    throw result.exceptionOrNull() ?: Exception("NYM connection failed")
                }
            }
        }
    }
    
    /**
     * Derive rendezvous point from shared secret and current epoch
     * Both parties with same secret derive same point
     */
    fun deriveRendezvous(sharedSecret: String): RendezvousPoint {
        val epoch = getCurrentEpoch()
        val epochBytes = epoch.toString().toByteArray(Charsets.UTF_8)
        val secretBytes = sharedSecret.toByteArray(Charsets.UTF_8)
        val saltBytes = RENDEZVOUS_SALT.toByteArray(Charsets.UTF_8)
        
        // Combine: secret || epoch || salt
        val input = secretBytes + epochBytes + saltBytes
        
        // Hash to get rendezvous point
        val rendezvousBytes = ByteArray(RENDEZVOUS_LENGTH)
        sodium.cryptoGenericHash(
            rendezvousBytes,
            RENDEZVOUS_LENGTH,
            input,
            input.size.toLong(),
            null,
            0
        )
        
        val rendezvousId = rendezvousBytes.toHexString()
        val expiresAt = (epoch + 1) * EPOCH_DURATION_SECONDS * 1000L
        
        return RendezvousPoint(
            id = rendezvousId,
            epoch = epoch,
            expiresAt = expiresAt
        )
    }
    
    /**
     * Get current epoch (5-minute window)
     */
    private fun getCurrentEpoch(): Long {
        return System.currentTimeMillis() / 1000 / EPOCH_DURATION_SECONDS
    }
    
    /**
     * Check if rendezvous is still valid
     */
    fun isValid(rendezvous: RendezvousPoint): Boolean {
        val now = System.currentTimeMillis()
        return now < rendezvous.expiresAt && !isConsumed(rendezvous)
    }
    
    /**
     * Check if rendezvous has been consumed
     */
    fun isConsumed(rendezvous: RendezvousPoint): Boolean {
        return consumedRendezvous.contains(rendezvous.id)
    }
    
    /**
     * Mark rendezvous as consumed (after successful handshake)
     */
    fun markConsumed(rendezvous: RendezvousPoint) {
        consumedRendezvous.add(rendezvous.id)
    }
    
    /**
     * Publish our handle at a rendezvous point
     * Called during handshake to signal our presence
     */
    suspend fun publishAtRendezvous(rendezvous: RendezvousPoint, myHandle: ByteArray): Result<Unit> {
        ensureConnected()
        if (!isValid(rendezvous)) {
            return Result.failure(IllegalStateException("Rendezvous expired"))
        }
        return transport.publishAtRendezvous(rendezvous.id, myHandle)
    }
    
    /**
     * Poll rendezvous with constant-rate + jitter
     * Returns flow of poll results
     */
    fun pollRendezvous(rendezvous: RendezvousPoint): Flow<PollResult> = flow {
        // Ensure NYM connection before polling
        ensureConnected()
        
        var attempts = 0
        
        while (attempts < MAX_POLL_ATTEMPTS && isValid(rendezvous)) {
            emit(PollResult.Polling(attempts + 1, MAX_POLL_ATTEMPTS))
            
            // Poll via NYM mixnet transport
            val response = transport.pollRendezvous(rendezvous.id)
            
            if (response != null) {
                markConsumed(rendezvous)
                emit(PollResult.Found(response.senderHandle))
                return@flow
            }
            
            // Constant interval + jitter (PL-01)
            val jitter = Random.nextLong(-POLL_JITTER_MS, POLL_JITTER_MS)
            delay(POLL_INTERVAL_MS + jitter)
            
            attempts++
        }
        
        // Timeout - silent failure (FL-01)
        if (!isValid(rendezvous)) {
            emit(PollResult.Expired)
        } else {
            emit(PollResult.Timeout)
        }
    }
    
    /**
     * Clear all consumed rendezvous (on app lock/exit)
     */
    fun clearAll() {
        consumedRendezvous.clear()
    }
    
    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02x".format(it) }
    }
}

/**
 * Rendezvous point - ephemeral meeting location
 */
data class RendezvousPoint(
    val id: String,        // Derived from HKDF(secret, epoch)
    val epoch: Long,       // Time window
    val expiresAt: Long    // Timestamp when this rendezvous expires
)

/**
 * Poll result states
 */
sealed class PollResult {
    data class Polling(val attempt: Int, val max: Int) : PollResult()
    data class Found(val peerHandle: ByteArray) : PollResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            return peerHandle.contentEquals((other as Found).peerHandle)
        }
        override fun hashCode(): Int = peerHandle.contentHashCode()
    }
    object Timeout : PollResult()
    object Expired : PollResult()
}
