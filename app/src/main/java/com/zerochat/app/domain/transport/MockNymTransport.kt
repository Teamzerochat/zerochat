package com.zerochat.app.domain.transport

import kotlinx.coroutines.delay
import android.util.Log
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
    
    // Store messages for polling (pointId -> List of payloads)
    private val rendezvousStore = mutableMapOf<String, MutableList<ByteArray>>()
    
    override suspend fun connect(gatewayUrl: String): Result<Unit> {
        // Simulate connection delay
        delay(500)
        this.gatewayUrl = gatewayUrl
        connected = true
        return Result.success(Unit)
    }
    
    override suspend fun connectRendezvous(pointId: String): Result<String> {
        delay(500)
        connected = true
        return Result.success("mock-rendezvous-address-123")
    }

    override suspend fun connectRendezvousWithAuthSeed(pointId: String, gatewayAuthSeed: ByteArray): Result<String> {
        // BUG 1 FIX: Mock implementation - same behavior as connectRendezvous
        delay(500)
        connected = true
        return Result.success("mock-rendezvous-address-with-auth-seed")
    }

    override fun disconnectRendezvous(pointId: String) {
        // Mock implementation
        Log.i("MockNymTransport", "Rendezvous client disconnected: $pointId")
        connected = true 
    }
    
    override fun disconnectAllRendezvous() {
        // Mock implementation
        Log.i("MockNymTransport", "All rendezvous clients disconnected")
    }
    
    override fun disconnect() {
        connected = false
        gatewayUrl = null
        publishedHandles.clear()
        rendezvousStore.clear()
    }
    
    override fun isConnected(): Boolean = connected

    override fun isRendezvousConnected(pointId: String): Boolean {
        // Mock always allows reconnection or assumes not connected for simplicity
        return false
    }
    
    override suspend fun pollRendezvous(pointId: String, obfs4State: ByteArray): List<RendezvousResponse>? {
        if (!connected) return null

        val messages = rendezvousStore[pointId]
        if (messages.isNullOrEmpty()) {
            return null
        }
        
        // In mock mode, we "consume" messages by returning them and removing them
        // This simulates a pop from a queue, but for rendezvous we actually might want to peek
        // But let's just return all messages for now to match real behavior
        // Real behavior: returns all messages currently in buffer
        
        val responseList = messages.map { 
            RendezvousResponse(
                senderHandle = "mock_sender".toByteArray(),
                payload = it
            )
        }
        
        return responseList
    }
    
    override suspend fun publishAtRendezvous(pointId: String, myHandle: ByteArray, basePointId: String): Result<Unit> {
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
        
        // Mock: Message "sent" successfully
        // Real implementation would route through NYM
        return Result.success(Unit)
    }
    
    override suspend fun receiveMessage(timeoutMs: Long): NymMessage? {
        if (!connected) return null
        // Mock: No messages in test mode
        delay(timeoutMs.coerceAtMost(100))
        return null
    }
    
    override fun getMyAddress(): ByteArray? {
        return if (connected) "mock-nym-address".toByteArray() else null
    }
    
    override suspend fun connectWithCustomIdentity(rendezvousSeed: List<UByte>, gatewayId: String): Result<String> {
        // Mock implementation - not used in debug mode
        delay(500)
        connected = true
        return Result.success("mock-custom-identity-address")
    }

    override suspend fun getRendezvousAddress(pointId: String): Result<String> {
        return Result.success(pointId + "@mock-gateway-id")
    }

    // TLI Lifecycle methods (Paper §5.3) - Mock implementations
    @Throws(kotlin.Exception::class)
    override fun tliTransition(phase: UByte): UByte {
        Log.d(TAG, "Mock TLI transition to phase $phase")
        return phase
    }

    override fun tliCurrentPhase(): UByte {
        return 1u // Rendezvous
    }

    override fun tliCheckChurn(signalType: UByte): Boolean {
        return false // No churn in mock
    }

    @Throws(kotlin.Exception::class)
    override fun tliTerminateSession() {
        Log.d(TAG, "Mock TLI session terminated")
    }

    // Cover traffic methods (Paper §5) - Mock implementations
    override fun coverTrafficStart() {
        Log.d(TAG, "Mock cover traffic started")
    }

    override fun coverTrafficStop() {
        Log.d(TAG, "Mock cover traffic stopped")
    }

    override fun coverTrafficSetThermalThrottle(active: Boolean) {
        Log.d(TAG, "Mock cover traffic thermal throttle: $active")
    }

    override fun coverTrafficCurrentDelayMs(): ULong {
        return 1000uL // 1 second
    }

    /**
     * Test helper: Simulate peer publishing their handle
     */
    fun simulatePeerPublish(pointId: String, peerHandle: ByteArray) {
        publishedHandles[pointId] = peerHandle.copyOf()
    }

    companion object {
        private const val TAG = "MockNymTransport"
    }
}
