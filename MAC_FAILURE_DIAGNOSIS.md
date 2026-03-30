# ChaCha20-Poly1305 MAC Failure - Root Cause Analysis

## Problem
```
2026-03-26 15:30:02.271  3916-4139  Obfs4FrameUnwrapper     W  ChaCha20-Poly1305 decryption failed: error:1e000065:BAD_DECRYPT
2026-03-26 15:30:02.325  3916-4139  Obfs4FrameUnwrapper     W  ChaCha20-Poly1305 MAC failure or decryption error: BAD_DECRYPT
```

## Root Cause: **obfs4_state Mismatch Between INITIATOR and RESPONDER**

### Timeline Analysis

**RESPONDER logs:**
```
15:29:59.374 - Connected to rendezvous slot (RESPONDER)
15:29:59.395 - Derived TEMPORARY obfs4_state: dd7e0815945c2040
15:29:59.402 - obfs4_state SET: dd7e0815945c2040

(3-4 seconds pass - SPAKE2+ handshake should complete here)

15:30:01.826 - First message received (1452 bytes)
15:30:02.102 - Obfs4FrameUnwrapper initialized with: dd7e0815945c2040  ← ⚠️ STILL TEMPORARY
15:30:02.271 - MAC FAILURE
```

### The Race Condition

**RESPONDER (Slot B):**
1. ✅ Connects to slot B
2. ✅ Derives temporary obfs4_state from deterministic seed
3. ⏳ Waiting for messages to decrypt with temporary obfs4_state
4. ❌ **Has NOT retrieved SPAKE2+-derived obfs4_state yet**
5. ❌ Tries to decrypt with TEMPORARY obfs4_state → MAC FAILURE

**INITIATOR (Slot A) - Presumed sequence:**
1. ✅ Connects to slot A  
2. ✅ Derives temporary obfs4_state (same as RESPONDER)
3. ✅ Completes SPAKE2+ **handshake early** (maybe faster network)
4. ✅ Retrieves FINAL obfs4_state from Rust session store
5. ⚠️ **Switches to FINAL obfs4_state**
6. 🔄 Sends application message encrypted with FINAL obfs4_state
7. ❌ RESPONDER tries to decrypt with TEMPORARY obfs4_state → MAC FAILURE

## Why This Happens

### Current Code Flow
```kotlin
// ConnectionManager.kt - Lines 297-320
// BUG: obfs4_state retrieved AFTER verification, but BEFORE polling loop

// 1. SPAKE2+ sends/receives handshake (uses any obfs4_state available)
// 2. If SPAKE2+ succeeds and verification passes:
try {
    val obfs4StateList = sessionGetObfs4StateWrapper(sessionHandle)  // ← Retrieved HERE
    val obfs4StateBytes = obfs4StateList.map { it.toByte() }.toByteArray()
    rendezvousManager.setObfs4State(obfs4StateBytes)  // ← Set HERE
} catch (e: Exception) {
    collector.emit(ConnectionState.Failed("Failed to retrieve obfs4_state"))
}
```

**Issue:** If INITIATOR finishes this early and starts sending, while RESPONDER hasn't yet:
- Both poll with different obfs4_state values
- MAC verification fails for RESPONDER

## Solution: **Force obfs4_state Synchronization**

### Option 1: **Ensure Same obfs4_state for Handshake + Data (RECOMMENDED)**

**Approach:** Use TEMPORARY obfs4_state throughout handshake AND initial data exchange.

**Implementation:**
```kotlin
// In ConnectionManager after SPAKE2+ completes:
fun verifyAndSwitchObfs4State(sessionHandle: Long, role: HandshakeRole) {
    // 1. BEFORE switching: Both sides can decrypt with temporary obfs4_state
    // 2. Exchange obfs4_state "fingerprint" (first 8 bytes) wrapped in handshake
    // 3. Both sides verify fingerprint matches
    // 4. THEN retrieve and switch to final obfs4_state
    
    try {
        val obfs4StateList = sessionGetObfs4StateWrapper(sessionHandle)
        val obfs4StateBytes = obfs4StateList.map { it.toByte() }.toByteArray()
        val fingerprint = obfs4StateBytes.sliceArray(0 until 8)
        
        // TODO: Send fingerprint confirmation (wrapped in temporary obfs4_state)
        // TODO: Wait for peer's fingerprint confirmation
        // TODO: Verify both sides have same fingerprint
        
        // ONLY THEN switch to final obfs4_state
        rendezvousManager.setObfs4State(obfs4StateBytes)
        Log.i(TAG, "✓ Verified final obfs4_state: ${fingerprint.joinToString("") { "%02x".format(it) }}")
    } catch (e: Exception) {
        Log.e(TAG, "Failed to verify/switch obfs4_state: ${e.message}")
        throw e
    }
}
```

### Option 2: **Counter-Based State Detection**

**Approach:** Don't rely on sequential SPAKE2+ completion. Instead, detect state switch based on counter value.

```kotlin
// ObFS4FrameUnwrapper - Add state detection:
// If MAC fails N times with temporary state, assume peer switched → try to load final state
```

### Option 3: **Add Synchronization Point in RendezvousManager**

**Approach:** Add explicit barrier before switching obfs4_state

```kotlin
// RendezvousManager.kt
@Volatile
private var obfs4StateReady = false  // New flag

fun markObfs4StateReady() {
    obfs4StateReady = true
    val state = obfs4State?.sliceArray(0 until 8)?.joinToString("") { "%02x".format(it) } ?: "?"
    Log.i(TAG, "✓ Final obfs4_state verified and ready: $state")
}

suspend fun waitForObfs4StateReady() {
    var waited = 0
    while (!obfs4StateReady && waited < 5000) {
        delay(100)
        waited += 100
    }
    if (!obfs4StateReady) {
        throw TimeoutException("obfs4_state handoff timeout — peer may not have switched state")
    }
}

// In polling loop:
waitForObfs4StateReady()  // Ensure peer is ready before using final state
pollRendezvous(...)
```

## Why temp == final is WRONG

**Current RendezvousManager code:**
```kotlin
val tempObfs4State = deriveTemporaryObfs4State(...)  // From deterministic seed
rendezvousManager.setObfs4State(tempObfs4State)      // Set immediately

// Later in handshake...
val obfs4StateList = sessionGetObfs4StateWrapper(sessionHandle)  // From SPAKE2+ shared secret
rendezvousManager.setObfs4State(obfs4StateBytes)     // Overwrite
```

These are **DIFFERENT derivations**:
- **Temporary**: `SHA256(sessionToken || epoch || "zerochat-obfs4-state-v1")`
- **Final**: `HKDF(SPAKE2_shared_secret, "zerochat-obfs4-state-v1")`

They should produce different keys! If they're the same, something is wrong with the derivation.

## Immediate Fix

**Add obfs4_state fingerprint logging to detect desynchronization:**

```kotlin
// In Obfs4FrameUnwrapper - track which key is in use:
private val obfs4StateFingerprint = obfs4State.sliceArray(0 until 8)
    .joinToString("") { "%02x".format(it) }

fun decodeFrame(ciphertext: ByteArray): ByteArray? {
    try {
        // ... decryption logic ...
        return plaintext
    } catch (e: Exception) {
        Log.w(TAG, "MAC failure with obfs4_state[$obfs4StateFingerprint] — peer may be using different state")
        return null
    }
}

// AND in ConnectionManager after retrieving final obfs4_state:
val finalFingerprint = obfs4StateBytes.sliceArray(0 until 8)
    .joinToString("") { "%02x".format(it) }
Log.i(TAG, "Final obfs4_state[$finalFingerprint] retrieved from SPAKE2+")
Log.i(TAG, "Temporary obfs4_state[$tempFingerprint] was used for handshake")
Log.i(TAG, "States are ${if (tempFingerprint == finalFingerprint) "SAME" else "DIFFERENT"}")
```

## Recommended Fix: Check if Temporary == Final

If temporary and final obfs4_state fingerprints are the same, the derivation is working correctly and the issue is timing.

If they're different, ensure explicit synchronization between INITIATOR and RESPONDER before switching.

