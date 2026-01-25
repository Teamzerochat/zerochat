# ZeroChat - Phase 1 Implementation Plan

**Threat Model**: State-level adversaries, device seizure, coercion  
**Design**: Anonymity over availability, unlinkability over convenience

---

## ⚠️ Phase 1 Limitations

> [!CAUTION]
> - **Both users must be online** - No offline messaging
> - No delivery guarantees
> - No persistent sessions
> - Connection failure preferred over metadata leakage

---

## Peer Bootstrap: Secret-Only Exchange

### Before (Removed)
```
User exchanges: Shared Secret + NYM Address A + NYM Address B
❌ 3 pieces of information, UX friction, operational risk
```

### After (Implemented)
```
User exchanges: ONLY Shared Secret
✓ 1 piece of information, minimal friction
✓ NYM addresses derived and handled by app
✓ Routing handles never shown to user
```

---

## Connection Flow (Secret-Derived Rendezvous)

```mermaid
sequenceDiagram
    participant A as User A
    participant B as User B
    
    Note over A,B: Out-of-band: Exchange ONLY shared secret
    
    A->>A: Enter secret
    B->>B: Enter SAME secret
    
    Note over A,B: Phase 1: Derive Rendezvous
    A->>A: rendezvous = HKDF(secret, epoch)
    B->>B: rendezvous = HKDF(secret, epoch)
    Note over A,B: Both derive SAME meeting point
    
    Note over A,B: Phase 2: Poll Rendezvous (via NYM)
    A->>A: Poll at constant 10s ± jitter
    B->>B: Poll at constant 10s ± jitter
    Note over A,B: Cover traffic hides contact moment
    
    Note over A,B: Phase 3: SPAKE2+ Handshake
    A->>B: Commitment (encrypted via NYM)
    B->>A: Response (encrypted via NYM)
    Note over A,B: Mutual auth - session key derived
    
    Note over A,B: Phase 4: Exchange Ephemeral Handles
    A->>B: Encrypt(A_ephemeral_routing_handle)
    B->>A: Encrypt(B_ephemeral_routing_handle)
    Note over A,B: Handles RAM-only, rotate per message
    
    Note over A,B: Phase 5: WebRTC via Handles
    A->>B: Direct messaging (relay-only)
    Note over A,B: Routing handles never shown to user
```

---

## Technology Stack

| Component | Choice |
|-----------|--------|
| **Language** | Kotlin 1.9+ |
| **UI** | Jetpack Compose |
| **Mixnet** | Self-hosted NYM |
| **Crypto** | Lazysodium (Argon2id, HKDF, SecretBox) |
| **DB** | SQLCipher (passphrase-derived key) |
| **WebRTC** | Relay-only mode |

---

## Security Properties

| Property | Implementation |
|----------|----------------|
| **No user-visible identifiers** | Addresses derived internally |
| **Ephemeral rendezvous** | TTL ≤ 5 min, epoch-rotated |
| **RAM-only routing handles** | Never persisted |
| **Constant-rate polling** | 10s ± 2s jitter |
| **Duress protection** | Alternative passphrase → wipe |
| **Failure = silence** | No retries, no distinguishable errors |

---

## Files Changed

| File | Change |
|------|--------|
| `ConnectScreen.kt` | Remove NYM address input |
| `RendezvousManager.kt` | NEW: Derive + poll rendezvous |
| `RoutingHandleManager.kt` | NEW: Ephemeral handle lifecycle |
| `KeyManager.kt` | Add HKDF derivation |
| `SECURITY_GUARDRAILS.md` | NEW: Audit checklist |

---

See [SECURITY_GUARDRAILS.md](./SECURITY_GUARDRAILS.md) for enforceable invariants.
