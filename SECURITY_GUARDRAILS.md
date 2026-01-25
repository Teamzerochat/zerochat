# ZeroChat Security Guardrails

**Purpose:** Enforceable invariants to prevent anonymity regression  
**Scope:** Secret-derived rendezvous protocol implementation

---

## 1. Rendezvous Lifetime

| ID | Rule | Rationale |
|----|------|-----------|
| RV-01 | Rendezvous TTL ≤ 5 minutes from first poll | Limits observation window |
| RV-02 | After TTL: Stop polling, derive NEW rendezvous with new epoch | Prevents long-term correlation |
| RV-03 | Rendezvous = `HKDF(secret ‖ epoch)` where epoch = ⌊time / 300⌋ | Deterministic meeting point |
| RV-04 | No rendezvous reuse after successful handshake | One-time meeting point |
| RV-05 | On expiry: "Peer not online", no auto-retry | Prevents traffic signature |

---

## 2. Polling & Traffic Shape

| ID | Rule | Rationale |
|----|------|-----------|
| PL-01 | Polling interval: constant 10s ± jitter(2s) | Defeats timing analysis |
| PL-02 | Cover traffic: Continue polling until handshake complete | Hides contact moment |
| PL-03 | No burst polling on app foreground/network change | Prevents user-triggered signatures |
| PL-04 | Poll payload: padded to fixed size | Prevents size correlation |
| PL-05 | Fresh NYM SURB per request | Prevents reply correlation |

---

## 3. Rendezvous Reuse Prevention

| ID | Rule | Rationale |
|----|------|-----------|
| RU-01 | Derivation MUST include epoch/nonce | Same secret → different rendezvous per session |
| RU-02 | Completed rendezvous marked "consumed" in memory | Prevents handshake replay |
| RU-03 | Same secret + new session = new epoch = new rendezvous | No long-term identifiers |
| RU-04 | Epoch rotation: 5 minutes OR new session (whichever shorter) | Bounds reuse window |

---

## 4. Routing Handle Storage

| ID | Rule | Rationale |
|----|------|-----------|
| RH-01 | Routing handles: RAM-only, NEVER disk/DB/logs | Persistence = identity |
| RH-02 | Handle lifetime: Single session only | No cross-session linkability |
| RH-03 | App background > 30s: WIPE handles | Seizure protection |
| RH-04 | App lock: WIPE handles immediately | Coercion resistance |
| RH-05 | New handle in each outgoing message | Message unlinkability |
| RH-06 | Secure wipe: Zero-overwrite before deallocation | Memory forensics resistance |

---

## 5. UI & UX Exposure

| ID | Rule | Rationale |
|----|------|-----------|
| UI-01 | NEVER display: Rendezvous, NYM address, routing handle, SURB | Visible = identity |
| UI-02 | NEVER log: Routing metadata to logcat/crashlytics/disk | Logs persist |
| UI-03 | Permitted states: "Connecting...", "Connected", "Disconnected", "Peer offline" | Minimal, non-identifying |
| UI-04 | NEVER show: Timing, retry count, hop count, gateway name | Operational security |
| UI-05 | Errors: Generic only ("Connection failed") | Prevents fingerprinting |

---

## 6. Failure Semantics

| ID | Rule | Rationale |
|----|------|-----------|
| FL-01 | Rendezvous timeout: Silent fail, wipe state, return idle | No retry storm |
| FL-02 | Epoch desync: Fail closed, require user re-initiation | Prevents probing |
| FL-03 | Network change mid-handshake: Abort, wipe, restart | No partial state leakage |
| FL-04 | App kill during handshake: All state lost | Crash = clean slate |
| FL-05 | Auth failure (wrong secret): Same as "peer offline" | Prevents oracle attacks |
| FL-06 | Never auto-retry: User must explicitly re-initiate | Prevents automated probing |

---

## Audit Checklist

```
□ RV-01 to RV-05: Rendezvous lifetime enforced
□ PL-01 to PL-05: Constant-rate polling, no bursts
□ RU-01 to RU-04: No rendezvous reuse across epochs
□ RH-01 to RH-06: RAM-only handles, session-scoped, wiped on exit
□ UI-01 to UI-05: No routing metadata in UI
□ FL-01 to FL-06: Failures prefer silence over leakage
```

---

**Guiding Principle:**
- Nothing long-lived becomes identity ✓
- Nothing user-visible becomes identity ✓
- Failure = silence ✓
