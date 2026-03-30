package com.zerochat.app.domain.transport

import android.util.Log
import com.zerochat.app.domain.i2p.EncryptedChannel
import com.zerochat.app.domain.i2p.I2PRouterService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Hybrid Transport — Nym-first with I2P cross-fade.
 *
 * Paper §3: "During the 15-25s I2P bootstrap, all traffic flows through the
 * Nym mixnet. Once the I2P tunnel is established, traffic gradually cross-fades
 * from Nym (100%) to I2P (100%) over 10s to avoid a detectable traffic cliff."
 *
 * Strategy:
 * Phase 0 (I2P building):  Nym=100%, I2P=0%
 * Phase 1 (stochastic delay): Wait Δ ~ U[5,40]s before starting cross-fade (Paper §9)
 * Phase 2 (cross-fade):    Nym→0%, I2P→100% over CROSSFADE_DURATION_MS
 * Phase 3 (steady-state):  Nym=0%, I2P=100%
 */
@Singleton
class HybridTransport @Inject constructor(
    private val controller: TransportController
) {
    companion object {
        private const val TAG = "HybridTransport"
        private const val CROSSFADE_DURATION_MS = 10_000L  // 10s cross-fade
        private const val CROSSFADE_STEP_MS = 500L         // Update ratio every 500ms
        
        // Stochastic handover delay (Paper §9): Δ ~ U[5,40]s
        private const val MIN_MIGRATION_DELAY_S = 5L
        private const val MAX_MIGRATION_DELAY_S = 40L
    }

    sealed class DeliveryRoute {
        /** Send all traffic via Nym (I2P not ready) */
        object NymOnly : DeliveryRoute()
        /** Cross-fading: some via Nym, some via I2P */
        data class CrossFade(val i2pRatio: Float) : DeliveryRoute()
        /** Steady-state: all traffic via I2P */
        object I2POnly : DeliveryRoute()
    }

    private val _route = MutableStateFlow<DeliveryRoute>(DeliveryRoute.NymOnly)
    val route: StateFlow<DeliveryRoute> = _route.asStateFlow()

    private var crossfadeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Notify that I2P tunnel is now established. Start stochastic delay, then cross-fade.
     * 
     * Paper §9: After I2P reports stable (δc > 0.95), draw Δ ~ U[5,40]s delay
     * before starting the 10s cross-fade window.
     */
    fun onI2PReady() {
        // Draw stochastic delay: Δ ~ U[5,40]s
        val migrationDelaySeconds = Random.nextLong(MIN_MIGRATION_DELAY_S, MAX_MIGRATION_DELAY_S + 1)
        Log.i(TAG, "I2P ready — stochastic migration delay: ${migrationDelaySeconds}s (U[${MIN_MIGRATION_DELAY_S},${MAX_MIGRATION_DELAY_S}]s)")
        
        crossfadeJob = scope.launch {
            // Wait for stochastic delay before starting cross-fade
            delay(migrationDelaySeconds * 1000)
            
            Log.i(TAG, "Stochastic delay complete — starting cross-fade (${CROSSFADE_DURATION_MS}ms)")
            
            val steps = (CROSSFADE_DURATION_MS / CROSSFADE_STEP_MS).toInt()
            for (i in 1..steps) {
                val ratio = i.toFloat() / steps
                _route.value = DeliveryRoute.CrossFade(ratio)
                delay(CROSSFADE_STEP_MS)
            }
            _route.value = DeliveryRoute.I2POnly
            Log.i(TAG, "Cross-fade complete — I2P steady-state")
        }
    }

    /**
     * Check if a message should be sent via I2P or Nym based on current cross-fade ratio.
     *
     * During cross-fade, we probabilistically route:
     * - Random float < i2pRatio → I2P
     * - Otherwise → Nym
     */
    fun shouldUseI2P(): Boolean {
        return when (val current = _route.value) {
            is DeliveryRoute.NymOnly -> false
            is DeliveryRoute.I2POnly -> true
            is DeliveryRoute.CrossFade -> Math.random() < current.i2pRatio
        }
    }

    /**
     * Reset to Nym-only (e.g. on I2P tunnel failure / churn fallback).
     */
    fun resetToNym() {
        crossfadeJob?.cancel()
        _route.value = DeliveryRoute.NymOnly
        Log.w(TAG, "Reset to Nym-only delivery (I2P lost)")
    }

    /**
     * Full shutdown.
     */
    fun shutdown() {
        crossfadeJob?.cancel()
        scope.coroutineContext.cancelChildren()
        _route.value = DeliveryRoute.NymOnly
    }
}
