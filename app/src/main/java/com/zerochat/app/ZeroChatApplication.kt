package com.zerochat.app

import android.app.Application
import com.zerochat.app.lifecycle.AppLifecycleObserver
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * ZeroChat Application - Journalist-Grade Anonymous Messaging
 * 
 * Security Properties:
 * - Passphrase-based key hierarchy (NOT Android Keystore)
 * - Volatile KEK in RAM only
 * - SQLCipher encrypted database
 * - Session-scoped encryption (no persistent ratchet)
 * - Handle wiping on background/lock (RH-03, RH-04)
 */
@HiltAndroidApp
class ZeroChatApplication : Application() {
    
    @Inject
    lateinit var lifecycleObserver: AppLifecycleObserver
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Lazysodium (libsodium)
        initializeCrypto()
        
        // Register lifecycle callbacks for security guardrails
        registerActivityLifecycleCallbacks(lifecycleObserver)

        // Start I2P Router Service immediately (takes 30-90s to bootstrap)
        com.zerochat.app.domain.i2p.I2PRouterService.start(this)
    }
    
    private fun initializeCrypto() {
        // Lazysodium will be initialized on first use
        // No need for explicit initialization
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        // Critical: Wipe sensitive data when app goes to background
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Lifecycle observer handles this via background timer
        }
    }
    
    override fun onTerminate() {
        super.onTerminate()
        lifecycleObserver.cleanup()
    }
}

