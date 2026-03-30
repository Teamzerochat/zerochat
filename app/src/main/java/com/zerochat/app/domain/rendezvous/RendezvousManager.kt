package com.zerochat.app.domain.rendezvous

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.zerochat.app.domain.transport.Obfs4FrameWrapper
import com.zerochat.app.domain.transport.RealNymTransport
import com.zerochat.app.domain.transport.TransportController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Rendezvous Manager - Model 3 (Strict Lifecycle & State Machine)
 * 
 * Enforces strict single-connection lifecycle to prevent gateway session collisions.
 * All transport calls routed through TransportController for panic isolation.
 * 
 * States:
 * - IDLE: No connection
 * - CONNECTING: Establishing determininstic identity session
 * - CONNECTED: Session established
 * - PUBLISHED: Nonce/Message sent
 * - POLLING: Active polling loop (reusing connection)
 * - HANDSHAKE_COMPLETE: Success
 * - TEARDOWN: Cleanup and cool-down
 */
@Singleton
class RendezvousManager @Inject constructor(
    private val controller: TransportController
) {
    
    private val sodium: LazySodiumAndroid = LazySodiumAndroid(SodiumAndroid())
    
    // STRICT STATE MACHINE
    enum class RendezvousState {
        IDLE,
        CONNECTING,
        CONNECTED,
        PUBLISHED,
        POLLING,
        HANDSHAKE_COMPLETE,
        TEARDOWN
    }
    
    private val state = AtomicReference(RendezvousState.IDLE)
    private val connectionMutex = Mutex()
    private var lastTeardownTime: Long = 0
    private var handshakeComplete = false

    companion object {
        private const val TAG = "RendezvousManager"
        const val EPOCH_DURATION_SECONDS = 300
        const val RENDEZVOUS_LENGTH = 32
        const val POLL_INTERVAL_MS = 2_000L
        // INCREASED: 60 → 600 attempts (120 sec → 1200 sec = 20 min)
        // Physical devices need more time for I2P startup + rendezvous connection
        const val MAX_POLL_ATTEMPTS = 600
        private const val RENDEZVOUS_SALT = "zerochat-rendezvous"
        
        // TEARDOWN COOL-DOWN (5s)
        private const val TEARDOWN_COOLDOWN_MS = 5000L
    }

    // Track consumed points to prevent reuse in same session
    private val consumedRendezvous = mutableSetOf<String>()

    // BUG 3 FIX: Track whether obfs4/application-layer context is ready before polling.
    // This prevents race conditions where messages are received before the decryption
    // context is initialized, which would cause parsing failures.
    @Volatile
    private var obfs4ContextReady: Boolean = false

    // BUG 4 FIX: Track role determined at slot assignment time.
    // Peer that occupies Slot A = INITIATOR, Peer that occupies Slot B = RESPONDER.
    // This is determined at slot assignment, not post-connection.
    enum class Role { INITIATOR, RESPONDER }
    @Volatile
    private var assignedRole: Role? = null

    // BUG 5 FIX: Track session handle from SPAKE2+ handshake for obfs4_state retrieval.
    // Set by ConnectionManager after SPAKE2+ handshake completes.
    // Used by poll() to retrieve the 64-byte obfs4_state for per-frame ChaCha20-Poly1305 decryption.
    @Volatile
    private var obfs4State: ByteArray? = null

    /**
     * BUG 4 FIX: Get the role assigned at slot connection time.
     * Returns null if no slot has been connected yet.
     */
    fun getAssignedRole(): Role? = assignedRole

    /**
     * BUG 5 FIX: Set obfs4_state retrieved from SPAKE2+ shared secret derivation.
     * Call this after SPAKE2+ completes and before polling begins.
     * This allows poll() to use the obfs4_state for per-frame decryption.
     */
    fun setObfs4State(state: ByteArray) {
        val oldStateHex = obfs4State?.sliceArray(0 until minOf(8, obfs4State!!.size))?.joinToString("") { "%02x".format(it) } ?: "null"
        obfs4State = state
        val newStateHex = state.sliceArray(0 until minOf(8, state.size)).joinToString("") { "%02x".format(it) }
        
        // DIAGNOSTIC: Detect state transition (temporary → final)
        val changed = if (oldStateHex == newStateHex) "" else " (CHANGED from $oldStateHex)"
        Log.i(TAG, "obfs4_state set: first 8 bytes = $newStateHex$changed")
        
        // Stack trace to identify which call set the state
        if (oldStateHex != "null" && oldStateHex != newStateHex) {
            Log.w(TAG, "⚠️ obfs4_state SWITCHED (race condition risk): $oldStateHex → $newStateHex")
            Thread.dumpStack()  // Log call stack to debug timing
        }
    }

    /**     * CRITICAL FIX: Derive temporary obfs4_state from deterministic rendezvous seed.
     * This allows polling to deobfuscate the nonce message BEFORE SPAKE2+ handshake completion.
     * 
     * Uses HKDF-like derivation: obfs4_state = SHA256(sessionToken || epoch || "obfs4-seed-v1")
     * This ensures both INITIATOR and RESPONDER derive identical obfs4_state values.
     */
    @androidx.annotation.VisibleForTesting
    fun deriveTemporaryObfs4State(sessionToken: ByteArray, epoch: Long): ByteArray {
        val epochBytes = epoch.toString().padStart(16, '0').toByteArray(Charsets.UTF_8)
        val infoBytes = "zerochat-obfs4-state-v1".toByteArray(Charsets.UTF_8)
        val input = sessionToken + epochBytes + infoBytes
        val output = ByteArray(64)  // 64 bytes to match SPAKE2+ derived output
        
        // Use libsodium's generic hash for HKDF-like derivation
        // This produces a deterministic 64-byte output from input
        try {
            sodium.cryptoGenericHash(output, 64, input, input.size.toLong(), null, 0)
            val stateHex = output.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }
            Log.i(TAG, "Derived temporary obfs4_state from deterministic seed: $stateHex (first 8 bytes)")
            return output
        } catch (e: Exception) {
            Log.e(TAG, "Failed to derive obfs4_state: ${e.message}")
            // Fallback: return zeros (will fail during polling, which is correct behavior)
            return ByteArray(64)
        }
    }

    /**     * BUG 3 FIX: Mark obfs4 context as ready. Call this after connection handshake
     * completes and before any polling begins.
     */
    fun markObfs4ContextReady() {
        obfs4ContextReady = true
        Log.i(TAG, "obfs4 context marked as ready")
    }

    /**
     * BUG 3 FIX: Check if obfs4 context is ready. Throws if not.
     * This ensures polling doesn't happen before decryption context is initialized.
     */
    private fun checkObfs4ContextReady() {
        if (!obfs4ContextReady) {
            throw IllegalStateException(
                "obfs4 context not ready - cannot poll before application-layer handshake complete. " +
                "Call markObfs4ContextReady() after connection handshake."
            )
        }
    }
    
    // CRITICAL: Buffer for out-of-order messages.
    // When polling for type X, if we receive type Y, we buffer it instead of
    // discarding it. Later when polling for type Y, we check the buffer first.
    // Without this, messages consumed from the Nym queue are permanently lost.
    private val messageBuffer = mutableListOf<Pair<Byte, ByteArray>>()

    /**
     * BUG 2 FIX: Strip 2-byte big-endian length prefix from Nym transport framing.
     * The Nym delivery layer prepends a 2-byte length prefix to messages.
     * This must be stripped before passing to application-layer parsers.
     * 
     * @param raw The raw payload from Nym (e.g., 1454 bytes)
     * @return The payload without the length prefix (e.g., 1452 bytes)
     * @throws IllegalArgumentException if length prefix doesn't match payload size
     */
    private fun stripLengthPrefix(raw: ByteArray): ByteArray {
        if (raw.size < 2) {
            return raw // Too short to have a length prefix
        }
        
        // Extract 2-byte big-endian length prefix
        val length = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
        
        // Validate length prefix matches actual payload size
        require(length == raw.size - 2) { 
            "Length prefix mismatch: expected $length but payload is ${raw.size - 2} bytes" 
        }
        
        // Strip the 2-byte prefix and return the actual payload
        return raw.drop(2).toByteArray()
    }
    
    /**
     * Derive SINGLE deterministic rendezvous point
     * Uses explicit epoch for strict synchronization
     */
    fun deriveRendezvousPoint(sharedSecret: String, epoch: Long, sessionToken: ByteArray): RendezvousPoint {
        val expiresAt = (epoch + 1) * EPOCH_DURATION_SECONDS * 1000L
        val rendezvousId = derivePointId(sharedSecret, epoch, "")
        
        Log.i(TAG, "Derived BASE rendezvous point: ${rendezvousId.take(16)}... (Epoch: $epoch)")
        
        return RendezvousPoint(
            id = rendezvousId,
            epoch = epoch,
            expiresAt = expiresAt,
            sharedSecret = sharedSecret,
            sessionToken = sessionToken
        )
    }
    
    private var activeRendezvousId: String? = null
    private var peerAddress: String? = null

    suspend fun getMyAddress(): String? {
        val id = activeRendezvousId ?: return null
        return controller.withTransport { it.getRendezvousAddress(id) }.getOrNull()
    }

    fun getPeerAddress(): String? = peerAddress
    
    fun getActiveRendezvousId(): String? = activeRendezvousId

    /**
     * STEP 1: Connect with Two-Slot Strategy
     * Tries Slot A, filters for "already open", then falls back to Slot B.
     * BUG 1 FIX: Uses unique gateway auth seed per peer to prevent identity collisions.
     */
    suspend fun connect(point: RendezvousPoint): Result<Unit> = connectionMutex.withLock {
        if (state.get() != RendezvousState.IDLE) {
            Log.w(TAG, "Preventing duplicate rendezvous connection. State: ${state.get()}")
            return Result.success(Unit)
        }

        // Enforce Cool-down
        val timeSinceTeardown = System.currentTimeMillis() - lastTeardownTime
        if (timeSinceTeardown < TEARDOWN_COOLDOWN_MS) {
            val wait = TEARDOWN_COOLDOWN_MS - timeSinceTeardown
            Log.i(TAG, "Cool-down active. Waiting ${wait}ms...")
            delay(wait)
        }

        try {
            transitionTo(RendezvousState.CONNECTING)

            // TWO-SLOT STRATEGY - Cryptographically distinct via suffix hashing
            val idA = derivePointId(point.sharedSecret!!, point.epoch, "_A")
            val idB = derivePointId(point.sharedSecret!!, point.epoch, "_B")

            Log.i(TAG, "Attempting Slot A ($idA) with deterministic gateway auth...")

            // Try Slot A with deterministic identity (both devices derive the same address)
            val resultA = controller.withTransport { it.connectRendezvous(idA) }

            if (resultA.isSuccess) {
                activeRendezvousId = idA
                assignedRole = Role.INITIATOR
                Log.i(TAG, "ROLE assigned: INITIATOR (connected to Slot A)")
                // Calculate Peer Address (Slot B)
                peerAddress = controller.withTransport { it.getRendezvousAddress(idB) }.getOrThrow()
                Log.i(TAG, "Connected to Slot A. Peer (Slot B) Address: $peerAddress")
            } else {
                // ANY Slot A failure → try Slot B
                // (could be "already open", gateway panic, stale session, etc.)
                val errorA = resultA.exceptionOrNull()?.message ?: "Unknown"
                Log.w(TAG, "Slot A failed: $errorA")

                // Explicitly tear down Slot A attempt to prevent "already open" gateway collisions
                try {
                    controller.withTransport { it.disconnectRendezvous(idA) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to disconnect Slot A: ${e.message}")
                }

                Log.i(TAG, "Draining Slot A connection. Waiting 300ms...")
                delay(300)
                Log.i(TAG, "Drain complete. Attempting Slot B ($idB) with deterministic gateway auth...")

                // Try Slot B with deterministic identity
                val resultB = controller.withTransport { it.connectRendezvous(idB) }
                if (resultB.isFailure) {
                    val errorB = resultB.exceptionOrNull() ?: Exception("Both slots failed")
                    Log.e(TAG, "Slot B also failed: ${errorB.message}")
                    throw errorB
                }

                activeRendezvousId = idB
                assignedRole = Role.RESPONDER
                Log.i(TAG, "ROLE assigned: RESPONDER (connected to Slot B)")
                // Calculate Peer Address (Slot A)
                peerAddress = controller.withTransport { it.getRendezvousAddress(idA) }.getOrThrow()
                Log.i(TAG, "Connected to Slot B. Peer (Slot A) Address: $peerAddress")
            }

            transitionTo(RendezvousState.CONNECTED)
            return Result.success(Unit)

        } catch (e: Exception) {
            handleConnectionError(e)
            return Result.failure(e)
        }
    }
    
    /**
     * STEP 2: Publish to Peer (Direct Message)
     */
    suspend fun publish(point: RendezvousPoint, payload: ByteArray): Result<Unit> = connectionMutex.withLock {
        if (!isValid(point)) return Result.failure(IllegalStateException("Expired"))
        
        val s = state.get()
        if (s != RendezvousState.CONNECTED && s != RendezvousState.PUBLISHED && s != RendezvousState.POLLING) {
             return Result.failure(IllegalStateException("Cannot publish in state $s. Must be CONNECTED."))
        }

        try {
            val targetAddress = peerAddress ?: return Result.failure(IllegalStateException("Peer address unknown"))
            
            // Encrypt payload with obfs4 before sending (Paper §6)
            val obfs4StateValue = obfs4State ?: return Result.failure(IllegalStateException("obfs4_state not ready"))
            val wrapper = Obfs4FrameWrapper(obfs4StateValue)
            val encryptedPayload = wrapper.encodeFrame(payload)
                ?: return Result.failure(Exception("Failed to encrypt message with obfs4"))
            
            Log.d(TAG, "Publishing encrypted payload: ${payload.size} bytes (plaintext) → ${encryptedPayload.size} bytes (ciphertext)")
            
            // Send encrypted message to the peer's rendezvous client address
            val sendResult = controller.withTransport { it.sendMessage(targetAddress.toByteArray(), encryptedPayload) }
            if (sendResult.isFailure) {
                throw sendResult.exceptionOrNull() ?: Exception("Send to peer failed")
            }
            
            if (s == RendezvousState.CONNECTED) {
                transitionTo(RendezvousState.PUBLISHED)
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            handleConnectionError(e)
            return Result.failure(e)
        }
    }

    /**
     * STEP 3: Poll (Poll MY Slot)
     * BUG 3 FIX: Checks obfs4 context is ready before polling to prevent
     * race conditions with message decryption.
     */
    fun poll(
        point: RendezvousPoint,
        ignoreBodies: Set<String> = emptySet(),
        expectedType: Byte? = null
    ): Flow<PollResult> = flow {

        if (state.get() != RendezvousState.PUBLISHED && state.get() != RendezvousState.CONNECTED && state.get() != RendezvousState.POLLING) {
             emit(PollResult.Timeout)
             return@flow
        }

        // BUG 3 FIX: Ensure obfs4 context is ready before polling
        // This prevents receiving messages before decryption context is initialized
        checkObfs4ContextReady()

        transitionTo(RendezvousState.POLLING)
        
        var attempts = 0
        // We poll our OWN active slot
        val mySlotId = activeRendezvousId ?: run {
            emit(PollResult.Timeout)
            return@flow
        }
        
        Log.i(TAG, "POLLING my slot: ${mySlotId.take(16)}... (Strict Mode)")
        
        // FIRST: Check if the message we need was already buffered from a previous poll
        if (expectedType != null) {
            val buffered = messageBuffer.find { (type, body) ->
                type == expectedType && !ignoreBodies.contains(body.toHexString())
            }
            if (buffered != null) {
                messageBuffer.remove(buffered)
                Log.i(TAG, "FOUND BUFFERED MESSAGE (Type=0x%02x)".format(buffered.first))
                emit(PollResult.Found(buffered.second, buffered.first))
                return@flow
            }
        }

        while (attempts < MAX_POLL_ATTEMPTS && isValid(point)) {
            // Strict check: if state shifted to TEARDOWN externally, abort
            if (state.get() == RendezvousState.TEARDOWN || state.get() == RendezvousState.IDLE) {
                emit(PollResult.Timeout)
                return@flow
            }
            
            emit(PollResult.Polling(attempts + 1, MAX_POLL_ATTEMPTS))
            
            // CRITICAL BUG FIX: Calculate canonical Slot A base point ID for obfs4 seeding.
            // Both INITIATOR and RESPONDER use this same ID to derive the obfs4 mask_key,
            // ensuring deobfuscation works regardless of which slot is being polled.
            val basePointId = derivePointId(point.sharedSecret!!, point.epoch, "_A")
            
            // BUG 5 FIX: Use obfs4_state from SPAKE2+ shared secret derivation
            // This 64-byte value was derived from SPAKE2+ shared secret via HKDF in Rust
            // and passed from ConnectionManager after handshake completes
            val obfs4StateValue = obfs4State ?: return@flow run {
                emit(PollResult.Timeout)
                Log.e(TAG, "Cannot poll: obfs4_state not set. " +
                    "Call setObfs4State() after SPAKE2+ handshake completes and before polling begins.")
            }
            
            // CRITICAL ASSERTION: obfs4_state must not be all zeros
            check(obfs4StateValue.any { it != 0.toByte() }) {
                "obfs4_state is all zeros — HKDF input (shared secret) was not set before key derivation. " +
                "This indicates a critical bug in SPAKE2+ key material handling."
            }
            val responses = try {
                 controller.withTransport { it.pollRendezvous(mySlotId, obfs4StateValue) }
            } catch (e: Exception) {
                 Log.e(TAG, "Poll failed: ${e.message}")
                 null
            }
            
            if (!responses.isNullOrEmpty()) {
                for (response in responses) {
                    // Transport layer (RealNymTransport.pollRendezvous) already handles
                    // obfs4 decoding and unpadding — payload is clean application data
                    val payload = response.payload
                    
                    val parsed = RendezvousFrame.parse(payload)
                    if (parsed == null) {
                        Log.w(TAG, "PARSE FAILED: payload ${payload.size} bytes, first 4: ${payload.take(4).joinToString(",") { "0x%02x".format(it) }}")
                        continue
                    }
                    val (type, msgEpoch, msgToken, body) = parsed
                    
                    if (msgEpoch != point.epoch) {
                        Log.w(TAG, "DROPPED stale message from epoch $msgEpoch (Expected: ${point.epoch})")
                        continue
                    }
                    
                    if (!msgToken.contentEquals(point.sessionToken)) {
                        Log.w(TAG, "DROPPED message from mismatched session token (Cross-session ghost)")
                        continue
                    }
                    
                    val bodyHex = body.toHexString()

                    if (ignoreBodies.contains(bodyHex)) continue

                    if (expectedType != null && type != expectedType) {
                        // BUFFER instead of discard! This message will be needed later.
                        Log.i(TAG, "BUFFERED out-of-order message (Got 0x%02x, Expected 0x%02x)".format(type, expectedType))
                        messageBuffer.add(type to body)
                        continue
                    }

                    Log.i(TAG, "ACCEPTED PEER MESSAGE (Type=0x%02x)".format(type))
                    emit(PollResult.Found(body, type))
                    return@flow
                }
            }
            
            delay(POLL_INTERVAL_MS)
            attempts++
        }
        
        if (!isValid(point)) emit(PollResult.Expired) else emit(PollResult.Timeout)
    }

    /**
     * STEP 4: Success & Teardown
     */
    suspend fun markHandshakeComplete() {
        handshakeComplete = true
        Log.i(TAG, "Epoch frozen after handshake.")
    }
    
    suspend fun teardownRendezvous() = connectionMutex.withLock {
        Log.i(TAG, "🔥 TEARDOWN: Cleanup rendezvous session...")
        transitionTo(RendezvousState.TEARDOWN)

        try {
            consumedRendezvous.clear()
            messageBuffer.clear()
            activeRendezvousId = null
            peerAddress = null
            handshakeComplete = false
            // BUG 3 FIX: Reset obfs4 context ready flag on teardown
            obfs4ContextReady = false
            // BUG 4 FIX: Reset role on teardown
            assignedRole = null
        } catch (e: Exception) {
            Log.e(TAG, "Teardown error", e)
        } finally {
            lastTeardownTime = System.currentTimeMillis()
            transitionTo(RendezvousState.IDLE)
        }
    }
    
    // --- INTERNAL HELPERS ---
    
    private fun transitionTo(newState: RendezvousState) {
        val old = state.getAndSet(newState)
        Log.d(TAG, "State Transition: $old -> $newState")
    }

    private suspend fun handleConnectionError(e: Throwable?) {
        val msg = e?.message ?: "Unknown error"
        Log.e(TAG, "Connection Error: $msg")
        
        if (msg.contains("already an open connection")) {
            Log.w(TAG, "CASE 1: Duplicate Connection. Forcing cleanup.")
            try { controller.withTransport { it.disconnectAllRendezvous() } } catch (_: Exception) { }
            transitionTo(RendezvousState.IDLE)
        } 
        else if (msg.contains("failed to verify")) {
            Log.e(TAG, "CASE 2: Handshake Corruption. CRITICAL RESET.")
            try { controller.withTransport { it.disconnectAllRendezvous() } } catch (_: Exception) { }
            transitionTo(RendezvousState.IDLE)
        }
        else {
             Log.e(TAG, "CASE 3: Generic/Rust Error. Forcing teardown.")
             try { controller.withTransport { it.disconnectAllRendezvous() } } catch (_: Exception) { }
             transitionTo(RendezvousState.IDLE)
        }
    }

    fun derivePointId(secret: String, epoch: Long, slotSuffix: String): String {
        val epochBytes = epoch.toString().toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val saltBytes = RENDEZVOUS_SALT.toByteArray(Charsets.UTF_8)
        val suffixBytes = slotSuffix.toByteArray(Charsets.UTF_8)
        val input = secretBytes + epochBytes + saltBytes + suffixBytes
        val output = ByteArray(RENDEZVOUS_LENGTH)
        sodium.cryptoGenericHash(output, RENDEZVOUS_LENGTH, input, input.size.toLong(), null, 0)
        return output.toHexString()
    }
    
    fun getCurrentEpoch(): Long = System.currentTimeMillis() / 1000 / EPOCH_DURATION_SECONDS
    
    fun isValid(point: RendezvousPoint): Boolean {
        if (!handshakeComplete) {
            // PRE-HANDSHAKE: Strict epoch enforcement (with 1-epoch overlap for boundary crossings)
            val currentEpoch = getCurrentEpoch()
            val isValidEpoch = (currentEpoch == point.epoch) || (currentEpoch == point.epoch + 1)
            
            if (!isValidEpoch) {
                Log.w(TAG, "Epoch mismatch during discovery. Point=${point.epoch}, Current=$currentEpoch. ABORTING.")
                return false
            }
        }
        // POST-HANDSHAKE: No epoch enforcement — peer is authenticated, accept all frames
        return !consumedRendezvous.contains(point.id)
    }
    
    fun clearAll() {
        consumedRendezvous.clear()
        messageBuffer.clear()
    }
    
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }
}

/**
 * Model 3 Message Framing Protocol
 */
sealed class RendezvousFrame(val type: Byte) {
    companion object {
        const val TYPE_NONCE: Byte = 0x00
        const val TYPE_SPAKE_A: Byte = 0x01
        const val TYPE_SPAKE_B: Byte = 0x02
        const val TYPE_CONFIRM: Byte = 0x03
        const val TYPE_HANDLE: Byte = 0x04

        /**
         * Wrap with strict length prefixing & epoch: [TYPE] [EPOCH(8)] [TOKEN(16)] [LEN_HI] [LEN_LO] [BODY]
         */
        fun wrap(type: Byte, epoch: Long, sessionToken: ByteArray, body: ByteArray): ByteArray {
            val len = body.size
            if (len > 65535) throw IllegalArgumentException("Body too large")
            if (sessionToken.size != 16) throw IllegalArgumentException("Session token must be precisely 16 bytes")
            
            val epochBytes = ByteArray(8)
            for (i in 0..7) {
                epochBytes[7 - i] = ((epoch ushr (i * 8)) and 0xFF).toByte()
            }
            
            val lenHi = (len shr 8).toByte()
            val lenLo = (len and 0xFF).toByte()
            
            return byteArrayOf(type) + epochBytes + sessionToken + byteArrayOf(lenHi, lenLo) + body
        }

        /**
         * Parse strict framing
         */
        data class ParsedFrame(val type: Byte, val epoch: Long, val sessionToken: ByteArray, val body: ByteArray)
        
        fun parse(payload: ByteArray): ParsedFrame? {
            if (payload.size < 27) return null
            
            val type = payload[0]
            
            var epoch: Long = 0
            for (i in 0..7) {
                epoch = (epoch shl 8) or (payload[i + 1].toLong() and 0xFF)
            }
            
            val sessionToken = payload.sliceArray(9 until 25)
            
            val lenHi = payload[25].toInt() and 0xFF
            val lenLo = payload[26].toInt() and 0xFF
            val length = (lenHi shl 8) or lenLo
            
            if (payload.size < 27 + length) {
                 return null
            }
            
            val body = payload.sliceArray(27 until 27 + length)
            return ParsedFrame(type, epoch, sessionToken, body)
        }
    }
}

data class RendezvousPoint(
    val id: String,
    val epoch: Long,
    val expiresAt: Long,
    val sharedSecret: String? = null, // Passed for live dynamic derivation in connect()
    val sessionToken: ByteArray       // 16-byte ephemeral token to isolate parallel sessions within the same epoch
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as RendezvousPoint

        if (id != other.id) return false
        if (epoch != other.epoch) return false
        if (expiresAt != other.expiresAt) return false
        if (sharedSecret != other.sharedSecret) return false
        if (!sessionToken.contentEquals(other.sessionToken)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + epoch.hashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + (sharedSecret?.hashCode() ?: 0)
        result = 31 * result + sessionToken.contentHashCode()
        return result
    }
}

sealed class PollResult {
    data class Polling(val attempt: Int, val max: Int) : PollResult()
    data class Found(val body: ByteArray, val type: Byte) : PollResult()
    object Timeout : PollResult()
    object Expired : PollResult()
}
