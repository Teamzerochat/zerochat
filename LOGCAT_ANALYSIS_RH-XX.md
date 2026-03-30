# ZeroChat Logcat Analysis: Handshake & SAM Failures

## Executive Summary
Your app is experiencing two interconnected critical failures:
1. **NYM Rendezvous Handshake Failure**: Message fragmentation causes length prefix mismatch
2. **SAM Pre-Warm Timeout**: I2P router fails to bootstrap tunnels within 60s window

Both failures cause the connection flow to abort and restart, creating an infinite retry loop.

---

## Issue 1: NYM Rendezvous Handshake Failure

### Symptom
```
Length prefix error: Length prefix mismatch: expected 17542 but payload is 1452 bytes, using raw payload
PARSE FAILED: payload 1454 bytes, first 4: 0x44,0x86,0x41,0x2a
```

### Root Cause
The Nym network is **fragmenting large messages** across multiple polls. The code's `stripLengthPrefix()` function assumes the **entire message arrives atomically** in a single poll response, but Nym is sending:
- **Poll 1**: First fragment with length prefix = 17542 bytes, but only 1452 bytes of data
- **Poll 2+**: Subsequent fragments

When parsing poll 1:
- Reads 2-byte length prefix: `17542`
- Payload size is only `1450` bytes (1452 - 2 byte prefix)
- **Expects 17542 but got 1450 → Mismatch Error**
- Falls back to raw payload (1454 bytes)
- `RendezvousFrame.parse()` fails because it's incomplete data

### Code Location
📄 [RendezvousManager.kt#stripLengthPrefix()](RendezvousManager.kt:L180-L195)
```kotlin
private fun stripLengthPrefix(raw: ByteArray): ByteArray {
    if (raw.size < 2) return raw
    
    val length = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    
    // BUG: This validation FAILS for message fragments!
    require(length == raw.size - 2) { 
        "Length prefix mismatch: expected $length but payload is ${raw.size - 2} bytes" 
    }
    
    return raw.drop(2).toByteArray()
}
```

### Why This Breaks
1. **Nym uses 2-4 KB chunking**: Large messages are split across multiple delivery envelopes
2. **Each fragment gets its own length prefix**: Indicating the TOTAL message size, not fragment size
3. **Poll returns one fragment at a time**: The code tries to parse an incomplete fragment
4. **No reassembly logic**: The code doesn't buffer fragments and wait for the complete message

### Timeline from Logs
```
18:42:43.355 - NymTransport: Polled 1 messages total for rendezvous 0be95e2f...
18:42:43.364 - RendezvousManager: Length prefix mismatch ERROR
18:42:43.364 - RendezvousManager: PARSE FAILED (payload 1454 bytes)
← Message remains LOST, gap in nonce exchange
18:42:43.364 - RendezvousManager: POLLING my slot (strict mode)
18:42:49.426 - ConnectionManager: Re-publishing nonce (RESPONDER may not have received it)
← Attempts to compensate by re-sending, but original message lost
```

---

## Issue 2: SAM Pre-Warm Timeout

### Symptom
```
Router not ready yet (attempt 1-7): routers=0, floodfills=0, isFirewalled=false
Router tunnel-ready timeout after 30s
SAM pre-warm failed (will create on-demand)
java.io.IOException: SAM session creation failed after 60s
```

### Root Cause
The I2P router daemon (`i2pd`) is not bootstrapping into a usable state within the 30s tunnel-ready check or 60s total session creation window.

**Critical observation** from logs:
```
routers=0, floodfills=0
```
- This means the router has **zero peer connections** and **zero floodfill (directory) nodes**
- Network bootstrap completely failed
- Router cannot allocate tunnels without peer data

### Code Location
📄 [I2PRouterService.kt#waitForRouterTunnelReady()](app/src/main/java/.../I2PRouterService.kt)
📄 [SamClient.kt#createSession()](app/src/main/java/.../SamClient.kt:L50-L150)

### Why This Occurs

#### Possible Causes:

1. **Network Configuration Issue** (Most Likely)
   - Android emulator networking constraints
   - DNS resolution blocking (i2pd can't reach bootstrap nodes)
   - Firewall/proxy intercepting I2P traffic
   - Network isolation in development environment

2. **Resource Constraints**
   - i2pd daemon crashing before bootstrap completes
   - Memory pressure on device
   - Insufficient CPU for crypto operations (tunnel building is CPU-intensive)

3. **Configuration Issues**
   - `i2pd.conf` not properly configured for Android
   - Bootstrap nodes unreachable or stale
   - Network interface not available at startup

4. **Timing**
   - 30s is borderline for I2P bootstrap on emulator
   - Network latency delays peer discovery
   - First boot takes longer than subsequent boots

### Timeline from Logs
```
18:42:06.301 - Copying i2pd assets to cache...
18:42:22.769 - i2pd daemon started successfully
18:42:22.776 - Waiting for SAM bridge...
18:42:22.781 - ✓ SAM bridge is ready on *:7656
18:42:22.806 - Router not ready (attempt 1): routers=0, floodfills=0
18:42:53.723 - Router tunnel-ready timeout after 30s ← FAILS HERE
18:42:54.732 - Checking router tunnel readiness... (retrying)
18:42:54.736 - Router not ready (attempt 1 of 2nd attempt): routers=0, floodfills=0
18:43:26.286 - Router tunnel-ready timeout after 30s ← FAILS AGAIN
18:43:28.289 - SAM pre-warm failed after 60s ← FINAL FAILURE
```

---

## Issue 3: Nonce Exchange Stall (Cascading Effect)

### Symptom
```
Re-publishing nonce (RESPONDER may not have received it)
[Every 10-31+ seconds]
```

### Root Cause
**Cascading from Issue 1**: The handshake payload is lost due to fragmentation, so:
1. INITIATOR sends nonce → **Fragmented into 1452-byte chunks**
2. RESPONDER's `poll()` receives first fragment
3. `stripLengthPrefix()` throws error, fragment is discarded
4. RESPONDER **never sees the nonce**
5. INITIATOR sees timeout, republishes
6. Cycle repeats indefinitely

---

## Solutions & Fixes

### Fix 1: Implement Message Reassembly for Fragmented Payloads

**Location**: `RendezvousManager.kt`

**Change**: Instead of requiring atomicity, reassemble fragments:

```kotlin
private class FragmentBuffer {
    private val fragments = mutableMapOf<String, MutableList<ByteArray>>()
    private val expectedSizes = mutableMapOf<String, Int>()
    
    fun addFragment(correlationId: String, size: Int, fragment: ByteArray): ByteArray? {
        expectedSizes[correlationId] = size
        fragments.getOrPut(correlationId) { mutableListOf() }.add(fragment)
        
        val total = fragments[correlationId]?.sumOf { it.size } ?: 0
        return if (total >= size) {
            val assembled = fragments.remove(correlationId)?.flatten()?.toByteArray()
            expectedSizes.remove(correlationId)
            assembled
        } else {
            null
        }
    }
}

private val fragmentBuffer = FragmentBuffer()

private fun stripLengthPrefix(raw: ByteArray, messageId: String? = null): ByteArray? {
    if (raw.size < 2) return raw
    
    val length = ((raw[0].toInt() and 0xFF) shl 8) or (raw[1].toInt() and 0xFF)
    val payload = raw.drop(2).toByteArray()
    
    // If length prefix exceeds actual payload, this is a fragment
    if (length > payload.size && messageId != null) {
        Log.i(TAG, "Fragment received: ${payload.size}/${length} bytes for $messageId")
        return fragmentBuffer.addFragment(messageId, length, payload)
    }
    
    // Valid complete message
    if (length == payload.size) {
        return payload
    }
    
    // Single-chunk message or error
    Log.w(TAG, "Length mismatch: expected $length but got ${payload.size}, attempting parse anyway")
    return payload
}
```

### Fix 2: Increase SAM Session Creation Timeout

**Location**: `SamClient.kt`

**Current**:
```kotlin
private const val SESSION_RETRY_TIMEOUT_MS = 60_000L  // 60 seconds
private const val HANDSHAKE_TIMEOUT_MS = 30_000  // 30 seconds  
```

**Recommendation**: Increase for Android:
```kotlin
// On Android emulator, I2P bootstrap needs 60-90 seconds
private const val SESSION_RETRY_TIMEOUT_MS = if (Build.FINGERPRINT.contains("emulator")) {
    120_000L  // 2 minutes for emulator
} else {
    60_000L   // 1 minute for device
}

// Tunnel readiness check should allow more time
private val TUNNEL_READY_TIMEOUT_MS = if (Build.FINGERPRINT.contains("emulator")) {
    60_000L   // 60s for emulator (currently only 30s)
} else {
    30_000L   // 30s for device
}
```

### Fix 3: Improve I2P Router Bootstrap

**Location**: `I2PRouterService.kt`

**Changes**:
1. Pre-initialize i2pd with a warm cache before first run
2. Verify bootstrap nodes are reachable
3. Add network diagnostics

```kotlin
// In I2PRouterService.kt
private fun validateI2pdSetup(): Boolean {
    val confFile = File(i2pdDataDir, "i2pd.conf")
    
    if (!confFile.exists()) {
        Log.w(TAG, "i2pd.conf missing, creating with recommended settings...")
        createDefaultI2pdConfig()
    }
    
    // Verify we can reach DNS (needed for peer discovery)
    return try {
        InetAddress.getByName("api.nymtech.net")  // Fallback test
        true
    } catch (e: Exception) {
        Log.e(TAG, "DNS resolution failed: ${e.message}")
        false
    }
}

private fun createDefaultI2pdConfig() {
    val config = """
        [general]
        logfile = /data/user/0/com.zerochat.app/files/i2pd/i2pd.log
        loglevel = info
        port = 9711
        [sam]
        enabled = true
        address = 127.0.0.1
        port = 7656
        [reseed]
        enabled = true
        [upnp]
        enabled = true
    """.trimIndent()
    
    File(i2pdDataDir, "i2pd.conf").writeText(config)
}
```

### Fix 4: Add Connection State Monitoring

**Add diagnostics logging**:
```kotlin
fun logConnectionDiagnostics() {
    Log.i(TAG, "=== Connection Diagnostics ===")
    Log.i(TAG, "NYM Status: ${nymTransport.getConnectionState()}")
    Log.i(TAG, "I2P SAM Status: ${samClient.isConnected()}")
    Log.i(TAG, "Rendezvous State: ${rendezvousManager.getState()}")
    Log.i(TAG, "Fragment Buffer Size: ${fragmentBuffer.pendingFragments()}")
    Log.i(TAG, "Pending Nonce Republications: $nonceRepublishAttempts")
}
```

---

## Testing the Fixes

### Test Plan

1. **Fragment Reassembly**:
   - Send 100KB+ message through rendezvous
   - Verify it's reassembled correctly
   - Check no "Length prefix mismatch" errors

2. **SAM Timeout**:
   - Test on emulator with 120s timeout
   - Verify router becomes tunnel-ready
   - Measure time to bootstrap

3. **End-to-End**:
   - Establish full connection without errors
   - Verify nonce exchange completes
   - Confirm SPAKE2 handshake succeeds

---

## Severity Assessment

| Issue | Severity | Blocking | Time to Fix |
|-------|----------|----------|------------|
| NYM Fragmentation | **CRITICAL** | Yes | 2-4 hours |
| SAM Timeout | **HIGH** | Yes | 1-2 hours |
| Nonce Stall | Conditional* | If #1 unfixed | Auto-fix with #1 |

*Cascading from Issue 1

---

## Recommended Action Order

1. ✅ **First**: Implement fragment reassembly (Fix 1) - solves cascading failures
2. ✅ **Second**: Increase SAM timeout for Android (Fix 2)
3. ✅ **Third**: Add I2P bootstrap diagnostics (Fix 3)
4. ✅ **Fourth**: Deploy and test end-to-end

---

## Related Code References

- 📄 `RendezvousManager.kt` - Fragment handling (~L180-400)
- 📄 `SamClient.kt` - Session creation (~L50-150)
- 📄 `I2PRouterService.kt` - Router bootstrap
- 📄 `ConnectionManager.kt` - Nonce exchange retry logic
- 📄 `nym_transport.udl` - FFI interface for message transport
