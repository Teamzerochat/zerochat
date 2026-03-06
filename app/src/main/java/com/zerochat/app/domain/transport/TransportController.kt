package com.zerochat.app.domain.transport

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

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
class TransportController @Inject constructor() {

    enum class TransportState { IDLE, CONNECTING, CONNECTED, FAILED }

    private val mutex = Mutex()
    private var state = TransportState.IDLE
    private var transport: RealNymTransport? = null

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

    companion object {
        private const val TAG = "TransportController"
    }
}
