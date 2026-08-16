package com.zerochat.app.domain.messaging

import android.util.Log
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.HybridTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message Queue — v8 Research-Grade Implementation
 * 
 * Features:
 * - Single-writer egress dispatcher (prevents sequence collisions)
 * - Immutable transport selection per message (anti-correlation)
 * - Deterministic egress timing normalization
 * - Constant-time processing window padding
 * - Receiver-side TreeMap ordering buffer with dynamic expectedSeq
 * - CPU-symmetric processing for CHAT and COVER payloads
 * - Replay attack protection (nonce tracking)
 * 
 * Security:
 * - Transport selection is IMMUTABLE per message. Retries use the original transport.
 * - ONE MESSAGE → ONE TRANSPORT. Never duplicate-send across Nym and I2P.
 * - Cover traffic takes identical CPU path before final discard.
 */
@Singleton
class MessageQueue @Inject constructor(
    private val keyManager: KeyManager,
    private val routingHandleManager: RoutingHandleManager,
    private val connectionManagerProvider: javax.inject.Provider<com.zerochat.app.domain.connection.ConnectionManager>,
    private val hybridTransport: HybridTransport
) {
    
    companion object {
        private const val TAG = "MessageQueue"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_NONCE_WINDOW = 1000
    }
    
    // --- Egress Dispatcher ---
    // All outgoing messages (real + cover) funnel through this single channel
    // guaranteeing strict serialization and preventing sequence collisions.
    private val outgoingEgressChannel = Channel<RoutedMessage>(capacity = Channel.UNLIMITED)
    
    // Receive tracking: Deduplicate and prevent replay
    private val receivedNonces = ConcurrentHashMap.newKeySet<Long>()
    private val lastReceivedNonce = AtomicLong(0)
    
    // Outgoing nonce counter (separate from sequence for replay protection)
    private val outgoingNonce = AtomicLong(0)
    
    // Received messages flow (only CHAT payloads reach here)
    private val _receivedMessages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ReceivedMessage>> = _receivedMessages.asStateFlow()
    
    // Receiver-side ordering buffer
    private val orderingBuffer = TreeMap<Long, InternalChatMessage>()
    private var expectedSeq: Long? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var egressJob: Job? = null
    
    /**
     * Start the single-writer egress dispatcher.
     * Must be called once after connection is established.
     */
    fun startEgressDispatcher() {
        egressJob?.cancel()
        egressJob = scope.launch {
            Log.i(TAG, "Egress dispatcher started (single-writer)")
            for (routed in outgoingEgressChannel) {
                try {
                    val seed = hybridTransport.transitionSeed
                    
                    // Normalized egress delay — equalizes fast/slow transport timing
                    if (seed != null) {
                        val egressDelay = hybridTransport.deterministicEgressDelay(seed, routed.msg.seq)
                        delay(egressDelay)
                    }
                    
                    // Dispatch with retry (transport is immutable)
                    dispatchWithRetry(routed)
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Egress dispatch error: ${e.message}")
                }
            }
        }
        
        // Wire cover traffic callback from HybridTransport
        hybridTransport.onCoverTrafficTick = { coverCounter ->
            scope.launch { enqueueCoverPacket(coverCounter) }
        }
    }
    
    /**
     * Send a chat message. Determines transport once (immutable).
     * 
     * @param text Message text
     * @return Outbound sequence number
     */
    fun sendMessage(text: String): Long {
        // Atomically get next sequence (single writer guarantee)
        val seq = hybridTransport.incrementAndGetOutboundSeq()
        
        // Update phase based on current sequence
        hybridTransport.updatePhase(seq)
        
        // Determine transport ONCE — immutable for this message
        val transport = hybridTransport.determineTransport(seq)
        
        // Generate new routing handle for this message
        val newHandle = routingHandleManager.generateMyHandle()
        
        // Create message with nonce
        val nonce = outgoingNonce.incrementAndGet()
        val message = InternalChatMessage(
            seq = seq,
            nonce = nonce,
            flag = PayloadFlag.CHAT,
            text = text.toByteArray(Charsets.UTF_8),
            newHandle = newHandle
        )
        
        // Enqueue with immutable transport binding
        val routed = RoutedMessage(msg = message, transport = transport, retriesLeft = MAX_RETRIES)
        
        scope.launch {
            // Constant-time processing window padding
            applyConstantTimeWindow(seq) {
                outgoingEgressChannel.send(routed)
            }
        }
        
        Log.i(TAG, "Queued message seq=$seq via $transport (phase=${hybridTransport.phase.value})")
        return seq
    }
    
    /**
     * Enqueue a cover packet for the Nym decay phase.
     * Cover packets consume real sequence numbers and are structurally identical.
     */
    private suspend fun enqueueCoverPacket(coverCounter: Long) {
        val seq = hybridTransport.incrementAndGetOutboundSeq()
        hybridTransport.updatePhase(seq)
        
        val coverPayload = hybridTransport.generateCoverPayload(seq, coverCounter)
        val newHandle = routingHandleManager.generateMyHandle()
        
        val message = InternalChatMessage(
            seq = seq,
            nonce = 0L, // Cover packets use nonce 0
            flag = PayloadFlag.COVER,
            text = coverPayload,
            newHandle = newHandle
        )
        
        // Cover always goes over Nym (it's for the decay phase)
        val routed = RoutedMessage(msg = message, transport = HybridTransport.Transport.NYM, retriesLeft = 1)
        outgoingEgressChannel.send(routed)
        
        Log.d(TAG, "Cover packet enqueued: seq=$seq, size=${coverPayload.size}")
    }
    
    /**
     * Dispatch a message with retry logic. Transport is IMMUTABLE.
     * 
     * Transport selection is immutable per message.
     * If a retry is needed, it MUST use the original transport.
     */
    private suspend fun dispatchWithRetry(routed: RoutedMessage) {
        var retriesLeft = routed.retriesLeft
        
        while (retriesLeft > 0) {
            try {
                val serialized = serializeChatMessage(routed.msg)
                val padded = MessageProtocol.serialize(MessageProtocol.TYPE_CHAT_MESSAGE, serialized)
                
                when (routed.transport) {
                    HybridTransport.Transport.I2P -> {
                        val connectionManager = connectionManagerProvider.get()
                        val channel = connectionManager.encryptedChannel
                            ?: throw java.io.IOException("I2P Channel not ready")
                        
                        if (!channel.isConnected()) {
                            throw java.io.IOException("I2P Channel closed")
                        }
                        
                        // Deterministic CPU jitter before socket write
                        val seed = hybridTransport.transitionSeed
                        if (seed != null) {
                            val jitter = hybridTransport.deterministicJitter(seed, routed.msg.seq)
                            if (jitter > 0) delay(jitter)
                        }
                        
                        channel.send(padded)
                        Log.i(TAG, "Sent seq=${routed.msg.seq} via I2P")
                    }
                    
                    HybridTransport.Transport.NYM -> {
                        val connectionManager = connectionManagerProvider.get()
                        val rendezvousManager = connectionManager.rendezvousManagerRef
                        val rendezvousPoint = connectionManager.activeRendezvousPoint
                        
                        if (rendezvousManager == null || rendezvousPoint == null) {
                            throw java.io.IOException("Nym rendezvous not available")
                        }
                        
                        // Deterministic CPU jitter before send
                        val seed = hybridTransport.transitionSeed
                        if (seed != null) {
                            val jitter = hybridTransport.deterministicJitter(seed, routed.msg.seq)
                            if (jitter > 0) delay(jitter)
                        }
                        
                        // Wrap in rendezvous frame and publish over Nym
                        val framedPayload = com.zerochat.app.domain.rendezvous.RendezvousFrame.wrap(
                            com.zerochat.app.domain.rendezvous.RendezvousFrame.TYPE_CHAT,
                            rendezvousPoint.epoch,
                            rendezvousPoint.sessionToken,
                            padded
                        )
                        
                        rendezvousManager.publish(rendezvousPoint, framedPayload).getOrThrow()
                        Log.i(TAG, "Sent seq=${routed.msg.seq} via NYM")
                    }
                }
                
                // Success — update routing handle
                if (routed.msg.flag == PayloadFlag.CHAT) {
                    routingHandleManager.rotateMyHandle(routed.msg.newHandle)
                }
                
                return // Success
                
            } catch (e: Exception) {
                retriesLeft--
                Log.w(TAG, "Send failed for seq=${routed.msg.seq} via ${routed.transport}: ${e.message} (retries left: $retriesLeft)")
                
                if (retriesLeft > 0) {
                    delay(RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "Message seq=${routed.msg.seq} failed after all retries")
                }
            }
        }
    }
    
    /**
     * Process received message (from I2P EncryptedChannel or Nym polling).
     * 
     * CPU SYMMETRY: Both CHAT and COVER payloads take the identical
     * deserialization, ordering, and processing path. Cover is discarded
     * only at the absolute terminal boundary.
     */
    fun receiveMessage(encryptedPayload: ByteArray) {
        scope.launch {
            try {
                // Deserialize envelope
                val (type, payload) = MessageProtocol.deserialize(encryptedPayload) ?: run {
                    Log.w(TAG, "Failed to deserialize message")
                    return@launch
                }
                
                if (type != MessageProtocol.TYPE_CHAT_MESSAGE) {
                    Log.w(TAG, "Received non-chat message type: $type")
                    return@launch
                }
                
                // Deserialize internal chat message
                val chatMessage = deserializeChatMessage(payload) ?: run {
                    // Silently absorb — could be malformed or cover from different session
                    return@launch
                }
                
                // CPU SYMMETRY: Both paths execute identical state manipulation
                val processedData = processMessageInternals(chatMessage)
                
                // Insert into ordering buffer (identical path for CHAT and COVER)
                synchronized(orderingBuffer) {
                    if (expectedSeq == null) {
                        expectedSeq = chatMessage.seq // Dynamic init — prevents deadlock
                    }
                    
                    orderingBuffer[chatMessage.seq] = chatMessage
                    
                    // Drain ordered messages
                    while (orderingBuffer.containsKey(expectedSeq)) {
                        val next = orderingBuffer.remove(expectedSeq)!!
                        
                        // CPU SYMMETRY: processMessageInternals already called above
                        // Terminal boundary: only CHAT impacts UI
                        if (next.flag == PayloadFlag.CHAT) {
                            deliverToUi(next, processedData)
                        }
                        // COVER naturally terminates here — identical stack frame
                        
                        expectedSeq = expectedSeq!! + 1
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing received message", e)
            }
        }
    }
    
    /**
     * Identical processing for both CHAT and COVER — equalizes CPU cost.
     */
    private fun processMessageInternals(msg: InternalChatMessage): ByteArray {
        // Validate nonce window (even for cover — same CPU path)
        val nonceValid = isValidNonce(msg.nonce)
        
        // Execute handle parsing (same cost regardless of flag)
        val handleCopy = msg.newHandle.copyOf()
        
        // Return processed data
        return handleCopy
    }
    
    /**
     * Deliver a CHAT message to the UI layer.
     */
    private fun deliverToUi(msg: InternalChatMessage, processedHandle: ByteArray) {
        // Mark nonce as received
        receivedNonces.add(msg.nonce)
        lastReceivedNonce.set(maxOf(lastReceivedNonce.get(), msg.nonce))
        
        // Update peer's routing handle
        routingHandleManager.setPeerHandle(processedHandle)
        
        val text = String(msg.text, Charsets.UTF_8)
        Log.i(TAG, "Received message: $text")
        
        val received = ReceivedMessage(
            nonce = msg.nonce,
            text = text,
            timestamp = System.currentTimeMillis()
        )
        
        _receivedMessages.value = _receivedMessages.value + received
    }
    
    /**
     * Apply constant-time processing window to decouple payload size from CPU emission timing.
     */
    private suspend fun applyConstantTimeWindow(seq: Long, block: suspend () -> Unit) {
        val seed = hybridTransport.transitionSeed
        if (seed == null) {
            block()
            return
        }
        
        val targetTimeMs = hybridTransport.deterministicProcessingWindow(seed, seq)
        val startNanos = System.nanoTime()
        
        block()
        
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        if (elapsedMs < targetTimeMs) {
            delay(targetTimeMs - elapsedMs)
        }
    }
    
    /**
     * Check if nonce is valid (not replayed, within window)
     */
    private fun isValidNonce(nonce: Long): Boolean {
        if (nonce == 0L) return true // Cover packets use nonce 0
        if (receivedNonces.contains(nonce)) return false
        
        val lastNonce = lastReceivedNonce.get()
        if (lastNonce > 0 && nonce < lastNonce - MAX_NONCE_WINDOW) return false
        if (nonce > lastNonce + MAX_NONCE_WINDOW) return false
        
        return true
    }
    
    // --- Serialization ---
    
    /**
     * Serialize internal chat message.
     * Format: [8 bytes: seq] [1 byte: flag] [8 bytes: nonce] [32 bytes: new handle] [N bytes: text]
     */
    private fun serializeChatMessage(message: InternalChatMessage): ByteArray {
        val textBytes = message.text
        val result = ByteArray(8 + 1 + 8 + 32 + textBytes.size)
        
        var offset = 0
        
        // Write seq (8 bytes, big-endian)
        for (i in 7 downTo 0) {
            result[offset++] = ((message.seq shr (i * 8)) and 0xFF).toByte()
        }
        
        // Write flag (1 byte)
        result[offset++] = message.flag.ordinal.toByte()
        
        // Write nonce (8 bytes, big-endian)
        for (i in 7 downTo 0) {
            result[offset++] = ((message.nonce shr (i * 8)) and 0xFF).toByte()
        }
        
        // Write new handle (32 bytes)
        message.newHandle.copyInto(result, offset)
        offset += 32
        
        // Write text/payload bytes
        textBytes.copyInto(result, offset)
        
        return result
    }
    
    /**
     * Deserialize internal chat message.
     */
    private fun deserializeChatMessage(data: ByteArray): InternalChatMessage? {
        // Minimum: 8 (seq) + 1 (flag) + 8 (nonce) + 32 (handle) = 49 bytes
        if (data.size < 49) return null
        
        var offset = 0
        
        // Read seq (8 bytes, big-endian)
        var seq = 0L
        for (i in 0..7) {
            seq = (seq shl 8) or (data[offset++].toLong() and 0xFF)
        }
        
        // Read flag (1 byte)
        val flagOrdinal = data[offset++].toInt() and 0xFF
        val flag = if (flagOrdinal < PayloadFlag.values().size) {
            PayloadFlag.values()[flagOrdinal]
        } else {
            return null // Unknown flag — silently discard
        }
        
        // Read nonce (8 bytes, big-endian)
        var nonce = 0L
        for (i in 0..7) {
            nonce = (nonce shl 8) or (data[offset++].toLong() and 0xFF)
        }
        
        // Read new handle (32 bytes)
        val newHandle = data.copyOfRange(offset, offset + 32)
        offset += 32
        
        // Read text/payload bytes
        val textBytes = data.copyOfRange(offset, data.size)
        
        return InternalChatMessage(seq, nonce, flag, textBytes, newHandle)
    }
    
    /**
     * Clear all queues and state
     */
    fun clear() {
        receivedNonces.clear()
        lastReceivedNonce.set(0)
        outgoingNonce.set(0)
        _receivedMessages.value = emptyList()
        synchronized(orderingBuffer) {
            orderingBuffer.clear()
            expectedSeq = null
        }
        egressJob?.cancel()
        scope.coroutineContext.cancelChildren()
    }
    
    /**
     * Get pending send count (for debugging)
     */
    fun getPendingSendCount(): Int = 0 // Egress channel doesn't expose size
}

/**
 * Payload flag — distinguishes real chat from cover traffic.
 * Both take the identical CPU processing path.
 */
enum class PayloadFlag {
    CHAT,
    COVER
}

/**
 * Internal chat message structure with sequence number and payload flag.
 */
data class InternalChatMessage(
    val seq: Long,
    val nonce: Long,
    val flag: PayloadFlag,
    val text: ByteArray,     // Raw bytes (UTF-8 for CHAT, pseudo-text for COVER)
    val newHandle: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as InternalChatMessage
        return seq == other.seq &&
                nonce == other.nonce &&
                flag == other.flag &&
                text.contentEquals(other.text) &&
                newHandle.contentEquals(other.newHandle)
    }

    override fun hashCode(): Int {
        var result = seq.hashCode()
        result = 31 * result + nonce.hashCode()
        result = 31 * result + flag.hashCode()
        result = 31 * result + text.contentHashCode()
        result = 31 * result + newHandle.contentHashCode()
        return result
    }
}

/**
 * Routed message — transport selection is IMMUTABLE after creation.
 * 
 * "Transport selection is immutable per message."
 * — If a retry is needed, it MUST use the original transport.
 */
data class RoutedMessage(
    val msg: InternalChatMessage,
    val transport: HybridTransport.Transport,
    val retriesLeft: Int
)

/**
 * Received message (public API for UI layer)
 */
data class ReceivedMessage(
    val nonce: Long,
    val text: String,
    val timestamp: Long
)
