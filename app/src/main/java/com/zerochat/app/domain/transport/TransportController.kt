package com.zerochat.app.domain.transport

import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * Transport Controller - Panic-Safe State Machine
 *
 * Wraps RealNymTransport with lifecycle isolation.
 * All transport calls MUST go through withTransport().
 *
 * On Rust panic:
 * 1. State → FAILED
 * 2. destroyClient() runs fully (with 250ms lifecycle drain)
 * 3. Next withTransport() creates fresh RealNymTransport
 *
 * No shared state survives panic. No corrupted client reuse.
 */
@Singleton
class TransportController @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class TransportState { IDLE, CONNECTING, CONNECTED, FAILED }

    private val mutex = Mutex()
    private var state = TransportState.IDLE
    private var transport: RealNymTransport? = null

    /**
     * Start a brand-new native TLI lifecycle.
     *
     * Zeroized is terminal in the Rust TLI state machine, so a completed or
     * failed session cannot be reused for another Init -> Rendezvous attempt.
     */
    suspend fun resetForNewSession() {
        mutex.withLock {
            Log.i(TAG, "Resetting transport for new TLI session")
            destroyClient()
            state = TransportState.IDLE
        }
    }

    /**
     * Execute a block with a guaranteed-healthy transport instance.
     * If the previous instance panicked, it is fully destroyed and a fresh one is created.
     * All lifecycle operations are mutex-protected — no concurrent connect/disconnect overlap.
     */
    suspend fun <T> withTransport(block: suspend (NymTransport) -> T): T {
        mutex.withLock {
            if (state == TransportState.FAILED || transport == null) {
                destroyClient()
                Log.i(TAG, "Creating fresh transport")
                
                // Clear stale Nym client data to prevent gateway auth failures
                val nymDir = context.filesDir.resolve("nym_client")
                if (nymDir.exists()) {
                    nymDir.deleteRecursively()
                    Log.d(TAG, "Cleared stale Nym client state")
                }
                
                transport = RealNymTransport()
                state = TransportState.IDLE
            }
        }
        return try {
            block(transport!!)
        } catch (e: Exception) {
            if (isPanicException(e)) {
                mutex.withLock {
                    Log.e(TAG, "Transport state → FAILED")
                    state = TransportState.FAILED
                    destroyClient()
                }
            }
            throw e
        }
    }

    /**
     * Full shutdown with lifecycle drain window.
     * MUST be called inside mutex.withLock.
     * The 250ms delay is NOT a retry hack — it allows Rust async
     * cancellation + WebSocket close to fully complete before
     * clearing the reference.
     */
    private suspend fun destroyClient() {
        val old = transport ?: return
        Log.i(TAG, "Destroying transport instance")

        try { old.disconnectAllRendezvous() } catch (_: Exception) { }
        try { old.disconnect() } catch (_: Exception) { }

        // Lifecycle drain: allow Rust cancellation + websocket close to complete
        delay(250)

        transport = null
    }

    /**
     * Detect Rust panic signatures in exception messages.
     */
    private fun isPanicException(e: Exception): Boolean =
        e.message?.let {
            it.contains("InternalException") ||
            it.contains("unexpected") ||
            it.contains("receiver is gone") ||
            it.contains("panicked")
        } ?: false

    // TLI Lifecycle methods (Paper §5.3) - direct pass-through to transport
    @Throws(kotlin.Exception::class)
    suspend fun tliTransition(phase: UByte): UByte {
        return withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.tliTransition(phase)
            } else {
                0u // Mock transport returns Init phase
            }
        }
    }

    suspend fun tliCurrentPhase(): UByte {
        return withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.tliCurrentPhase()
            } else {
                0u
            }
        }
    }

    suspend fun tliCheckChurn(signalType: UByte): Boolean {
        return withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.tliCheckChurn(signalType)
            } else {
                false
            }
        }
    }

    @Throws(kotlin.Exception::class)
    suspend fun tliTerminateSession() {
        withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.tliTerminateSession()
            }
        }
        mutex.withLock {
            state = TransportState.FAILED
        }
    }

    // Cover traffic methods (Paper §5) - direct pass-through
    suspend fun coverTrafficStart() {
        withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.coverTrafficStart()
            }
        }
    }

    suspend fun coverTrafficStop() {
        withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.coverTrafficStop()
            }
        }
    }

    suspend fun coverTrafficSetThermalThrottle(active: Boolean) {
        withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.coverTrafficSetThermalThrottle(active)
            }
        }
    }

    suspend fun coverTrafficCurrentDelayMs(): ULong {
        return withTransport { transport ->
            if (transport is RealNymTransport) {
                transport.coverTrafficCurrentDelayMs()
            } else {
                0uL
            }
        }
    }

    companion object {
        private const val TAG = "TransportController"
    }
}
