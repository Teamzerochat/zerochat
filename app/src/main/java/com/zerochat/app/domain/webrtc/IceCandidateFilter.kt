package com.zerochat.app.domain.webrtc

import org.webrtc.IceCandidate

/**
 * ICE Candidate Filter - Security Layer
 * 
 * Filters out non-relay ICE candidates to prevent IP leakage.
 * 
 * ICE Candidate Types:
 * - host: Direct local IP (REJECT)
 * - srflx: Server reflexive, reveals public IP (REJECT)  
 * - prflx: Peer reflexive (REJECT)
 * - relay: Through TURN server (ACCEPT)
 */
object IceCandidateFilter {
    
    /**
     * Check if candidate is relay type (safe to use)
     */
    fun isRelayCandidate(candidate: IceCandidate): Boolean {
        return candidate.sdp.contains("typ relay", ignoreCase = true)
    }
    
    /**
     * Check if candidate would leak IP (unsafe)
     */
    fun isHostCandidate(candidate: IceCandidate): Boolean {
        return candidate.sdp.contains("typ host", ignoreCase = true)
    }
    
    /**
     * Check if candidate is server reflexive (unsafe)
     */
    fun isServerReflexiveCandidate(candidate: IceCandidate): Boolean {
        return candidate.sdp.contains("typ srflx", ignoreCase = true)
    }
    
    /**
     * Filter list of candidates to only relay
     */
    fun filterRelayOnly(candidates: List<IceCandidate>): List<IceCandidate> {
        return candidates.filter { isRelayCandidate(it) }
    }
}
