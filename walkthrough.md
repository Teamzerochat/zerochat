# Concurrency Audit — `domain/i2p` module

Audited files:
- [I2PRouterService.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt)
- [SamClient.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt)
- [I2PStream.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PStream.kt)
- [EncryptedChannel.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/EncryptedChannel.kt)

---

## 1. JNI Daemon Not Stopped on Init Failure

| | |
|-|-|
| **Severity** | **HIGH** |
| **Location** | `I2PRouterService.initAndStartRouter()` — [L159–L191](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L159-L191) |
| **Risk** | JNI daemon resource leak / orphaned native process |

**Why dangerous:** After `I2PD_JNI.startDaemon()` returns `"ok"` (L159), if the subsequent `pollSamReady()` times out (L173) or the `catch` block at L186 fires, the method returns/errors without calling `I2PD_JNI.stopDaemon()`. The native daemon keeps running, consuming CPU/memory/ports, and the next `onCreate()` will attempt to start a **second** daemon instance against a locked PID file.

**Minimal fix:** Add a `stopDaemon()` call in both the SAM-timeout branch (L180) and the `catch` block (L186). Consider wrapping the post-start section in its own try/finally.

---

## 2. Race Between `waitUntilReady()` and `notifyReady()`

| | |
|-|-|
| **Severity** | **MEDIUM** |
| **Location** | `I2PRouterService.Companion.waitUntilReady()` — [L57–L73](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L57-L73) |
| **Risk** | Coroutine hangs forever / deferred never completes |

**Why dangerous:** There is a TOCTOU race between checking `isRouterReady` outside the `synchronized` block (L58–59) and adding the `CompletableDeferred` inside the block (L62–64). If `notifyReady(false)` fires between L59 and L62 (e.g. from `onDestroy()`), the deferred is added *after* `notifyReady` has already cleared and completed all listeners. That deferred is never completed (except by its own timeout). Callers that set a very long timeout will hang.

**Minimal fix:** Move the initial `isRouterReady` / `startError` checks **inside** the `synchronized(readyListeners)` block, so registration and state check are atomic.

---

## 3. Stale Companion State on Service Restart

| | |
|-|-|
| **Severity** | **HIGH** |
| **Location** | `I2PRouterService.Companion` static vars — [L39–L48](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L39-L48) |
| **Risk** | Double daemon start / stale ready flag |

**Why dangerous:** `isRunning`, `isRouterReady`, `startError`, and `readyListeners` are `companion object` (static) fields but scoped to a per-instance `Service`. If the system kills and quickly re-creates the service (as Android does with foreground services under memory pressure), the **new** instance reads stale `isRunning = true` from the old instance. The `start()` helper does not guard against this, meaning callers may see `isRunning == true` and skip launching the service, or `isRouterReady == true` pointing at a dead daemon.

**Minimal fix:** Clear all companion state at the very top of `onCreate()` and at the top of `onDestroy()` before `serviceScope.cancel()`.

---

## 4. `readyListeners` Not Thread-Safe Between `onDestroy` and `waitUntilReady`

| | |
|-|-|
| **Severity** | **MEDIUM** |
| **Location** | `I2PRouterService.Companion.readyListeners` — [L51](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L51) |
| **Risk** | `ConcurrentModificationException` |

**Why dangerous:** `readyListeners` is a plain `mutableListOf` (backed by `ArrayList`). It is guarded by `synchronized(readyListeners)` in `waitUntilReady` and `notifyReady`, but the same list is **also** the lock token. If a coroutine calls `waitUntilReady` while `onDestroy` calls `notifyReady`, they are correctly serialised *only* if both callers synchronize on the exact same object reference. This works today, but a refactor that replaces the list (e.g. `readyListeners = mutableListOf()`) would silently break the lock.

**Minimal fix:** Use a dedicated `private val lock = Any()` object for synchronization instead of locking on the mutable collection itself.

---

## 5. `SamClient` Singleton Allows Concurrent `createSession()` Calls — Session Overwrite

| | |
|-|-|
| **Severity** | **HIGH** |
| **Location** | `SamClient.createSession()` — [L51–L100](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L51-L100) |
| **Risk** | Control socket leak / orphaned SAM session |

**Why dangerous:** `SamClient` is annotated `@Singleton`, but `createSession()` has no guard against double invocation. If two coroutines call `createSession()` concurrently, both will create a control socket and SAM session. The second call overwrites `controlSocketRef` (L92), `sessionId` (L84), and `localDestination` (L85), **leaking** the first control socket forever. The first SAM session stays alive on the i2pd side with no way to clean it up.

**Minimal fix:** Add a `Mutex` or `synchronized` block around the entire session-creation flow, or check-and-throw if `sessionId != null`.

---

## 6. No Socket Timeout on SAM Handshake Sockets

| | |
|-|-|
| **Severity** | **HIGH** |
| **Location** | `SamClient.connectStream()` [L128–L132](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L128-L132) and `SamClient.acceptStream()` [L183–L187](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L183-L187) |
| **Risk** | Indefinite thread blocking / coroutine leak |

**Why dangerous:** Both `connectStream` and `acceptStream` create sockets with `soTimeout = 0` (infinite). The `readLine()` calls during SAM handshake (L139, L146, L194, L201, L210) will block the `Dispatchers.IO` thread **forever** if the SAM bridge hangs, becomes unresponsive, or half-closes the connection. Since `Dispatchers.IO` has a limited thread pool (default 64), a few hung connections can exhaust it.

Additionally, even though `withContext(Dispatchers.IO)` is cancellable between suspension points, the blocking `input.read()` inside `readLine()` is **not cancellation-aware** — coroutine cancellation will not interrupt the blocked thread.

**Minimal fix:** Set `soTimeout` to a reasonable handshake timeout (e.g. 30s) during the SAM HELLO/SESSION/STREAM command phase. Then reset to `0` after handshake completes, before returning the `I2PStream`.

---

## 7. `SamClient.close()` Is Not Thread-Safe

| | |
|-|-|
| **Severity** | **MEDIUM** |
| **Location** | `SamClient.close()` — [L228–L238](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/SamClient.kt#L228-L238) |
| **Risk** | Race condition / NPE |

**Why dangerous:** `close()` reads and nulls `controlSocketRef`, `sessionId`, and `localDestination` without synchronization. If a concurrent `connectStream()` reads `sessionId` (L118) right as `close()` nulls it, the behavior is undefined — the session ID could be partially visible. Also, `controlSocketRef?.close()` followed by `controlSocketRef = null` is not atomic; another thread could observe the socket as non-null but already closed.

**Minimal fix:** Guard `close()` with the same mutex/synchronized block recommended for `createSession()`, or use `AtomicReference` for `controlSocketRef`.

---

## 8. `I2PStream.close()` Double-Close Race

| | |
|-|-|
| **Severity** | **LOW** |
| **Location** | `I2PStream.close()` — [L85–L101](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PStream.kt#L85-L101) |
| **Risk** | Double invocation |

**Why dangerous:** The `if (closed) return` check at L87 followed by `closed = true` at L88 is not atomic. Two threads calling `close()` simultaneously can both pass the check before either sets the flag, leading to double-close of the socket. While `Socket.close()` is documented as idempotent, the pattern is fragile and the log message would fire twice.

**Minimal fix:** Use `AtomicBoolean.compareAndSet(false, true)` instead of the check-then-set pattern.

---

## 9. `EncryptedChannel` Send/Receive Are Not Serialize-Safe

| | |
|-|-|
| **Severity** | **MEDIUM** |
| **Location** | `EncryptedChannel.send()` [L42–L76](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/EncryptedChannel.kt#L42-L76) and `EncryptedChannel.receive()` [L84–L123](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/EncryptedChannel.kt#L84-L123) |
| **Risk** | Corrupted wire frames / interleaved writes |

**Why dangerous:** If two coroutines call `send()` concurrently, their `stream.write(frame.array())` calls can interleave, producing a corrupted wire frame that the receiver cannot decrypt. Similarly, concurrent `receive()` calls could read partial frames from each other. There is no synchronization on the underlying stream.

**Minimal fix:** Add a `Mutex` (or `ReentrantLock`) for `send()` and another for `receive()` to serialize access to the underlying stream.

---

## 10. `pollSamReady()` Not Cancellation-Cooperative

| | |
|-|-|
| **Severity** | **LOW** |
| **Location** | `I2PRouterService.pollSamReady()` — [L194–L207](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L194-L207) |
| **Risk** | Slow shutdown / delayed scope cancellation |

**Why dangerous:** The loop uses `System.currentTimeMillis()` for its deadline instead of relying solely on structured concurrency. While `delay()` is cancellable, the JNI call `I2PD_JNI.getSAMState()` is blocking and not interruptible. If `serviceScope.cancel()` fires in `onDestroy()`, the coroutine will only check cancellation at the next `delay()` call — up to 2 seconds late. During that window, the daemon may already be stopped (via `stopDaemon()`) but the JNI call is still in-flight.

**Minimal fix:** Add `ensureActive()` before the JNI call, or wrap with `yield()`.

---

## 11. `EncryptedChannel.close()` Double-Close Race (identical to I2PStream)

| | |
|-|-|
| **Severity** | **LOW** |
| **Location** | `EncryptedChannel.close()` — [L130–L144](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/EncryptedChannel.kt#L130-L144) |
| **Risk** | Double session key wipe / double stream close |

**Why dangerous:** Same non-atomic check-then-set on `closed` as `I2PStream`. Two concurrent `close()` calls can both pass the guard. The session key would be wiped twice (harmless but sloppy), and `stream.close()` called twice.

**Minimal fix:** Use `AtomicBoolean.compareAndSet(false, true)`.

---

## 12. `SamClient` Singleton Not Cleaned Up Before Service Restart

| | |
|-|-|
| **Severity** | **MEDIUM** |
| **Location** | `SamClient` (Hilt `@Singleton`) vs `I2PRouterService.onDestroy()` — [L113–L126](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/i2p/I2PRouterService.kt#L113-L126) |
| **Risk** | Orphaned SAM session / stale destination after service restart |

**Why dangerous:** `I2PRouterService.onDestroy()` calls `I2PD_JNI.stopDaemon()` but there is no call to `SamClient.close()` anywhere in the service lifecycle. The `SamClient` singleton survives the service's destruction (it's scoped to the Hilt component, not the service). On restart, the singleton still holds references to a dead control socket and a stale `sessionId` that the freshly-started daemon knows nothing about. Any `connectStream()` / `acceptStream()` call will use the dead session ID.

**Minimal fix:** Call `samClient.close()` in `onDestroy()` (inject `SamClient` into the service or call via a shared dependency), or add a `reset()` method that `createSession()` invokes before creating a new session.

---

## Summary by Severity

| Severity | Count | Risk IDs |
|----------|-------|----------|
| **HIGH** | 4 | #1, #3, #5, #6 |
| **MEDIUM** | 4 | #2, #4, #7, #9 |
| **LOW** | 4 | #8, #10, #11, #12 → (corrected: #12 is MEDIUM) |

> [!CAUTION]
> Risk #1 (JNI daemon not stopped on failure) and #5 (session overwrite) are the most operationally dangerous — they silently leak native OS resources that survive process lifecycle and are very difficult to diagnose in production.
