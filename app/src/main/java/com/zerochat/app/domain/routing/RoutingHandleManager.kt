package com.zerochat.app.domain.routing

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routing Handle Manager - Ephemeral routing addresses
 * 
 * Security Invariants (see SECURITY_GUARDRAILS.md):
 * - RH-01: RAM-only, never disk/DB/logs
 * - RH-02: Single session lifetime
 * - RH-03: Wipe on background > 30s
 * - RH-04: Wipe on app lock
 * - RH-05: Rotate per message
 * - RH-06: Secure zero-wipe
 */
@Singleton
class RoutingHandleManager @Inject constructor() {
    
    // Volatile: Handles exist in RAM only
    @Volatile
    private var myCurrentHandle: ByteArray? = null
    
    @Volatile
    private var myNextHandle: ByteArray? = null
    
    @Volatile
    private var peerHandle: ByteArray? = null
    
    companion object {
        const val HANDLE_LENGTH = 32
    }
    
    /**
     * Generate a new ephemeral routing handle
     * This handle is used by peer to send messages to us
     */
    fun generateMyHandle(): ByteArray {
        val handle = ByteArray(HANDLE_LENGTH)
        java.security.SecureRandom().nextBytes(handle)
        
        // Rotate: current becomes old, new becomes current
        secureWipe(myCurrentHandle)
        myCurrentHandle = myNextHandle
        myNextHandle = handle
        
        return handle.copyOf()
    }
    
    /**
     * Get my current handle to send to peer
     */
    fun getMyCurrentHandle(): ByteArray? {
        return myCurrentHandle?.copyOf()
    }
    
    /**
     * Set peer's routing handle (from encrypted handshake)
     */
    fun setPeerHandle(handle: ByteArray) {
        secureWipe(peerHandle)
        peerHandle = handle.copyOf()
    }
    
    /**
     * Get peer's handle for sending messages
     */
    fun getPeerHandle(): ByteArray? {
        return peerHandle?.copyOf()
    }
    
    /**
     * Rotate my handle (call before sending each message)
     * Returns the new handle to embed in outgoing message
     */
    fun rotateMyHandle(): ByteArray {
        return generateMyHandle()
    }
    
    /**
     * Update peer's handle (from received message)
     */
    fun updatePeerHandle(newHandle: ByteArray) {
        secureWipe(peerHandle)
        peerHandle = newHandle.copyOf()
    }
    
    /**
     * Check if we have valid handles for communication
     */
    fun isReady(): Boolean {
        return myCurrentHandle != null && peerHandle != null
    }
    
    /**
     * Secure wipe all handles - call on:
     * - App lock (RH-04)
     * - App background > 30s (RH-03)
     * - Session end
     */
    fun wipeAll() {
        secureWipe(myCurrentHandle)
        secureWipe(myNextHandle)
        secureWipe(peerHandle)
        
        myCurrentHandle = null
        myNextHandle = null
        peerHandle = null
    }
    
    /**
     * Secure wipe: Overwrite with zeros before deallocation (RH-06)
     */
    private fun secureWipe(data: ByteArray?) {
        data?.let { bytes ->
            for (i in bytes.indices) {
                bytes[i] = 0
            }
        }
    }
}
