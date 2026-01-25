package com.zerochat.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * ZeroChat Application - Journalist-Grade Anonymous Messaging
 * 
 * Security Properties:
 * - Passphrase-based key hierarchy (NOT Android Keystore)
 * - Volatile KEK in RAM only
 * - SQLCipher encrypted database
 * - Session-scoped encryption (no persistent ratchet)
 */
@HiltAndroidApp
class ZeroChatApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize Lazysodium (libsodium)
        initializeCrypto()
    }
    
    private fun initializeCrypto() {
        // Lazysodium will be initialized on first use
        // No need for explicit initialization
    }
    
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        // Critical: Wipe sensitive data when app goes to background
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // Signal to security manager to clear volatile keys
            // This will be implemented in KeyManager
        }
    }
}
