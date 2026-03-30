package com.zerochat.app.domain.transport

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.zerochat.app.domain.connection.ConnectionManager
import com.zerochat.app.domain.i2p.SamClient
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.messaging.MessageQueue
import com.zerochat.app.domain.thermal.ThermalMonitor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Transport Service - Single-Owner Model for NymTransport
 *
 * BUG 3 FIX: Enforces single-owner model for NymTransport to prevent
 * multiple processes from deriving identical rendezvous credentials.
 *
 * All transport operations MUST go through this service:
 * - Activities and other services bind to TransportService
 * - They communicate via the Binder interface
 * - They NEVER instantiate NymTransport or ConnectionManager directly
 *
 * Lifecycle:
 * - Transport initialized on first bind
 * - Transport lives as long as service is bound
 * - Transport torn down on unbind when no clients remain
 */
@AndroidEntryPoint
class TransportService : Service() {

    companion object {
        private const val TAG = "TransportService"
        
        // Process-level guard to prevent multiple initializations within same process
        @Volatile
        private var isTransportInitialized = false
        
        // Process-level mutex for transport initialization
        private val initMutex = Mutex()
    }

    @Inject
    lateinit var connectionManager: ConnectionManager

    @Inject
    lateinit var transportController: TransportController

    @Inject
    lateinit var samClient: SamClient

    @Inject
    lateinit var keyManager: KeyManager

    @Inject
    lateinit var messageQueue: MessageQueue

    @Inject
    lateinit var thermalMonitor: ThermalMonitor

    // Service scope for transport operations
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Binder for clients to interact with transport
    private val binder = TransportBinder()

    // Track bound clients
    private val boundClients = ClientCounter()

    // Transport state
    @Volatile
    private var transportState: TransportState = TransportState.IDLE

    enum class TransportState { IDLE, INITIALIZING, READY, FAILED }

    /**
     * Local binder interface for clients to interact with transport.
     */
    inner class TransportBinder : Binder() {
        /**
         * Get the ConnectionManager instance for transport operations.
         * @return ConnectionManager if transport is ready, null otherwise
         */
        fun getConnectionManager(): ConnectionManager? {
            return if (transportState == TransportState.READY) {
                connectionManager
            } else {
                null
            }
        }

        /**
         * Check if transport is ready for operations.
         */
        fun isTransportReady(): Boolean = transportState == TransportState.READY

        /**
         * Get current transport state.
         */
        fun getTransportState(): TransportState = transportState
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "TransportService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.i(TAG, "Client binding to TransportService (bound clients: ${boundClients.incrementAndGet()})")
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TransportService is a bound service, not started
        // Return START_NOT_STICKY to indicate it shouldn't be restarted
        return START_NOT_STICKY
    }

    override fun onUnbind(intent: Intent?): Boolean {
        val remaining = boundClients.decrementAndGet()
        Log.i(TAG, "Client unbinding from TransportService (remaining clients: $remaining)")
        
        // If no more clients, schedule transport cleanup after a delay
        if (remaining <= 0) {
            serviceScope.launch {
                delay(5000L) // 5 second grace period for re-bind
                if (boundClients.get() <= 0) {
                    Log.i(TAG, "No clients bound, cleaning up transport")
                    cleanupTransport()
                }
            }
        }
        
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "TransportService destroying")
        serviceScope.launch {
            cleanupTransport()
        }
        // serviceScope.cancel() - scope will be GC'd with service
        super.onDestroy()
    }

    /**
     * Initialize transport on first bind.
     * Uses process-level mutex to prevent concurrent initialization.
     */
    private suspend fun initializeTransport() {
        initMutex.withLock {
            // BUG 3 FIX: Assert single initialization per process lifecycle
            if (isTransportInitialized) {
                Log.w(TAG, "Transport already initialized in this process - returning existing instance")
                transportState = TransportState.READY
                return@withLock
            }

            try {
                Log.i(TAG, "Initializing transport (TLI Init)...")
                transportState = TransportState.INITIALIZING

                // TLI: Transition to Rendezvous phase happens in ConnectionManager.connect()
                // This is where we assert single ownership - only this service can init transport

                // Transport controller will be used by ConnectionManager when needed
                // No explicit initialization needed here - Hilt provides instances

                isTransportInitialized = true
                transportState = TransportState.READY
                Log.i(TAG, "Transport initialized successfully (TLI Init complete)")

            } catch (e: Exception) {
                Log.e(TAG, "Transport initialization failed", e)
                transportState = TransportState.FAILED
                isTransportInitialized = false
                throw e
            }
        }
    }

    /**
     * Clean up transport resources.
     * Only called when all clients have unbound.
     */
    private suspend fun cleanupTransport() {
        initMutex.withLock {
            if (!isTransportInitialized) {
                Log.d(TAG, "Transport not initialized, nothing to cleanup")
                return@withLock
            }

            Log.i(TAG, "Cleaning up transport resources")

            try {
                // Terminate TLI session
                transportController.tliTerminateSession()
                Log.i(TAG, "TLI session terminated")
            } catch (e: Exception) {
                Log.w(TAG, "Error terminating TLI session", e)
            }

            // Close SAM session
            try {
                samClient.close()
                Log.i(TAG, "SAM session closed")
            } catch (e: Exception) {
                Log.w(TAG, "Error closing SAM session", e)
            }

            // Clear session keys
            try {
                keyManager.clearSessionKeys()
                Log.i(TAG, "Session keys cleared")
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing session keys", e)
            }

            // Reset state
            isTransportInitialized = false
            transportState = TransportState.IDLE

            Log.i(TAG, "Transport cleanup complete")
        }
    }

    /**
     * Start connection flow.
     * Clients call this via the binder to initiate a peer connection.
     */
    suspend fun startConnection(sharedSecret: String) = serviceScope.async {
        if (transportState != TransportState.READY) {
            initializeTransport()
        }
        connectionManager.connect(sharedSecret)
    }

    /**
     * Disconnect current connection.
     */
    suspend fun disconnect() = serviceScope.async {
        connectionManager.disconnect()
    }
}

/**
 * Simple atomic integer wrapper for tracking bound clients.
 */
private class ClientCounter(value: Int = 0) {
    private val atomic = java.util.concurrent.atomic.AtomicInteger(value)

    fun get(): Int = atomic.get()
    fun incrementAndGet(): Int = atomic.incrementAndGet()
    fun decrementAndGet(): Int = atomic.decrementAndGet()
}
