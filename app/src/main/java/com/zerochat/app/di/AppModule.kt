package com.zerochat.app.di

import android.content.Context
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.rendezvous.RendezvousManager
import com.zerochat.app.domain.routing.RoutingHandleManager
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
    fun provideRendezvousManager(): RendezvousManager {
        return RendezvousManager()
    }
    
    @Provides
    @Singleton
    fun provideRoutingHandleManager(): RoutingHandleManager {
        return RoutingHandleManager()
    }
    
    @Provides
    @Singleton
    fun provideContext(@ApplicationContext context: Context): Context {
        return context
    }
}
