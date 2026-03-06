package com.zerochat.app.domain.rendezvous

import android.util.Log
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
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
        const val MAX_POLL_ATTEMPTS = 60
        private const val RENDEZVOUS_SALT = "zerochat-rendezvous"
        
        // TEARDOWN COOL-DOWN (5s)
        private const val TEARDOWN_COOLDOWN_MS = 5000L
    }

    // Track consumed points to prevent reuse in same session
    private val consumedRendezvous = mutableSetOf<String>()
    
    // CRITICAL: Buffer for out-of-order messages.
    // When polling for type X, if we receive type Y, we buffer it instead of
    // discarding it. Later when polling for type Y, we check the buffer first.
    // Without this, messages consumed from the Nym queue are permanently lost.
    private val messageBuffer = mutableListOf<Pair<Byte, ByteArray>>()
    
    /**
     * Derive SINGLE deterministic rendezvous point
     * Uses explicit epoch for strict synchronization
     */
    fun deriveRendezvousPoint(sharedSecret: String, epoch: Long): RendezvousPoint {
        val expiresAt = (epoch + 1) * EPOCH_DURATION_SECONDS * 1000L
        val rendezvousId = derivePointId(sharedSecret, epoch)
        
        Log.i(TAG, "Derived SINGLE rendezvous point: ${rendezvousId.take(16)}... (Epoch: $epoch)")
        
        return RendezvousPoint(
            id = rendezvousId,
            epoch = epoch,
            expiresAt = expiresAt
        )
    }
    
    private var activeRendezvousId: String? = null
    private var peerAddress: String? = null

    suspend fun getMyAddress(): String? {
        val id = activeRendezvousId ?: return null
        return controller.withTransport { it.getRendezvousAddress(id) }.getOrNull()
    }

    fun getPeerAddress(): String? = peerAddress

    /**
     * STEP 1: Connect with Two-Slot Strategy
     * Tries Slot A, filters for "already open", then falls back to Slot B.
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
            
            // TWO-SLOT STRATEGY
            val baseId = point.id
            val idA = baseId + "_A"
            val idB = baseId + "_B"
            
            Log.i(TAG, "Attempting Slot A ($idA)...")
            
            // Try Slot A - MUST check Result properly
            val resultA = controller.withTransport { it.connectRendezvous(idA) }
            
            if (resultA.isSuccess) {
                activeRendezvousId = idA
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
                Log.i(TAG, "Drain complete. Attempting Slot B ($idB)...")
                
                val resultB = controller.withTransport { it.connectRendezvous(idB) }
                if (resultB.isFailure) {
                    val errorB = resultB.exceptionOrNull() ?: Exception("Both slots failed")
                    Log.e(TAG, "Slot B also failed: ${errorB.message}")
                    throw errorB
                }
                
                activeRendezvousId = idB
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
            
            // Send directly to the peer's rendezvous client address
            val sendResult = controller.withTransport { it.sendMessage(targetAddress.toByteArray(), payload) }
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
            
            // Poll using existing connection
            val responses = try {
                 controller.withTransport { it.pollRendezvous(mySlotId) }
            } catch (e: Exception) {
                 Log.e(TAG, "Poll failed: ${e.message}")
                 null
            }
            
            if (!responses.isNullOrEmpty()) {
                for (response in responses) {
                    val parsed = RendezvousFrame.parse(response.payload) ?: continue
                    val (type, body) = parsed
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

    private fun derivePointId(secret: String, epoch: Long): String {
        val epochBytes = epoch.toString().toByteArray(Charsets.UTF_8)
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        val saltBytes = RENDEZVOUS_SALT.toByteArray(Charsets.UTF_8)
        val input = secretBytes + epochBytes + saltBytes
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
         * Wrap with strict length prefixing: [TYPE] [LEN_HI] [LEN_LO] [BODY]
         */
        fun wrap(type: Byte, body: ByteArray): ByteArray {
            val len = body.size
            if (len > 65535) throw IllegalArgumentException("Body too large")
            
            val lenHi = (len shr 8).toByte()
            val lenLo = (len and 0xFF).toByte()
            
            return byteArrayOf(type, lenHi, lenLo) + body
        }

        /**
         * Parse strict framing
         */
        fun parse(payload: ByteArray): Pair<Byte, ByteArray>? {
            if (payload.size < 3) return null
            
            val type = payload[0]
            val lenHi = payload[1].toInt() and 0xFF
            val lenLo = payload[2].toInt() and 0xFF
            val length = (lenHi shl 8) or lenLo
            
            if (payload.size < 3 + length) {
                 // Incomplete frame?
                 return null
            }
            
            val body = payload.sliceArray(3 until 3 + length)
            return type to body
        }
    }
}

data class RendezvousPoint(
    val id: String,
    val epoch: Long,
    val expiresAt: Long
)

sealed class PollResult {
    data class Polling(val attempt: Int, val max: Int) : PollResult()
    data class Found(val body: ByteArray, val type: Byte) : PollResult()
    object Timeout : PollResult()
    object Expired : PollResult()
}
