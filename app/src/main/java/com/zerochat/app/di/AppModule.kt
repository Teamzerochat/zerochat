package com.zerochat.app.di

import android.content.Context
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.i2p.SamClient
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.transport.TransportController
import com.zerochat.app.domain.group.GroupManager
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
    fun provideTransportController(@ApplicationContext context: Context): TransportController {
        return TransportController(context)
    }
    
    @Provides
    @Singleton
    fun provideRendezvousManager(controller: TransportController): RendezvousManager {
        return RendezvousManager(controller)
    }

    @Provides
    @Singleton
    fun provideGroupManager(controller: TransportController): GroupManager {
        return GroupManager(controller)
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
