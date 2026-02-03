package com.zerochat.app.domain.webrtc

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Signaling Protocol - Serialize/deserialize WebRTC signaling messages
 * 
 * Message Format:
 * [1 byte: type] [4 bytes: length] [N bytes: payload]
 * 
 * Types:
 * - 0x20: SDP Offer
 * - 0x21: SDP Answer
 * - 0x22: ICE Candidate
 */
object SignalingProtocol {
    
    const val TYPE_SDP_OFFER: Byte = 0x20
    const val TYPE_SDP_ANSWER: Byte = 0x21
    const val TYPE_ICE_CANDIDATE: Byte = 0x22
    
    /**
     * Serialize SDP to bytes
     * Format: [type][sdp_string]
     */
    fun serializeSdp(sdp: SessionDescription): ByteArray {
        val type = when (sdp.type) {
            SessionDescription.Type.OFFER -> TYPE_SDP_OFFER
            SessionDescription.Type.ANSWER -> TYPE_SDP_ANSWER
            else -> TYPE_SDP_OFFER
        }
        
        val sdpBytes = sdp.description.toByteArray(StandardCharsets.UTF_8)
        val result = ByteArray(1 + sdpBytes.size)
        result[0] = type
        System.arraycopy(sdpBytes, 0, result, 1, sdpBytes.size)
        return result
    }
    
    /**
     * Deserialize bytes to SDP
     */
    fun deserializeSdp(data: ByteArray): SessionDescription? {
        if (data.isEmpty()) return null
        
        val type = when (data[0]) {
            TYPE_SDP_OFFER -> SessionDescription.Type.OFFER
            TYPE_SDP_ANSWER -> SessionDescription.Type.ANSWER
            else -> return null
        }
        
        val description = String(data, 1, data.size - 1, StandardCharsets.UTF_8)
        return SessionDescription(type, description)
    }
    
    /**
     * Serialize ICE candidate to bytes
     * Format: [type][sdpMid_len:2][sdpMid][sdpMLineIndex:4][candidate_string]
     */
    fun serializeIceCandidate(candidate: IceCandidate): ByteArray {
        val sdpMidBytes = (candidate.sdpMid ?: "").toByteArray(StandardCharsets.UTF_8)
        val candidateBytes = candidate.sdp.toByteArray(StandardCharsets.UTF_8)
        
        // 1 (type) + 2 (sdpMid length) + sdpMid + 4 (index) + candidate
        val totalSize = 1 + 2 + sdpMidBytes.size + 4 + candidateBytes.size
        val buffer = ByteBuffer.allocate(totalSize)
        
        buffer.put(TYPE_ICE_CANDIDATE)
        buffer.putShort(sdpMidBytes.size.toShort())
        buffer.put(sdpMidBytes)
        buffer.putInt(candidate.sdpMLineIndex)
        buffer.put(candidateBytes)
        
        return buffer.array()
    }
    
    /**
     * Deserialize bytes to ICE candidate
     */
    fun deserializeIceCandidate(data: ByteArray): IceCandidate? {
        if (data.size < 7) return null // Minimum: type + sdpMid len + index
        if (data[0] != TYPE_ICE_CANDIDATE) return null
        
        val buffer = ByteBuffer.wrap(data)
        buffer.get() // Skip type
        
        val sdpMidLen = buffer.short.toInt()
        if (buffer.remaining() < sdpMidLen + 4) return null
        
        val sdpMidBytes = ByteArray(sdpMidLen)
        buffer.get(sdpMidBytes)
        val sdpMid = String(sdpMidBytes, StandardCharsets.UTF_8)
        
        val sdpMLineIndex = buffer.int
        
        val candidateBytes = ByteArray(buffer.remaining())
        buffer.get(candidateBytes)
        val candidateSdp = String(candidateBytes, StandardCharsets.UTF_8)
        
        return IceCandidate(sdpMid, sdpMLineIndex, candidateSdp)
    }
    
    /**
     * Check message type
     */
    fun getMessageType(data: ByteArray): Byte? {
        return if (data.isNotEmpty()) data[0] else null
    }
    
    /**
     * Check if message is signaling message
     */
    fun isSignalingMessage(data: ByteArray): Boolean {
        val type = getMessageType(data) ?: return false
        return type == TYPE_SDP_OFFER || type == TYPE_SDP_ANSWER || type == TYPE_ICE_CANDIDATE
    }
}
