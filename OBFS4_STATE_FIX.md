# obfs4_state Synchronization Fix

## Problem Identified
MAC failures occur because **INITIATOR and RESPONDER use different obfs4_state values** during message exchange:
- INITIATOR: Completes SPAKE2+, retrieves FINAL obfs4_state, sends messages
- RESPONDER: Still using TEMPORARY obfs4_state when trying to decrypt

*Root cause: Race condition between SPAKE2+ completion and obfs4_state switch*

## Solution: Multi-Phase Verification

### Phase 1: Confirm Both Sides Derived Same Temporary obfs4_state
Before SPAKE2+ handshake, exchange fingerprints of temporary states to ensure deterministic derivation is working.

### Phase 2: SPAKE2+ Handshake
Both sides complete handshake and form shared secret.

### Phase 3: Verify & Switch obfs4_state
- Both sides retrieve FINAL obfs4_state from SPAKE2+ shared secret
- Exchange fingerprints of FINAL states
- Verify they match
- Only then switch to using FINAL obfs4_state for decryption

## Implementation Plan

### Step 1: Add State Verification to RendezvousManager

```kotlin
// RendezvousManager.kt
@Volatile
private var obfs4StateFinal: ByteArray? = null  // Final state (only set after verification)

fun getObfs4State(): ByteArray? = obfs4State  // Returns current (temporary or final)

fun getObfs4StateFinal(): ByteArray? = obfs4StateFinal  // Returns ONLY verified final state

fun setObfs4StateFinal(state: ByteArray) {
    // This is ONLY called after verification
    obfs4StateFinal = state
    obfs4State = state  // Switch active state
    
    val fingerprint = state.sliceArray(0 until 8).joinToString("") { "%02x".format(it) }
    Log.i(TAG, "✅ obfs4_state switched to FINAL (verified): $fingerprint")
}
```

### Step 2: Add Fingerprint Exchange to Handshake

In the SPAKE2+ confirmation phase, also exchange obfs4_state fingerprints:

```kotlin
// In ConnectionManager - after SPAKE2+ verification:
try {
    val obfs4StateList = sessionGetObfs4StateWrapper(sessionHandle)
    val obfs4StateBytes = obfs4StateList.map { it.toByte() }.toByteArray()
    val fingerprint = obfs4StateBytes.sliceArray(0 until 8)
    
    // Send fingerprint to peer (wrapped in current obfs4 transport)
    val confirmMsg = buildObfs4StateConfirmation(fingerprint, role)
    controller.withTransport { 
        it.sendRendezvousMessage(selectedSlot, confirmMsg)
    }
    
    // Receive peer's fingerprint
    val peerFingerprint = receiveObfs4StateConfirmation()
    
    // Compare fingerprints
    if (fingerprint.contentEquals(peerFingerprint)) {
        Log.i(TAG, "✅ obfs4_state fingerprints match — switching to final state")
        rendezvousManager.setObfs4StateFinal(obfs4StateBytes)
    } else {
        Log.e(TAG, "❌ obfs4_state fingerprint mismatch!")
        Log.e(TAG, "  Local:  ${fingerprint.joinToString("") { "%02x".format(it) }}")
        Log.e(TAG, "  Remote: ${peerFingerprint.joinToString("") { "%02x".format(it) }}")
        collector.emit(ConnectionState.Failed("obfs4_state mismatch — cannot proceed"))
        return@flow
    }
} catch (e: Exception) {
    collector.emit(ConnectionState.Failed("Failed to verify obfs4_state: ${e.message}"))
    return@flow
}
```

### Step 3: Update Polling to Wait for Final State

```kotlin
// RendezvousManager - polling loop
suspend fun poll(...): Flow<PollResult> = flow {
    // ... existing code ...
    
    while (attempts < MAX_POLL_ATTEMPTS) {
        // Before using obfs4_state for decryption, ensure it's been verified
        val currentState = obfs4State ?: run {
            emit(PollResult.Timeout)
            Log.e(TAG, "Cannot poll: obfs4_state not set")
            return@flow
        }
        
        // Check if this is the verified FINAL state or just temporary
        val isFinalState = obfs4StateFinal != null && obfs4StateFinal.contentEquals(currentState)
        
        if (!isFinalState) {
            // Still using temporary state — this is OK for handshake/initial messages
            // But log it for diagnostics
            Log.d(TAG, "Using temporary obfs4_state (not yet verified final)")
        }
        
        val responses = controller.withTransport { 
            it.pollRendezvous(mySlotId, currentState) 
        }
        
        // ... handle responses ...
    }
}
```

## Testing the Fix

### Diagnostic Output Expected

**Before fix (Race condition):**
```
15:29:59.395 - Derived temporary obfs4_state: dd7e0815945c2040
15:30:01.826 - First message received
15:30:02.102 - Using obfs4_state[$dd7e0815945c2040] for decryption
15:30:02.271 - ❌ MAC failure #1 with obfs4_state[$dd7e0815945c2040]
15:30:02.825 - ❌ MAC failure #2 with obfs4_state[$dd7e0815945c2040]
15:30:03.127 - ❌ MAC failure #3 with obfs4_state[$dd7e0815945c2040]
15:30:03.427 - ⚠️ CRITICAL: Persistent MAC failures suggest obfs4_state mismatch!
```

**After fix (Synchronized):**
```
15:29:59.395 - Derived temporary obfs4_state: dd7e0815945c2040
15:29:59.402 - obfs4_state set: dd7e0815945c2040
15:30:00.555 - 🔄 SPAKE2+ handshake complete. Switching to final: a1b2c3d4e5f6g7h8
15:30:00.556 - ✅ obfs4_state fingerprints match — switching to final
15:30:00.600 - ✅ obfs4_state switched to FINAL (verified): a1b2c3d4e5f6g7h8
15:30:02.000 - Polling with final obfs4_state (verified)
15:30:02.125 - ✅ Frame decrypted successfully (counter=1)
15:30:02.450 - ✅ Frame decrypted successfully (counter=2)
```

## Fallback if Fingerprints Don't Match

If obfs4_state fingerprints differ between devices:

1. **Check temporary state derivation**: Both should derive SAME temporary from deterministic seed
2. **Check SPAKE2+ shared secret**: Both should get SAME final state from SPAKE2+ output
3. **Check HKDF**: Rust HKDF and/or Java HKDF might differ

If temp fingerprints DON'T match: **Deterministic derivation is broken**
If final fingerprints don't match: **SPAKE2+ shared secrets differ**

## Quick Validation

Before implementing full fix, check if temp == final by comparing fingerprints in logs:
```
15:29:59.395 - Derived temporary: dd7e0815945c2040
15:30:00.556 - Final: ???
```

If they're the same (`dd7e...` on both), then the derivations are synchronized and the issue is purely timing-based.
If they're different, there's a deeper key derivation issue.

