# ZeroChat Diagnostics Report

## Current Status
**Success:** The previous fix for the I2P SAM session creation was 100% successful. The logs clearly show the `SESSION CLOSE` protocol violation is gone, and the app successfully establishes the SAM session on the very first try:
```
I2PRouterService: ✓ SAM bridge is ready on *********:7656
SamClient: HELLO reply: HELLO REPLY RESULT=OK VERSION=3.1
I2PRouterService: Router tunnel-ready after 1 checks (routers=907, floodfills=484, has tunnel activity)
SamClient: ✓ SAM session created: zc-3251392c-0000
```
The Mixnet Handshake also correctly completes (`🔄 SPAKE2+ handshake complete`).

---

## Primary Issue: App Crash - Flow Invariant Violation

Instantly after dropping into the established chat window, the app crashes with a `FATAL EXCEPTION`.

```
java.lang.IllegalStateException: Flow invariant is violated:
    Emission from another coroutine is detected.
    Child of StandaloneCoroutine{Active}@c9794b4, expected child of ProducerCoroutine{Active}@31158dd.
    FlowCollector is not thread-safe and concurrent emissions are prohibited.
    To mitigate this restriction please use 'channelFlow' builder instead of 'flow'
```

### Root Cause Analysis

1. **Network Churn:** Shortly after connecting to the NYM gateway, the connection experiences transient dropouts (`nym_gateway_client::packet_router: Failed to send mixnet message (receiver gone)`). This is a normal attribute of mixnet anonymity networks and happens randomly.
2. **Churn Monitor Triggered:** The `ConnectionManager` correctly detects this network stall and counts consecutive failures:
   ```
   ConnectionManager: Churn detection: 1/3
   ConnectionManager: Churn detection: 2/3
   ConnectionManager: Churn detection: 3/3
   ConnectionManager: Churn threshold reached - attempting recovery
   ```
3. **The Crash (Kotlin Coroutines):** When the failure threshold is reached (line 460 in `ConnectionManager.kt`), a background coroutine named `churnMonitorJob` attempts to notify the UI by emitting a fallback state:
   ```kotlin
   collector.emit(ConnectionState.Fallback("Churn detected"))
   ```
   However, `ConnectionManager.connect()` exposes its states using a standard `flow { ... }` builder. Kotlin strictly enforces that a `FlowCollector` is **not thread-safe**. You are not allowed to call `.emit()` from a newly launched child coroutine (`scope.launch { ... }`). Doing so instantly crashes the application to protect against race conditions.

### Proposed Fix Approach

The official Kotlin coroutine mitigation for this problem is exactly what the crash log suggests:
1. Refactor the `connect()` function in `ConnectionManager.kt` to use a `channelFlow { ... }` builder instead of the standard `flow { ... }` builder.
2. Replace all instances of `collector.emit(state)` with `send(state)`.
3. `channelFlow` provides a thread-safe `SendChannel` that allows concurrent emissions from any `launch` blocks (like the background `churnMonitorJob`).

---

## Applied Fix

**File:** [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt) — `connect()` function

### Change 1: Import — `flow` → `channelFlow`
```diff
-import kotlinx.coroutines.flow.flow
+import kotlinx.coroutines.flow.channelFlow
```

### Change 2: Flow builder — `flow { }` → `channelFlow { }`
```diff
-    ): Flow<ConnectionState> = flow {
-        val collector = this
+    ): Flow<ConnectionState> = channelFlow {
```
The `val collector = this` alias is removed because `channelFlow` provides a `ProducerScope` (with `send()`) instead of a `FlowCollector` (with `emit()`).

### Change 3: All emissions — `collector.emit()` → `send()`
Every call to `collector.emit(state)` throughout the function was replaced with `send(state)`. This includes:
- All `ConnectionState.Failed(...)` error paths (~20 locations)
- State transitions: `ConnectingToNym`, `DerivedRendezvous`, `Handshaking`, `PollingRendezvous`, `ExchangingHandles`, `EstablishingI2P`, `Connected`, `Disconnected`
- The **crash site**: `collector.emit(ConnectionState.Fallback("Churn detected"))` inside `churnMonitorJob` (line ~467) → `send(ConnectionState.Fallback("Churn detected"))`

### Change 4: All early returns — `return@flow` → `return@channelFlow`
All `return@flow` exit points were updated to `return@channelFlow` to match the new builder label.

### Why This Works
- `flow { }` enforces **sequential, single-coroutine** emission. Any `emit()` from a child `launch { }` block crashes immediately.
- `channelFlow { }` backs the emissions with a **`Channel`**, which is thread-safe and allows concurrent `send()` from any coroutine within the `ProducerScope`.
- The `churnMonitorJob` (launched via `scope.launch { }`) can now safely call `send(ConnectionState.Fallback(...))` without violating Kotlin's flow invariant.

**Build:** `assembleDebug` passed ✅
