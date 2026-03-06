package com.zerochat.app.domain.i2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext
import org.purplei2p.i2pd.I2PD_JNI
import java.io.File
import java.io.FileOutputStream

/**
 * I2P Router Service — Foreground service managing the embedded i2pd daemon.
 *
 * Lifecycle:
 * 1. Copies assets (certificates, config) to app-private storage
 * 2. Starts the i2pd native daemon via JNI
 * 3. Polls until SAM bridge is ready
 * 4. Exposes router readiness to the rest of the app
 *
 * The router persists its keys in dataDir, so the I2P destination
 * remains stable across app restarts (no new tunnel setup each time).
 */
class I2PRouterService : Service() {

    companion object {
        private const val TAG = "I2PRouterService"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "i2p_router_channel"
        private const val SAM_POLL_INTERVAL_MS = 2000L
        private const val SAM_READY_TIMEOUT_MS = 90_000L

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var isRouterReady = false
            private set

        @Volatile
        var startError: String? = null
            private set

        // FIX #4: Dedicated lock object — never lock on a mutable collection
        private val lock = Any()
        private val readyListeners = mutableListOf<CompletableDeferred<Boolean>>()

        /**
         * FIX #3: Reset all companion state. Called at top of onCreate()
         * so a re-created service never sees stale flags from a dead instance.
         */
        fun resetCompanionState() {
            synchronized(lock) {
                isRunning = false
                isRouterReady = false
                startError = null
                readyListeners.forEach { it.complete(false) }
                readyListeners.clear()
            }
        }

        /**
         * Wait until the router's SAM bridge is accepting connections.
         * Returns true if ready, false if timeout or error.
         */
        suspend fun waitUntilReady(timeoutMs: Long = SAM_READY_TIMEOUT_MS): Boolean {
            // FIX #2: All state checks + registration INSIDE the lock
            // to eliminate TOCTOU race with notifyReady()
            val deferred = synchronized(lock) {
                if (isRouterReady) return true
                if (startError != null) return false
                CompletableDeferred<Boolean>().also { readyListeners.add(it) }
            }

            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Router ready timeout after ${timeoutMs}ms")
                false
            }
        }

        private fun notifyReady(ready: Boolean) {
            synchronized(lock) {
                readyListeners.forEach { it.complete(ready) }
                readyListeners.clear()
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, I2PRouterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, I2PRouterService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataDir: String
    private var bootstrapWakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // FIX #3: Clear stale companion state from any previous instance
        resetCompanionState()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("I2P router starting..."))
        isRunning = true
        startError = null
        isRouterReady = false

        // H4: Acquire wake lock during bootstrap (60s safety timeout)
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        bootstrapWakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "zerochat:i2p-bootstrap"
        ).apply {
            acquire(60_000) // 60s max safety timeout
        }
        Log.i(TAG, "Bootstrap wake lock acquired")

        dataDir = File(filesDir, "i2pd").absolutePath
        serviceScope.launch { initAndStartRouter() }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Stopping i2pd router...")
        // R4 FIX: Mark NOT ready early (prevents new connections to dying daemon)
        // but keep isRunning=true until daemon actually stops (prevents double-start)
        isRouterReady = false
        notifyReady(false)
        releaseBootstrapWakeLock()
        serviceScope.cancel()
        try {
            I2PD_JNI.stopDaemon()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping daemon", e)
        }
        // R4 FIX: Only now mark as not running — daemon is actually gone
        isRunning = false
        Log.i(TAG, "i2pd router stopped")
    }

    private fun releaseBootstrapWakeLock() {
        bootstrapWakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Bootstrap wake lock released")
            }
        }
        bootstrapWakeLock = null
    }

    private suspend fun initAndStartRouter() {
        var daemonStarted = false
        try {
            // Step 1: Ensure data directory exists
            val dir = File(dataDir)
            if (!dir.exists()) dir.mkdirs()

            // Step 2: Copy assets (certificates, config) if needed
            copyAssetsIfNeeded(applicationContext.assets, dataDir)

            // Step 3: Load native library and start daemon
            Log.d(TAG, "Loading native libraries...")
            I2PD_JNI.loadLibraries()
            Log.d(TAG, "Loaded libraries successfully")
            
            // ABI check removed - method not in binary
            
            I2PD_JNI.setDataDir(dataDir)

            Log.i(TAG, "Starting i2pd daemon (dataDir=$dataDir)...")
            
            // Cleanup lock file
            val pidFile = File(dataDir, "i2pd.pid")
            if (pidFile.exists()) pidFile.delete()
            
            // TEMPORARY: Delete config file to test if default start works
            val configFile = File(dataDir, "i2pd.conf")
            if (configFile.exists()) {
                Log.w(TAG, "Deleting i2pd.conf to test default startup")
                configFile.delete()
            }

            val result = I2PD_JNI.startDaemon()

            if (result != "ok") {
                Log.e(TAG, "Daemon start failed: $result")
                startError = result
                notifyReady(false)
                updateNotification("Router failed: $result")
                return
            }

            daemonStarted = true  // FIX #1: Track that daemon is running

            Log.i(TAG, "i2pd daemon started. Waiting for SAM bridge...")
            updateNotification("I2P router bootstrapping...")

            // Step 4: Poll until SAM bridge is ready
            val samReady = pollSamReady()
            if (samReady) {
                isRouterReady = true
                releaseBootstrapWakeLock()
                notifyReady(true)
                updateNotification("I2P router ready")
                Log.i(TAG, "✓ SAM bridge is ready on 127.0.0.1:7656")
            } else {
                // FIX #1: Stop daemon if SAM never became ready
                Log.e(TAG, "SAM bridge did not become ready in time — stopping daemon")
                try { I2PD_JNI.stopDaemon() } catch (_: Exception) {}
                daemonStarted = false
                startError = "SAM bridge not ready after timeout"
                notifyReady(false)
                updateNotification("Router timeout")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Router init error", e)
            // FIX #1: Stop daemon if it was started before the error
            if (daemonStarted) {
                try { I2PD_JNI.stopDaemon() } catch (_: Exception) {}
            }
            startError = e.message
            notifyReady(false)
            updateNotification("Router error: ${e.message}")
        }
    }

    private suspend fun pollSamReady(): Boolean {
        val deadline = System.currentTimeMillis() + SAM_READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // FIX #10: Check for cancellation BEFORE the blocking JNI call
            coroutineContext.ensureActive()
            try {
                if (I2PD_JNI.getSAMState()) {
                    return true
                }
            } catch (e: Exception) {
                // JNI call may fail early during bootstrap
            }
            delay(SAM_POLL_INTERVAL_MS)
        }
        return false
    }

    private fun copyAssetsIfNeeded(assets: AssetManager, targetDir: String) {
        val versionFile = File(targetDir, "assets.version")
        val currentVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) { "unknown" }

        // Check if assets already copied for this version
        // FORCE COPY for debugging to ensure i2pd.conf is present and up to date
        /*
        if (versionFile.exists() && versionFile.readText() == currentVersion) {
            Log.d(TAG, "Assets already up to date ($currentVersion)")
            return
        }
        */

        Log.i(TAG, "Copying i2pd assets to $targetDir...")
        copyAssetDir(assets, "certificates", targetDir)
        copyAssetDir(assets, "tunnels.d", targetDir)
        copyAssetFile(assets, "i2pd.conf", targetDir)

        // Write version marker
        versionFile.writeText(currentVersion ?: "unknown")
        Log.i(TAG, "Assets copied successfully")
    }

    private fun copyAssetDir(assets: AssetManager, dirName: String, targetDir: String) {
        val targetPath = File(targetDir, dirName)
        if (!targetPath.exists()) targetPath.mkdirs()

        val files = assets.list(dirName) ?: return
        for (fileName in files) {
            val subFiles = assets.list("$dirName/$fileName")
            if (subFiles != null && subFiles.isNotEmpty()) {
                // Subdirectory — recurse
                copyAssetDir(assets, "$dirName/$fileName", targetDir)
            } else {
                // File — copy
                val outFile = File(targetDir, "$dirName/$fileName")
                outFile.parentFile?.mkdirs()
                if (!outFile.exists()) {
                    assets.open("$dirName/$fileName").use { input ->
                        FileOutputStream(outFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun copyAssetFile(assets: AssetManager, fileName: String, targetDir: String) {
        val outFile = File(targetDir, fileName)
        // Always overwrite config to pick up changes
        assets.open(fileName).use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "I2P Router",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "I2P router status"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ZeroChat")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("ZeroChat")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
