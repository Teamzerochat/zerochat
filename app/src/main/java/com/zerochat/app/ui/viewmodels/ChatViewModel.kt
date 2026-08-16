package com.zerochat.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.connection.ConnectionManager
import com.zerochat.app.domain.messaging.MessageQueue
import com.zerochat.app.domain.messaging.ReceivedMessage
import com.zerochat.app.domain.transport.HybridTransport
import com.zerochat.app.domain.transport.TransportUiEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ChatViewModel - Manages chat messages with dual-transport UI awareness
 * 
 * Exposes message list, handles sending, and shows transport transition toasts.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val messageQueue: MessageQueue,
    private val connectionManager: ConnectionManager,
    private val hybridTransport: HybridTransport
) : ViewModel() {
    
    // Received messages from peer
    val receivedMessages: StateFlow<List<ReceivedMessage>> = messageQueue.receivedMessages
    
    // Sent messages (local tracking)
    private val _sentMessages = MutableStateFlow<List<SentMessage>>(emptyList())
    val sentMessages: StateFlow<List<SentMessage>> = _sentMessages.asStateFlow()
    
    // Combined messages for UI
    private val _allMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val allMessages: StateFlow<List<ChatMessage>> = _allMessages.asStateFlow()
    
    // Message input
    private val _messageInput = MutableStateFlow("")
    val messageInput: StateFlow<String> = _messageInput.asStateFlow()
    
    // Transport UI events (for Toast notifications)
    private val _transportToast = MutableStateFlow<String?>(null)
    val transportToast: StateFlow<String?> = _transportToast.asStateFlow()
    
    // Current transport phase (for status chip)
    val transportPhase: StateFlow<HybridTransport.Phase> = hybridTransport.phase
    
    init {
        // Combine sent and received messages
        viewModelScope.launch {
            receivedMessages.collect { received ->
                updateAllMessages()
            }
        }
        
        // Listen for transport UI events
        viewModelScope.launch {
            hybridTransport.uiEvent.collect { event ->
                when (event) {
                    is TransportUiEvent.I2PBuilding -> {
                        _transportToast.value = "Building I2P tunnel in background..."
                    }
                    is TransportUiEvent.SwitchingToI2P -> {
                        // Add synthetic UI jitter to decouple from network timing
                        kotlinx.coroutines.delay(
                            hybridTransport.transitionSeed?.let { seed ->
                                val seq = hybridTransport.currentOutboundSeq()
                                hybridTransport.deterministicEgressDelay(seed, seq xor 0x5549L) // UI-specific derivation
                            } ?: 500L
                        )
                        _transportToast.value = "Switching to I2P — speeds will improve"
                    }
                    is TransportUiEvent.I2PActive -> {
                        _transportToast.value = "Connected via I2P"
                    }
                    null -> { /* No event */ }
                }
                // Clear the event after processing
                if (event != null) {
                    hybridTransport.clearUiEvent()
                }
            }
        }
    }
    
    /**
     * Clear the transport toast (called after auto-dismiss timer)
     */
    fun clearTransportToast() {
        _transportToast.value = null
    }
    
    /**
     * Update message input
     */
    fun updateMessageInput(text: String) {
        _messageInput.value = text
    }
    
    /**
     * Send message
     */
    fun sendMessage() {
        val text = _messageInput.value.trim()
        if (text.isBlank()) return
        
        // Add to sent messages
        val sent = SentMessage(
            id = System.currentTimeMillis(),
            text = text,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING
        )
        _sentMessages.value = _sentMessages.value + sent
        
        // Send through message queue (returns sequence number)
        val seq = messageQueue.sendMessage(text)
        
        // Update status to sent (optimistic)
        viewModelScope.launch {
            kotlinx.coroutines.delay(500)
            updateMessageStatus(sent.id, MessageStatus.SENT)
        }
        
        // Clear input
        _messageInput.value = ""
        
        // Update combined list
        updateAllMessages()
    }
    
    /**
     * Update message status
     */
    private fun updateMessageStatus(id: Long, status: MessageStatus) {
        _sentMessages.value = _sentMessages.value.map { msg ->
            if (msg.id == id) msg.copy(status = status) else msg
        }
        updateAllMessages()
    }
    
    /**
     * Combine sent and received messages
     */
    private fun updateAllMessages() {
        val sent = _sentMessages.value.map { msg ->
            ChatMessage(
                id = msg.id.toString(),
                text = msg.text,
                timestamp = msg.timestamp,
                isMine = true,
                status = msg.status
            )
        }
        
        val received = receivedMessages.value.map { msg ->
            ChatMessage(
                id = msg.nonce.toString(),
                text = msg.text,
                timestamp = msg.timestamp,
                isMine = false,
                status = MessageStatus.RECEIVED
            )
        }
        
        // Combine and sort by timestamp
        _allMessages.value = (sent + received).sortedBy { it.timestamp }
    }
    
    /**
     * Disconnect
     */
    fun disconnect() {
        connectionManager.disconnect()
        messageQueue.clear()
        _sentMessages.value = emptyList()
        _allMessages.value = emptyList()
    }
    
    /**
     * Clear all messages
     */
    fun clearMessages() {
        _sentMessages.value = emptyList()
        _allMessages.value = emptyList()
    }
}

/**
 * Sent message (local tracking)
 */
data class SentMessage(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val status: MessageStatus
)

/**
 * Message status
 */
enum class MessageStatus {
    SENDING,
    SENT,
    FAILED,
    RECEIVED
}

/**
 * Chat message for UI
 */
data class ChatMessage(
    val id: String,
    val text: String,
    val timestamp: Long,
    val isMine: Boolean,
    val status: MessageStatus
)
