package com.zerochat.app.domain.messaging

import android.util.Log
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.NymTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Message Queue - Send/Receive buffering with encryption
 * 
 * Features:
 * - Send queue with retry logic
 * - Receive buffer for out-of-order messages
 * - Message deduplication
 * - Replay attack protection (nonce tracking)
 * - End-to-end encryption with session keys
 * 
 * Security:
 * - All messages encrypted before Nym transport
 * - Nonces prevent replay attacks
 * - Failed sends are retried with limits (FL-06)
 * - Silent failures (no distinguishable errors)
 */
@Singleton
class MessageQueue @Inject constructor(
    private val keyManager: KeyManager,
    private val routingHandleManager: RoutingHandleManager,
    private val connectionManagerProvider: javax.inject.Provider<com.zerochat.app.domain.connection.ConnectionManager>
) {
    
    companion object {
        private const val TAG = "MessageQueue"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_NONCE_WINDOW = 1000  // Accept messages within 1000 nonces
    }
    
    // Send queue: Messages waiting to be sent
    private val sendQueue = ConcurrentHashMap<Long, PendingMessage>()
    private val messageIdCounter = AtomicLong(0)
    
    // Receive tracking: Deduplicate and prevent replay
    private val receivedNonces = ConcurrentHashMap.newKeySet<Long>()
    private val lastReceivedNonce = AtomicLong(0)
    
    // Outgoing nonce counter
    private val outgoingNonce = AtomicLong(0)
    
    // Received messages flow
    private val _receivedMessages = MutableStateFlow<List<ReceivedMessage>>(emptyList())
    val receivedMessages: StateFlow<List<ReceivedMessage>> = _receivedMessages.asStateFlow()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Send a chat message
     * 
     * @param text Message text
     * @return Message ID for tracking
     */
    fun sendMessage(text: String): Long {
        val messageId = messageIdCounter.incrementAndGet()
        
        Log.i(TAG, "Queueing message #$messageId")
        
        // Generate new routing handle for this message
        val newHandle = routingHandleManager.generateMyHandle()
        
        // Create message with nonce
        val nonce = outgoingNonce.incrementAndGet()
        val message = ChatMessage(
            nonce = nonce,
            text = text,
            newHandle = newHandle
        )
        
        // Add to send queue
        val pending = PendingMessage(
            id = messageId,
            message = message,
            retries = 0
        )
        sendQueue[messageId] = pending
        
        // Send asynchronously
        scope.launch {
            sendWithRetry(pending)
        }
        
        return messageId
    }
    
    /**
     * Send message with retry logic
     */
    private suspend fun sendWithRetry(pending: PendingMessage) {
        var currentRetries = pending.retries
        
        while (currentRetries < MAX_RETRIES) {
            try {
                // Peer handle check removed for I2P streaming - stream handles routing
                // val peerHandle = routingHandleManager.getPeerHandle()
                
                // Serialize message
                val serialized = serializeChatMessage(pending.message)
                
                // Wrap in MessageProtocol (Type + Length)
                // Note: EncryptedChannel provides its own encryption (XChaCha20-Poly1305),
                // so we don't strictly need double encryption. However, to keep compatibility
                // with existing message structures or if we want nested layers, we can keep it.
                // For now, let's just send the serialized chat message directly or wrap it.
                // The EncryptedChannel expects plaintext, then encrypts it.
                
                // Let's stick to simple: [Type][Len][Payload] via MessageProtocol
                // We SKIP KeyManager.encryptMessage because EncryptedChannel handles security.
                
                val padded = MessageProtocol.serialize(MessageProtocol.TYPE_CHAT_MESSAGE, serialized)
                
                // Send through I2P Encrypted Channel
                val connectionManager = connectionManagerProvider.get()
                
                // Get channel or throw exception to trigger retry
                val channel = connectionManager.encryptedChannel ?: throw java.io.IOException("I2P Channel not ready")
                
                if (!channel.isConnected()) {
                    throw java.io.IOException("I2P Channel closed")
                }
                
                channel.send(padded)

                Log.i(TAG, "Message #${pending.id} sent via I2P")
                
                // Update our handle for next message (legacy Nym logic, maybe keep for nonce)
                routingHandleManager.rotateMyHandle(pending.message.newHandle)
                
                // Remove from queue
                sendQueue.remove(pending.id)
                return
                
            } catch (e: Exception) {
                currentRetries++
                Log.w(TAG, "Send attempt $currentRetries failed for message #${pending.id}: ${e.message}")
                
                if (currentRetries < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "Message #${pending.id} failed after $MAX_RETRIES retries")
                    sendQueue.remove(pending.id)
                }
            }
        }
    }
    
    /**
     * Process received message from Nym transport
     * 
     * @param encryptedPayload Encrypted message payload
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
                
                // Decrypt with session key
                // NOTE: EncryptedChannel already decrypted the outer layer.
                // If we skipped internal encryption in sendWithRetry, we skip it here too.
                // Current implementation: EncryptedChannel( [MessageProtocol [ChatMessage]] )
                
                // So 'payload' from MessageProtocol is just the serialized ChatMessage
                val decrypted = payload 

                
                // Deserialize chat message
                val chatMessage = deserializeChatMessage(decrypted) ?: run {
                    Log.w(TAG, "Failed to deserialize chat message")
                    return@launch
                }
                
                // Check for replay attack
                if (!isValidNonce(chatMessage.nonce)) {
                    Log.w(TAG, "Replay attack detected: nonce ${chatMessage.nonce}")
                    return@launch
                }
                
                // Mark nonce as received
                receivedNonces.add(chatMessage.nonce)
                lastReceivedNonce.set(maxOf(lastReceivedNonce.get(), chatMessage.nonce))
                
                // Update peer's routing handle
                routingHandleManager.setPeerHandle(chatMessage.newHandle)
                
                Log.i(TAG, "Received message: ${chatMessage.text}")
                
                // Add to received messages
                val received = ReceivedMessage(
                    nonce = chatMessage.nonce,
                    text = chatMessage.text,
                    timestamp = System.currentTimeMillis()
                )
                
                _receivedMessages.value = _receivedMessages.value + received
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing received message", e)
            }
        }
    }
    
    /**
     * Check if nonce is valid (not replayed, within window)
     */
    private fun isValidNonce(nonce: Long): Boolean {
        // Already received?
        if (receivedNonces.contains(nonce)) {
            return false
        }
        
        // Too old (outside window)?
        val lastNonce = lastReceivedNonce.get()
        if (lastNonce > 0 && nonce < lastNonce - MAX_NONCE_WINDOW) {
            return false
        }
        
        // Too far in future?
        if (nonce > lastNonce + MAX_NONCE_WINDOW) {
            return false
        }
        
        return true
    }
    
    /**
     * Serialize chat message
     * Format: [8 bytes: nonce] [32 bytes: new handle] [N bytes: text]
     */
    private fun serializeChatMessage(message: ChatMessage): ByteArray {
        val textBytes = message.text.toByteArray(Charsets.UTF_8)
        val result = ByteArray(8 + 32 + textBytes.size)
        
        // Write nonce (8 bytes, big-endian)
        var offset = 0
        for (i in 7 downTo 0) {
            result[offset++] = ((message.nonce shr (i * 8)) and 0xFF).toByte()
        }
        
        // Write new handle (32 bytes)
        message.newHandle.copyInto(result, offset)
        offset += 32
        
        // Write text
        textBytes.copyInto(result, offset)
        
        return result
    }
    
    /**
     * Deserialize chat message
     */
    private fun deserializeChatMessage(data: ByteArray): ChatMessage? {
        if (data.size < 40) {  // 8 + 32 minimum
            return null
        }
        
        // Read nonce (8 bytes, big-endian)
        var nonce = 0L
        for (i in 0..7) {
            nonce = (nonce shl 8) or (data[i].toLong() and 0xFF)
        }
        
        // Read new handle (32 bytes)
        val newHandle = data.copyOfRange(8, 40)
        
        // Read text
        val textBytes = data.copyOfRange(40, data.size)
        val text = String(textBytes, Charsets.UTF_8)
        
        return ChatMessage(nonce, text, newHandle)
    }
    
    /**
     * Clear all queues and state
     */
    fun clear() {
        sendQueue.clear()
        receivedNonces.clear()
        lastReceivedNonce.set(0)
        outgoingNonce.set(0)
        _receivedMessages.value = emptyList()
        scope.coroutineContext.cancelChildren()
    }
    
    /**
     * Get pending send count (for debugging)
     */
    fun getPendingSendCount(): Int = sendQueue.size
}

/**
 * Pending message in send queue
 */
private data class PendingMessage(
    val id: Long,
    val message: ChatMessage,
    val retries: Int
)

/**
 * Chat message structure
 */
private data class ChatMessage(
    val nonce: Long,
    val text: String,
    val newHandle: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ChatMessage
        return nonce == other.nonce &&
                text == other.text &&
                newHandle.contentEquals(other.newHandle)
    }

    override fun hashCode(): Int {
        var result = nonce.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + newHandle.contentHashCode()
        return result
    }
}

/**
 * Received message
 */
data class ReceivedMessage(
    val nonce: Long,
    val text: String,
    val timestamp: Long
)
