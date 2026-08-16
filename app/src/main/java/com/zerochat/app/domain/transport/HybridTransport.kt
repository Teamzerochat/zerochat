package com.zerochat.app.domain.transport

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Hybrid Transport — Forensic-Hardened Dual-Transport State Machine (v8)
 *
 * All transition logic is deterministic and sequence-based. Zero local entropy
 * is used for routing decisions, cover timing, or jitter computation.
 *
 * Lifecycle:
 *   Phase 0 (NYM_ONLY):   All real traffic via Nym. I2P building in background.
 *   Phase 1 (TRANSITION): Probabilistic routing (deterministic per-seq). Cover traffic starts.
 *   Phase 2 (I2P_ONLY):   All real traffic via I2P. Nym decays with cover traffic.
 *
 * Properties:
 *   - Transport selection is immutable per message (anti-correlation)
 *   - Transition triggers on outbound sequence count, NOT clocks (anti-skew)
 *   - All randomness derived from HMAC(seed, seq) (anti-fingerprint)
 *   - Cover traffic uses valid sequence numbers and identical encryption pipeline
 *   - Nym cover decays asymptotically with deterministic noise (anti curve-fitting)
 */
@Singleton
class HybridTransport @Inject constructor(
    private val controller: TransportController
) {
    companion object {
        private const val TAG = "HybridTransport"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        // Maximum terminal decay delay before Nym is shut off (ms)
        const val MAX_TERMINAL_DECAY_MS = 60_000L
    }

    // --- Phase & Mode Enums ---

    enum class Phase {
        NYM_ONLY,
        TRANSITION,
        I2P_ONLY
    }

    enum class NymMode {
        FULL,        // Real messages flow through Nym
        COVER_ONLY,  // Only cover traffic on Nym (I2P is primary)
        OFF          // Nym torn down
    }

    enum class Transport {
        NYM,
        I2P
    }

    // --- Observable State ---

    private val _phase = MutableStateFlow(Phase.NYM_ONLY)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _nymMode = MutableStateFlow(NymMode.FULL)
    val nymMode: StateFlow<NymMode> = _nymMode.asStateFlow()

    // UI event channel for toast notifications
    private val _uiEvent = MutableStateFlow<TransportUiEvent?>(null)
    val uiEvent: StateFlow<TransportUiEvent?> = _uiEvent.asStateFlow()

    // --- Internal State ---

    @Volatile
    var transitionSeed: ByteArray? = null
        private set

    @Volatile
    var i2pReady: Boolean = false
        private set

    // Deterministic transition boundaries (sequence-based)
    private var transitionStartSeq: Long = Long.MAX_VALUE
    private var transitionDurationSeq: Long = Long.MAX_VALUE
    private var readinessOffset: Long = Long.MAX_VALUE

    // Outbound sequence counter (single writer via AtomicLong)
    private val _localSendSeq = AtomicLong(0)

    // Cover traffic state
    private var coverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Initialization ---

    /**
     * Initialize the transport with the shared SPAKE2+ secret.
     * Both peers derive identical transition parameters from this seed.
     */
    fun initialize(sharedSecret: ByteArray) {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        transitionSeed = md.digest(sharedSecret)

        val seed = transitionSeed!!

        // Derive deterministic transition boundaries
        transitionStartSeq = deriveDeterministicSeq(seed)
        transitionDurationSeq = deriveDeterministicDuration(seed)
        readinessOffset = deriveDeterministicOffset(seed)

        Log.i(TAG, "Initialized: startSeq=$transitionStartSeq, durationSeq=$transitionDurationSeq, readinessOffset=$readinessOffset")

        // Emit UI event: I2P tunnel building
        _uiEvent.value = TransportUiEvent.I2PBuilding
    }

    /**
     * Signal that the I2P tunnel is now established and ready for traffic.
     */
    fun onI2PReady() {
        i2pReady = true
        Log.i(TAG, "I2P tunnel ready. Transition will activate at seq >= $readinessOffset")
    }

    /**
     * Signal I2P failure — fall back to NYM_ONLY.
     */
    fun onI2PFailure() {
        i2pReady = false
        _phase.value = Phase.NYM_ONLY
        _nymMode.value = NymMode.FULL
        coverJob?.cancel()
        transitionStartSeq = Long.MAX_VALUE // Prevent future transitions
        Log.w(TAG, "I2P failure — reset to NYM_ONLY")
    }

    // --- Sequence & Phase Management ---

    /**
     * Atomically increment and return the next outbound sequence number.
     * Used by the single-writer egress dispatcher in MessageQueue.
     */
    fun incrementAndGetOutboundSeq(): Long {
        return _localSendSeq.getAndIncrement()
    }

    /**
     * Get current outbound sequence without incrementing (for UI/logging).
     */
    fun currentOutboundSeq(): Long = _localSendSeq.get()

    /**
     * Update the phase based on current outbound sequence and I2P readiness.
     * Called before every send operation.
     *
     * IMPORTANT: If I2P is not ready OR seq < readinessOffset, force NYM_ONLY.
     * This smears the activation edge to hide the exact I2P build completion moment.
     */
    fun updatePhase(localSendSeq: Long) {
        val seed = transitionSeed ?: return

        // Smeared readiness gate: hides exact I2P completion timing
        if (!i2pReady || localSendSeq < readinessOffset) {
            _phase.value = Phase.NYM_ONLY
            return
        }

        val previousPhase = _phase.value

        when {
            localSendSeq < transitionStartSeq -> _phase.value = Phase.NYM_ONLY
            localSendSeq < transitionStartSeq + transitionDurationSeq -> {
                _phase.value = Phase.TRANSITION

                // Fire UI toast on first entry into TRANSITION
                if (previousPhase == Phase.NYM_ONLY) {
                    _uiEvent.value = TransportUiEvent.SwitchingToI2P
                }
            }
            else -> {
                _phase.value = Phase.I2P_ONLY

                // Start cover traffic on first entry into I2P_ONLY
                if (previousPhase != Phase.I2P_ONLY) {
                    _nymMode.value = NymMode.COVER_ONLY
                    _uiEvent.value = TransportUiEvent.I2PActive
                    startCoverTrafficDecay()
                }
            }
        }
    }

    // --- Deterministic Routing ---

    /**
     * Compute the I2P routing probability for the current sequence position
     * within the transition window.
     */
    fun computeI2PProbability(localSendSeq: Long): Double {
        if (localSendSeq < transitionStartSeq) return 0.0
        if (localSendSeq >= transitionStartSeq + transitionDurationSeq) return 1.0

        val progress = (localSendSeq - transitionStartSeq).toDouble() / transitionDurationSeq
        return progress.coerceIn(0.0, 1.0)
    }

    /**
     * Deterministic per-sequence routing decision.
     * ZERO local entropy. Device RNG is never used.
     *
     * @return Transport.I2P or Transport.NYM
     */
    fun determineTransport(localSendSeq: Long): Transport {
        val currentPhase = _phase.value

        return when (currentPhase) {
            Phase.NYM_ONLY -> Transport.NYM
            Phase.I2P_ONLY -> Transport.I2P
            Phase.TRANSITION -> {
                val seed = transitionSeed ?: return Transport.NYM
                val r = deterministicRandomDouble(seed, localSendSeq)
                val p = computeI2PProbability(localSendSeq)
                val chosen = if (r < p) Transport.I2P else Transport.NYM
                Log.d(TAG, "phase=TRANSITION seq=$localSendSeq p=${"%.3f".format(p)} r=${"%.3f".format(r)} transport=$chosen")
                chosen
            }
        }
    }

    // --- Cover Traffic ---

    /**
     * Generate a cover packet with a valid sequence number and modeled size.
     * Cover packets are structurally identical to real messages but marked
     * with PayloadFlag.COVER inside the encrypted payload.
     */
    fun generateCoverPayload(localSendSeq: Long, coverCounter: Long): ByteArray {
        val seed = transitionSeed ?: return ByteArray(0)
        val size = deterministicSize(seed, coverCounter)
        return generatePseudoTextBytes(seed, coverCounter, size)
    }

    /**
     * Start the asymptotic cover traffic decay on Nym.
     * Cover frequency decreases over time with deterministic noise
     * to prevent perfect curve-fitting by DPI.
     */
    private fun startCoverTrafficDecay() {
        coverJob?.cancel()
        coverJob = scope.launch {
            var coverCounter = 0L
            Log.i(TAG, "Cover traffic decay started (NymMode=COVER_ONLY)")

            while (_nymMode.value == NymMode.COVER_ONLY && isActive) {
                val seed = transitionSeed ?: break

                val baseDelay = deterministicExpDecay(seed, coverCounter)
                val jitter = deterministicGaussianNoise(seed, coverCounter)
                val totalDelay = (baseDelay + jitter).coerceAtLeast(500L)

                delay(totalDelay)

                // The actual cover packet send is triggered by the callback
                onCoverTrafficTick?.invoke(coverCounter)
                coverCounter++

                if (totalDelay > MAX_TERMINAL_DECAY_MS) {
                    Log.i(TAG, "Cover decay terminal threshold reached. Nym → OFF")
                    _nymMode.value = NymMode.OFF
                    break
                }
            }
        }
    }

    // Callback for MessageQueue to send cover packets
    var onCoverTrafficTick: ((Long) -> Unit)? = null

    // --- Deterministic Derivation Functions ---

    /**
     * HMAC-based uniform double in [0.0, 1.0).
     * Uses 53 bits of entropy from HMAC output for IEEE 754 precision.
     */
    fun deterministicRandomDouble(seed: ByteArray, seq: Long): Double {
        val hmacBytes = hmac(seed, seq)
        val buffer = ByteBuffer.wrap(hmacBytes)
        val bits = buffer.long
        // Convert 53 bits to a double in [0.0, 1.0) — no modulo bias
        return (bits ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    /**
     * Derive the sequence number at which the transition starts.
     * Range: [15, 50] messages into the session.
     */
    private fun deriveDeterministicSeq(seed: ByteArray): Long {
        val hmacBytes = hmac(seed, 0x5451_0001L) // "TQ" + derivation index
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 15 + (value % 36) // [15, 50]
    }

    /**
     * Derive the number of messages over which the transition spans.
     * Range: [10, 30] messages.
     */
    private fun deriveDeterministicDuration(seed: ByteArray): Long {
        val hmacBytes = hmac(seed, 0x5451_0002L)
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 10 + (value % 21) // [10, 30]
    }

    /**
     * Derive the readiness offset to smear the I2P activation edge.
     * Range: [5, 15] messages after I2P reports ready.
     */
    private fun deriveDeterministicOffset(seed: ByteArray): Long {
        val hmacBytes = hmac(seed, 0x5451_0003L)
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 5 + (value % 11) // [5, 15]
    }

    /**
     * Deterministic egress delay (ms) for normalizing queue dispatch timing.
     * Range: [10, 120] ms.
     */
    fun deterministicEgressDelay(seed: ByteArray, seq: Long): Long {
        val hmacBytes = hmac(seed, seq xor 0x4547_0001L) // "EG" + index
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 10 + (value % 111) // [10, 120]
    }

    /**
     * Deterministic processing window target (ms) for constant-time padding.
     * Range: [5, 25] ms.
     */
    fun deterministicProcessingWindow(seed: ByteArray, seq: Long): Long {
        val hmacBytes = hmac(seed, seq xor 0x5057_0001L) // "PW" + index
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 5 + (value % 21) // [5, 25]
    }

    /**
     * Deterministic queue delay under backpressure (ms).
     * Range: [50, 500] ms.
     */
    fun deterministicQueueDelay(seed: ByteArray, seq: Long): Long {
        val hmacBytes = hmac(seed, seq xor 0x5144_0001L) // "QD" + index
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 50 + (value % 451) // [50, 500]
    }

    /**
     * Deterministic CPU jitter delay (ms) before socket write.
     * Range: [10, 120] ms. Occasionally skips (if bit 0 of HMAC is 0).
     */
    fun deterministicJitter(seed: ByteArray, seq: Long): Long {
        val hmacBytes = hmac(seed, seq xor 0x4A54_0001L) // "JT" + index
        // Occasionally skip jitter entirely (deterministic decision)
        if (hmacBytes[31].toInt() and 0x01 == 0) return 0L
        val value = abs(ByteBuffer.wrap(hmacBytes).long)
        return 10 + (value % 111) // [10, 120]
    }

    /**
     * Deterministic exponential decay delay for cover traffic (ms).
     * Starts at ~3000ms and grows exponentially.
     */
    private fun deterministicExpDecay(seed: ByteArray, counter: Long): Long {
        val hmacBytes = hmac(seed, counter xor 0x4544_0001L) // "ED" + index
        val baseDouble = abs(ByteBuffer.wrap(hmacBytes).long % 1000).toDouble() / 1000.0
        // Exponential growth: 3000 * e^(counter * 0.15)
        val base = (3000.0 * Math.exp(counter * 0.15)).toLong()
        return base + (baseDouble * 1000).toLong()
    }

    /**
     * Deterministic Gaussian-like noise to prevent curve-fitting of decay profile.
     * Returns a value in [-500, 500] ms.
     */
    private fun deterministicGaussianNoise(seed: ByteArray, counter: Long): Long {
        val hmacBytes = hmac(seed, counter xor 0x474E_0001L) // "GN" + index
        val value = ByteBuffer.wrap(hmacBytes).long
        return (value % 1001) - 500 // [-500, 500]
    }

    /**
     * Deterministic cover packet size modeling human text length distribution.
     * Range: [10, 300] bytes (log-normal-ish).
     */
    fun deterministicSize(seed: ByteArray, counter: Long): Int {
        val hmacBytes = hmac(seed, counter xor 0x535A_0001L) // "SZ" + index
        val baseDouble = abs(ByteBuffer.wrap(hmacBytes).long % 1000).toDouble() / 1000.0
        // Log-normal distribution modeling: median ~80 bytes, tail to 300
        val size = (10 + 290 * Math.pow(baseDouble, 0.7)).toInt()
        return size.coerceIn(10, 300)
    }

    /**
     * Generate pseudo-text bytes that mimic the entropy profile of UTF-8 chat text.
     * Uses printable ASCII range with word-length spacing patterns.
     */
    fun generatePseudoTextBytes(seed: ByteArray, counter: Long, size: Int): ByteArray {
        val result = ByteArray(size)
        // Use HMAC chain to fill bytes deterministically
        var chainSeed = hmac(seed, counter xor 0x5054_0001L) // "PT" + index
        var offset = 0
        var wordLen = 0

        while (offset < size) {
            if (offset % 32 == 0) {
                chainSeed = hmac(chainSeed, offset.toLong())
            }
            val b = chainSeed[offset % 32]
            val charByte = (b.toInt() and 0x7F)

            // Simulate word boundaries: space every 3-8 chars
            if (wordLen > 2 + (charByte % 6)) {
                result[offset] = ' '.code.toByte()
                wordLen = 0
            } else {
                // Printable ASCII range [0x20, 0x7E]
                result[offset] = (0x20 + (abs(charByte) % 95)).toByte()
                wordLen++
            }
            offset++
        }
        return result
    }

    // --- HMAC Primitive ---

    private fun hmac(key: ByteArray, data: Long): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        val dataBytes = ByteBuffer.allocate(8).putLong(data).array()
        return mac.doFinal(dataBytes)
    }

    // --- Lifecycle ---

    fun clearUiEvent() {
        _uiEvent.value = null
    }

    fun shutdown() {
        coverJob?.cancel()
        scope.coroutineContext.cancelChildren()
        _phase.value = Phase.NYM_ONLY
        _nymMode.value = NymMode.FULL
        i2pReady = false
        transitionSeed = null
        _localSendSeq.set(0)
    }
}

/**
 * UI events emitted by HybridTransport for the chat screen.
 */
sealed class TransportUiEvent {
    /** I2P tunnel is being built in the background */
    object I2PBuilding : TransportUiEvent()
    /** Transition to I2P is starting — "speeds will improve" */
    object SwitchingToI2P : TransportUiEvent()
    /** I2P is now the primary transport */
    object I2PActive : TransportUiEvent()
}
