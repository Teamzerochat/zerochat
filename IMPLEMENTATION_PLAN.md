# Paper Claims Audit & Implementation Plan

## Background

Our handshake fixes (deterministic role assignment, removed address advertisement, unpadding fix) were correct — they fixed real bugs. However, the paper makes several architectural claims that the current codebase does **not** implement end-to-end. The Rust core has building blocks ([obfs4_shim.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs), [tli.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs), [session_store.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/session_store.rs), [mem_pin.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/mem_pin.rs)) but they are **not wired** into the active send/receive path or exposed to Kotlin.

---

## Defied Claims Summary

| # | Paper Claim | Section | Status | Implementable? |
|---|---|---|---|---|
| 1 | obfs4 wraps **all** outbound Sphinx frames | §6 | ❌ [obfs4_shim.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs) exists but [send_message](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#936-975)/[poll_rendezvous](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#537-593) never call [encode_frame](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#43-71)/[decode_frame](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#72-100) | ✅ Yes — ~20 lines Rust |
| 2 | TLI lifecycle automaton drives phase transitions | §5.3 | ❌ [tli.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs) has the state machine but no FFI export; Kotlin never calls it | ✅ Yes — UDL + Kotlin wiring |
| 3 | `TliSessionState` with `ZeroizeOnDrop` holds all session material | §5.2 | ⚠️ [session_store.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/session_store.rs) + [PinnedSecret](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/mem_pin.rs#15-19) handle session keys correctly, but the paper's `TliSessionState` struct (with `obfs4_state`, `i2p_dest_privkey`, `session_nonce`) doesn't exist as a unified struct | ✅ Yes — new struct + wiring |
| 4 | Churn oracle at 2 Hz with `Sfallback` transition | §7 | ❌ [tli.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs) has `ChurnSignal` enum but no background task; Kotlin has zero churn references | ✅ Yes — Tokio task + FFI callback |
| 5 | Adaptive bandwidth: Nym throttled to `λmin` in `Shard` | §8 | ❌ No cover traffic scheduling, no `λmin` calculation | ⚠️ Partial — cover traffic control requires Nym SDK knobs we may not have |
| 6 | Stochastic handover with `Δ ~ U[5,40]s` cross-fade | §9 | ❌ No cross-fade, no stochastic delay | ✅ Yes — Kotlin coroutine |
| 7 | Thermal rate limiting at 82°C | §10, §11.2 | ❌ No thermal monitoring | ✅ Yes — Android `ThermalManager` API |

---

## Detailed Analysis

### Claim 1: obfs4 Integration (§6)

**Paper says:** *"We wrap all outbound Sphinx frames in the Rust layer before they reach the Android socket."*

**Reality:** [obfs4_shim.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs) has working [encode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#43-71)/[decode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#72-100) + [session_opener_jitter()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#102-108), complete with tests. But [lib.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs) [send_message()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#936-975) (line 937) and [publish_at_rendezvous()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#594-649) (line 599) never call these. Messages go out as raw Sphinx frames.

**Fix:** Add [Obfs4State](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#20-27) to [ClientState](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#159-173), call [encode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#43-71) before `send_plain_message()` and [decode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#72-100) after `wait_for_messages()`.

---

### Claim 2: TLI Lifecycle Automaton (§5.3)

**Paper says:** *"M = (S, Σ, δ, s₀, F) with states Sinit, Srend, Shard, Sfall, Szero"* with formally validated transitions.

**Reality:** [tli.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs) implements [TliLifecycle](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs#80-88) with all 5 phases and valid transition guards. But it's not in the UDL, not exposed via FFI, and Kotlin never calls it. The Kotlin [ConnectionManager](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt#33-409) manages its own informal state without any lifecycle enforcement.

**Fix:** Add [TliLifecycle](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs#80-88) to [ClientState](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#159-173), expose `tli_transition()` and `tli_current_phase()` via UDL, call from Kotlin at each connection stage.

---

### Claim 3: TliSessionState with ZeroizeOnDrop (§5.2)

**Paper says:** Listing 1.1 shows `TliSessionState` holding `spake_intermediate`, `i2p_dest_privkey`, `session_nonce`, `obfs4_state` — all `ZeroizeOnDrop`.

**Reality:** [session_store.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/session_store.rs) uses [PinnedSecret](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/mem_pin.rs#15-19) (mlock'd + zeroize) for session keys — this is **correct and working**. But the unified `TliSessionState` struct from the paper doesn't exist. The `obfs4_state` and `i2p_dest_privkey` are not co-located.

**Fix:** Create `TliSessionState` as shown in the paper, co-locating all session material. Wire `terminate()` to [session_destroy()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/session_store.rs#212-221).

---

### Claim 4: Churn Oracle at 2 Hz (§7)

**Paper says:** *"The churn oracle runs as a background Tokio task in the Rust core, sampling at 2 Hz."* Thresholds: >3 ACK failures, RTT > 5× median, tunnel failure > 40%.

**Reality:** [tli.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs) has [heartbeat_fail()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs#134-152)/[heartbeat_ok()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs#129-133) and `ChurnSignal` enum, but no Tokio background task, no actual network health sampling, and no FFI callback to Kotlin.

**Fix:** Add a Tokio `spawn` that polls gateway health every 500ms, fires `ChurnSignal` on threshold breach, and notifies Kotlin via a callback or poll-based status check.

---

### Claim 5: Adaptive Bandwidth Scheduling (§8)

**Paper says:** In `Shard`, Nym cover traffic throttled to `λmin = ln|St| / Δanon ≈ 0.12 s⁻¹`.

**Reality:** No cover traffic scheduling exists. The Nym SDK manages its own cover traffic internally; we don't control `λ`.

**Fix:** The Nym SDK's `MixnetClientBuilder` may expose cover traffic configuration. If it does, we set `loop_cover_traffic_average_delay` based on the phase. If the SDK doesn't expose this, we can still implement **message-level** rate limiting on the Kotlin side (throttle our own sends, not the SDK's cover traffic).

> [!WARNING]
> This claim depends on Nym SDK API surface. Full implementation may require SDK source modification or may only be approximated.

---

### Claim 6: Stochastic Handover with Cross-Fade (§9)

**Paper says:** `Tmig = Tstable + Δ, Δ ~ U[5,40]s`. Cross-fade: [P(Ti2p) = min(1, (t - Tmig) / τ)](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/rendezvous/RendezvousManager.kt#493-494) with `τ = 10s`.

**Reality:** No handover logic exists. Once I2P is ready, there's no stochastic delay and no traffic splitting.

**Fix:** After I2P reports stable (`δc > 0.95`), draw `Δ ~ U[5,40]` seconds. During the 10s cross-fade window, probabilistically route each message to I2P vs Nym. After cross-fade, Nym carries only cover traffic.

---

### Claim 7: Thermal Rate Limiting at 82°C (§10, §11.2)

**Paper says:** *"thermal rate limiting at 82°C prevents session collapse on budget devices"*. Extends sustainable session from ~18min to ~34min.

**Reality:** No thermal monitoring exists in the codebase.

**Fix:** Use Android's `PowerManager.getThermalHeadroom()` (API 30+) or `ThermalStatusListener` (API 29+). When thermal status exceeds threshold, pause I2P reseed and throttle Sphinx send rate.

---

## Proposed Changes

### Tier 1: Critical (Paper's core security claims)

---

#### Rust Core: obfs4 Wire Integration

##### [MODIFY] [lib.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs)

- Add `obfs4_state: Option<Obfs4State>` to [ClientState](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#159-173)
- Initialize `Obfs4State::new()` on [connect()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#718-793)
- In [send_message()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#936-975) (L937): call `obfs4_state.encode_frame(&padded_payload)` before `send_plain_message`
- In [publish_at_rendezvous()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#594-649) (L599): call [encode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#43-71) before `send_plain_message`
- In [poll_rendezvous()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#537-593) (L542): call [decode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#72-100) on `msg.message` after `wait_for_messages`
- In [receive_message()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#978-1043) (L978): call [decode_frame()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#72-100) on received payload
- Apply [session_opener_jitter()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/obfs4_shim.rs#102-108) delay on first message per session

---

#### Rust Core: TLI Lifecycle + FFI

##### [MODIFY] [lib.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs)

- Add `tli: TliLifecycle` to [ClientState](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#159-173)
- Initialize `TliLifecycle::new()` in `ClientState::default()`
- Add FFI-exported functions: `tli_transition(phase: u8) -> Result<u8>`, `tli_current_phase() -> u8`

##### [MODIFY] [nym_transport.udl](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/nym_transport.udl)

- Add `tli_transition(u8 phase)` and `tli_current_phase() -> u8` to UDL namespace

##### [MODIFY] [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt)

- Call `tliTransition(RENDEZVOUS)` at handshake start
- Call `tliTransition(HARDENED)` after I2P stabilizes
- Call `tliTransition(ZEROIZED)` on session end / abort

---

#### Rust Core: TliSessionState (Paper Listing 1.1)

##### [NEW] Add a `TliSessionState` struct to [tli.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/tli.rs) or a new file

- Co-locate `spake_intermediate`, `i2p_dest_privkey`, `session_nonce`, `obfs4_state`
- Derive `ZeroizeOnDrop`
- Wire `terminate()` to call [session_destroy()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/session_store.rs#212-221) + zeroize obfs4 state
- Add `tli_terminate_session(handle: u64)` FFI export

---

### Tier 2: Important (Resilience claims)

---

#### Rust Core: Churn Oracle Background Task

##### [MODIFY] [lib.rs](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs)

- Spawn a Tokio task on [connect()](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/nym-transport/src/lib.rs#718-793) that runs at 2 Hz
- Check gateway health (ACK failures, RTT) using the Nym client's internal stats
- On churn detection, call `tli.transition(Fallback)` and store churn status
- Add `tli_check_churn() -> u8` FFI function (returns 0=OK, 1=CHURN_NYM, 2=CHURN_I2P)

##### [MODIFY] [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt)

- After entering `Shard`, periodically call `tliCheckChurn()`
- On churn: buffer messages, attempt exponential backoff reconnection (2s base)
- On recovery within 90s: replay buffer, transition back to `Srend`
- On timeout > 90s: terminate session

---

#### Kotlin: Stochastic Handover + Cross-Fade

##### [MODIFY] [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt)

- After I2P reports `δc > 0.95`: draw `Δ ~ U[5,40]` seconds and delay
- During 10s cross-fade window: route message with probability [P(I2P) = min(1, elapsed/10)](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/rendezvous/RendezvousManager.kt#493-494)
- After cross-fade completes: transition to `Shard`, all messages via I2P

---

### Tier 3: Nice-to-have (Performance claims)

---

#### Kotlin: Thermal Rate Limiting

##### [NEW] [ThermalMonitor.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ThermalMonitor.kt)

- Use `PowerManager.addThermalStatusListener()` (API 29+)
- On `THERMAL_STATUS_SEVERE` or manual 82°C threshold: emit `ThrottleEvent`
- ConnectionManager pauses I2P reseed and throttles Sphinx sends

#### Kotlin: Adaptive Bandwidth (Best-Effort)

##### [MODIFY] [ConnectionManager.kt](file:///c:/Users/harsh/OneDrive/Documents/projects/zerochat/app/src/main/java/com/zerochat/app/domain/connection/ConnectionManager.kt)

- In `Shard`: rate-limit outbound Nym messages to `λmin ≈ 0.12 s⁻¹`
- This is application-level only; full cover-traffic control requires Nym SDK cooperation

---

## Verification Plan

### Automated Tests
- `cargo test` in `nym-transport/` — verify obfs4 roundtrip, TLI state transitions, churn detection
- `./gradlew assembleDebug` — verify Kotlin compilation
- Unit test: `TliPhase` transitions match paper's Table 2

### Manual Verification
- Run on two devices → confirm obfs4 encoding doesn't break handshake
- Verify `TLI lifecycle: Init → Rendezvous` in logcat
- Verify `tli_terminate_session` zeroizes correctly (entropy scanner)
