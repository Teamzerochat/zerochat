package com.zerochat.app.domain.thermal

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thermal Monitor — Duty-cycle system for budget hardware.
 *
 * Paper §8: Prevents session collapse on SD 460-class devices by throttling
 * Sphinx packet rate and pausing i2pd tunnel building when temp exceeds 82°C.
 * Resumes at 78°C (hysteresis prevents oscillation).
 *
 * Temperature is polled every 5s from /sys/class/thermal/thermal_zone0/temp
 * (millidegrees on most ARM SoCs).
 */
@Singleton
class ThermalMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ThermalMonitor"
        const val THROTTLE_TEMP_C = 82  // Paper: 82°C trigger (public for ConnectionManager)
        const val RESUME_TEMP_C = 78    // Paper: 78°C recovery (hysteresis)
        private const val POLL_INTERVAL_MS = 5_000L
        private val THERMAL_ZONES = listOf(
            "/sys/class/thermal/thermal_zone0/temp",
            "/sys/class/thermal/thermal_zone1/temp",
            "/sys/class/thermal/thermal_zone2/temp"
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isThrottled = MutableStateFlow(false)
    val isThrottled: StateFlow<Boolean> = _isThrottled.asStateFlow()

    private val _currentTempC = MutableStateFlow(0)
    val currentTempC: StateFlow<Int> = _currentTempC.asStateFlow()

    // Callback for notifying other components (cover traffic, etc.)
    private var onThrottleChanged: ((Boolean) -> Unit)? = null

    fun setOnThrottleChanged(callback: (Boolean) -> Unit) {
        onThrottleChanged = callback
    }

    /**
     * Start polling thermal zones. Call after session establishment.
     */
    fun startMonitoring() {
        scope.launch {
            Log.i(TAG, "Thermal monitoring started (throttle=${THROTTLE_TEMP_C}°C, resume=${RESUME_TEMP_C}°C)")
            while (isActive) {
                try {
                    val tempC = readTemperature()
                    _currentTempC.value = tempC

                    val wasThrottled = _isThrottled.value

                    if (!wasThrottled && tempC >= THROTTLE_TEMP_C) {
                        // TRIGGER: temperature exceeded threshold
                        _isThrottled.value = true
                        Log.w(TAG, "⚠ THERMAL THROTTLE ON at ${tempC}°C (threshold=${THROTTLE_TEMP_C}°C)")
                        onThrottleChanged?.invoke(true)
                    } else if (wasThrottled && tempC < RESUME_TEMP_C) {
                        // RECOVERY: temperature dropped below hysteresis point
                        _isThrottled.value = false
                        Log.i(TAG, "✓ Thermal throttle OFF at ${tempC}°C (resume=${RESUME_TEMP_C}°C)")
                        onThrottleChanged?.invoke(false)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Thermal read failed: ${e.message}")
                }

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop monitoring (call on session teardown).
     */
    fun stopMonitoring() {
        scope.coroutineContext.cancelChildren()
        _isThrottled.value = false
        Log.i(TAG, "Thermal monitoring stopped")
    }

    /**
     * Read current device temperature from sysfs thermal zones.
     * Returns temperature in Celsius.
     */
    private fun readTemperature(): Int {
        // Try each thermal zone (different SoCs use different zones)
        for (zone in THERMAL_ZONES) {
            try {
                val file = File(zone)
                if (file.exists() && file.canRead()) {
                    val milliC = file.readText().trim().toIntOrNull() ?: continue
                    // Most SoCs report in millidegrees (e.g. 45000 = 45°C)
                    return if (milliC > 1000) milliC / 1000 else milliC
                }
            } catch (_: Exception) {
                continue
            }
        }

        // Fallback: use Android PowerManager thermal status (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val status = pm.currentThermalStatus
            return when (status) {
                PowerManager.THERMAL_STATUS_NONE -> 40
                PowerManager.THERMAL_STATUS_LIGHT -> 55
                PowerManager.THERMAL_STATUS_MODERATE -> 65
                PowerManager.THERMAL_STATUS_SEVERE -> 75
                PowerManager.THERMAL_STATUS_CRITICAL -> 85
                PowerManager.THERMAL_STATUS_EMERGENCY -> 95
                PowerManager.THERMAL_STATUS_SHUTDOWN -> 100
                else -> 50
            }
        }

        // Can't read temperature — return safe default
        return 50
    }
}
