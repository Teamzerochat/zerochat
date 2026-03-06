package com.zerochat.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.connection.ConnectionManager
import com.zerochat.app.domain.connection.ConnectionState
import com.zerochat.app.domain.transport.TransportController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ConnectViewModel - Manages connection initiation
 * 
 * Exposes connection state to UI and handles connection flow.
 * Transport: Nym for rendezvous/handshake, I2P for message streaming.
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager,
    private val controller: TransportController
) : ViewModel() {
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _sharedSecret = MutableStateFlow("")
    val sharedSecret: StateFlow<String> = _sharedSecret.asStateFlow()
    
    /**
     * Update shared secret input
     */
    fun updateSharedSecret(secret: String) {
        _sharedSecret.value = secret
    }
    
    /**
     * Connect to peer. Roles are derived automatically via nonce exchange.
     */
    fun connect() {
        val secret = _sharedSecret.value
        if (secret.isBlank()) {
            _connectionState.value = ConnectionState.Failed("Secret required")
            return
        }
        
        viewModelScope.launch {
            // Connect to NYM first (on IO thread to avoid blocking UI)
            _connectionState.value = ConnectionState.ConnectingToNym
            val connectResult = withContext(Dispatchers.IO) {
                controller.withTransport { it.connect("") }
            }
            if (connectResult.isFailure) {
                _connectionState.value = ConnectionState.Failed("Failed to connect to network")
                return@launch
            }
            
            // Now call ConnectionManager (Symmetric Flow)
            connectionManager.connect(
                sharedSecret = secret
            ).collect { state ->
                _connectionState.value = state
            }
        }
    }
    
    /**
     * Disconnect
     */
    fun disconnect() {
        connectionManager.disconnect()
        _connectionState.value = ConnectionState.Disconnected
    }
    
    /**
     * Reset to idle state
     */
    fun reset() {
        _connectionState.value = ConnectionState.Idle
        _sharedSecret.value = ""
    }
}
