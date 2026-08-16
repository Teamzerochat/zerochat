package com.zerochat.app.domain.group

import android.util.Log
import com.zerochat.app.domain.messaging.MessageProtocol
import com.zerochat.app.domain.transport.TransportController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Group Message Queue — Single-Client Fan-Out Egress & Causal Ordering
 *
 * Responsibilities:
 * 1. **Fan-Out Egress:** When a user sends a message, dispatch N-1 individual
 *    Sphinx packets to the respective peer slots.
 * 2. **Constant-Time Window:** Pad and normalize egress timing with HMAC
 *    micro-jitter (10-120ms) to prevent burst correlation.
 * 3. **Vector Clock Sync:** Maintain and embed vector clocks for causal ordering.
 * 4. **Deduplication:** Track seen nonces to drop duplicate messages.
 * 5. **Cover Traffic:** Periodically emit encrypted cover packets on all peer
 *    slots, indistinguishable from real messages.
 * 6. **Causal Reordering:** Buffer out-of-order messages and deliver to UI in
 *    causal sequence via priority queue.
 *
 * Transport: Uses the shared TransportController without modification.
 * Each outbound message is wrapped in a standard TYPE_CHAT (0x05) envelope.
 */
class GroupMessageQueue(
    private val controller: TransportController,
    private val cryptoManager: GroupCryptoManager,
    private val myMemberIndex: Int,
    private val memberCount: Int,
    private val peerSlotPointIds: List<String> // N-1 peer slot IDs (excluding self)
) {
    companion object {
        private const val TAG = "GroupMessageQueue"
        private const val EGRESS_JITTER_MIN_MS = 10L
        private const val EGRESS_JITTER_MAX_MS = 120L
        private const val COVER_TRAFFIC_INTERVAL_MS = 5_000L
        private const val RECEIVE_POLL_INTERVAL_MS = 1_500L
        private const val MAX_PENDING_BUFFER = 128
        private const val JITTER_HMAC_INFO = "EGRESS_JITTER"
    }

    // Vector clock: one entry per group member
    private val vectorClock = LongArray(memberCount) { 0L }

    // Monotonic group nonce (replay protection)
    private val groupNonce = AtomicLong(0)

    // Deduplication set: tracks seen (senderToken || groupNonce) pairs
    private val seenNonces = ConcurrentHashMap.newKeySet<String>()

    // Incoming message flow (delivered in causal order to UI)
    private val _incomingMessages = MutableSharedFlow<GroupChatMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<GroupChatMessage> = _incomingMessages.asSharedFlow()

    // Causal reordering buffer
    private val pendingBuffer = PriorityBlockingQueue<PendingMessage>(
        MAX_PENDING_BUFFER,
        compareBy { it.causalPriority }
    )

    // Background job handles
    private var coverTrafficJob: Job? = null
    private var receiveJob: Job? = null

    // Session active flag
    @Volatile
    private var active = false

    /**
     * Send a chat message to all group peers.
     *
     * Flow:
     * 1. Increment own vector clock entry.
     * 2. Build inner payload (Layer 1) with flag=CHAT.
     * 3. Encrypt with K_group (Layer 2) → 1024 bytes.
     * 4. Wrap in standard TYPE_CHAT transport frame.
     * 5. Fan-out to N-1 peer slots with HMAC jitter.
     *
     * @param text Chat message text
     * @return true if sent to at least one peer
     */
    suspend fun sendMessage(text: String): Boolean {
        if (!active) {
            Log.w(TAG, "Cannot send: queue not active")
            return false
        }

        // Step 1: Increment my vector clock
        vectorClock[myMemberIndex]++

        // Step 2: Build inner payload
        val contentBytes = text.toByteArray(Charsets.UTF_8)
        val innerPayload = cryptoManager.buildInnerPayload(
            senderIndex = myMemberIndex,
            groupNonce = groupNonce.getAndIncrement(),
            vectorClock = vectorClock,
            flag = GroupInnerPayload.FLAG_CHAT,
            content = contentBytes
        )

        // Step 3: Encrypt → 1024 bytes
        val encrypted = cryptoManager.encrypt(innerPayload)
        if (encrypted == null) {
            Log.e(TAG, "Failed to encrypt group message")
            return false
        }

        // Step 4: Wrap in standard TYPE_CHAT frame (reuses existing transport frame)
        val frame = MessageProtocol.serialize(MessageProtocol.TYPE_CHAT_MESSAGE, encrypted)

        // Step 5: Fan-out to N-1 peers with jitter
        return fanOut(frame)
    }

    /**
     * Start the inbound message receive loop and cover traffic generator.
     *
     * @param scope CoroutineScope for background work
     * @param claimedPointId The point ID of this device's claimed slot (for polling)
     */
    fun start(scope: CoroutineScope, claimedPointId: String) {
        if (active) return
        active = true

        // Start receive loop
        receiveJob = scope.launch {
            Log.i(TAG, "Receive loop started on slot $claimedPointId")
            while (isActive && active) {
                try {
                    receiveAndProcess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Receive error (non-fatal)", e)
                }
                delay(RECEIVE_POLL_INTERVAL_MS)
            }
        }

        // Start cover traffic
        coverTrafficJob = scope.launch {
            Log.i(TAG, "Cover traffic started (interval: ${COVER_TRAFFIC_INTERVAL_MS}ms)")
            while (isActive && active) {
                delay(COVER_TRAFFIC_INTERVAL_MS)
                try {
                    sendCoverTraffic()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.v(TAG, "Cover traffic error (non-fatal)", e)
                }
            }
        }
    }

    /**
     * Stop all background work and clean up.
     */
    fun stop() {
        active = false
        coverTrafficJob?.cancel()
        receiveJob?.cancel()
        seenNonces.clear()
        pendingBuffer.clear()
        vectorClock.fill(0)
        groupNonce.set(0)
        Log.i(TAG, "Group message queue stopped")
    }

    /**
     * Get the current vector clock state (for debugging/UI).
     */
    fun getVectorClock(): LongArray = vectorClock.copyOf()

    // --- Private Implementation ---

    /**
     * Fan-out a framed message to all N-1 peer slots with HMAC jitter.
     */
    private suspend fun fanOut(frame: ByteArray): Boolean {
        var successCount = 0

        for (peerPointId in peerSlotPointIds) {
            try {
                // HMAC jitter: derive per-peer delay to decorrelate timing
                val jitterMs = computeJitter(peerPointId)
                delay(jitterMs)

                controller.withTransport { transport ->
                    transport.sendMessage(
                        handle = peerPointId.toByteArray(Charsets.UTF_8),
                        payload = frame
                    )
                }
                successCount++
            } catch (e: Exception) {
                Log.w(TAG, "Fan-out to peer slot failed: ${e.message}")
            }
        }

        Log.d(TAG, "Fan-out complete: $successCount/${peerSlotPointIds.size} peers")
        return successCount > 0
    }

    /**
     * Receive and process an incoming message from the claimed slot.
     */
    private suspend fun receiveAndProcess() {
        val message = controller.withTransport { transport ->
            transport.receiveMessage(timeoutMs = 500)
        } ?: return

        // Unwrap the standard TYPE_CHAT frame
        val parsed = MessageProtocol.deserialize(message.payload) ?: return
        val (type, payload) = parsed

        if (type != MessageProtocol.TYPE_CHAT_MESSAGE) return
        if (payload.size != GroupCryptoManager.ENCRYPTED_SIZE) return

        // Decrypt AEAD (Layer 2)
        val innerPayload = cryptoManager.decrypt(payload) ?: return

        // Parse inner payload (Layer 1)
        val inner = cryptoManager.parseInnerPayload(innerPayload, memberCount) ?: return

        // Deduplication check
        val deduplicationKey = inner.senderToken.joinToString("") { "%02x".format(it) } +
                inner.groupNonce.toString()
        if (!seenNonces.add(deduplicationKey)) {
            Log.d(TAG, "Duplicate message dropped (nonce: ${inner.groupNonce})")
            return
        }

        // Skip cover traffic (silently consumed)
        if (inner.flag == GroupInnerPayload.FLAG_COVER) {
            Log.v(TAG, "Cover traffic consumed")
            return
        }

        // Update vector clock (merge)
        mergeVectorClock(inner.vectorClock)

        // Check causal order
        if (isCausallyReady(inner)) {
            // Deliver immediately
            deliverMessage(inner)
            // Check if any pending messages are now deliverable
            drainPendingBuffer()
        } else {
            // Buffer for later delivery
            if (pendingBuffer.size < MAX_PENDING_BUFFER) {
                pendingBuffer.add(PendingMessage(inner, computeCausalPriority(inner.vectorClock)))
            } else {
                Log.w(TAG, "Pending buffer full, dropping message")
            }
        }
    }

    /**
     * Send cover traffic to all peer slots.
     * Cover packets are identical in size and encryption to real messages,
     * with flag=0x02 inside the AEAD envelope (invisible to observer).
     */
    private suspend fun sendCoverTraffic() {
        val coverContent = ByteArray(64).also { SecureRandom().nextBytes(it) }
        val innerPayload = cryptoManager.buildInnerPayload(
            senderIndex = myMemberIndex,
            groupNonce = groupNonce.getAndIncrement(),
            vectorClock = vectorClock,
            flag = GroupInnerPayload.FLAG_COVER,
            content = coverContent
        )

        val encrypted = cryptoManager.encrypt(innerPayload) ?: return
        val frame = MessageProtocol.serialize(MessageProtocol.TYPE_CHAT_MESSAGE, encrypted)

        fanOut(frame)
    }

    /**
     * Deliver a decrypted message to the UI via SharedFlow.
     */
    private suspend fun deliverMessage(inner: GroupInnerPayload) {
        val chatMessage = GroupChatMessage(
            senderIndex = inner.senderIndex,
            text = String(inner.content, Charsets.UTF_8),
            groupNonce = inner.groupNonce,
            vectorClock = inner.vectorClock,
            timestamp = System.currentTimeMillis()
        )
        _incomingMessages.emit(chatMessage)
    }

    /**
     * Merge a received vector clock with our local clock.
     * For each entry: local[i] = max(local[i], received[i])
     */
    private fun mergeVectorClock(received: LongArray) {
        for (i in vectorClock.indices) {
            if (i < received.size) {
                vectorClock[i] = maxOf(vectorClock[i], received[i])
            }
        }
    }

    /**
     * Check if a message is causally ready (all preceding causal dependencies met).
     */
    private fun isCausallyReady(inner: GroupInnerPayload): Boolean {
        val senderIndex = inner.senderIndex
        val receivedClock = inner.vectorClock
        if (senderIndex !in vectorClock.indices) return true

        val isNext = receivedClock[senderIndex] <= vectorClock[senderIndex] + 1
        val dependenciesMet = vectorClock.indices.all { i ->
            i == senderIndex || (i < receivedClock.size && receivedClock[i] <= vectorClock[i])
        }
        return isNext && dependenciesMet
    }

    /**
     * Drain the pending buffer, delivering messages that are now causally ready.
     */
    private suspend fun drainPendingBuffer() {
        while (pendingBuffer.isNotEmpty()) {
            val peek = pendingBuffer.peek() ?: break
            if (isCausallyReady(peek.inner)) {
                pendingBuffer.poll()
                deliverMessage(peek.inner)
            } else {
                break
            }
        }
    }

    /**
     * Compute HMAC-derived jitter delay for a given peer slot ID.
     * Deterministic per-peer to ensure consistent timing patterns.
     */
    private fun computeJitter(peerPointId: String): Long {
        val mac = Mac.getInstance("HmacSHA256")
        val key = SecretKeySpec(
            JITTER_HMAC_INFO.toByteArray(Charsets.UTF_8),
            "HmacSHA256"
        )
        mac.init(key)
        val hash = mac.doFinal(peerPointId.toByteArray(Charsets.UTF_8))
        val value = (hash[0].toLong() and 0xFF)
        return EGRESS_JITTER_MIN_MS + (value % (EGRESS_JITTER_MAX_MS - EGRESS_JITTER_MIN_MS + 1))
    }

    /**
     * Compute causal priority for buffered message ordering.
     * Lower priority = earlier delivery.
     */
    private fun computeCausalPriority(clock: LongArray): Long {
        return clock.sum()
    }
}

/**
 * A chat message delivered to the UI.
 */
data class GroupChatMessage(
    val senderIndex: Int,
    val text: String,
    val groupNonce: Long,
    val vectorClock: LongArray,
    val timestamp: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GroupChatMessage
        return groupNonce == other.groupNonce && text == other.text
    }

    override fun hashCode(): Int {
        var result = groupNonce.hashCode()
        result = 31 * result + text.hashCode()
        return result
    }
}

/**
 * Buffered message awaiting causal delivery.
 */
private data class PendingMessage(
    val inner: GroupInnerPayload,
    val causalPriority: Long
)
