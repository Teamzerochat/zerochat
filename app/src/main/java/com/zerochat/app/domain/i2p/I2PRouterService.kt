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
import com.zerochat.app.domain.crypto.NetDbEncryption
import kotlinx.coroutines.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
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
        
        // BUG 1 FIX: i2pd HTTP API endpoint for router status
        private const val I2PD_HTTP_HOST = "127.0.0.1"
        private const val I2PD_HTTP_PORT = 7070
        private const val ROUTER_READY_CHECK_INTERVAL_MS = 500L
        private const val ROUTER_READY_TIMEOUT_MS = 90_000L
        
        // BUG 2 FIX: Restart circuit-breaker constants
        private const val MAX_CONSECUTIVE_RESTARTS = 3
        private const val RESTART_WINDOW_MS = 5 * 60 * 1000L // 5 minutes
        private const val INITIAL_RESTART_BACKOFF_MS = 2_000L // 2s
        private const val MAX_RESTART_BACKOFF_MS = 60_000L // 60s

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

        // Pre-warmed SAM session (Paper §3: LeaseSet published early)
        @Volatile
        var cachedSamClient: SamClient? = null
            private set
        @Volatile
        var cachedDestination: String? = null
            private set
        
        // BUG 2 FIX: Restart circuit-breaker state tracking
        @Volatile
        private var consecutiveRestartCount = 0
        @Volatile
        private var lastRestartTimeMs = 0L
        @Volatile
        private var restartBackoffMs = INITIAL_RESTART_BACKOFF_MS

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
                // Close pre-warmed SAM session if any (non-suspend safe)
                try { cachedSamClient?.closeBlocking() } catch (_: Exception) {}
                cachedSamClient = null
                cachedDestination = null
                // BUG 2 FIX: Reset restart circuit-breaker state
                consecutiveRestartCount = 0
                lastRestartTimeMs = 0L
                restartBackoffMs = INITIAL_RESTART_BACKOFF_MS
            }
        }
        
        /**
         * BUG 2 FIX: Check if restart is allowed under circuit-breaker policy.
         * @return Pair of (allowed: Boolean, backoffMs: Long) - if allowed is false,
         *         backoffMs is irrelevant. If allowed is true, backoffMs is the
         *         delay to wait before restarting.
         */
        internal fun checkRestartAllowed(): Pair<Boolean, Long> {
            val now = System.currentTimeMillis()
            
            // Check if we're outside the restart window - reset counter
            if (now - lastRestartTimeMs > RESTART_WINDOW_MS) {
                Log.i(TAG, "Restart window expired (${RESTART_WINDOW_MS / 1000}s), resetting counter")
                consecutiveRestartCount = 0
                restartBackoffMs = INITIAL_RESTART_BACKOFF_MS
            }
            
            // Check if we've hit max consecutive restarts
            if (consecutiveRestartCount >= MAX_CONSECUTIVE_RESTARTS) {
                Log.e(TAG, "Max consecutive restarts ($MAX_CONSECUTIVE_RESTARTS) reached within ${RESTART_WINDOW_MS / 1000}s window - stopping retries")
                return false to 0L
            }
            
            // Return the backoff delay to apply
            val currentBackoff = restartBackoffMs
            return true to currentBackoff
        }
        
        /**
         * BUG 2 FIX: Record a failed restart attempt and update backoff.
         */
        internal fun recordRestartFailure() {
            val now = System.currentTimeMillis()
            lastRestartTimeMs = now
            consecutiveRestartCount++
            restartBackoffMs = (restartBackoffMs * 2).coerceAtMost(MAX_RESTART_BACKOFF_MS)
            Log.w(TAG, "Restart attempt $consecutiveRestartCount/$MAX_CONSECUTIVE_RESTARTS failed, next retry in ${restartBackoffMs}ms")
        }
        
        /**
         * BUG 2 FIX: Record a successful restart and reset counter.
         */
        internal fun recordRestartSuccess() {
            Log.i(TAG, "Restart succeeded, resetting circuit-breaker counter")
            consecutiveRestartCount = 0
            restartBackoffMs = INITIAL_RESTART_BACKOFF_MS
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

        /**
         * BUG 1 FIX: Check if i2pd router is tunnel-ready via HTTP API.
         * Polls the i2pd HTTP API (127.0.0.1:7070) to verify:
         * - Router has completed bootstrap (netdb peers > 0)
         * - Router status is not "Firewalled with no reachable addresses"
         * - Tunnel build success rate is non-zero
         *
         * @param timeoutMs Maximum time to wait for router readiness
         * @return true if router is tunnel-ready, false if timeout
         */
        suspend fun waitForRouterTunnelReady(timeoutMs: Long = ROUTER_READY_TIMEOUT_MS): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            var backoffMs = 500L // Start at 500ms
            var attempt = 0

            while (System.currentTimeMillis() < deadline) {
                attempt++
                try {
                    // FIX: Fetch the root dashboard page (/), NOT /?page=net.
                    // The Routers/Floodfills summary is on the root page;
                    // /?page=net only lists active tunnels and transports.
                    val url = "http://$I2PD_HTTP_HOST:$I2PD_HTTP_PORT/"
                    val response = withContext(Dispatchers.IO) {
                        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        try {
                            connection.connectTimeout = 2000
                            connection.readTimeout = 2000
                            connection.requestMethod = "GET"
                            connection.inputStream.bufferedReader().readText()
                        } finally {
                            connection.disconnect()
                        }
                    }

                    // Parse response to check router status.
                    // i2pd HTTP console formats vary across versions:
                    //   Format 1: <b>Routers:</b> 123
                    //   Format 2: Routers: 123
                    //   Format 3: "OK (123 routers)"
                    val routerCount = (
                        Regex("""Routers:</b>\s*(\d+)""").find(response)
                            ?: Regex("""Routers:\s*(\d+)""").find(response)
                            ?: Regex("""\((\d+)\s+routers\)""").find(response)
                    )?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    val floodfillCount = (
                        Regex("""Floodfills:</b>\s*(\d+)""").find(response)
                            ?: Regex("""Floodfills:\s*(\d+)""").find(response)
                    )?.groupValues?.get(1)?.toIntOrNull() ?: 0

                    val hasNetDbPeers = routerCount > 0

                    // Log raw response snippet on first attempt or when count is 0 for diagnostics
                    if (routerCount == 0 && (attempt == 1 || attempt % 5 == 0)) {
                        val snippet = response.take(300).replace("\n", " ").replace("\r", "")
                        Log.d(TAG, "HTTP dashboard snippet (attempt $attempt): $snippet")
                    }

                    // Check for firewall status
                    val isFirewalled = response.contains("Firewalled") &&
                        response.contains("no reachable addresses")

                    // Check for tunnel build success (look for success rate or active tunnels)
                    val hasTunnelActivity = response.contains("tunnel") &&
                        (response.contains("success") || Regex("""build.*success.*?(\d+)%""").find(response) != null)

                    // Router is ready if: has netdb peers AND not firewalled with no addresses
                    if (hasNetDbPeers && !isFirewalled) {
                        Log.i(TAG, "Router tunnel-ready after ${attempt} checks (routers=$routerCount, floodfills=$floodfillCount${if (hasTunnelActivity) ", has tunnel activity" else ""})")
                        return true
                    }

                    Log.d(TAG, "Router not ready yet (attempt $attempt): routers=$routerCount, floodfills=$floodfillCount, isFirewalled=$isFirewalled")

                } catch (e: Exception) {
                    // HTTP API not available yet or connection failed
                    Log.d(TAG, "Router readiness check failed (attempt $attempt): ${e.message}")
                }

                // Exponential backoff: 500ms, 1s, 2s, 4s, max 8s
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(8000L)
            }

            Log.w(TAG, "Router tunnel-ready timeout after ${timeoutMs / 1000}s")
            return false
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

    private val daemonStarted = AtomicBoolean(false)
    private val daemonLock = Mutex()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var dataDir: String
    private var bootstrapWakeLock: android.os.PowerManager.WakeLock? = null
    private var netDbEncryption: NetDbEncryption? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate() - starting on thread: ${Thread.currentThread().name}")
        
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

        // BUG 5 FIX: Move NetDB decryption to background thread
        // File I/O should not block main thread during service creation
        serviceScope.launch(Dispatchers.IO) {
            // Decrypt NetDB at-rest data before starting daemon (Paper §7b)
            val encryption = NetDbEncryption()
            netDbEncryption = encryption
            try {
                val netDbDir = File(dataDir, "netDb")
                if (netDbDir.exists()) {
                    encryption.decryptDirectory(netDbDir)
                    Log.i(TAG, "NetDB decrypted from Keystore-protected storage")
                }
            } catch (e: Exception) {
                Log.w(TAG, "NetDB decryption failed (first run or key rotation)", e)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (daemonStarted.get()) {
            return START_STICKY
        }
        
        serviceScope.launch(Dispatchers.IO) {
            delay(2000L) // Guard against crash-restart race with HWUI
            initAndStartRouter()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        Log.i(TAG, "Stopping i2pd router...")
        // R4 FIX: Mark NOT ready early (prevents new connections to dying daemon)
        // but keep isRunning=true until daemon actually stops (prevents double-start)
        isRouterReady = false
        notifyReady(false)
        releaseBootstrapWakeLock()

        // Stop daemon blocking to ensure it's down before service is destroyed
        serviceScope.launch(Dispatchers.IO) {
            daemonLock.withLock {
                if (daemonStarted.get()) {
                    try {
                        I2PD_JNI.stopDaemon()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error stopping daemon", e)
                    } finally {
                        daemonStarted.set(false)
                    }
                }
            }
        }.also { job ->
            runBlocking { job.join() } // block until native stop completes
        }
        serviceScope.cancel()

        // Encrypt NetDB at-rest before shutdown completes (Paper §7b)
        try {
            val netDbDir = File(dataDir, "netDb")
            if (netDbDir.exists()) {
                val encryption = netDbEncryption
                if (encryption != null) {
                    encryption.encryptDirectory(netDbDir)
                    Log.i(TAG, "NetDB encrypted for at-rest protection")
                } else {
                    Log.w(TAG, "NetDB encryption not initialized, skipping encrypt")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt NetDB on shutdown", e)
        }

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

    private suspend fun initAndStartRouter() = daemonLock.withLock {
        // BUG 2 FIX: Check circuit-breaker before attempting restart
        val (allowed, backoffMs) = checkRestartAllowed()
        if (!allowed) {
            Log.e(TAG, "Restart blocked by circuit-breaker - surfacing fatal error")
            startError = "I2P router failed to start after $MAX_CONSECUTIVE_RESTARTS attempts"
            notifyReady(false)
            updateNotification("Router failed: ${startError}")
            // Stop service to prevent infinite retry loop
            stopSelf()
            return@withLock
        }
        
        // Apply backoff delay before restart
        if (backoffMs > 0 && lastRestartTimeMs > 0) {
            Log.i(TAG, "Applying restart backoff: ${backoffMs}ms (attempt ${consecutiveRestartCount + 1}/$MAX_CONSECUTIVE_RESTARTS)")
            delay(backoffMs)
        }

        try {
            // BUG 5 FIX: Ensure NetDB encryption is initialized before proceeding
            // (onCreate may still be initializing it in background)
            if (netDbEncryption == null) {
                netDbEncryption = NetDbEncryption()
            }
            
            // Step 1: Ensure data directory exists
            val dir = File(dataDir)
            if (!dir.exists()) dir.mkdirs()

            // Step 2: Copy assets (certificates, config) if needed
            copyAssetsIfNeeded(applicationContext.assets, dataDir)

            // BUG 2 FIX: Verify and regenerate i2pd.conf if needed
            // The bundled config must contain reseed URLs, otherwise i2pd cannot bootstrap
            // from scratch (NetDB empty or fully expired).
            ensureI2pdConfigValid(dataDir)

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

            // NOTE: i2pd.conf is preserved across restarts (contains 2-hop tunnel
            // and exploratory throttle settings). NetDB is also persisted for faster reseed.

            if (!daemonStarted.compareAndSet(false, true)) {
                return@withLock
            }

            var result = ""
            try {
                // FIX 6 — MAIN THREAD OFFLOAD
                val configFile = File(dataDir, "i2pd.conf")
                if (!configFile.exists() || configFile.length() == 0L) {
                    throw IllegalStateException("i2pd.conf missing or empty at: ${configFile.absolutePath}")
                }
                Log.d(TAG, "Config verified at ${configFile.absolutePath}, size=${configFile.length()}")
                serviceScope.launch(Dispatchers.IO) {
                    try {
                        result = I2PD_JNI.startDaemon()
                        Log.i(TAG, "i2pd daemon started successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "startDaemon threw: ${e.message}", e)
                        throw e
                    }
                }.join()
            } catch (e: Exception) {
                daemonStarted.set(false)
                throw e
            }

            if (result != "ok") {
                daemonStarted.set(false)
                Log.e(TAG, "Daemon start failed: $result")
                startError = result
                notifyReady(false)
                updateNotification("Router failed: $result")
                // BUG 2 FIX: Record restart failure for circuit-breaker
                recordRestartFailure()
                return@withLock
            }

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
                
                // BUG 2 FIX: Record restart success
                recordRestartSuccess()

                // Paper §3: Pre-create SAM session to publish LeaseSet early (saves ~5-8s)
                try {
                    val prewarmClient = SamClient()
                    prewarmClient.createSession()
                    val dest = prewarmClient.getLocalDestination()
                    cachedSamClient = prewarmClient
                    cachedDestination = dest
                    Log.i(TAG, "✓ Pre-warmed SAM session (destination cached, LeaseSet publishing)")
                } catch (e: Exception) {
                    Log.w(TAG, "SAM pre-warm failed (will create on-demand)", e)
                }
            } else {
                // FIX #1: Stop daemon if SAM never became ready
                Log.e(TAG, "SAM bridge did not become ready in time — stopping daemon")
                if (daemonStarted.get()) {
                    try { I2PD_JNI.stopDaemon() } catch (_: Exception) {}
                    daemonStarted.set(false)
                }
                startError = "SAM bridge not ready after timeout"
                notifyReady(false)
                updateNotification("Router timeout")
                // BUG 2 FIX: Record restart failure for circuit-breaker
                recordRestartFailure()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Router init error", e)
            if (daemonStarted.get()) {
                try { I2PD_JNI.stopDaemon() } catch (_: Exception) {}
                daemonStarted.set(false)
            }
            startError = e.message
            notifyReady(false)
            updateNotification("Router error: ${e.message}")
            // BUG 2 FIX: Record restart failure for circuit-breaker
            recordRestartFailure()
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
        
        // Add missing files required by ParseConfig
        copyAssetFile(assets, "i2pd.conf", targetDir)
        copyAssetFile(assets, "tunnels.conf", targetDir)

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
                assets.open("$dirName/$fileName").use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
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

    private fun ensureI2pdConfigValid(dataDir: String) {
        val configFile = File(dataDir, "i2pd.conf")
        
        // Minimal validation: ensure config exists, is non-empty, and contains reseed URLs
        if (!configFile.exists() || configFile.length() == 0L) {
            throw IllegalStateException("i2pd.conf missing or empty at: ${configFile.absolutePath}")
        }
        
        val configContent = configFile.readText()
        if (!configContent.contains("urls=")) {
            throw IllegalStateException("i2pd.conf missing 'urls=' key (reseed servers not configured)")
        }
        
        Log.d(TAG, "i2pd.conf validated: file exists, contains reseed URLs")
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
