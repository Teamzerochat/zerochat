package com.zerochat.app.domain.transport

import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mock Transport - Simulates NYM mixnet for testing
 * 
 * Used until real NYM infrastructure is available.
 * Provides same interface but no actual network communication.
 */
@Singleton
class MockNymTransport @Inject constructor() : NymTransport {
    
    @Volatile
    private var connected = false
    
    @Volatile
    private var gatewayUrl: String? = null
    
    // Simulated "published" handles at rendezvous points (for testing)
    private val publishedHandles = mutableMapOf<String, ByteArray>()
    
    override suspend fun connect(gatewayUrl: String): Result<Unit> {
        // Simulate connection delay
        delay(500)
        this.gatewayUrl = gatewayUrl
        connected = true
        return Result.success(Unit)
    }
    
    override fun disconnect() {
        connected = false
        gatewayUrl = null
        publishedHandles.clear()
    }
    
    override fun isConnected(): Boolean = connected
    
    override suspend fun pollRendezvous(pointId: String): RendezvousResponse? {
        if (!connected) return null
        
        // In mock mode, check if another "user" has published
        // For real testing, this would check NYM network
        return publishedHandles[pointId]?.let { handle ->
            RendezvousResponse(
                senderHandle = handle,
                payload = ByteArray(0)  // Empty payload for handshake
            )
        }
    }
    
    override suspend fun publishAtRendezvous(pointId: String, myHandle: ByteArray): Result<Unit> {
        if (!connected) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        
        publishedHandles[pointId] = myHandle.copyOf()
        return Result.success(Unit)
    }
    
    override suspend fun sendMessage(handle: ByteArray, payload: ByteArray): Result<Unit> {
        if (!connected) {
            return Result.failure(IllegalStateException("Not connected"))
        }
        
        if (handle.size != 32) {
            return Result.failure(IllegalArgumentException("Invalid handle length"))
        }
        
        // Mock: Message "sent" successfully
        // Real implementation would route through NYM
        return Result.success(Unit)
    }
    
    /**
     * Test helper: Simulate peer publishing their handle
     */
    fun simulatePeerPublish(pointId: String, peerHandle: ByteArray) {
        publishedHandles[pointId] = peerHandle.copyOf()
    }
}
