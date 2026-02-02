package com.zerochat.app.domain.messaging

import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.nio.ByteBuffer

/**
 * Message Protocol - Defines message types and serialization
 * 
 * Message Format:
 * [1 byte: type] [4 bytes: payload length] [N bytes: payload] [padding to 1024 bytes]
 * 
 * Security:
 * - Fixed size (1024 bytes) for traffic analysis resistance
 * - All payloads encrypted with session key before transmission
 */
object MessageProtocol {
    
    const val MESSAGE_SIZE = 1024  // Fixed size for all messages
    
    // Message types
    const val TYPE_HANDSHAKE_COMMITMENT: Byte = 0x01
    const val TYPE_HANDSHAKE_RESPONSE: Byte = 0x02
    const val TYPE_ROUTING_HANDLE: Byte = 0x03
    const val TYPE_WEBRTC_OFFER: Byte = 0x04
    const val TYPE_WEBRTC_ANSWER: Byte = 0x05
    const val TYPE_WEBRTC_ICE_CANDIDATE: Byte = 0x06
    const val TYPE_CHAT_MESSAGE: Byte = 0x07
    
    /**
     * Serialize message with type and padding
     */
    fun serialize(type: Byte, payload: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(MESSAGE_SIZE)
        
        // Write type
        buffer.put(type)
        
        // Write payload length
        buffer.putInt(payload.size)
        
        // Write payload
        buffer.put(payload)
        
        // Pad remaining bytes with zeros
        while (buffer.hasRemaining()) {
            buffer.put(0)
        }
        
        return buffer.array()
    }
    
    /**
     * Deserialize message
     * @return Pair of (type, payload)
     */
    fun deserialize(data: ByteArray): Pair<Byte, ByteArray>? {
        if (data.size != MESSAGE_SIZE) {
            return null
        }
        
        val buffer = ByteBuffer.wrap(data)
        
        // Read type
        val type = buffer.get()
        
        // Read payload length
        val payloadLength = buffer.getInt()
        
        if (payloadLength < 0 || payloadLength > MESSAGE_SIZE - 5) {
            return null
        }
        
        // Read payload
        val payload = ByteArray(payloadLength)
        buffer.get(payload)
        
        return Pair(type, payload)
    }
    
    /**
     * Serialize WebRTC SDP offer/answer
     */
    fun serializeSdp(sdp: SessionDescription): ByteArray {
        val type = if (sdp.type == SessionDescription.Type.OFFER) {
            TYPE_WEBRTC_OFFER
        } else {
            TYPE_WEBRTC_ANSWER
        }
        
        val payload = sdp.description.toByteArray(Charsets.UTF_8)
        return serialize(type, payload)
    }
    
    /**
     * Deserialize WebRTC SDP
     */
    fun deserializeSdp(data: ByteArray): SessionDescription? {
        val (type, payload) = deserialize(data) ?: return null
        
        val sdpType = when (type) {
            TYPE_WEBRTC_OFFER -> SessionDescription.Type.OFFER
            TYPE_WEBRTC_ANSWER -> SessionDescription.Type.ANSWER
            else -> return null
        }
        
        val description = String(payload, Charsets.UTF_8)
        return SessionDescription(sdpType, description)
    }
    
    /**
     * Serialize WebRTC ICE candidate
     */
    fun serializeIceCandidate(candidate: IceCandidate): ByteArray {
        // Format: sdpMid|sdpMLineIndex|sdp
        val payload = "${candidate.sdpMid}|${candidate.sdpMLineIndex}|${candidate.sdp}"
            .toByteArray(Charsets.UTF_8)
        
        return serialize(TYPE_WEBRTC_ICE_CANDIDATE, payload)
    }
    
    /**
     * Deserialize WebRTC ICE candidate
     */
    fun deserializeIceCandidate(data: ByteArray): IceCandidate? {
        val (type, payload) = deserialize(data) ?: return null
        
        if (type != TYPE_WEBRTC_ICE_CANDIDATE) {
            return null
        }
        
        val parts = String(payload, Charsets.UTF_8).split("|")
        if (parts.size != 3) {
            return null
        }
        
        val sdpMid = parts[0]
        val sdpMLineIndex = parts[1].toIntOrNull() ?: return null
        val sdp = parts[2]
        
        return IceCandidate(sdpMid, sdpMLineIndex, sdp)
    }
    
    /**
     * Serialize chat message
     */
    fun serializeChatMessage(text: String, newHandle: ByteArray): ByteArray {
        // Format: [32 bytes: new handle] [N bytes: message text]
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val payload = newHandle + textBytes
        
        return serialize(TYPE_CHAT_MESSAGE, payload)
    }
    
    /**
     * Deserialize chat message
     * @return Pair of (new handle, message text)
     */
    fun deserializeChatMessage(data: ByteArray): Pair<ByteArray, String>? {
        val (type, payload) = deserialize(data) ?: return null
        
        if (type != TYPE_CHAT_MESSAGE || payload.size < 32) {
            return null
        }
        
        val newHandle = payload.copyOfRange(0, 32)
        val textBytes = payload.copyOfRange(32, payload.size)
        val text = String(textBytes, Charsets.UTF_8)
        
        return Pair(newHandle, text)
    }
}
