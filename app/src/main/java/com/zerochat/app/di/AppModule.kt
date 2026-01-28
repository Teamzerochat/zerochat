package com.zerochat.app.di

import android.content.Context
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.transport.MockNymTransport
import com.zerochat.app.domain.transport.NymTransport
import com.zerochat.app.domain.webrtc.WebRtcConfig
import com.zerochat.app.domain.webrtc.WebRtcManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt Dependency Injection Module
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideKeyManager(): KeyManager {
        return KeyManager()
    }
    
    @Provides
    @Singleton
    fun provideNymTransport(): NymTransport {
        // Native lib is ready! Switch to RealNymTransport() when NYM gateway is available
        // return RealNymTransport()  // Uncomment when gateway is set up
        return MockNymTransport()  // Using mock for now
    }
    
    @Provides
    @Singleton
    fun provideRendezvousManager(transport: NymTransport): RendezvousManager {
        return RendezvousManager(transport)
    }
    
    @Provides
    @Singleton
    fun provideRoutingHandleManager(): RoutingHandleManager {
        return RoutingHandleManager()
    }
    
    @Provides
    @Singleton
    fun provideWebRtcConfig(): WebRtcConfig {
        // TODO: Load from secure storage or config
        return WebRtcConfig.default()
    }
    
    @Provides
    @Singleton
    fun provideWebRtcManager(@ApplicationContext context: Context): WebRtcManager {
        return WebRtcManager(context)
    }
    
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }
}


