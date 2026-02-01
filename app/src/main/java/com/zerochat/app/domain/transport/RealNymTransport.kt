package com.zerochat.app.domain.transport

import android.util.Log
import uniffi.nym_transport.NymTransportClient
import uniffi.nym_transport.TransportException
import uniffi.nym_transport.RendezvousMessage as FfiRendezvousMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real NYM Transport - Uses Rust FFI to communicate via NYM mixnet
 * 
 * This implementation wraps the native Rust library generated via UniFFI.
 */
@Singleton
class RealNymTransport @Inject constructor() : NymTransport {
    
    companion object {
        private const val TAG = "RealNymTransport"
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
    
    override suspend fun pollRendezvous(pointId: String): RendezvousResponse? {
        return try {
            val ffiMsg: FfiRendezvousMessage? = getOrCreateClient().pollRendezvous(pointId)
            ffiMsg?.let {
                RendezvousResponse(
                    senderHandle = it.senderHandle.map { b -> b.toByte() }.toByteArray(),
                    payload = it.payload.map { b -> b.toByte() }.toByteArray()
                )
            }
        } catch (e: TransportException) {
            null
        } catch (e: Exception) {
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
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
