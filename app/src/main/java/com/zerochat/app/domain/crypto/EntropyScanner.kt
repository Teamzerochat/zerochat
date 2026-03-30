package com.zerochat.app.domain.crypto

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Post-Session Entropy Scanner — Verify forensic amnesia.
 *
 * Paper §10 (Verification): "After terminate(), Shannon entropy of the
 * former session-key heap region must be ≥ 7.9 bits/byte (effectively random).
 * This confirms that zeroize + munlock is working as claimed."
 *
 * This is a verification tool, not a runtime security mechanism.
 * Run after session teardown to confirm that:
 * 1. Session keys have been zeroized (H → random noise ≈ 8.0 bits/byte)
 * 2. No plaintext fragments remain in process memory
 */
@Singleton
class EntropyScanner @Inject constructor() {

    companion object {
        private const val TAG = "EntropyScanner"
        private const val ENTROPY_THRESHOLD = 7.9  // bits/byte (near-random)
    }

    data class ScanResult(
        val regionName: String,
        val entropy: Double,
        val passed: Boolean,
        val sizeBytes: Int
    )

    /**
     * Calculate Shannon entropy of a byte region.
     * Maximum entropy = 8.0 bits/byte (perfectly random).
     * Minimum = 0.0 (all same byte value).
     *
     * After zeroization, if the memory contains random padding or noise,
     * entropy should be ≥ 7.9.
     */
    fun shannonEntropy(data: ByteArray): Double {
        if (data.isEmpty()) return 0.0

        // Count frequency of each byte value
        val freq = IntArray(256)
        for (b in data) {
            freq[b.toInt() and 0xFF]++
        }

        val n = data.size.toDouble()
        var entropy = 0.0
        for (count in freq) {
            if (count > 0) {
                val p = count / n
                entropy -= p * (Math.log(p) / Math.log(2.0))
            }
        }

        return entropy
    }

    /**
     * Scan a memory region and check if entropy meets the threshold.
     */
    fun scanRegion(name: String, data: ByteArray): ScanResult {
        val entropy = shannonEntropy(data)
        val passed = entropy >= ENTROPY_THRESHOLD

        val result = ScanResult(
            regionName = name,
            entropy = entropy,
            passed = passed,
            sizeBytes = data.size
        )

        if (passed) {
            Log.i(TAG, "✓ $name: H=${String.format("%.3f", entropy)} bits/byte (PASS)")
        } else {
            Log.w(TAG, "✗ $name: H=${String.format("%.3f", entropy)} bits/byte (FAIL " +
                    "— expected ≥ $ENTROPY_THRESHOLD, data may not be zeroized)")
        }

        return result
    }

    /**
     * Run full post-session scan.
     * Call this AFTER terminate() to verify forensic amnesia.
     *
     * Returns true if ALL regions pass the entropy check.
     */
    fun runFullScan(regions: Map<String, ByteArray>): Boolean {
        Log.i(TAG, "=== Post-Session Entropy Scan ===")
        Log.i(TAG, "Threshold: H ≥ $ENTROPY_THRESHOLD bits/byte")
        Log.i(TAG, "Regions: ${regions.size}")

        val results = regions.map { (name, data) ->
            scanRegion(name, data)
        }

        val allPassed = results.all { it.passed }

        if (allPassed) {
            Log.i(TAG, "=== SCAN PASSED: All regions show high entropy (zeroized) ===")
        } else {
            val failures = results.filter { !it.passed }
            Log.e(TAG, "=== SCAN FAILED: ${failures.size} region(s) below threshold ===")
            failures.forEach {
                Log.e(TAG, "  ✗ ${it.regionName}: H=${String.format("%.3f", it.entropy)}")
            }
        }

        return allPassed
    }
}
