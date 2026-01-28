package com.zerochat.app.domain.webrtc

import org.webrtc.SessionDescription

/**
 * SDP Sanitizer - Security Layer
 * 
 * Removes potentially identifying information from SDP before transmission.
 * Defense in depth: even with relay-only, we sanitize SDP to prevent any leaks.
 */
object SdpSanitizer {
    
    /**
     * Sanitize SDP by removing identifying information
     */
    fun sanitize(sdp: SessionDescription): SessionDescription {
        val sanitizedDescription = sanitizeString(sdp.description)
        return SessionDescription(sdp.type, sanitizedDescription)
    }
    
    /**
     * Sanitize SDP string
     */
    fun sanitizeString(sdp: String): String {
        var result = sdp
        
        // Replace connection IP with 0.0.0.0
        result = result.replace(
            Regex("c=IN IP4 \\d+\\.\\d+\\.\\d+\\.\\d+"),
            "c=IN IP4 0.0.0.0"
        )
        result = result.replace(
            Regex("c=IN IP6 [a-fA-F0-9:]+"),
            "c=IN IP6 ::"
        )
        
        // Remove host candidates completely
        result = result.replace(
            Regex("a=candidate:[^\\r\\n]*typ host[^\\r\\n]*[\\r\\n]+"),
            ""
        )
        
        // Remove server reflexive candidates
        result = result.replace(
            Regex("a=candidate:[^\\r\\n]*typ srflx[^\\r\\n]*[\\r\\n]+"),
            ""
        )
        
        // Remove peer reflexive candidates
        result = result.replace(
            Regex("a=candidate:[^\\r\\n]*typ prflx[^\\r\\n]*[\\r\\n]+"),
            ""
        )
        
        // Remove origin IP (o= line)
        result = result.replace(
            Regex("o=([^ ]+) (\\d+) (\\d+) IN IP4 \\d+\\.\\d+\\.\\d+\\.\\d+"),
            "o=$1 $2 $3 IN IP4 0.0.0.0"
        )
        
        return result
    }
    
    /**
     * Validate SDP has no leaked IPs
     */
    fun validateNoLeakedIps(sdp: String): Boolean {
        // Check for any private or public IP addresses
        val ipPattern = Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")
        
        val matches = ipPattern.findAll(sdp)
        for (match in matches) {
            val ip = match.value
            // Allow only 0.0.0.0 and loopback
            if (ip != "0.0.0.0" && ip != "127.0.0.1") {
                // Check if it's inside a relay candidate (allowed)
                val context = sdp.substring(
                    maxOf(0, match.range.first - 50),
                    minOf(sdp.length, match.range.last + 20)
                )
                if (!context.contains("typ relay")) {
                    return false  // Found leaked IP
                }
            }
        }
        return true
    }
}
