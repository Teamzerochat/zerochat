package com.zerochat.app.domain.transport

import java.lang.Exception

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
     * Disconnect from a specific rendezvous point
     * @param pointId The rendezvous point ID to disconnect
     */
    fun disconnectRendezvous(pointId: String)

    /**
     * Check if a specific rendezvous point is connected
     */
    fun isRendezvousConnected(pointId: String): Boolean

    /**
     * Disconnect all active rendezvous clients
     * Call this after handshake completion or abort
     */
    fun disconnectAllRendezvous()

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
     * Connect to a specific rendezvous point using a deterministic gateway.
     * This enforces the single mailbox invariant.
     *
     * @param pointId The rendezvous point ID to connect to
     * @return Result containing the connected NYM address
     */
    suspend fun connectRendezvous(pointId: String): Result<String>

    /**
     * BUG 1 FIX: Connect to rendezvous with unique gateway auth identity per peer.
     * Uses (slot_hash || peer_own_public_key) for gateway auth derivation to prevent
     * identity collisions when both peers connect to the same slot.
     *
     * @param pointId The rendezvous point ID for routing (mailbox to poll)
     * @param gatewayAuthSeed 32-byte seed for gateway auth keypair derivation
     * @return Result containing the connected NYM address
     */
    suspend fun connectRendezvousWithAuthSeed(pointId: String, gatewayAuthSeed: ByteArray): Result<String>



    companion object {
        // Hardcoded Gateway for Deterministic Rendezvous (One Lick, One Mailbox)
        // This ensures both peers derive the exact same full NYM address (IdentityKey @ GatewayKey)
        const val RENDEZVOUS_GATEWAY_ID = "DP2V2ck8nTVedTGftpqcFEpuS4ZnXNNpCU43k5xTi84i"
    }

    /**
     * Poll a rendezvous point for waiting messages
     * 
     * CRITICAL BUG FIX: basePointId is the canonical obfs4 seed for both INITIATOR and RESPONDER.
     * - INITIATOR polls Slot A, basePointId = slot A ID
     * - RESPONDER polls Slot B, basePointId = SAME slot A ID (the canonical obfs4 seed)
     * Poll for messages at a rendezvous point using per-frame ChaCha20-Poly1305 decryption.
     *
     * Paper §6: Each ~1452-byte Nym message is one complete obfs4 frame.
     * Decrypted using 64-byte obfs4_state derived from SPAKE2+ shared secret.
     *
     * @param pointId The rendezvous point ID currently being polled (Slot A or B)
     * @param obfs4State The 64-byte obfs4 key state for per-frame ChaCha20-Poly1305 decryption
     * @return Messages if available, null otherwise
     */
    suspend fun pollRendezvous(pointId: String, obfs4State: ByteArray): List<RendezvousResponse>?
    
    /**
     * Publish our handle at a rendezvous point (for handshake)
     * @param pointId The rendezvous point to publish at
     * @param myHandle Our ephemeral routing handle
     */
    /**
     * Publish at rendezvous - creates shared mailbox using derived keypair
     * 
     * CRITICAL BUG FIX: basePointId (canonical Slot A ID) is passed to ensure
     * both INITIATOR and RESPONDER seed obfs4 asymmetrically with the same value.
     *
     * @param pointId The rendezvous point to publish at (Slot A or B)
     * @param myHandle Our ephemeral routing handle
     * @param basePointId The canonical Slot A ID for asymmetric obfs4 seeding
     */
    suspend fun publishAtRendezvous(pointId: String, myHandle: ByteArray, basePointId: String): Result<Unit>
    
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
    
    /**
     * DEBUG MODE ONLY: Connect with custom identity
     * @param rendezvousSeed 32-byte seed for deterministic keypair derivation
     * @param gatewayId Hardcoded gateway ID for determinism
     * @return Connected NYM address
     */
    suspend fun connectWithCustomIdentity(rendezvousSeed: List<UByte>, gatewayId: String): Result<String>

    /**
     * Calculate rendezvous address for Two-Slot strategy (pointId + "_A" or "_B")
     * Does not connect, just derives the address.
     */
    suspend fun getRendezvousAddress(pointId: String): Result<String>

    // TLI Lifecycle methods (Paper §5.3)
    @Throws(kotlin.Exception::class)
    fun tliTransition(phase: UByte): UByte

    fun tliCurrentPhase(): UByte

    fun tliCheckChurn(signalType: UByte): Boolean

    @Throws(kotlin.Exception::class)
    fun tliTerminateSession()

    // Cover traffic methods (Paper §5)
    fun coverTrafficStart()
    fun coverTrafficStop()
    fun coverTrafficSetThermalThrottle(active: Boolean)
    fun coverTrafficCurrentDelayMs(): ULong
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
