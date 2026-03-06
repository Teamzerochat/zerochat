package com.zerochat.app.domain.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nym_transport.NymTransportClient
import uniffi.nym_transport.TransportException
import uniffi.nym_transport.RendezvousMessage as FfiRendezvousMessage

/**
 * Real NYM Transport - Uses Rust FFI to communicate via NYM mixnet
 * 
 * This implementation wraps the native Rust library generated via UniFFI.
 * NOT a singleton — TransportController manages lifecycle and re-instantiation.
 */
class RealNymTransport : NymTransport {
    
    companion object {
        private const val TAG = "RealNymTransport"
        
        /** Detect Rust panic signatures that should propagate to TransportController */
        fun isPanicSignature(e: Exception): Boolean {
            val msg = e.message ?: ""
            return msg.contains("receiver is gone") ||
                   msg.contains("panicked") ||
                   e::class.simpleName == "InternalException"
        }
    }
    
    // FFI client instance - lazy init to avoid issues if native lib not loaded
    private var client: NymTransportClient? = null
    private var myNymAddress: String? = null
    
    private fun getOrCreateClient(): NymTransportClient {
        return client ?: NymTransportClient().also { 
            client = it
            Log.i(TAG, "Created NymTransportClient instance")
        }
    }
    
    override suspend fun connect(gatewayUrl: String): Result<Unit> {
        return try {
            Log.i(TAG, "Attempting to connect to NYM mixnet...")
            val address = getOrCreateClient().connect(gatewayUrl)
            myNymAddress = address
            Log.i(TAG, "Successfully connected! NYM Address: $address")
            Result.success(Unit)
        } catch (e: TransportException) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error during connection: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    override fun disconnect() {
        try {
            client?.disconnect()
        } catch (e: Exception) {
            // Ignore disconnect errors
        }
    }
    
    override fun isConnected(): Boolean {
        return try {
            client?.isConnected() ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun connectRendezvous(pointId: String): Result<String> {
        return try {
            Log.i(TAG, "Connecting to rendezvous point: $pointId")
            
            val address = getOrCreateClient().connectRendezvous(pointId)
            
            Log.i(TAG, "Rendezvous connected: $address")
            Result.success(address)
        } catch (e: Exception) {
            Log.e(TAG, "Rendezvous connection failed", e)
            if (isPanicSignature(e)) throw e
            Result.failure(e)
        }
    }

    override fun isRendezvousConnected(pointId: String): Boolean {
        // Rust client handles idempotency safely (checks map).
        // Returning true here would force a disconnect, which might be overkill.
        // Returning false allows 'connect' to proceed and verify status in Rust.
        return false 
    }

    override suspend fun pollRendezvous(pointId: String): List<RendezvousResponse>? {
        return try {
            val ffiMsgs = getOrCreateClient().pollRendezvous(pointId)
            // ffiMsgs is now a List<RendezvousMessage> (sequence in UDL)
            // If empty, return null or empty list? Let's return null to signal "nothing new" 
            // but empty list is also fine. Let's return empty list if connected but no messages.
            
            if (ffiMsgs.isEmpty()) {
                return null
            }
            
            ffiMsgs.map { msg ->
                RendezvousResponse(
                    senderHandle = msg.senderHandle.map { b -> b.toByte() }.toByteArray(),
                    payload = msg.payload.map { b -> b.toByte() }.toByteArray()
                )
            }
        } catch (e: TransportException) {
            if (isPanicSignature(e)) throw e
            null
        } catch (e: Exception) {
            Log.e(TAG, "Poll error", e)
            if (isPanicSignature(e)) throw e
            null
        }
    }
    
    override suspend fun publishAtRendezvous(pointId: String, myHandle: ByteArray): Result<Unit> {
        return try {
            val handleList = myHandle.map { it.toUByte() }
            getOrCreateClient().publishAtRendezvous(pointId, handleList)
            Result.success(Unit)
        } catch (e: TransportException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun sendMessage(handle: ByteArray, payload: ByteArray): Result<Unit> {
        return try {
            val handleList = handle.map { it.toUByte() }
            val payloadList = payload.map { it.toUByte() }
            getOrCreateClient().sendMessage(handleList, payloadList)
            Result.success(Unit)
        } catch (e: TransportException) {
            if (isPanicSignature(e)) throw e
            Result.failure(e)
        } catch (e: Exception) {
            if (isPanicSignature(e)) throw e
            Result.failure(e)
        }
    }
    
    override suspend fun receiveMessage(timeoutMs: Long): NymMessage? {
        return try {
            val ffiMsg = getOrCreateClient().receiveMessage(timeoutMs.toULong())
            ffiMsg?.let {
                NymMessage(
                    senderAddress = it.senderHandle.map { b -> b.toByte() }.toByteArray(),
                    payload = it.payload.map { b -> b.toByte() }.toByteArray()
                )
            }
        } catch (e: TransportException) {
            Log.w(TAG, "Receive error: ${e.message}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Receive error: ${e.message}")
            null
        }
    }
    
    override fun getMyAddress(): ByteArray? {
        return myNymAddress?.toByteArray(Charsets.UTF_8)
    }
    
    override suspend fun connectWithCustomIdentity(rendezvousSeed: List<UByte>, gatewayId: String): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                if (client == null) {
                    client = NymTransportClient()
                }
                
                Log.i(TAG, "🔧 DEBUG: Connecting with custom identity...")
                // Use safe call or just assume client is set now
                val address = client!!.connectWithCustomIdentity(rendezvousSeed, gatewayId)
                Log.i(TAG, "✓ Connected as: $address")
                
                Result.success(address)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect with custom identity", e)
                Result.failure(e)
            }
        }
    }
    
    override fun disconnectRendezvous(pointId: String) {
        if (client == null) return
        try {
            client?.disconnectRendezvous(pointId)
            Log.i(TAG, "Rendezvous client disconnected: $pointId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to disconnect rendezvous client: $pointId", e)
        }
    }
    
    override fun disconnectAllRendezvous() {
        if (client == null) return
        try {
             client?.disconnectAllRendezvous()
             Log.i(TAG, "All rendezvous clients disconnected")
        } catch (e: Exception) {
             Log.e(TAG, "Failed to disconnect rendezvous clients", e)
        }
    }

    override suspend fun getRendezvousAddress(pointId: String): Result<String> {
        return try {
            val address = getOrCreateClient().getRendezvousAddress(pointId)
            Result.success(address)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get rendezvous address", e)
            Result.failure(e)
        }
    }
}
