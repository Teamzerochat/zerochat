package com.zerochat.app.domain.webrtc

/**
 * WebRTC Configuration
 * 
 * Holds TURN server credentials and settings.
 * Configure this with your self-hosted coturn server.
 */
data class WebRtcConfig(
    val turnServerUrl: String,
    val turnUsername: String,
    val turnPassword: String
) {
    companion object {
        /**
         * Create default config (placeholder - replace with your server)
         */
        fun default(): WebRtcConfig {
            return WebRtcConfig(
                turnServerUrl = "turn:your-oracle-server:3478",
                turnUsername = "zerochat",
                turnPassword = "your-secret-password"
            )
        }
        
        /**
         * Create config from server details
         */
        fun create(
            serverIp: String,
            port: Int = 3478,
            username: String,
            password: String
        ): WebRtcConfig {
            return WebRtcConfig(
                turnServerUrl = "turn:$serverIp:$port",
                turnUsername = username,
                turnPassword = password
            )
        }
    }
}
