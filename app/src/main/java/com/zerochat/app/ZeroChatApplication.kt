package com.zerochat.app

import android.app.Application
import android.util.Log
import com.zerochat.app.lifecycle.AppLifecycleObserver
import com.zerochat.app.domain.i2p.I2PRouterService
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * 
 * BUG 5 FIX: All startup work moved to background threads to prevent
 * main thread blocking during app launch.
 */
@HiltAndroidApp
class ZeroChatApplication : Application() {

    companion object {
        private const val TAG = "ZeroChatApplication"
    }

    @Inject
    lateinit var lifecycleObserver: AppLifecycleObserver

    // Application scope for background startup tasks
    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() - starting on thread: ${Thread.currentThread().name}")

        // Initialize Lazysodium (libsodium) - lazy init on first use
        // No explicit initialization needed on main thread

        // Register lifecycle callbacks for security guardrails
        registerActivityLifecycleCallbacks(lifecycleObserver)

        // BUG 5 FIX: Start I2P Router Service in background - it takes 30-90s to bootstrap
        // This MUST NOT block the main thread during app startup
        appScope.launch {
            Log.i(TAG, "Starting I2P Router Service in background...")
            I2PRouterService.start(this@ZeroChatApplication)
        }

        Log.d(TAG, "onCreate() complete - main thread free for first frame render")
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
        // appScope.cancel() - not available, scope will be GC'd with application
    }
}

