package com.zerochat.app.di

import android.content.Context
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.i2p.SamClient
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.transport.NymTransport
import com.zerochat.app.domain.transport.RealNymTransport
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
        // Real NYM SDK integration enabled!
        return RealNymTransport()
    }
    
    @Provides
    @Singleton
    fun provideRendezvousManager(transport: NymTransport): RendezvousManager {
        return RendezvousManager(transport)
    }
    
    @Provides
    @Singleton
    fun provideSamClient(): SamClient {
        return SamClient()
    }
    
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }
}
