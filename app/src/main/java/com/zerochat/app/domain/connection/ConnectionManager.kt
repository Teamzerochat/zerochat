package com.zerochat.app.domain.connection

import android.util.Log
import com.zerochat.app.domain.crypto.HandshakeManager
import com.zerochat.app.domain.rendezvous.PollResult
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.NymTransport
import com.zerochat.app.domain.webrtc.WebRtcManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection Manager - Orchestrates complete P2P connection flow
 * 
 * State Machine:
 * Idle → ConnectingToNym → DerivedRendezvous → PollingRendezvous → 
 * Handshaking → ExchangingHandles → EstablishingWebRTC → Connected
 * 
 * Security Invariants:
 * - All communication through Nym mixnet
 * - Ephemeral rendezvous points (5 min TTL)
 * - RAM-only routing handles
 * - Silent failures (no distinguishable errors)
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val rendezvousManager: RendezvousManager,
    private val handshakeManager: HandshakeManager,
    private val routingHandleManager: RoutingHandleManager,
    private val webRtcManager: WebRtcManager,
    private val nymTransport: NymTransport
) {
    
    companion object {
        private const val TAG = "ConnectionManager"
    }
    
    /**
     * Initiate connection as Alice (initiator)
     * 
     * Flow:
     * 1. Derive rendezvous from shared secret
     * 2. Start SPAKE2+ handshake (generate commitment)
     * 3. Publish commitment at rendezvous
     * 4. Poll for peer's response
     * 5. Finish SPAKE2+ (derive session key)
     * 6. Exchange routing handles
     * 7. Establish WebRTC connection
     * 
     * @param sharedSecret The shared secret exchanged out-of-band
     * @param turnServerUrl TURN server URL for WebRTC relay
     * @param turnUsername TURN username
     * @param turnPassword TURN password
     * @return Flow of connection states
     */
    fun connectAsInitiator(
        sharedSecret: String,
        turnServerUrl: String,
        turnUsername: String,
        turnPassword: String
    ): Flow<ConnectionState> = flow {
        try {
            emit(ConnectionState.ConnectingToNym)
            
            // Phase 1: Derive rendezvous point
            val rendezvous = rendezvousManager.deriveRendezvous(sharedSecret)
            Log.i(TAG, "Derived rendezvous (epoch: ${rendezvous.epoch})")
            emit(ConnectionState.DerivedRendezvous(rendezvous.epoch))
            
            // Phase 2: Start SPAKE2+ handshake
            emit(ConnectionState.Handshaking)
            val commitment = handshakeManager.startAsInitiator(sharedSecret)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to start handshake", error)
                    emit(ConnectionState.Failed("Handshake initialization failed"))
                    return@flow
                }
            
            // Phase 3: Publish commitment at rendezvous
            Log.i(TAG, "Publishing commitment at rendezvous")
            rendezvousManager.publishAtRendezvous(rendezvous, commitment)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish commitment", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            // Phase 4: Poll for peer's response
            emit(ConnectionState.PollingRendezvous)
            var peerResponse: ByteArray? = null
            
            rendezvousManager.pollRendezvous(rendezvous).collect { pollResult ->
                when (pollResult) {
                    is PollResult.Polling -> {
                        emit(ConnectionState.PollingRendezvous)
                    }
                    is PollResult.Found -> {
                        peerResponse = pollResult.peerHandle
                    }
                    is PollResult.Timeout -> {
                        emit(ConnectionState.Failed("Peer not online"))
                        return@collect
                    }
                    is PollResult.Expired -> {
                        emit(ConnectionState.Failed("Connection timeout"))
                        return@collect
                    }
                }
            }
            
            if (peerResponse == null) {
                emit(ConnectionState.Failed("Peer not found"))
                return@flow
            }
            
            // Phase 5: Finish SPAKE2+ handshake
            Log.i(TAG, "Received peer response, finishing handshake")
            val sessionKey = handshakeManager.finishAsInitiator(peerResponse!!)
                .getOrElse { error ->
                    Log.e(TAG, "Handshake failed", error)
                    emit(ConnectionState.Failed("Authentication failed"))
                    return@flow
                }
            
            Log.i(TAG, "Handshake complete! Session key derived")
            
            // Phase 6: Exchange routing handles
            emit(ConnectionState.ExchangingHandles)
            val myHandle = routingHandleManager.generateMyHandle()
            
            // TODO: Encrypt handle with session key before sending
            rendezvousManager.publishAtRendezvous(rendezvous, myHandle)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish handle", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            // Poll for peer's handle
            var peerHandle: ByteArray? = null
            rendezvousManager.pollRendezvous(rendezvous).collect { pollResult ->
                when (pollResult) {
                    is PollResult.Found -> {
                        peerHandle = pollResult.peerHandle
                    }
                    is PollResult.Timeout, is PollResult.Expired -> {
                        emit(ConnectionState.Failed("Handle exchange failed"))
                        return@collect
                    }
                    else -> {}
                }
            }
            
            if (peerHandle == null) {
                emit(ConnectionState.Failed("Handle exchange failed"))
                return@flow
            }
            
            routingHandleManager.setPeerHandle(peerHandle!!)
            Log.i(TAG, "Routing handles exchanged")
            
            // Phase 7: Establish WebRTC connection
            emit(ConnectionState.EstablishingWebRTC)
            
            // Initialize WebRTC with TURN server
            webRtcManager.initialize(turnServerUrl, turnUsername, turnPassword)
            webRtcManager.createDataChannel()
            
            // Set up callbacks for WebRTC signaling through Nym
            webRtcManager.onLocalSdp = { sdp ->
                // TODO: Send SDP through Nym transport to peer handle
                Log.i(TAG, "Local SDP created: ${sdp.type}")
            }
            
            webRtcManager.onIceCandidate = { candidate ->
                // TODO: Send ICE candidate through Nym transport
                Log.i(TAG, "ICE candidate: ${candidate.sdp}")
            }
            
            webRtcManager.onIceConnectionChange = { state ->
                Log.i(TAG, "ICE connection state: $state")
                if (state == org.webrtc.PeerConnection.IceConnectionState.CONNECTED) {
                    // TODO: Emit Connected state
                }
            }
            
            // Create offer
            webRtcManager.createOffer()
            
            emit(ConnectionState.Connected)
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed with exception", e)
            emit(ConnectionState.Failed("Connection error"))
        } finally {
            // Cleanup on any exit
            handshakeManager.cleanup()
        }
    }
    
    /**
     * Accept connection as Bob (responder)
     * 
     * Flow:
     * 1. Derive rendezvous from shared secret
     * 2. Poll for peer's commitment
     * 3. Process SPAKE2+ handshake (generate response + derive key)
     * 4. Publish response at rendezvous
     * 5. Exchange routing handles
     * 6. Establish WebRTC connection
     * 
     * @param sharedSecret The shared secret exchanged out-of-band
     * @param turnServerUrl TURN server URL for WebRTC relay
     * @param turnUsername TURN username
     * @param turnPassword TURN password
     * @return Flow of connection states
     */
    fun connectAsResponder(
        sharedSecret: String,
        turnServerUrl: String,
        turnUsername: String,
        turnPassword: String
    ): Flow<ConnectionState> = flow {
        try {
            emit(ConnectionState.ConnectingToNym)
            
            // Phase 1: Derive rendezvous point
            val rendezvous = rendezvousManager.deriveRendezvous(sharedSecret)
            Log.i(TAG, "Derived rendezvous (epoch: ${rendezvous.epoch})")
            emit(ConnectionState.DerivedRendezvous(rendezvous.epoch))
            
            // Phase 2: Poll for peer's commitment
            emit(ConnectionState.PollingRendezvous)
            var peerCommitment: ByteArray? = null
            
            rendezvousManager.pollRendezvous(rendezvous).collect { pollResult ->
                when (pollResult) {
                    is PollResult.Polling -> {
                        emit(ConnectionState.PollingRendezvous)
                    }
                    is PollResult.Found -> {
                        peerCommitment = pollResult.peerHandle
                    }
                    is PollResult.Timeout -> {
                        emit(ConnectionState.Failed("Peer not online"))
                        return@collect
                    }
                    is PollResult.Expired -> {
                        emit(ConnectionState.Failed("Connection timeout"))
                        return@collect
                    }
                }
            }
            
            if (peerCommitment == null) {
                emit(ConnectionState.Failed("Peer not found"))
                return@flow
            }
            
            // Phase 3: Process SPAKE2+ handshake
            emit(ConnectionState.Handshaking)
            val (response, sessionKey) = handshakeManager.processAsResponder(sharedSecret, peerCommitment!!)
                .getOrElse { error ->
                    Log.e(TAG, "Handshake failed", error)
                    emit(ConnectionState.Failed("Authentication failed"))
                    return@flow
                }
            
            Log.i(TAG, "Handshake complete! Session key derived")
            
            // Phase 4: Publish response
            rendezvousManager.publishAtRendezvous(rendezvous, response)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish response", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            // Phase 5: Exchange routing handles (same as initiator)
            emit(ConnectionState.ExchangingHandles)
            val myHandle = routingHandleManager.generateMyHandle()
            
            rendezvousManager.publishAtRendezvous(rendezvous, myHandle)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish handle", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            var peerHandle: ByteArray? = null
            rendezvousManager.pollRendezvous(rendezvous).collect { pollResult ->
                when (pollResult) {
                    is PollResult.Found -> {
                        peerHandle = pollResult.peerHandle
                    }
                    is PollResult.Timeout, is PollResult.Expired -> {
                        emit(ConnectionState.Failed("Handle exchange failed"))
                        return@collect
                    }
                    else -> {}
                }
            }
            
            if (peerHandle == null) {
                emit(ConnectionState.Failed("Handle exchange failed"))
                return@flow
            }
            
            routingHandleManager.setPeerHandle(peerHandle!!)
            Log.i(TAG, "Routing handles exchanged")
            
            // Phase 6: Establish WebRTC connection
            emit(ConnectionState.EstablishingWebRTC)
            
            webRtcManager.initialize(turnServerUrl, turnUsername, turnPassword)
            
            // Wait for peer's offer and create answer
            // TODO: Implement WebRTC signaling through Nym
            
            emit(ConnectionState.Connected)
            
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed with exception", e)
            emit(ConnectionState.Failed("Connection error"))
        } finally {
            handshakeManager.cleanup()
        }
    }
    
    /**
     * Disconnect and cleanup
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting...")
        webRtcManager.close()
        routingHandleManager.wipeAll()
        handshakeManager.cleanup()
        rendezvousManager.clearAll()
    }
}

/**
 * Connection state machine
 */
sealed class ConnectionState {
    object Idle : ConnectionState()
    object ConnectingToNym : ConnectionState()
    data class DerivedRendezvous(val epoch: Long) : ConnectionState()
    object PollingRendezvous : ConnectionState()
    object Handshaking : ConnectionState()
    object ExchangingHandles : ConnectionState()
    object EstablishingWebRTC : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
    object Disconnected : ConnectionState()
}
