package com.zerochat.app.domain.connection

import android.util.Log
import com.zerochat.app.domain.crypto.HandshakeManager
import com.zerochat.app.domain.crypto.HandshakeRole
import com.zerochat.app.domain.i2p.EncryptedChannel
import com.zerochat.app.domain.i2p.I2PRouterService
import com.zerochat.app.domain.i2p.SamClient
import com.zerochat.app.domain.rendezvous.PollResult
import com.zerochat.app.domain.rendezvous.RendezvousFrame
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.messaging.MessageQueue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connection Manager - Model 3 (Symmetric) with I2P Transport
 * 
 * Flow:
 * 1. Derive Deterministic Identity (HKDF)
 * 2. Generate Random Election Nonce
 * 3. Publish Nonce / Poll Peer Nonce
 * 4. Derive Role (Initiator/Responder)
 * 5. Symmetric SPAKE2+ Handshake & Confirmation
 * 6. Exchange Encrypted I2P Destinations (Session Key)
 * 7. Teardown Rendezvous
 * 8. Establish I2P Streaming Connection (via SAM Bridge)
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val rendezvousManager: RendezvousManager,
    private val handshakeManager: HandshakeManager,
    private val samClient: SamClient,
    private val keyManager: com.zerochat.app.domain.crypto.KeyManager,
    private val messageQueue: MessageQueue
) {
    
    companion object {
        private const val TAG = "ConnectionManager"
    }
    
    // Active encrypted channel for message exchange
    @Volatile
    var encryptedChannel: EncryptedChannel? = null
        private set
    
    /**
     * Connect using Model 3 (Deterministic + Symmetric)
     */
    fun connect(
        sharedSecret: String
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
            
            // No fixed delay needed - Nym gateways buffer messages for registered addresses.
            // Proceed directly to nonce exchange. Peer will receive our nonce when they connect.
            Log.i(TAG, "Connected to slot. Proceeding to nonce exchange.")
            
            // Phase 2: Derive Role BEFORE Nonce Exchange
            val myAddr = rendezvousManager.getMyAddress() ?: run {
                collector.emit(ConnectionState.Failed("My address not found"))
                return@flow
            }
            val peerAddr = rendezvousManager.getPeerAddress() ?: run {
                collector.emit(ConnectionState.Failed("Peer address not found"))
                return@flow
            }
            
            val role = if (myAddr < peerAddr) HandshakeRole.INITIATOR else HandshakeRole.RESPONDER
            Log.i(TAG, "ROLE: $role (MyAddr: ${myAddr.take(8)}... vs PeerAddr: ${peerAddr.take(8)}...)")
            
            collector.emit(ConnectionState.Handshaking)
            val myNonce = handshakeManager.generateElectionNonce()
            val ignoreBodies = mutableSetOf<String>()
            ignoreBodies.add(myNonce.toHexString())
            
            val framedNonce = RendezvousFrame.wrap(RendezvousFrame.TYPE_NONCE, myNonce)
            var peerNonce: ByteArray? = null

            // Phase 3 & 4: Publish and Poll based on Role
            if (role == HandshakeRole.INITIATOR) {
                // INITIATOR: Publish first, then poll
                rendezvousManager.publish(rendezvousPoint, framedNonce).getOrElse { _ ->
                    collector.emit(ConnectionState.Failed("Publish failed"))
                    return@flow
                }
                
                collector.emit(ConnectionState.PollingRendezvous)
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
            } else {
                // RESPONDER: Poll first, then publish
                collector.emit(ConnectionState.PollingRendezvous)
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
                
                rendezvousManager.publish(rendezvousPoint, framedNonce).getOrElse { _ ->
                    collector.emit(ConnectionState.Failed("Publish failed"))
                    return@flow
                }
            }
            
            if (peerNonce == null) return@flow

            if (myNonce.contentEquals(peerNonce!!)) {
                 collector.emit(ConnectionState.Failed("Nonce collision"))
                 return@flow
            }
            
            val sessionHandle: ULong
            
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
                
                sessionHandle = handshakeManager.finishAsInitiator(msgB ?: run {
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
                
                val (msgB, handle) = handshakeManager.processAsResponder(sharedSecret, msgA ?: run {
                    collector.emit(ConnectionState.Failed("No SPAKE2 commitment received"))
                    return@flow
                }).getOrThrow()
                sessionHandle = handle
                ignoreBodies.add(msgB.toHexString())
                rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_SPAKE_B, msgB))
            }
            
            Log.i(TAG, "✓ Handshake complete!")
            
            // Phase 7: Confirmation
            val myRoleU8: UByte = if (role == HandshakeRole.INITIATOR) 0u else 1u
            val peerRoleU8: UByte = if (role == HandshakeRole.INITIATOR) 1u else 0u
            
            val myConfirm = handshakeManager.generateConfirmation(sessionHandle, myRoleU8)
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
            
            // Verify peer's confirmation
            if (!handshakeManager.verifyConfirmation(sessionHandle, peerConfirmData, peerRoleU8)) {
                collector.emit(ConnectionState.Failed("Peer confirmation verification failed"))
                return@flow
            }
            
            // FREEZE EPOCH: Handshake is secure, ignore subsequent epoch shifts
            rendezvousManager.markHandshakeComplete()
            
            // Phase 8: Exchange Encrypted I2P Destinations
            collector.emit(ConnectionState.ExchangingHandles)
            
            // Ensure I2P router is ready before creating SAM session
            collector.emit(ConnectionState.EstablishingI2P)
            Log.i(TAG, "Waiting for I2P router to be ready...")
            
            val routerReady = I2PRouterService.waitUntilReady()
            if (!routerReady) {
                collector.emit(ConnectionState.Failed("I2P router not ready: ${I2PRouterService.startError ?: "timeout"}"))
                return@flow
            }
            Log.i(TAG, "✓ I2P router ready")
            
            // Create SAM session to get our I2P destination
            val myDestination = samClient.createSession()
            val myDestBytes = myDestination.toByteArray(Charsets.UTF_8)
            val encryptedDest = uniffi.nym_transport.sessionEncryptWrapper(sessionHandle, myDestBytes.map { it.toUByte() })
                .map { it.toByte() }.toByteArray()
            
            ignoreBodies.add(encryptedDest.toHexString())
            rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_HANDLE, encryptedDest))
            
            var peerEncryptedDest: ByteArray? = null
             try {
                rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_HANDLE).collect { res ->
                     if (res is PollResult.Found) { peerEncryptedDest = res.body; throw CancellationException("FOUND") }
                     else if (res is PollResult.Timeout) throw Exception("Handle timeout")
                }
            } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
            catch (e: Exception) { collector.emit(ConnectionState.Failed(e.message ?: "Error")); return@flow }
            
            val peerDestBytes = uniffi.nym_transport.sessionDecryptWrapper(
                sessionHandle,
                (peerEncryptedDest ?: run {
                    collector.emit(ConnectionState.Failed("No I2P destination received from peer"))
                    return@flow
                }).map { it.toUByte() }
            ).map { it.toByte() }.toByteArray()
            
            val peerDestination = String(peerDestBytes, Charsets.UTF_8)
            Log.i(TAG, "✓ Peer I2P destination received: ${peerDestination.take(32)}...")
            
            // Self-connect check
            if (peerDestination == myDestination) {
                collector.emit(ConnectionState.Failed("Cannot connect to self"))
                return@flow
            }
            
            // Phase 9: TEARDOWN Rendezvous (session handle stays alive for EncryptedChannel)
            rendezvousManager.teardownRendezvous()
            // NOTE: sessionHandle is NOT destroyed here — EncryptedChannel.close() handles it
            
            // Phase 10: Establish I2P Streaming Connection
            Log.i(TAG, "Establishing I2P stream (role=$role)...")
            
            val stream = if (role == HandshakeRole.INITIATOR) {
                // INITIATOR: short delay for responder to start accepting, then connect
                delay(2_000)
                
                var connectedStream: com.zerochat.app.domain.i2p.I2PStream? = null
                var attempt = 1
                val maxAttempts = 24 // ~120 seconds total wait
                
                while (connectedStream == null && attempt <= maxAttempts) {
                    try {
                        Log.i(TAG, "I2P connect attempt $attempt/$maxAttempts")
                        connectedStream = samClient.connectStream(peerDestination)
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        if (msg.contains("LeaseSet not found") || msg.contains("CANT_REACH_PEER")) {
                            Log.w(TAG, "Attempt $attempt/$maxAttempts failed: LeaseSet not found. Retrying in 5s...")
                            delay(5_000)
                            attempt++
                         } else {
                            throw e
                         }
                    }
                }
                
                connectedStream ?: throw java.io.IOException("Timeout waiting for peer LeaseSet")
            } else {
                // RESPONDER: accept incoming connection
                // R7 FIX: Timeout aligned with INITIATOR's total retry window (~120s) + margin
                val inbound = withTimeout(150_000) { samClient.acceptStream() }
                Log.i(TAG, "I2P inbound stream accepted")
                inbound
            }
            
            Log.i(TAG, "✓ I2P stream established!")
            
            // Wrap stream with application-layer encryption (handle-based)
            encryptedChannel = EncryptedChannel(sessionHandle, stream)
            
            collector.emit(ConnectionState.Connected)
            
            // Phase 11: Listen for Incoming Messages
            // This loop keeps the flow active and processes incoming data.
            Log.i(TAG, "Starting I2P message listener loop...")
            while (currentCoroutineContext().isActive && encryptedChannel?.isConnected() == true) {
                try {
                    // receive() is blocking (runInterruptible for cancellation support)
                    val payload = runInterruptible(Dispatchers.IO) {
                         encryptedChannel?.receive()
                    }
                    
                    if (payload != null) {
                        messageQueue.receiveMessage(payload)
                    } else {
                        Log.w(TAG, "Channel returned null payload (closing)")
                        break
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Read error: ${e.message}")
                    break
                }
            }
            
            Log.i(TAG, "I2P listener loop ended")
            collector.emit(ConnectionState.Disconnected)

        } catch (e: Exception) {
            Log.e(TAG, "Connection Loop Error", e)
            collector.emit(ConnectionState.Failed("Error: ${e.message}"))
            rendezvousManager.teardownRendezvous() // Ensure teardown on error
            // R5 FIX: Close SAM session to prevent resource leak after mid-flow failure
            try { samClient.close() } catch (_: Exception) {}
        } finally {
            handshakeManager.cleanup()
        }
    }.flowOn(Dispatchers.IO)
    
    fun disconnect() {
        encryptedChannel?.close()
        encryptedChannel = null
        // R1 FIX: Use fire-and-forget instead of runBlocking to avoid deadlock.
        // closeInternal() in createSession() ensures cleanup if this races with reconnect.
        CoroutineScope(Dispatchers.IO).launch { samClient.close() }
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
    object EstablishingI2P : ConnectionState()
    object Connected : ConnectionState()
    data class Failed(val reason: String) : ConnectionState()
    object Disconnected : ConnectionState()
}
