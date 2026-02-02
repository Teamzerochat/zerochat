package com.zerochat.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.zerochat.app.lifecycle.AppLifecycleObserver
import com.zerochat.app.ui.theme.ZeroChatTheme
import com.zerochat.app.ui.navigation.ZeroChatNavHost
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Main Activity - Entry point for ZeroChat
 * 
 * Security: 
 * - Activity is destroyed on back press to clear session state
 * - Detects screen lock and wipes handles (RH-04)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    
    @Inject
    lateinit var lifecycleObserver: AppLifecycleObserver
    
    private val screenLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // Screen turned off - might be locked
                    val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (keyguardManager.isKeyguardLocked) {
                        lifecycleObserver.onScreenLocked()
                    }
                }
                Intent.ACTION_USER_PRESENT -> {
                    // User unlocked device
                    // No action needed - handles already wiped
                }
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Register screen lock receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(screenLockReceiver, filter)
        
        setContent {
            ZeroChatTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZeroChatNavHost()
                }
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Unregister receiver
        try {
            unregisterReceiver(screenLockReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
        
        // Security: Clear any cached session data
        // KeyManager will handle volatile key destruction
    }
}

