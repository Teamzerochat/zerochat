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
import com.zerochat.app.domain.transport.TransportController
import com.zerochat.app.domain.transport.HybridTransport
import com.zerochat.app.domain.thermal.ThermalMonitor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import uniffi.nym_transport.sessionGetObfs4StateWrapper
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
 *
 * TLI Lifecycle (Paper §5.3):
 * - Init → Rendezvous: On handshake start
 * - Rendezvous → Hardened: After I2P stabilizes
 * - Hardened → Fallback: On churn detection
 * - Any → Zeroized: On session termination
 */
@Singleton
class ConnectionManager @Inject constructor(
    private val rendezvousManager: RendezvousManager,
    private val handshakeManager: HandshakeManager,
    private val samClient: SamClient,
    private val keyManager: com.zerochat.app.domain.crypto.KeyManager,
    private val messageQueue: MessageQueue,
    private val controller: TransportController,
    private val hybridTransport: HybridTransport,
    private val thermalMonitor: ThermalMonitor
) {
    
    companion object {
        private const val TAG = "ConnectionManager"
        
        // TLI Lifecycle phases (Paper §5.3)
        private const val TLI_PHASE_INIT = 0u
        private const val TLI_PHASE_RENDEZVOUS = 1u
        private const val TLI_PHASE_HARDENED = 2u
        private const val TLI_PHASE_FALLBACK = 3u
        private const val TLI_PHASE_ZEROIZED = 4u
    }
    
    // Coroutine scope for background tasks (churn monitoring, etc.)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Active encrypted channel for message exchange
    @Volatile
    var encryptedChannel: EncryptedChannel? = null
        private set
    
    /**
     * Connect using Model 3 (Deterministic + Symmetric)
     */
    fun connect(
        sharedSecret: String
    ): Flow<ConnectionState> = channelFlow {
        
        try {
            send(ConnectionState.ConnectingToNym)

            // TLI: Transition to Rendezvous phase (Paper §5.3)
            controller.tliTransition(1u) // Rendezvous phase
            Log.i(TAG, "TLI: Init → Rendezvous")

            // Phase 1: Derive Deterministic Rendezvous Point and Session Token
            // STRICT REQUIREMENT: Compute epoch ONCE and freeze it for this attempt.
            val attemptEpoch = rendezvousManager.getCurrentEpoch()
            
            // Generate deterministic 16-byte session token from sharedSecret + epoch.
            // Both devices MUST compute the exact same token. Per-attempt isolation is
            // impossible without a shared nonce exchange, so same-epoch retries will
            // see each other's stale messages — but the epoch filter already handles
            // cross-epoch isolation, and the message buffer + type filtering handles
            // in-session ordering.
            val tokenInput = (sharedSecret + attemptEpoch.toString() + "_SESSION_TOKEN").toByteArray(Charsets.UTF_8)
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val hash = md.digest(tokenInput)
            val sessionToken = hash.sliceArray(0 until 16)
            
            val rendezvousPoint = rendezvousManager.deriveRendezvousPoint(sharedSecret, attemptEpoch, sessionToken)
            
            Log.i(TAG, "derived rendezvous point: ${rendezvousPoint.id.take(16)} (epoch: ${rendezvousPoint.epoch}) (token: ${sessionToken.toHexString().take(8)}...)")
            send(ConnectionState.DerivedRendezvous(rendezvousPoint.epoch))
            
            // STRICT LIFECYCLE: Connect Once
            rendezvousManager.connect(rendezvousPoint).getOrElse { e ->
                Log.e(TAG, "Rendezvous connect failed", e)
                send(ConnectionState.Failed("Connect failed: ${e.message}"))
                return@channelFlow
            }

            // BUG 3 FIX: Mark obfs4 context as ready after connection handshake completes.
            // This ensures polling doesn't happen before decryption context is initialized.
            rendezvousManager.markObfs4ContextReady()
            
            // CRITICAL FIX: Derive and set temporary obfs4_state from deterministic seed
            // This allows polling to deobfuscate nonce messages BEFORE SPAKE2+ handshake.
            // Once SPAKE2+ completes, this will be verified/replaced with the session-derived obfs4_state.
            val tempObfs4State = rendezvousManager.deriveTemporaryObfs4State(
                rendezvousPoint.sessionToken,
                rendezvousPoint.epoch
            )
            rendezvousManager.setObfs4State(tempObfs4State)

            // No fixed delay needed - Nym gateways buffer messages for registered addresses.
            // Proceed directly to nonce exchange. Peer will receive our nonce when they connect.
            Log.i(TAG, "Connected to slot. Proceeding to nonce exchange.")

            // DETERMINISTIC ROLE ASSIGNMENT (Paper §5.3)
            // Both devices derive the same idA and idB from the shared secret + epoch.
            // The device whose active slot ID is idA (lexicographically smaller) becomes INITIATOR.
            // This is purely deterministic — it does NOT depend on which physical slot
            // we connected to, preventing the race condition where both devices succeed
            // at Slot A on different gateways and both get INITIATOR.
            val idA = rendezvousManager.derivePointId(sharedSecret, attemptEpoch, "_A")
            val idB = rendezvousManager.derivePointId(sharedSecret, attemptEpoch, "_B")
            val mySlot = rendezvousManager.getActiveRendezvousId() ?: run {
                send(ConnectionState.Failed("No active rendezvous ID"))
                return@channelFlow
            }
            val role = if (mySlot == idA) HandshakeRole.INITIATOR else HandshakeRole.RESPONDER
            Log.i(TAG, "ROLE: $role (mySlot=${if (mySlot == idA) "A" else "B"} myId=${mySlot.take(8)}...)")
            
            send(ConnectionState.Handshaking)
            val myNonce = handshakeManager.generateElectionNonce()
            val ignoreBodies = mutableSetOf<String>()
            ignoreBodies.add(myNonce.toHexString())
            
            val framedNonce = RendezvousFrame.wrap(RendezvousFrame.TYPE_NONCE, rendezvousPoint.epoch, rendezvousPoint.sessionToken, myNonce)
            var peerNonce: ByteArray? = null

            // Phase 3 & 4: Publish and Poll based on Role
            if (role == HandshakeRole.INITIATOR) {
                // INITIATOR: Publish first, then poll (with periodic re-publish)
                rendezvousManager.publish(rendezvousPoint, framedNonce).getOrElse { _ ->
                    send(ConnectionState.Failed("Publish failed"))
                    return@channelFlow
                }
                
                send(ConnectionState.PollingRendezvous)
                var pollsSinceLastPublish = 0
                try {
                    rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_NONCE).collect { result ->
                         if (result is PollResult.Found) {
                            peerNonce = result.body
                            throw CancellationException("FOUND")
                         } else if (result is PollResult.Timeout) {
                            throw Exception("Peer not online")
                         } else if (result is PollResult.Expired) {
                            throw Exception("Rendezvous expired")
                         } else if (result is PollResult.Polling) {
                            // Re-publish nonce every 5 poll cycles (~10s) to handle
                            // message loss during RESPONDER's Nym client setup
                            pollsSinceLastPublish++
                            if (pollsSinceLastPublish >= 5) {
                                pollsSinceLastPublish = 0
                                Log.i(TAG, "Re-publishing nonce (RESPONDER may not have received it)")
                                rendezvousManager.publish(rendezvousPoint, framedNonce)
                            }
                         }
                    }
                } catch (e: CancellationException) {
                    if (e.message != "FOUND") throw e
                } catch (e: Exception) {
                    send(ConnectionState.Failed(e.message ?: "Polling error"))
                    return@channelFlow
                }
            } else {
                // RESPONDER: Poll first, then publish
                send(ConnectionState.PollingRendezvous)
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
                    send(ConnectionState.Failed(e.message ?: "Polling error"))
                    return@channelFlow
                }
                
                rendezvousManager.publish(rendezvousPoint, framedNonce).getOrElse { _ ->
                    send(ConnectionState.Failed("Publish failed"))
                    return@channelFlow
                }
            }
            
            if (peerNonce == null) return@channelFlow

            if (myNonce.contentEquals(peerNonce!!)) {
                 send(ConnectionState.Failed("Nonce collision"))
                 return@channelFlow
            }
            
            val sessionHandle: ULong
            
            // Phase 6: SPAKE2+ Handshake
            if (role == HandshakeRole.INITIATOR) {
                // INITIATOR (Alice)
                val msgA = handshakeManager.startAsInitiator(sharedSecret).getOrThrow()
                ignoreBodies.add(msgA.toHexString())
                rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_SPAKE_A, rendezvousPoint.epoch, rendezvousPoint.sessionToken, msgA))
                
                var msgB: ByteArray? = null
                try {
                    rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_SPAKE_B).collect { res ->
                        if (res is PollResult.Found) { msgB = res.body; throw CancellationException("FOUND") }
                        else if (res is PollResult.Timeout) throw Exception("Handshake timeout")
                    }
                } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
                catch (e: Exception) { send(ConnectionState.Failed(e.message ?: "Error")); return@channelFlow }
                
                sessionHandle = handshakeManager.finishAsInitiator(msgB ?: run {
                    send(ConnectionState.Failed("No SPAKE2 response received"))
                    return@channelFlow
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
                catch (e: Exception) { send(ConnectionState.Failed(e.message ?: "Error")); return@channelFlow }
                
                val (msgB, handle) = handshakeManager.processAsResponder(sharedSecret, msgA ?: run {
                    send(ConnectionState.Failed("No SPAKE2 commitment received"))
                    return@channelFlow
                }).getOrThrow()
                sessionHandle = handle
                ignoreBodies.add(msgB.toHexString())
                rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_SPAKE_B, rendezvousPoint.epoch, rendezvousPoint.sessionToken, msgB))
            }
            
            Log.i(TAG, "✓ Handshake complete!")
            
            // Phase 7: Confirmation
            val myRoleU8: UByte = if (role == HandshakeRole.INITIATOR) 0u else 1u
            val peerRoleU8: UByte = if (role == HandshakeRole.INITIATOR) 1u else 0u
            
            val myConfirm = handshakeManager.generateConfirmation(sessionHandle, myRoleU8)
            ignoreBodies.add(myConfirm.toHexString())
            rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_CONFIRM, rendezvousPoint.epoch, rendezvousPoint.sessionToken, myConfirm))
            
            var peerConfirm: ByteArray? = null
            try {
                rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_CONFIRM).collect { res ->
                     if (res is PollResult.Found) { peerConfirm = res.body; throw CancellationException("FOUND") }
                     else if (res is PollResult.Timeout) throw Exception("Confirmation timeout")
                }
            } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
            catch (e: Exception) { 
                send(ConnectionState.Failed(e.message ?: "Error"))
                return@channelFlow 
            }

            val peerConfirmData = peerConfirm ?: run {
                send(ConnectionState.Failed("No confirmation received from peer"))
                return@channelFlow
            }
            
            // Verify peer's confirmation
            if (!handshakeManager.verifyConfirmation(sessionHandle, peerConfirmData, peerRoleU8)) {
                send(ConnectionState.Failed("Peer confirmation verification failed"))
                return@channelFlow
            }
            
            // BUG 5 FIX: Retrieve obfs4_state from FFI and pass to RendezvousManager
            // The obfs4_state (64 bytes) was derived from SPAKE2+ shared secret via HKDF in Rust
            // Must be retrieved and passed to RendezvousManager BEFORE any polling begins
            try {
                val obfs4StateList = sessionGetObfs4StateWrapper(sessionHandle)
                val obfs4StateBytes = obfs4StateList.map { it.toByte() }.toByteArray()
                val newStateHex = obfs4StateBytes.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }
                
                // DIAGNOSTIC: Log state transition with role and timing
                Log.i(TAG, "🔄 SPAKE2+ handshake complete. Switching obfs4_state to final: $newStateHex (role=$role)")
                
                rendezvousManager.setObfs4State(obfs4StateBytes)
                Log.i(TAG, "✅ obfs4_state retrieved and passed to RendezvousManager (${obfs4StateBytes.size} bytes)")
            } catch (e: Exception) {
                send(ConnectionState.Failed("Failed to retrieve obfs4_state: ${e.message}"))
                return@channelFlow
            }
            
            // FREEZE EPOCH: Handshake is secure, ignore subsequent epoch shifts
            rendezvousManager.markHandshakeComplete()
            
            // Phase 8: Exchange Encrypted I2P Destinations
            send(ConnectionState.ExchangingHandles)
            
            // Ensure I2P router is ready before creating SAM session
            send(ConnectionState.EstablishingI2P)
            Log.i(TAG, "Waiting for I2P router to be ready...")
            
            val routerReady = I2PRouterService.waitUntilReady()
            if (!routerReady) {
                send(ConnectionState.Failed("I2P router not ready: ${I2PRouterService.startError ?: "timeout"}"))
                return@channelFlow
            }
            Log.i(TAG, "✓ I2P router ready")
            
            // Create SAM session to get our I2P destination
            val myDestination = samClient.createSession()
            val myDestBytes = myDestination.toByteArray(Charsets.UTF_8)
            val encryptedDest = uniffi.nym_transport.sessionEncryptWrapper(sessionHandle, myDestBytes.map { it.toUByte() })
                .map { it.toByte() }.toByteArray()
            
            ignoreBodies.add(encryptedDest.toHexString())
            rendezvousManager.publish(rendezvousPoint, RendezvousFrame.wrap(RendezvousFrame.TYPE_HANDLE, rendezvousPoint.epoch, rendezvousPoint.sessionToken, encryptedDest))
            
            var peerEncryptedDest: ByteArray? = null
             try {
                rendezvousManager.poll(rendezvousPoint, ignoreBodies, RendezvousFrame.TYPE_HANDLE).collect { res ->
                     if (res is PollResult.Found) { peerEncryptedDest = res.body; throw CancellationException("FOUND") }
                     else if (res is PollResult.Timeout) throw Exception("Handle timeout")
                }
            } catch (e: CancellationException) { if (e.message != "FOUND") throw e }
            catch (e: Exception) { send(ConnectionState.Failed(e.message ?: "Error")); return@channelFlow }
            
            val peerDestBytes = uniffi.nym_transport.sessionDecryptWrapper(
                sessionHandle,
                (peerEncryptedDest ?: run {
                    send(ConnectionState.Failed("No I2P destination received from peer"))
                    return@channelFlow
                }).map { it.toUByte() }
            ).map { it.toByte() }.toByteArray()
            
            val peerDestination = String(peerDestBytes, Charsets.UTF_8)
            Log.i(TAG, "✓ Peer I2P destination received: ${peerDestination.take(32)}...")
            
            // Self-connect check
            if (peerDestination == myDestination) {
                send(ConnectionState.Failed("Cannot connect to self"))
                return@channelFlow
            }
            
            // Phase 9: TEARDOWN Rendezvous (session handle stays alive for EncryptedChannel)
            rendezvousManager.teardownRendezvous()
            // NOTE: sessionHandle is NOT destroyed here — EncryptedChannel.close() handles it

            // TLI: Transition to Hardened phase (I2P ready, Nym teardown complete)
            controller.tliTransition(2u) // Hardened phase
            Log.i(TAG, "TLI: Rendezvous → Hardened")

            // Notify hybrid transport that I2P is ready (starts stochastic delay + cross-fade)
            hybridTransport.onI2PReady()

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
            encryptedChannel = EncryptedChannel(sessionHandle, stream, thermalMonitor)

            send(ConnectionState.Connected)

            // Start thermal monitoring (Paper §10, §11.2)
            thermalMonitor.startMonitoring()
            Log.i(TAG, "Thermal monitoring enabled (throttle at ${ThermalMonitor.THROTTLE_TEMP_C}°C)")

            // Start cover traffic scheduler (Paper §5)
            controller.coverTrafficStart()
            Log.i(TAG, "Cover traffic started (adaptive λ_min)")

            // Wire thermal throttle to cover traffic
            thermalMonitor.setOnThrottleChanged { isThrottled ->
                CoroutineScope(Dispatchers.IO).launch {
                    controller.coverTrafficSetThermalThrottle(isThrottled)
                    Log.i(TAG, "Cover traffic thermal throttle: ${if (isThrottled) "ACTIVE" else "OFF"}")
                }
            }

            // Start churn monitoring coroutine (Paper §7)
            val churnMonitorJob = scope.launch {
                var consecutiveFailures = 0
                val maxFailures = 3
                val baseBackoffMs = 2_000L
                val maxBackoffMs = 30_000L
                
                while (currentCoroutineContext().isActive && encryptedChannel?.isConnected() == true) {
                    delay(500) // 2 Hz sampling

                    // Check churn status
                    val churnDetected = controller.tliCheckChurn(1u) // Heartbeat timeout check

                    if (churnDetected) {
                        consecutiveFailures++
                        Log.w(TAG, "Churn detection: $consecutiveFailures/$maxFailures")

                        if (consecutiveFailures >= maxFailures) {
                            Log.e(TAG, "Churn threshold reached - attempting recovery")

                            // TLI: Transition to Fallback
                            controller.tliTransition(3u) // Fallback phase
                            Log.i(TAG, "TLI: Hardened → Fallback")

                            // Emit fallback state
                            send(ConnectionState.Fallback("Churn detected"))

                            // Exponential backoff reconnection
                            var backoffMs = baseBackoffMs
                            var reconnected = false

                            for (attempt in 1..5) {
                                Log.i(TAG, "Reconnection attempt $attempt/5 (backoff: ${backoffMs}ms)")
                                delay(backoffMs)

                                // Try to reconnect
                                try {
                                    val recovered = controller.tliCheckChurn(1u) // Check if churn cleared

                                    if (!recovered) {
                                        Log.i(TAG, "Churn cleared - resuming session")
                                        controller.tliTransition(1u) // Back to Rendezvous
                                        Log.i(TAG, "TLI: Fallback → Rendezvous")
                                        reconnected = true
                                        consecutiveFailures = 0
                                        break
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Reconnection attempt $attempt failed: ${e.message}")
                                }

                                backoffMs = (backoffMs * 1.5).toLong().coerceAtMost(maxBackoffMs)
                            }

                            if (!reconnected) {
                                Log.e(TAG, "Recovery failed after 90s - terminating session")
                                // Timeout > 90s - terminate session
                                controller.tliTerminateSession()
                                Log.i(TAG, "TLI: → Zeroized (recovery timeout)")
                                break
                            }
                        }
                    } else {
                        // Reset failure counter on success
                        if (consecutiveFailures > 0) {
                            Log.i(TAG, "Churn cleared - resetting failure counter")
                            consecutiveFailures = 0
                        }
                    }
                }
            }

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
            send(ConnectionState.Disconnected)

        } catch (e: Exception) {
            Log.e(TAG, "Connection Loop Error", e)
            send(ConnectionState.Failed("Error: ${e.message}"))

            // TLI: Transition to Zeroized on error
            try {
                controller.tliTerminateSession()
                Log.i(TAG, "TLI: → Zeroized (error recovery)")
            } catch (tliError: Exception) {
                Log.w(TAG, "TLI terminate failed during error recovery", tliError)
            }

            rendezvousManager.teardownRendezvous() // Ensure teardown on error
            // R5 FIX: Close SAM session to prevent resource leak after mid-flow failure
            try { samClient.close() } catch (_: Exception) {}
        } finally {
            handshakeManager.cleanup()
        }
    }.flowOn(Dispatchers.IO)
    
    fun disconnect() {
        // TLI: Transition to Zeroized phase on session termination
        CoroutineScope(Dispatchers.IO).launch {
            controller.tliTerminateSession()
            Log.i(TAG, "TLI: → Zeroized (session terminated)")
        }

        // Stop thermal monitoring
        thermalMonitor.stopMonitoring()

        // Stop cover traffic
        CoroutineScope(Dispatchers.IO).launch {
            controller.coverTrafficStop()
        }

        // Cancel churn monitoring
        scope.coroutineContext.cancelChildren()
        
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

    // Paper §4: TLI lifecycle phases (Fallback + Zeroized)
    data class Fallback(val reason: String) : ConnectionState()
    object Zeroized : ConnectionState()
}
