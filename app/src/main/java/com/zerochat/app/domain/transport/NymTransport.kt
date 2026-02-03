package com.zerochat.app.domain.transport

/**
 * NYM Transport Interface
 * 
 * Abstraction over NYM mixnet communication.
 * Allows MockTransport for testing and RealNymTransport for production.
 * 
 * Security:
 * - All routing handles are ephemeral
 * - Payloads are padded to fixed size (PL-04)
 * - Fresh SURB per request (PL-05)
 */
interface NymTransport {
    
    /**
     * Connect to NYM gateway
     * @param gatewayUrl WebSocket URL of self-hosted gateway
     */
    suspend fun connect(gatewayUrl: String): Result<Unit>
    
    /**
     * Disconnect from gateway
     */
    fun disconnect()
    
    /**
     * Check if connected to gateway
     */
    fun isConnected(): Boolean
    
    /**
     * Poll a rendezvous point for waiting messages
     * @param pointId The derived rendezvous point ID
     * @return Message if one is waiting, null otherwise
     */
    suspend fun pollRendezvous(pointId: String): RendezvousResponse?
    
    /**
     * Publish our handle at a rendezvous point (for handshake)
     * @param pointId The rendezvous point to publish at
     * @param myHandle Our ephemeral routing handle
     */
    suspend fun publishAtRendezvous(pointId: String, myHandle: ByteArray): Result<Unit>
    
    /**
     * Send encrypted message to a routing handle
     * @param handle Peer's routing handle (NYM address as bytes)
     * @param payload Encrypted message data
     */
    suspend fun sendMessage(handle: ByteArray, payload: ByteArray): Result<Unit>
    
    /**
     * Receive pending messages from NYM mixnet
     * @param timeoutMs Timeout in milliseconds
     * @return Received message or null if timeout
     */
    suspend fun receiveMessage(timeoutMs: Long): NymMessage?
    
    /**
     * Get our NYM address for receiving messages
     */
    fun getMyAddress(): ByteArray?
}

/**
 * Response from rendezvous poll
 */
data class RendezvousResponse(
    val senderHandle: ByteArray,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RendezvousResponse
        return senderHandle.contentEquals(other.senderHandle) && 
               payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = senderHandle.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Message received from NYM mixnet
 */
data class NymMessage(
    val senderAddress: ByteArray,
    val payload: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NymMessage
        return senderAddress.contentEquals(other.senderAddress) && 
               payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = senderAddress.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
