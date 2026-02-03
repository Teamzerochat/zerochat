package com.zerochat.app.ui.screens.connect

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zerochat.app.domain.rendezvous.PollResult
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.NymTransport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val routingHandleManager: RoutingHandleManager,
    private val nymTransport: NymTransport
) : ViewModel() {
    
    companion object {
        private const val TAG = "ConnectViewModel"
        private const val NYM_GATEWAY_URL = "wss://gateway1.nymtech.net"
    }
    
    private val _uiState = MutableStateFlow<ConnectUiState>(ConnectUiState.Initial)
    val uiState: StateFlow<ConnectUiState> = _uiState

    
    fun connect(sharedSecret: String) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "Starting connection with shared secret (length: ${sharedSecret.length})")
                _uiState.value = ConnectUiState.Connecting(0, 30)
                
                // Run network operations on IO dispatcher to avoid ANR
                withContext(Dispatchers.IO) {
                    // Step 0: Connect to NYM mixnet first
                    if (!nymTransport.isConnected()) {
                        Log.i(TAG, "Connecting to NYM mixnet...")
                        
                        nymTransport.connect(NYM_GATEWAY_URL).onFailure { error ->
                            Log.e(TAG, "Failed to connect to NYM: ${error.message}", error)
                            withContext(Dispatchers.Main) {
                                _uiState.value = ConnectUiState.Error
                            }
                            return@withContext
                        }
                        Log.i(TAG, "Connected to NYM mixnet!")
                    }
                    
                    // Derive rendezvous from secret (RV-03)
                    val rendezvous = rendezvousManager.deriveRendezvous(sharedSecret)
                    Log.i(TAG, "Derived rendezvous: ${rendezvous.id.take(16)}...")
                    
                    // Check if already consumed
                    if (rendezvousManager.isConsumed(rendezvous)) {
                        Log.w(TAG, "Rendezvous already consumed")
                        withContext(Dispatchers.Main) {
                            _uiState.value = ConnectUiState.PeerOffline
                        }
                        return@withContext
                    }
                    
                    // Generate my ephemeral routing handle (RH-01)
                    val myHandle = routingHandleManager.generateMyHandle()
                    Log.i(TAG, "Generated my handle (${myHandle.size} bytes)")
                    
                    // Publish my handle at the rendezvous point
                    Log.i(TAG, "Publishing at rendezvous...")
                    rendezvousManager.publishAtRendezvous(rendezvous, myHandle)
                        .onFailure { error ->
                            Log.e(TAG, "Publish failed: ${error.message}", error)
                            withContext(Dispatchers.Main) {
                                _uiState.value = ConnectUiState.Error
                            }
                            return@withContext
                        }
                        .onSuccess {
                            Log.i(TAG, "Published successfully, starting poll...")
                        }
                    
                    // Poll rendezvous with constant rate (PL-01)
                    rendezvousManager.pollRendezvous(rendezvous).collect { result ->
                        when (result) {
                            is PollResult.Polling -> {
                                Log.i(TAG, "Polling attempt ${result.attempt}/${result.max}")
                                withContext(Dispatchers.Main) {
                                    _uiState.value = ConnectUiState.Connecting(result.attempt, result.max)
                                }
                            }
                            
                            is PollResult.Found -> {
                                Log.i(TAG, "Found peer! Handle size: ${result.peerHandle.size} bytes")
                                // Received peer's handle - connection established
                                val sessionId = UUID.randomUUID().toString()
                                withContext(Dispatchers.Main) {
                                    _uiState.value = ConnectUiState.Connected(sessionId)
                                }
                            }
                            
                            is PollResult.Timeout, is PollResult.Expired -> {
                                Log.w(TAG, "Poll result: ${result::class.simpleName}")
                                withContext(Dispatchers.Main) {
                                    _uiState.value = ConnectUiState.PeerOffline
                                }
                            }
                        }
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Connection exception: ${e.message}", e)
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
