package com.zerochat.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.connection.ConnectionManager
import com.zerochat.app.domain.connection.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ConnectViewModel - Manages connection initiation
 * 
 * Exposes connection state to UI and handles connection flow
 */
@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val connectionManager: ConnectionManager
) : ViewModel() {
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _sharedSecret = MutableStateFlow("")
    val sharedSecret: StateFlow<String> = _sharedSecret.asStateFlow()
    
    // TURN server configuration (should come from config)
    private val turnServerUrl = "turn:your-turn-server:3478"
    private val turnUsername = "username"
    private val turnPassword = "password"
    
    /**
     * Update shared secret input
     */
    fun updateSharedSecret(secret: String) {
        _sharedSecret.value = secret
    }
    
    /**
     * Connect as initiator (Alice)
     */
    fun connectAsInitiator() {
        val secret = _sharedSecret.value
        if (secret.isBlank()) {
            _connectionState.value = ConnectionState.Failed("Secret required")
            return
        }
        
        viewModelScope.launch {
            connectionManager.connectAsInitiator(
                sharedSecret = secret,
                turnServerUrl = turnServerUrl,
                turnUsername = turnUsername,
                turnPassword = turnPassword
            ).collect { state ->
                _connectionState.value = state
            }
        }
    }
    
    /**
     * Connect as responder (Bob)
     */
    fun connectAsResponder() {
        val secret = _sharedSecret.value
        if (secret.isBlank()) {
            _connectionState.value = ConnectionState.Failed("Secret required")
            return
        }
        
        viewModelScope.launch {
            connectionManager.connectAsResponder(
                sharedSecret = secret,
                turnServerUrl = turnServerUrl,
                turnUsername = turnUsername,
                turnPassword = turnPassword
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
