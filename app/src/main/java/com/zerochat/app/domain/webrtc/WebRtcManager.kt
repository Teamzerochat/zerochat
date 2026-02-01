package com.zerochat.app.domain.webrtc

import android.content.Context
import org.webrtc.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WebRTC Manager - Relay-Only Mode
 * 
 * Security: Forces all traffic through TURN relay to hide user IPs.
 * 
 * Invariants:
 * - IceTransportsType.RELAY - no direct peer connections
 * - All host/srflx candidates filtered out
 * - SDP sanitized before transmission
 */
@Singleton
class WebRtcManager @Inject constructor(
    private val context: Context
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null
    
    // Callbacks
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onDataChannelMessage: ((ByteArray) -> Unit)? = null
    var onLocalSdp: ((SessionDescription) -> Unit)? = null
    
    companion object {
        private const val DATA_CHANNEL_LABEL = "zerochat-secure"
    }
    
    /**
     * Initialize WebRTC with relay-only configuration
     * @param turnServerUrl TURN server URL (e.g., "turn:your-server:3478")
     * @param turnUsername TURN username
     * @param turnPassword TURN password
     */
    fun initialize(turnServerUrl: String, turnUsername: String, turnPassword: String) {
        // Initialize PeerConnectionFactory
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        
        val options = PeerConnectionFactory.Options()
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
        
        // Configure ICE servers - TURN only, no STUN
        val iceServers = listOf(
            PeerConnection.IceServer.builder(turnServerUrl)
                .setUsername(turnUsername)
                .setPassword(turnPassword)
                .createIceServer()
        )
        
        // CRITICAL: Relay-only configuration
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            iceTransportsType = PeerConnection.IceTransportsType.RELAY  // Force relay
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        
        // Create peer connection
        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            createPeerConnectionObserver()
        )
    }
    
    /**
     * Create data channel for messaging
     */
    fun createDataChannel() {
        val config = DataChannel.Init().apply {
            ordered = true
            negotiated = false
        }
        
        dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, config)
        dataChannel?.registerObserver(createDataChannelObserver())
    }
    
    /**
     * Create offer (initiator)
     */
    fun createOffer() {
        val constraints = MediaConstraints()
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { originalSdp ->
                    val sanitizedSdp = SdpSanitizer.sanitize(originalSdp)
                    peerConnection?.setLocalDescription(createSetSdpObserver(), sanitizedSdp)
                    onLocalSdp?.invoke(sanitizedSdp)
                }
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    /**
     * Create answer (receiver)
     */
    fun createAnswer() {
        val constraints = MediaConstraints()
        
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let { originalSdp ->
                    val sanitizedSdp = SdpSanitizer.sanitize(originalSdp)
                    peerConnection?.setLocalDescription(createSetSdpObserver(), sanitizedSdp)
                    onLocalSdp?.invoke(sanitizedSdp)
                }
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    /**
     * Set remote SDP
     */
    fun setRemoteSdp(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(createSetSdpObserver(), sdp)
    }
    
    /**
     * Add ICE candidate from remote peer (filtered)
     */
    fun addIceCandidate(candidate: IceCandidate) {
        // Only accept relay candidates
        if (IceCandidateFilter.isRelayCandidate(candidate)) {
            peerConnection?.addIceCandidate(candidate)
        }
    }
    
    /**
     * Send message through data channel
     */
    fun sendMessage(data: ByteArray): Boolean {
        return dataChannel?.send(DataChannel.Buffer(java.nio.ByteBuffer.wrap(data), true)) ?: false
    }
    
    /**
     * Close connection and cleanup
     */
    fun close() {
        dataChannel?.close()
        dataChannel = null
        
        peerConnection?.close()
        peerConnection = null
        
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
    }
    
    private fun createPeerConnectionObserver() = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let {
                // Filter: only relay candidates
                if (IceCandidateFilter.isRelayCandidate(it)) {
                    onIceCandidate?.invoke(it)
                }
            }
        }
        
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            state?.let { onIceConnectionChange?.invoke(it) }
        }
        
        override fun onDataChannel(channel: DataChannel?) {
            channel?.let {
                dataChannel = it
                it.registerObserver(createDataChannelObserver())
            }
        }
        
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
    }
    
    private fun createDataChannelObserver() = object : DataChannel.Observer {
        override fun onBufferedAmountChange(amount: Long) {}
        
        override fun onStateChange() {}
        
        override fun onMessage(buffer: DataChannel.Buffer?) {
            buffer?.let {
                if (it.binary) {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)
                    onDataChannelMessage?.invoke(data)
                }
            }
        }
    }
    
    private fun createSetSdpObserver() = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }
}
