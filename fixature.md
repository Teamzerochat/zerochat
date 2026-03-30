# Fixature — Regression Audit Fixes

All 7 regressions from `regression_audit.md` have been resolved.

---

## R1. `runBlocking` in `disconnect()` — **HIGH** → ✅ Fixed

**File:** [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt#L334-L342)

**Problem:** `runBlocking { samClient.close() }` blocks the calling thread while waiting for `sessionMutex`. If called from `Dispatchers.IO` while `createSession()` holds the mutex, this causes thread-starvation deadlock.

**Fix:** Replaced with `CoroutineScope(Dispatchers.IO).launch { samClient.close() }` — fire-and-forget. Safe because `createSession()` calls `closeInternal()` first, so any race with reconnect is self-healing.

```diff
-kotlinx.coroutines.runBlocking { samClient.close() }
+CoroutineScope(Dispatchers.IO).launch { samClient.close() }
```

---

## R2. `acceptStream()` ACCEPT Reply Reads With Infinite Timeout — **MEDIUM** → ✅ Fixed

**File:** [SamClient.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L242-L254)

**Problem:** `soTimeout` was reset to `0` *before* sending the STREAM ACCEPT command and reading its reply. If the SAM bridge crashed during ACCEPT processing, `readLine()` would block forever.

**Fix:** Moved `soTimeout = 0` to *after* validating `RESULT=OK`, just before the peer-wait `readLine`.

```diff
-acceptSocket.soTimeout = 0
 writeCommand(output, "STREAM ACCEPT ID=$id SILENT=false")
 val acceptReply = readLine(input)  // still uses HANDSHAKE_TIMEOUT_MS
 ...
 if (!acceptReply.contains("RESULT=OK")) { throw ... }
+acceptSocket.soTimeout = 0         // only now — peer-wait can be infinite
 val peerLine = readLine(input)
```

---

## R3. Non-Local `return null` Inside `receiveLock.withLock` — **LOW** → ✅ Fixed

**File:** [EncryptedChannel.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/EncryptedChannel.kt#L98-L135)

**Problem:** `return null` inside a `withLock` lambda is a non-local return. Although `ReentrantLock.withLock` uses `try/finally` so the lock is released, this is fragile and misleading.

**Fix:** Replaced all 4 occurrences with `return@withLock null`.

```diff
-val lenBytes = stream.readFully(LENGTH_SIZE) ?: return null
+val lenBytes = stream.readFully(LENGTH_SIZE) ?: return@withLock null
```

---

## R4. `onDestroy()` Clears `isRunning` Before Stopping Daemon — **MEDIUM** → ✅ Fixed

**File:** [I2PRouterService.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L131-L147)

**Problem:** `isRunning = false` was set before `stopDaemon()`. If `start()` is called during this window, two daemon instances would compete for the PID file and SAM port.

**Fix:** Set `isRouterReady = false` early (prevents new connections), but moved `isRunning = false` to *after* `stopDaemon()` completes.

```diff
 isRouterReady = false
 notifyReady(false)
 serviceScope.cancel()
 I2PD_JNI.stopDaemon()
-isRunning = false  // was here before
+isRunning = false  // moved after stopDaemon
```

---

## R5. Missing `samClient.close()` in Error Path — **MEDIUM** → ✅ Fixed

**File:** [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt#L322-L331)

**Problem:** If `createSession()` succeeded but a later phase failed, the `catch` block called `teardownRendezvous()` but never closed the SAM session. The session resources would leak until the next `createSession()` call.

**Fix:** Added `samClient.close()` in the `catch` block.

```diff
 } catch (e: Exception) {
     rendezvousManager.teardownRendezvous()
+    try { samClient.close() } catch (_: Exception) {}
 }
```

---

## R6. `connectStream`/`acceptStream` Read `sessionId` Outside Mutex — **MEDIUM** → ✅ Fixed

**File:** [SamClient.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L153-L157) and [L217-L221](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L217-L221)

**Problem:** Both functions read `sessionId` without holding `sessionMutex`. If `close()` runs concurrently, `sessionId` can be nulled between the check and the SAM command.

**Fix:** Snapshot `sessionId` under a brief mutex lock in both functions.

```diff
-val id = sessionId ?: throw IllegalStateException("No active session")
+val id = sessionMutex.withLock {
+    sessionId ?: throw IllegalStateException("No active session")
+}
```

---

## R7. Responder Blocks Forever If Initiator Gives Up — **LOW** → ✅ Fixed

**File:** [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt#L284-L285)

**Problem:** The responder's `acceptStream()` uses `soTimeout = 0` (infinite wait). If the initiator exhausts all 24 retry attempts and throws, the responder remains blocked forever.

**Fix:** Wrapped `acceptStream()` in `withTimeout(150_000)` (150s), which exceeds the initiator's total retry window (~122s) by a safe margin.

```diff
-val inbound = samClient.acceptStream()
+val inbound = withTimeout(150_000) { samClient.acceptStream() }
```

---

## Summary

| ID | Severity | File | Fix |
|----|----------|------|-----|
| R1 | **HIGH** | `ConnectionManager.kt` | `runBlocking` → `CoroutineScope.launch` |
| R2 | **MEDIUM** | `SamClient.kt` | Timeout stays active through ACCEPT reply |
| R3 | **LOW** | `EncryptedChannel.kt` | `return null` → `return@withLock null` |
| R4 | **MEDIUM** | `I2PRouterService.kt` | `isRunning = false` moved after `stopDaemon()` |
| R5 | **MEDIUM** | `ConnectionManager.kt` | `samClient.close()` added to `catch` block |
| R6 | **MEDIUM** | `SamClient.kt` | `sessionId` snapshot under `sessionMutex` |
| R7 | **LOW** | `ConnectionManager.kt` | `acceptStream()` wrapped in `withTimeout(150s)` |
