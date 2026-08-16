package com.zerochat.app

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
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
 * 
 * BUG 4 FIX: Handles orientation/screen size changes in-process without
 * Activity recreation via configChanges attribute in manifest.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

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
        Log.d(TAG, "onCreate() - orientation=${resources.configuration.orientation}")

        // ── Anti-forensic window hardening ───────────────────────────────
        // Prevents screenshots, screen recordings, ADB screencap, and
        // Recents thumbnail leakage.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        // Block overlay attacks (Android 12+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            applyHideOverlayWindows()
        }
        // Suppress Recents screenshot (Android 13+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applyDisableRecentsThumbnail()
        }
        // ────────────────────────────────────────────────────────────────

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

    /**
     * BUG 4 FIX: Handle configuration changes (orientation, screen size) without
     * Activity recreation. Transport state lives in TransportService so it
     * survives these changes automatically.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "onConfigurationChanged() - orientation=${newConfig.orientation}, screenSize=${newConfig.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK}")
        // No action needed - Compose UI handles layout changes automatically
        // Transport state is preserved in TransportService (not Activity scope)
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

    // ── API-gated helpers ─────────────────────────────────────────────────────
    // Kotlin requires @RequiresApi on the call-site method even when the call
    // is already guarded by a Build.VERSION.SDK_INT check, because the compiler
    // performs static API-level verification independently of branch analysis.

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun applyHideOverlayWindows() {
        window.setHideOverlayWindows(true)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun applyDisableRecentsThumbnail() {
        setRecentsScreenshotEnabled(false)
    }
}

