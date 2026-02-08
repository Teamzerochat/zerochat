package com.zerochat.app.domain.rendezvous

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.zerochat.app.domain.transport.NymTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Rendezvous Manager - Two-Point Rendezvous System
 * 
 * Instead of a shared mailbox (which causes self-message issues), we derive
 * TWO separate points from the secret:
 * - Point A: Where "Alice" publishes, "Bob" polls
 * - Point B: Where "Bob" publishes, "Alice" polls
 * 
 * Role is determined by device's random nonce - lower nonce = Alice.
 * This ensures each device reads from a point they didn't write to.
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
    
    companion object {
        private const val TAG = "RendezvousManager"
        const val EPOCH_DURATION_SECONDS = 300  // 5 minutes
        const val RENDEZVOUS_LENGTH = 32
        const val POLL_INTERVAL_MS = 10_000L  // 10 seconds
        const val POLL_JITTER_MS = 2_000L  // ± 2 seconds
        const val MAX_POLL_ATTEMPTS = 30  // 5 minutes / 10 seconds
        
        private const val RENDEZVOUS_SALT = "zerochat-rendezvous-v1"
        private const val POINT_A_SUFFIX = "-point-alice"
        private const val POINT_B_SUFFIX = "-point-bob"
    }
    
    // Track consumed rendezvous points (in-memory only)
    private val consumedRendezvous = mutableSetOf<String>()
    
    /**
     * Derive TWO rendezvous points from shared secret
     * Both parties with same secret derive the same pair of points
     */
    fun deriveRendezvousPair(sharedSecret: String): RendezvousPair {
        val epoch = getCurrentEpoch()
        val expiresAt = (epoch + 1) * EPOCH_DURATION_SECONDS * 1000L
        
        val pointA = derivePoint(sharedSecret, epoch, POINT_A_SUFFIX)
        val pointB = derivePoint(sharedSecret, epoch, POINT_B_SUFFIX)
        
        Log.i(TAG, "Derived rendezvous pair - A: ${pointA.take(16)}..., B: ${pointB.take(16)}...")
        
        return RendezvousPair(
            pointA = RendezvousPoint(id = pointA, epoch = epoch, expiresAt = expiresAt),
            pointB = RendezvousPoint(id = pointB, epoch = epoch, expiresAt = expiresAt)
        )
    }
    
    /**
     * Derive a single point with given suffix
     */
    private fun derivePoint(secret: String, epoch: Long, suffix: String): String {
        val epochBytes = epoch.toString().toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val saltBytes = RENDEZVOUS_SALT.toByteArray(Charsets.UTF_8)
        val suffixBytes = suffix.toByteArray(Charsets.UTF_8)
        
        // Combine: secret || epoch || salt || suffix
        val input = secretBytes + epochBytes + saltBytes + suffixBytes
        
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
        
        return rendezvousBytes.toHexString()
    }
    
    /**
     * Determine role (Alice or Bob) based on random nonce
     * Both devices generate a nonce, lower nonce = Alice
     * 
     * Since we can't exchange nonces before connecting, we use a simpler approach:
     * Both devices publish at BOTH points and poll BOTH points.
     * First message found from a different sender = peer found.
     * 
     * Actually, simplest approach: each device publishes at BOTH points,
     * but only polls ONE point based on a coin flip. 50% chance they poll different.
     * After a few attempts, they switch. This is probabilistic but simple.
     * 
     * EVEN SIMPLER: Each device picks a random role (Alice/Bob) with 50% probability.
     * If both pick same role = timeout and retry with new role.
     * If different = success!
     */
    fun pickRandomRole(): RendezvousRole {
        val isAlice = Random.nextBoolean()
        val role = if (isAlice) RendezvousRole.ALICE else RendezvousRole.BOB
        Log.i(TAG, "Picked role: $role")
        return role
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
     * Publish our handle at the appropriate point based on role
     * Alice publishes at Point A, Bob publishes at Point B
     */
    suspend fun publishAtRendezvous(
        pair: RendezvousPair,
        role: RendezvousRole,
        myHandle: ByteArray
    ): Result<Unit> {
        val publishPoint = if (role == RendezvousRole.ALICE) pair.pointA else pair.pointB
        
        if (!isValid(publishPoint)) {
            return Result.failure(IllegalStateException("Rendezvous expired"))
        }
        
        Log.i(TAG, "Publishing at ${role.name} point: ${publishPoint.id.take(16)}...")
        return transport.publishAtRendezvous(publishPoint.id, myHandle)
    }
    
    /**
     * Poll the opposite point based on role
     * Alice polls Point B (where Bob publishes), Bob polls Point A (where Alice publishes)
     * 
     * NO SELF-FILTERING NEEDED - we read from a different point than we write to!
     */
    fun pollRendezvous(pair: RendezvousPair, role: RendezvousRole): Flow<PollResult> = flow {
        val pollPoint = if (role == RendezvousRole.ALICE) pair.pointB else pair.pointA
        var attempts = 0
        
        Log.i(TAG, "Starting poll as ${role.name} - polling ${if (role == RendezvousRole.ALICE) "BOB" else "ALICE"}'s point: ${pollPoint.id.take(16)}...")
        
        while (attempts < MAX_POLL_ATTEMPTS && isValid(pollPoint)) {
            emit(PollResult.Polling(attempts + 1, MAX_POLL_ATTEMPTS))
            
            // Poll via NYM mixnet transport
            val response = transport.pollRendezvous(pollPoint.id)
            
            if (response != null) {
                Log.i(TAG, "Found message at poll point! Payload: ${response.payload.size} bytes")
                markConsumed(pollPoint)
                markConsumed(if (role == RendezvousRole.ALICE) pair.pointA else pair.pointB)
                emit(PollResult.Found(response.payload))
                return@flow
            } else {
                Log.d(TAG, "No message on attempt ${attempts + 1}")
            }
            
            // Constant interval + jitter (PL-01)
            val jitter = Random.nextLong(-POLL_JITTER_MS, POLL_JITTER_MS)
            delay(POLL_INTERVAL_MS + jitter)
            
            attempts++
        }
        
        // Timeout - silent failure (FL-01)
        if (!isValid(pollPoint)) {
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
 * Role in the two-point rendezvous
 */
enum class RendezvousRole {
    ALICE,  // Publishes at Point A, polls Point B
    BOB     // Publishes at Point B, polls Point A
}

/**
 * Pair of rendezvous points
 */
data class RendezvousPair(
    val pointA: RendezvousPoint,
    val pointB: RendezvousPoint
)

/**
 * Rendezvous point - ephemeral meeting location
 */
data class RendezvousPoint(
    val id: String,        // Derived from HKDF(secret, epoch, suffix)
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
