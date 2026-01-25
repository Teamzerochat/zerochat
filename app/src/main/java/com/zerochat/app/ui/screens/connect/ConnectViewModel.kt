package com.zerochat.app.ui.screens.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.rendezvous.PollResult
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Connect ViewModel - Secret-derived rendezvous
 * 
 * Security Invariants:
 * - RV-03: Rendezvous derived from secret
 * - PL-01: Constant-rate polling
 * - FL-05: Auth failure = same as "peer offline"
 * - FL-06: No auto-retry
 */
sealed class ConnectUiState {
    object Initial : ConnectUiState()
    data class Connecting(val attempt: Int, val max: Int) : ConnectUiState()
    data class Connected(val sessionId: String) : ConnectUiState()
    object PeerOffline : ConnectUiState()
    object Error : ConnectUiState()
}

@HiltViewModel
class ConnectViewModel @Inject constructor(
    private val rendezvousManager: RendezvousManager,
    private val routingHandleManager: RoutingHandleManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Initial)
    val uiState: StateFlow<ConnectUiState> = _uiState
    
    fun connect(sharedSecret: String) {
        viewModelScope.launch {
            try {
                // Derive rendezvous from secret (RV-03)
                val rendezvous = rendezvousManager.deriveRendezvous(sharedSecret)
                
                // Check if already consumed
                if (rendezvousManager.isConsumed(rendezvous)) {
                    // Same secret used recently - derive new epoch
                    _uiState.value = ConnectUiState.PeerOffline
                    return@launch
                }
                
                // Poll rendezvous with constant rate (PL-01)
                rendezvousManager.pollRendezvous(rendezvous).collect { result ->
                    when (result) {
                        is PollResult.Polling -> {
                            _uiState.value = ConnectUiState.Connecting(result.attempt, result.max)
                        }
                        
                        is PollResult.Found -> {
                            // Generate my ephemeral handle
                            routingHandleManager.generateMyHandle()
                            
                            // TODO: Exchange handles via SPAKE2+ encrypted channel
                            // For now, simulate successful connection
                            
                            val sessionId = UUID.randomUUID().toString()
                            _uiState.value = ConnectUiState.Connected(sessionId)
                        }
                        
                        is PollResult.Timeout, is PollResult.Expired -> {
                            // FL-01, FL-05: Silent failure, same message
                            _uiState.value = ConnectUiState.PeerOffline
                        }
                    }
                }
                
            } catch (e: Exception) {
                // UI-05: Generic error only
                _uiState.value = ConnectUiState.Error
            }
        }
    }
    
    fun reset() {
        _uiState.value = ConnectUiState.Initial
    }
    
    override fun onCleared() {
        super.onCleared()
        // RH-03, RH-04: Wipe handles on exit
        routingHandleManager.wipeAll()
    }
}
