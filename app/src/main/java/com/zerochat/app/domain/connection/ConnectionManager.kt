package com.zerochat.app.domain.connection

import android.util.Log
import com.zerochat.app.domain.crypto.HandshakeManager
import com.zerochat.app.domain.rendezvous.PollResult
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.NymTransport
import com.zerochat.app.domain.webrtc.SignalingProtocol
import com.zerochat.app.domain.webrtc.WebRtcManager
import kotlinx.coroutines.*
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
        private const val SIGNALING_RECEIVE_TIMEOUT_MS = 1000L
    }
    
    // Coroutine scope for signaling operations
    private val signalingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Peer's NYM address for sending signaling messages
    @Volatile
    private var peerNymAddress: ByteArray? = null
    
    /**
     * Send signaling message to peer through NYM
     */
    private suspend fun sendSignalingMessage(data: ByteArray) {
        val peerAddr = peerNymAddress
        if (peerAddr == null) {
            Log.w(TAG, "Cannot send signaling - no peer address")
            return
        }
        
        val result = nymTransport.sendMessage(peerAddr, data)
        if (result.isFailure) {
            Log.e(TAG, "Failed to send signaling: ${result.exceptionOrNull()?.message}")
        } else {
            Log.i(TAG, "Signaling message sent (${data.size} bytes)")
        }
    }
    
    /**
     * Start receive loop for signaling messages
     */
    private fun startSignalingReceiveLoop() {
        signalingScope.launch {
            Log.i(TAG, "Starting signaling receive loop")
            while (isActive) {
                try {
                    val msg = nymTransport.receiveMessage(SIGNALING_RECEIVE_TIMEOUT_MS)
                    if (msg != null) {
                        handleSignalingMessage(msg.payload)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Signaling receive error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Handle incoming signaling message
     */
    private fun handleSignalingMessage(data: ByteArray) {
        when (SignalingProtocol.getMessageType(data)) {
            SignalingProtocol.TYPE_SDP_OFFER, SignalingProtocol.TYPE_SDP_ANSWER -> {
                val sdp = SignalingProtocol.deserializeSdp(data)
                if (sdp != null) {
                    Log.i(TAG, "Received remote SDP: ${sdp.type}")
                    webRtcManager.setRemoteSdp(sdp)
                    // If we received an offer, create answer
                    if (sdp.type == SessionDescription.Type.OFFER) {
                        webRtcManager.createAnswer()
                    }
                }
            }
            SignalingProtocol.TYPE_ICE_CANDIDATE -> {
                val candidate = SignalingProtocol.deserializeIceCandidate(data)
                if (candidate != null) {
                    Log.i(TAG, "Received ICE candidate: ${candidate.sdpMid}")
                    webRtcManager.addIceCandidate(candidate)
                }
            }
            else -> {
                Log.w(TAG, "Unknown signaling message type")
            }
        }
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
            
            // Phase 1: Derive rendezvous pair and pick role
            val rendezvousPair = rendezvousManager.deriveRendezvousPair(sharedSecret)
            val role = rendezvousManager.pickRandomRole()
            Log.i(TAG, "Derived rendezvous pair (epoch: ${rendezvousPair.pointA.epoch}), role: $role")
            emit(ConnectionState.DerivedRendezvous(rendezvousPair.pointA.epoch))
            
            // Phase 2: Start SPAKE2+ handshake
            emit(ConnectionState.Handshaking)
            val commitment = handshakeManager.startAsInitiator(sharedSecret)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to start handshake", error)
                    emit(ConnectionState.Failed("Handshake initialization failed"))
                    return@flow
                }
            
            // Phase 3: Publish commitment at rendezvous
            Log.i(TAG, "Publishing commitment at rendezvous as $role")
            rendezvousManager.publishAtRendezvous(rendezvousPair, role, commitment)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish commitment", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            // Phase 4: Poll for peer's response
            emit(ConnectionState.PollingRendezvous)
            var peerResponse: ByteArray? = null
            
            rendezvousManager.pollRendezvous(rendezvousPair, role).collect { pollResult ->
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
            rendezvousManager.publishAtRendezvous(rendezvousPair, role, myHandle)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish handle", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            // Poll for peer's handle (this is actually peer's NYM address)
            var peerAddr: ByteArray? = null
            rendezvousManager.pollRendezvous(rendezvousPair, role).collect { pollResult ->
                when (pollResult) {
                    is PollResult.Found -> {
                        peerAddr = pollResult.peerHandle
                    }
                    is PollResult.Timeout, is PollResult.Expired -> {
                        emit(ConnectionState.Failed("Handle exchange failed"))
                        return@collect
                    }
                    else -> {}
                }
            }
            
            if (peerAddr == null) {
                emit(ConnectionState.Failed("Handle exchange failed"))
                return@flow
            }
            
            // Store peer's NYM address for signaling
            peerNymAddress = peerAddr
            routingHandleManager.setPeerHandle(peerAddr!!)
            Log.i(TAG, "Peer NYM address received (${peerAddr!!.size} bytes)")
            
            // Phase 7: Establish WebRTC connection
            emit(ConnectionState.EstablishingWebRTC)
            
            // Initialize WebRTC with TURN server
            webRtcManager.initialize(turnServerUrl, turnUsername, turnPassword)
            webRtcManager.createDataChannel()
            
            // Start receiving signaling messages from peer
            startSignalingReceiveLoop()
            
            // Set up callbacks for WebRTC signaling through Nym
            webRtcManager.onLocalSdp = { sdp ->
                Log.i(TAG, "Local SDP created: ${sdp.type}")
                val serialized = SignalingProtocol.serializeSdp(sdp)
                signalingScope.launch {
                    sendSignalingMessage(serialized)
                }
            }
            
            webRtcManager.onIceCandidate = { candidate ->
                Log.i(TAG, "ICE candidate: ${candidate.sdpMid}")
                val serialized = SignalingProtocol.serializeIceCandidate(candidate)
                signalingScope.launch {
                    sendSignalingMessage(serialized)
                }
            }
            
            webRtcManager.onIceConnectionChange = { state ->
                Log.i(TAG, "ICE connection state: $state")
            }
            
            // Create offer (initiator)
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
            
            // Phase 1: Derive rendezvous pair and pick role
            val rendezvousPair = rendezvousManager.deriveRendezvousPair(sharedSecret)
            val role = rendezvousManager.pickRandomRole()
            Log.i(TAG, "Derived rendezvous pair (epoch: ${rendezvousPair.pointA.epoch}), role: $role")
            emit(ConnectionState.DerivedRendezvous(rendezvousPair.pointA.epoch))
            
            // Phase 2: Poll for peer's commitment
            emit(ConnectionState.PollingRendezvous)
            var peerCommitment: ByteArray? = null
            
            rendezvousManager.pollRendezvous(rendezvousPair, role).collect { pollResult ->
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
            rendezvousManager.publishAtRendezvous(rendezvousPair, role, response)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish response", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            // Phase 5: Exchange routing handles (same as initiator)
            emit(ConnectionState.ExchangingHandles)
            val myHandle = routingHandleManager.generateMyHandle()
            
            rendezvousManager.publishAtRendezvous(rendezvousPair, role, myHandle)
                .getOrElse { error ->
                    Log.e(TAG, "Failed to publish handle", error)
                    emit(ConnectionState.Failed("Connection failed"))
                    return@flow
                }
            
            var peerAddr: ByteArray? = null
            rendezvousManager.pollRendezvous(rendezvousPair, role).collect { pollResult ->
                when (pollResult) {
                    is PollResult.Found -> {
                        peerAddr = pollResult.peerHandle
                    }
                    is PollResult.Timeout, is PollResult.Expired -> {
                        emit(ConnectionState.Failed("Handle exchange failed"))
                        return@collect
                    }
                    else -> {}
                }
            }
            
            if (peerAddr == null) {
                emit(ConnectionState.Failed("Handle exchange failed"))
                return@flow
            }
            
            // Store peer's NYM address for signaling
            peerNymAddress = peerAddr
            routingHandleManager.setPeerHandle(peerAddr!!)
            Log.i(TAG, "Peer NYM address received (${peerAddr!!.size} bytes)")
            
            // Phase 6: Establish WebRTC connection
            emit(ConnectionState.EstablishingWebRTC)
            
            webRtcManager.initialize(turnServerUrl, turnUsername, turnPassword)
            
            // Start receiving signaling messages (will receive peer's offer)
            startSignalingReceiveLoop()
            
            // Set up callbacks for WebRTC signaling through Nym
            webRtcManager.onLocalSdp = { sdp ->
                Log.i(TAG, "Local SDP created: ${sdp.type}")
                val serialized = SignalingProtocol.serializeSdp(sdp)
                signalingScope.launch {
                    sendSignalingMessage(serialized)
                }
            }
            
            webRtcManager.onIceCandidate = { candidate ->
                Log.i(TAG, "ICE candidate: ${candidate.sdpMid}")
                val serialized = SignalingProtocol.serializeIceCandidate(candidate)
                signalingScope.launch {
                    sendSignalingMessage(serialized)
                }
            }
            
            webRtcManager.onIceConnectionChange = { state ->
                Log.i(TAG, "ICE connection state: $state")
            }
            
            // Responder waits for offer via receive loop, then createAnswer is called
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
        signalingScope.coroutineContext.cancelChildren()
        peerNymAddress = null
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
