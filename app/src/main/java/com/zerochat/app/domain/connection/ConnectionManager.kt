package com.zerochat.app.domain.connection

import android.util.Log
import com.zerochat.app.domain.crypto.HandshakeManager
import com.zerochat.app.domain.crypto.HandshakeRole
import com.zerochat.app.domain.rendezvous.PollResult
import com.zerochat.app.domain.rendezvous.RendezvousFrame
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.NymTransport
import com.zerochat.app.domain.webrtc.SignalingProtocol
import com.zerochat.app.domain.webrtc.WebRtcManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.webrtc.SessionDescription
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection Manager - Model 3 (Symmetric)
 * 
 * Flow:
 * 1. Derive Deterministic Identity (HKDF)
 * 2. Generate Random Election Nonce
 * 3. Publish Nonce / Poll Peer Nonce
 * 4. Derive Role (Initiator/Responder)
 * 5. Symmetric SPAKE2+ Handshake & Confirmation
 * 6. Exchange Encrypted Routing Handles (Session Key)
 * 7. Teardown Rendezvous IMMEDIATELY
 * 8. Establish WebRTC (TURN only)
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val rendezvousManager: RendezvousManager,
    private val handshakeManager: HandshakeManager,
    private val routingHandleManager: RoutingHandleManager,
    private val webRtcManager: WebRtcManager,
    private val nymTransport: NymTransport,
    private val keyManager: com.zerochat.app.domain.crypto.KeyManager
) {
    
    companion object {
        private const val TAG = "ConnectionManager"
        private const val SIGNALING_RECEIVE_TIMEOUT_MS = 1000L
    }
    
    // Coroutine scope for signaling operations (WebRTC)
    private val signalingScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Peer's NYM address for sending signaling messages
    @Volatile
    private var peerNymAddress: ByteArray? = null
    
    /**
     * Connect using Model 3 (Deterministic + Symmetric)
     */
    fun connect(
        sharedSecret: String,
        turnServerUrl: String,
        turnUsername: String,
        turnPassword: String
    ): Flow<ConnectionState> = flow {
        val collector = this
        
        try {
            collector.emit(ConnectionState.ConnectingToNym)
            
            // Phase 1: Derive Deterministic Rendezvous Point
            // STRICT REQUIREMENT: Compute epoch ONCE and freeze it for this attempt.
            val attemptEpoch = rendezvousManager.getCurrentEpoch()
            val rendezvousPoint = rendezvousManager.deriveRendezvousPoint(sharedSecret, attemptEpoch)
            
            Log.i(TAG, "derived rendezvous point: ${rendezvousPoint.id.take(16)} (epoch: ${rendezvousPoint.epoch})")
            collector.emit(ConnectionState.DerivedRendezvous(rendezvousPoint.epoch))
            
            // STRICT LIFECYCLE: Connect Once
            rendezvousManager.connect(rendezvousPoint).getOrElse { e ->
                Log.e(TAG, "Rendezvous connect failed", e)
                collector.emit(ConnectionState.Failed("Connect failed: ${e.message}"))
                return@flow
            }
            
            // CRITICAL: Wait for peer to also connect to their slot before publishing.
            // Without this delay, our message arrives at the peer's gateway BEFORE they
            // finish registering, causing the gateway's packet_router to panic.
            Log.i(TAG, "Connected to slot. Waiting 10s for peer to also connect...")
            collector.emit(ConnectionState.WaitingForPeer)
            delay(10_000)
            
            // Phase 2: Role Election (Nonce Exchange)
            collector.emit(ConnectionState.Handshaking)
            val myNonce = handshakeManager.generateElectionNonce()
            val ignoreBodies = mutableSetOf<String>()
            ignoreBodies.add(myNonce.toHexString())
            
            // Phase 3: Publish Nonce
            val framedNonce = RendezvousFrame.wrap(RendezvousFrame.TYPE_NONCE, myNonce)
            rendezvousManager.publish(rendezvousPoint, framedNonce).getOrElse { _ ->
                collector.emit(ConnectionState.Failed("Publish failed"))
                return@flow
            }
            
            // Phase 4: Poll Peer Nonce
            collector.emit(ConnectionState.PollingRendezvous)
            var peerNonce: ByteArray? = null
            
            try {
                rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_NONCE).collect { result ->
                     if (result is PollResult.Found) {
                        peerNonce = result.body
                        throw CancellationException("FOUND")
                     } else if (result is PollResult.Timeout) {
                        throw Exception("Peer not online")
                     } else if (result is PollResult.Expired) {
                        throw Exception("Rendezvous expired")
                     }
                }
            } catch (e: CancellationException) {
                if (e.message != "FOUND") throw e
            } catch (e: Exception) {
                collector.emit(ConnectionState.Failed(e.message ?: "Polling error"))
                return@flow
            }
            
            if (peerNonce == null) return@flow

            if (myNonce.contentEquals(peerNonce!!)) {
                 collector.emit(ConnectionState.Failed("Nonce collision"))
                 return@flow
            }
            
            // Phase 5: Derive Role
            val role = handshakeManager.determineRole(myNonce, peerNonce!!) ?: run {
                 collector.emit(ConnectionState.Failed("Nonce collision (logic)"))
                 return@flow
            }
            Log.i(TAG, "✓ DERIVED ROLE: $role")
            
            val sessionKey: ByteArray
            
            // Phase 6: SPAKE2+ Handshake
            if (role == HandshakeRole.INITIATOR) {
                // INITIATOR (Alice)
                val msgA = handshakeManager.startAsInitiator(sharedSecret).getOrThrow()
                ignoreBodies.add(msgA.toHexString())
                rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_SPAKE_A, msgA))
                
                var msgB: ByteArray? = null
                try {
                    rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_SPAKE_B).collect { res ->
                        if (res is PollResult.Found) { msgB = res.body; throw CancellationException("FOUND") }
                        else if (res is PollResult.Timeout) throw Exception("Handshake timeout")
                    }
                } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
                catch (e: Exception) { collector.emit(ConnectionState.Failed(e.message ?: "Error")); return@flow }
                
                sessionKey = handshakeManager.finishAsInitiator(msgB ?: run {
                    collector.emit(ConnectionState.Failed("No SPAKE2 response received"))
                    return@flow
                }).getOrThrow()
                    
            } else {
                // RESPONDER (Bob)
                var msgA: ByteArray? = null
                 try {
                    rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_SPAKE_A).collect { res ->
                        if (res is PollResult.Found) { msgA = res.body; throw CancellationException("FOUND") }
                        else if (res is PollResult.Timeout) throw Exception("Handshake timeout")
                    }
                } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
                catch (e: Exception) { collector.emit(ConnectionState.Failed(e.message ?: "Error")); return@flow }
                
                val (msgB, key) = handshakeManager.processAsResponder(sharedSecret, msgA ?: run {
                    collector.emit(ConnectionState.Failed("No SPAKE2 commitment received"))
                    return@flow
                }).getOrThrow()
                sessionKey = key
                ignoreBodies.add(msgB.toHexString())
                rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_SPAKE_B, msgB))
            }
            
            Log.i(TAG, "✓ Handshake complete!")
            
            // Phase 7: Confirmation
            val myRoleStr = if (role == HandshakeRole.INITIATOR) "INITIATOR" else "RESPONDER"
            val peerRoleStr = if (role == HandshakeRole.INITIATOR) "RESPONDER" else "INITIATOR"
            
            val myConfirm = handshakeManager.generateConfirmation(sessionKey, myRoleStr)
            ignoreBodies.add(myConfirm.toHexString())
            rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_CONFIRM, myConfirm))
            
            var peerConfirm: ByteArray? = null
            try {
                rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_CONFIRM).collect { res ->
                     if (res is PollResult.Found) { peerConfirm = res.body; throw CancellationException("FOUND") }
                     else if (res is PollResult.Timeout) throw Exception("Confirmation timeout")
                }
            } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
            catch (e: Exception) { collector.emit(ConnectionState.Failed(e.message ?: "Error")); return@flow }
            
            val peerConfirmData = peerConfirm ?: run {
                collector.emit(ConnectionState.Failed("No confirmation received from peer"))
                return@flow
            }
            
            if (!handshakeManager.verifyConfirmation(sessionKey, peerConfirmData, peerRoleStr)) {
                collector.emit(ConnectionState.Failed("Auth validation failed"))
                return@flow
            }
            
            // Phase 8: Exchange Encrypted Handles
            collector.emit(ConnectionState.ExchangingHandles)
            val myRouterHandle = routingHandleManager.generateMyHandle()
            val encryptedHandle = keyManager.encrypt(myRouterHandle, sessionKey)!!
            
            ignoreBodies.add(encryptedHandle.toHexString())
            rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_HANDLE, encryptedHandle))
            
            var peerEncryptedHandle: ByteArray? = null
             try {
                rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_HANDLE).collect { res ->
                     if (res is PollResult.Found) { peerEncryptedHandle = res.body; throw CancellationException("FOUND") }
                     else if (res is PollResult.Timeout) throw Exception("Handle timeout")
                }
            } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
            catch (e: Exception) { collector.emit(ConnectionState.Failed(e.message ?: "Error")); return@flow }
            
            val peerHandle = keyManager.decrypt(peerEncryptedHandle ?: run {
                collector.emit(ConnectionState.Failed("No handle received from peer"))
                return@flow
            }, sessionKey) ?: run {
                collector.emit(ConnectionState.Failed("Decryption Error"))
                return@flow
            }
            
            peerNymAddress = peerHandle
            routingHandleManager.setPeerHandle(peerHandle)
            
            // Phase 9: TEARDOWN
            rendezvousManager.markHandshakeComplete() // Strict teardown
            
            // Phase 10: WebRTC
            if (nymTransport.isConnected()) {
                connectWebRTC(turnServerUrl, turnUsername, turnPassword, role == HandshakeRole.INITIATOR, collector)
                collector.emit(ConnectionState.Connected)
            } else {
                 collector.emit(ConnectionState.Failed("Transport disconnected"))
            }

        } catch (e: Exception) {
            Log.e(TAG, "Connection Loop Error", e)
            collector.emit(ConnectionState.Failed("Error: ${e.message}"))
            rendezvousManager.teardownRendezvous() // Ensure teardown on error
        } finally {
            handshakeManager.cleanup()
        }
    }.flowOn(Dispatchers.IO)

    
    private suspend fun connectWebRTC(
        url: String, 
        user: String, 
        pass: String, 
        isOfferer: Boolean,
        collector: kotlinx.coroutines.flow.FlowCollector<ConnectionState>
    ) {
        collector.emit(ConnectionState.EstablishingWebRTC)
        webRtcManager.initialize(url, user, pass)
        startSignalingReceiveLoop()
        
        webRtcManager.onLocalSdp = { sdp ->
            val data = SignalingProtocol.serializeSdp(sdp)
            signalingScope.launch { sendSignalingMessage(data) }
        }
        
        webRtcManager.onIceCandidate = { cand ->
            val data = SignalingProtocol.serializeIceCandidate(cand)
            signalingScope.launch { sendSignalingMessage(data) }
        }
        
        if (isOfferer) {
            webRtcManager.createDataChannel()
            webRtcManager.createOffer()
        }
    }
    
    private suspend fun sendSignalingMessage(data: ByteArray) {
        peerNymAddress?.let { addr ->
             nymTransport.sendMessage(addr, data)
        }
    }
    
    private fun startSignalingReceiveLoop() {
        signalingScope.launch {
            while (isActive) {
                val msg = nymTransport.receiveMessage(SIGNALING_RECEIVE_TIMEOUT_MS)
                msg?.let { handleSignalingMessage(it.payload) }
            }
        }
    }
    
    private fun handleSignalingMessage(data: ByteArray) {
        when (SignalingProtocol.getMessageType(data)) {
            SignalingProtocol.TYPE_SDP_OFFER, SignalingProtocol.TYPE_SDP_ANSWER -> {
                SignalingProtocol.deserializeSdp(data)?.let { sdp ->
                    webRtcManager.setRemoteSdp(sdp)
                    if (sdp.type == SessionDescription.Type.OFFER) webRtcManager.createAnswer()
                }
            }
            SignalingProtocol.TYPE_ICE_CANDIDATE -> {
                SignalingProtocol.deserializeIceCandidate(data)?.let { cand ->
                    webRtcManager.addIceCandidate(cand)
                }
            }
        }
    }
    
    fun disconnect() {
        signalingScope.coroutineContext.cancelChildren()
        peerNymAddress = null
        webRtcManager.close()
        routingHandleManager.wipeAll()
        handshakeManager.cleanup()
        rendezvousManager.clearAll()
    }
    
    private fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }
}

sealed class ConnectionState {
    object Idle : ConnectionState()
    object ConnectingToNym : ConnectionState()
    data class DerivedRendezvous(val epoch: Long) : ConnectionState()
    object PollingRendezvous : ConnectionState()
    object WaitingForPeer : ConnectionState()
    object Handshaking : ConnectionState()
    object ExchangingHandles : ConnectionState()
    object EstablishingWebRTC : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
    object Disconnected : ConnectionState()
}
