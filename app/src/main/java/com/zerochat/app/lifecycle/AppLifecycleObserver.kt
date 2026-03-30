package com.zerochat.app.lifecycle

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.zerochat.app.domain.routing.RoutingHandleManager
import com.zerochat.app.domain.crypto.KeyManager
import com.zerochat.app.domain.i2p.I2PRouterService
import kotlinx.coroutines.*
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App Lifecycle Observer - Security Guardrails
 * 
 * Implements:
 * - RH-03: Wipe handles on background > 30s
 * - RH-04: Wipe handles on app lock
 * 
 * Security Properties:
 * - RAM-only handles are wiped when app is not in foreground
 * - Prevents handle leakage if device is compromised while app is backgrounded
 */
@Singleton
class AppLifecycleObserver @Inject constructor(
    private val routingHandleManager: RoutingHandleManager,
    private val keyManager: KeyManager
) : Application.ActivityLifecycleCallbacks {
    
    companion object {
        private const val TAG = "AppLifecycle"
        private const val BACKGROUND_WIPE_DELAY_MS = 30_000L  // 30 seconds
    }
    
    private var activityCount = 0
    private var backgroundJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * Activity started - app is in foreground
     */
    override fun onActivityStarted(activity: Activity) {
        activityCount++
        
        if (activityCount == 1) {
            // App came to foreground
            Log.i(TAG, "App entered foreground")
            
            // Cancel background wipe if scheduled
            backgroundJob?.cancel()
            backgroundJob = null

            // Paper §1: Start i2pd eagerly on foreground entry
            // Overlaps tunnel build (~15-25s) with user interaction time
            if (!I2PRouterService.isRunning) {
                Log.i(TAG, "Starting i2pd router eagerly (foreground optimization)")
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    delay(1500L)
                    I2PRouterService.start(activity.applicationContext)
                }
            }
        }
    }
    
    /**
     * Activity stopped - app might be in background
     */
    override fun onActivityStopped(activity: Activity) {
        activityCount--
        
        if (activityCount == 0) {
            // App went to background
            Log.i(TAG, "App entered background")
            
            // Schedule handle wipe after 30 seconds
            backgroundJob = scope.launch {
                delay(BACKGROUND_WIPE_DELAY_MS)
                
                Log.w(TAG, "App backgrounded for >30s - wiping handles (RH-03)")
                wipeSecuritySensitiveData()
            }
        }
    }
    
    /**
     * Activity paused - might be screen lock
     */
    override fun onActivityPaused(activity: Activity) {
        // Note: We can't reliably detect screen lock here
        // Screen lock detection is handled in MainActivity
    }
    
    /**
     * Wipe all security-sensitive data
     */
    fun wipeSecuritySensitiveData() {
        Log.w(TAG, "Wiping security-sensitive data")
        
        // Wipe routing handles (RH-06)
        routingHandleManager.wipeAll()
        
        // Clear session keys
        keyManager.clearSessionKeys()
        
        // Note: KEK is kept in memory for quick unlock
        // Only wiped on explicit logout or duress
    }
    
    /**
     * Wipe on screen lock (called from MainActivity)
     */
    fun onScreenLocked() {
        Log.w(TAG, "Screen locked - wiping handles (RH-04)")
        wipeSecuritySensitiveData()
    }
    
    /**
     * Cleanup
     */
    fun cleanup() {
        backgroundJob?.cancel()
        scope.cancel()
    }
    
    // Unused lifecycle callbacks
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
