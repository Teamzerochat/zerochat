package com.zerochat.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.group.GroupChatMessage
import com.zerochat.app.domain.group.GroupManager
import com.zerochat.app.domain.group.GroupSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * GroupViewModel — Manages group chat UI state.
 *
 * Exposes GroupManager's session state and message flow to Jetpack Compose UI.
 * Handles user input for session creation, message sending, and termination.
 *
 * Isolation: This ViewModel is completely independent from ConnectViewModel
 * and ChatViewModel used in the 1:1 flow.
 */
@HiltViewModel
class GroupViewModel @Inject constructor(
    private val groupManager: GroupManager
) : ViewModel() {

    companion object {
        private const val TAG = "GroupViewModel"
    }

    // --- Input State ---

    private val _sharedSecret = MutableStateFlow("")
    val sharedSecret: StateFlow<String> = _sharedSecret.asStateFlow()

    private val _displayName = MutableStateFlow("")
    val displayName: StateFlow<String> = _displayName.asStateFlow()

    private val _isCreator = MutableStateFlow(true)
    val isCreator: StateFlow<Boolean> = _isCreator.asStateFlow()

    private val _groupSize = MutableStateFlow(3) // Default group size
    val groupSize: StateFlow<Int> = _groupSize.asStateFlow()

    private val _messageInput = MutableStateFlow("")
    val messageInput: StateFlow<String> = _messageInput.asStateFlow()

    // --- Session State ---

    val sessionState: StateFlow<GroupSessionState> = groupManager.state

    // Discovered peer count (for progress UI)
    private val _peerCount = MutableStateFlow(0)
    val peerCount: StateFlow<Int> = _peerCount.asStateFlow()

    // SAS verification words
    private val _sasWords = MutableStateFlow<List<String>>(emptyList())
    val sasWords: StateFlow<List<String>> = _sasWords.asStateFlow()

    // Chat message history (in-memory only, never persisted)
    private val _messages = MutableStateFlow<List<GroupChatMessage>>(emptyList())
    val messages: StateFlow<List<GroupChatMessage>> = _messages.asStateFlow()

    // My member index
    private val _myMemberIndex = MutableStateFlow(-1)
    val myMemberIndex: StateFlow<Int> = _myMemberIndex.asStateFlow()

    init {
        // Observe session state changes
        viewModelScope.launch {
            groupManager.state.collect { state ->
                when (state) {
                    is GroupSessionState.Sealed -> {
                        _sasWords.value = state.sasWords
                    }
                    is GroupSessionState.Active -> {
                        _myMemberIndex.value = groupManager.getMyMemberIndex()
                        startReceivingMessages()
                    }
                    is GroupSessionState.SecurityViolation -> {
                        // Messages stop flowing — UI shows alert
                    }
                    else -> { /* No special handling */ }
                }
            }
        }

        // Observe peer discovery count
        viewModelScope.launch {
            groupManager.getDiscoveredPeerCount()?.collect { count ->
                _peerCount.value = count
            }
        }
    }

    // --- User Actions ---

    /** Update the shared secret input field. */
    fun updateSharedSecret(secret: String) {
        _sharedSecret.value = secret
    }

    /** Update the display name input field. */
    fun updateDisplayName(name: String) {
        _displayName.value = name
    }

    /** Toggle creator/joiner mode. */
    fun setIsCreator(creator: Boolean) {
        _isCreator.value = creator
    }

    /** Update the group size selection. */
    fun updateGroupSize(size: Int) {
        _groupSize.value = size.coerceIn(2, 10)
    }

    /** Update the message input field. */
    fun updateMessageInput(text: String) {
        _messageInput.value = text
    }

    /**
     * Start a new group session with the entered shared secret and group size.
     */
    fun startGroupSession() {
        val secret = _sharedSecret.value
        val size = _groupSize.value
        val name = _displayName.value.trim().takeIf { it.isNotEmpty() } ?: "Anonymous"
        val creator = _isCreator.value

        if (secret.length < 6) return
        if (creator && size !in 2..10) return

        // For joiners, we pass size=0 and GroupManager will learn it from the creator
        val targetSize = if (creator) size else 0
        groupManager.startSession(secret, targetSize, name, creator)
    }

    /**
     * Get the display name map (memberIndex -> name) for the chat UI.
     */
    fun getDisplayNameMap(): Map<Int, String> = groupManager.getDisplayNameMap()

    /**
     * Get my display name.
     */
    fun getMyDisplayName(): String = groupManager.getMyDisplayName()

    /**
     * Send a chat message to the group.
     */
    fun sendMessage() {
        val text = _messageInput.value.trim()
        if (text.isEmpty()) return

        viewModelScope.launch {
            val success = groupManager.sendMessage(text)
            if (success) {
                // Add to local message list (sent messages)
                val sentMessage = GroupChatMessage(
                    senderIndex = groupManager.getMyMemberIndex(),
                    text = text,
                    groupNonce = -1, // Local-only marker
                    vectorClock = LongArray(0),
                    timestamp = System.currentTimeMillis()
                )
                _messages.value = _messages.value + sentMessage
                _messageInput.value = ""
            }
        }
    }

    /**
     * Terminate the current group session.
     */
    fun terminateSession() {
        groupManager.terminateSession()
        _messages.value = emptyList()
        _sasWords.value = emptyList()
        _peerCount.value = 0
        _myMemberIndex.value = -1
    }

    /**
     * Reset back to idle (after termination or violation).
     */
    fun reset() {
        groupManager.reset()
        _sharedSecret.value = ""
        _groupSize.value = 3
        _messageInput.value = ""
        _messages.value = emptyList()
        _sasWords.value = emptyList()
        _peerCount.value = 0
        _myMemberIndex.value = -1
    }

    // --- Private ---

    /**
     * Start collecting incoming messages from the group message queue.
     */
    private fun startReceivingMessages() {
        viewModelScope.launch {
            groupManager.getIncomingMessages()?.collect { message ->
                _messages.value = _messages.value + message
            }
        }
    }
}
