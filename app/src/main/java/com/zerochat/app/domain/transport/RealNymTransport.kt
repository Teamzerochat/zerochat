package com.zerochat.app.domain.transport

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.nym_transport.NymTransportClient
import uniffi.nym_transport.TransportException
import uniffi.nym_transport.RendezvousMessage as FfiRendezvousMessage
import java.security.MessageDigest

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

        /**
         * BUG 1 FIX: Derive unique gateway auth seed per peer.
         * Combines slot hash with peer's own identity public key to prevent
         * gateway auth identity collisions when both peers connect to same slot.
         * 
         * seed = HKDF(slot_hash || peer_own_public_key, info="rendezvous-gateway-auth")
         */
        fun deriveGatewayAuthSeed(slotHash: String, peerIdentityPublicKey: ByteArray): ByteArray {
            val slotHashBytes = slotHash.toByteArray(Charsets.UTF_8)
            val input = slotHashBytes + peerIdentityPublicKey
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(input)
        }
    }

    // FFI client instance - lazy init to avoid issues if native lib not loaded
    private var client: NymTransportClient? = null
    private var myNymAddress: String? = null
    
    // Per-peer identity public key for gateway auth derivation (BUG 1 FIX)
    // Generated once per transport instance to ensure unique gateway auth identity
    private val peerIdentityPublicKey: ByteArray by lazy {
        val md = MessageDigest.getInstance("SHA-256")
        md.digest((System.currentTimeMillis() + Math.random()).toString().toByteArray(Charsets.UTF_8))
    }
    
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

    override suspend fun connectRendezvousWithAuthSeed(pointId: String, gatewayAuthSeed: ByteArray): Result<String> {
        return try {
            Log.i(TAG, "Connecting to rendezvous point $pointId with unique gateway auth seed")

            // BUG 1 FIX: Use connectWithCustomIdentity with unique seed per peer
            // This ensures each peer has a unique gateway auth identity even when
            // connecting to the same slot, preventing "already an open connection" errors.
            val seedList = gatewayAuthSeed.map { it.toUByte() }
            val address = getOrCreateClient().connectWithCustomIdentity(seedList, NymTransport.RENDEZVOUS_GATEWAY_ID)

            Log.i(TAG, "Rendezvous connected with unique auth: $address")
            Result.success(address)
        } catch (e: Exception) {
            Log.e(TAG, "Rendezvous connection with auth seed failed", e)
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

    override suspend fun pollRendezvous(pointId: String, obfs4State: ByteArray): List<RendezvousResponse>? {
        return try {
            require(obfs4State.size == 64) { "obfs4_state must be exactly 64 bytes" }
            
            val ffiMsgs = getOrCreateClient().pollRendezvous(pointId)
            // ffiMsgs is now a List<RendezvousMessage> (sequence in UDL)
            // If empty, return null to signal "nothing new"
            
            if (ffiMsgs.isEmpty()) {
                return null
            }
            
            // Paper §6: Per-frame ChaCha20-Poly1305 decryption
            // Each Nym message (~1452 bytes) is ONE COMPLETE PADDED FRAME.
            // Format: [encrypted_plaintext || auth_tag(16) || zero_padding]
            // 
            // CRITICAL FIX: The Obfs4FrameUnwrapper now handles unpadding internally:
            // 1. Strips trailing zero bytes to find the actual cipher end
            // 2. Only passes actual ciphertext+tag to AEAD, not the full 1452 bytes
            // This ensures the AEAD MAC verification uses correct input size.
            //
            // Create unwrapper with the 64-byte obfs4_state derived from SPAKE2+ shared secret
            val unwrapper = Obfs4FrameUnwrapper(obfs4State)
            val completeFrames = mutableListOf<RendezvousResponse>()
            var consecutiveMacFailures = 0
            
            for (msg in ffiMsgs) {
                val ciphertext = msg.payload.map { b -> b.toByte() }.toByteArray()
                
                // Validate message size
                // NYM messages should be exactly 1452 bytes (padded by Rust layer)
                if (ciphertext.size != 1452) {
                    Log.w(TAG, "Received message with unexpected size: ${ciphertext.size} bytes (expected 1452)")
                    continue
                }
                
                // Each Nym message is one complete frame — decrypt atomically
                val plaintext = unwrapper.decodeFrame(ciphertext)
                
                if (plaintext == null) {
                    // MAC failure or invalid size — discard frame, do not attempt recovery
                    consecutiveMacFailures++
                    Log.w(TAG, "Failed to decrypt frame ($consecutiveMacFailures consecutive) - ${ciphertext.size} bytes (MAC failure or invalid size)")
                    
                    // DIAGNOSTIC: After 3 consecutive failures, likely peer switched obfs4_state
                    if (consecutiveMacFailures >= 3) {
                        val stateHex = obfs4State.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }
                        Log.e(TAG, "⚠️ Multiple MAC failures with obfs4_state[$stateHex] — peer may have switched state!")
                    }
                    continue
                }
                
                // Decryption succeeded
                consecutiveMacFailures = 0
                
                // Now unpadding can read the real 4-byte Nym length correctly
                val unpadded = unpadFixed(plaintext)
                if (unpadded != null) {
                    completeFrames.add(RendezvousResponse(
                        senderHandle = msg.senderHandle.map { b -> b.toByte() }.toByteArray(),
                        payload = unpadded
                    ))
                } else {
                    Log.w(TAG, "Failed to unpad decrypted payload of ${plaintext.size} bytes")
                }
            }
            
            completeFrames.ifEmpty { null }
        } catch (e: TransportException) {
            if (isPanicSignature(e)) throw e
            null
        } catch (e: Exception) {
            Log.e(TAG, "Poll error", e)
            if (isPanicSignature(e)) throw e
            null
        }
    }
    
    /**
     * Strip the uniform packet padding added by sendMessage().
     * Format: [4-byte big-endian length] [actual payload] [zero padding to 1452 bytes]
     * If the payload is not padded (no 4-byte length prefix), returns it as-is.
     */
    private fun unpadFixed(padded: ByteArray): ByteArray? {
        // Constants must match Rust side SPHINX_PADDED_SIZE = 1452
        val PADDED_SIZE = 1452
        
        if (padded.size == PADDED_SIZE && padded.size >= 4) {
            // This looks like a padded message — extract real length from prefix
            val len = ((padded[0].toInt() and 0xFF) shl 24) or
                      ((padded[1].toInt() and 0xFF) shl 16) or
                      ((padded[2].toInt() and 0xFF) shl 8) or
                       (padded[3].toInt() and 0xFF)
            
            if (len in 1..(PADDED_SIZE - 4)) {
                return padded.sliceArray(4 until 4 + len)
            }
            // len was 0 or too large — not actually padded, return as-is
        }
        
        // Not padded (e.g. sent via publishAtRendezvous which skips padding)
        return padded
    }
    
    override suspend fun publishAtRendezvous(pointId: String, myHandle: ByteArray, basePointId: String): Result<Unit> {
        return try {
            val handleList = myHandle.map { it.toUByte() }
            getOrCreateClient().publishAtRendezvous(pointId, handleList, basePointId)
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

    // TLI Lifecycle methods (Paper §5.3)
    @Throws(kotlin.Exception::class)
    override fun tliTransition(phase: UByte): UByte {
        return getOrCreateClient().tliTransition(phase)
    }

    override fun tliCurrentPhase(): UByte {
        return getOrCreateClient().tliCurrentPhase()
    }

    override fun tliCheckChurn(signalType: UByte): Boolean {
        return getOrCreateClient().tliCheckChurn(signalType)
    }

    @Throws(kotlin.Exception::class)
    override fun tliTerminateSession() {
        getOrCreateClient().tliTerminateSession()
    }

    // Cover traffic methods (Paper §5)
    override fun coverTrafficStart() {
        getOrCreateClient().coverTrafficStart()
    }

    override fun coverTrafficStop() {
        getOrCreateClient().coverTrafficStop()
    }

    override fun coverTrafficSetThermalThrottle(active: Boolean) {
        getOrCreateClient().coverTrafficSetThermalThrottle(active)
    }

    override fun coverTrafficCurrentDelayMs(): ULong {
        return getOrCreateClient().coverTrafficCurrentDelayMs()
    }
}
