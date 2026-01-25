# ZeroChat - Project Handover Document

**Date:** January 26, 2026  
**Repository:** https://github.com/RedxHarshit/zerochat  
**Branch:** main  

---

## 1. Project Overview

**ZeroChat** is a journalist-grade anonymous messaging app designed to resist state-level adversaries, device seizure, and coercion.

### Threat Model
| Adversary | Mitigation |
|-----------|------------|
| Global surveillance | NYM mixnet (traffic analysis resistant) |
| Device seizure | Passphrase-only encryption, no Keystore |
| Coercion/duress | Duress passphrase → silent wipe to fresh state |
| Metadata analysis | No persistent state, ephemeral sessions |
| IP disclosure | Relay-only WebRTC (no P2P) |

### Design Philosophy
- **Anonymity over availability** - Connection fails rather than leak metadata
- **Unlinkability over convenience** - No persistent identifiers
- **Both users must be online** - No offline messaging (Phase 1)

---

## 2. Current Architecture

```
zerochat/
├── IMPLEMENTATION_PLAN.md      # Architecture documentation
├── SECURITY_GUARDRAILS.md      # Enforceable security invariants
├── TASKS.md                    # Phase-based task tracker
├── build.gradle.kts            # Root Gradle config
├── settings.gradle.kts         # Project settings
├── gradle.properties           # AndroidX, JVM config
└── app/
    ├── build.gradle.kts        # Dependencies: Lazysodium, SQLCipher, WebRTC
    └── src/main/
        ├── AndroidManifest.xml # No backup, minimal permissions
        └── java/com/zerochat/app/
            ├── ZeroChatApplication.kt  # Hilt Application
            ├── MainActivity.kt         # Compose entry point
            ├── di/
            │   └── AppModule.kt        # Hilt DI providers
            ├── domain/
            │   ├── crypto/
            │   │   └── KeyManager.kt   # ⭐ Core security
            │   ├── rendezvous/
            │   │   └── RendezvousManager.kt  # Secret-derived meeting
            │   └── routing/
            │       └── RoutingHandleManager.kt  # Ephemeral handles
            ├── data/local/
            │   ├── ZeroChatDatabase.kt   # SQLCipher Room DB
            │   ├── entity/               # Message, Session entities
            │   └── dao/                  # Room DAOs
            └── ui/
                ├── theme/                # Dark-only Material3
                ├── navigation/           # Compose NavGraph
                └── screens/
                    ├── unlock/           # Passphrase entry
                    ├── setup/            # First-time + duress setup
                    ├── connect/          # Secret-only peer connection
                    └── chat/             # Live messaging
```

---

## 3. Core Security Logic

### 3.1 Key Hierarchy
```
User Passphrase
       │
       ▼
   Argon2id (64MB, 3 iterations)
       │
       ▼
   KEK (Key Encryption Key) ──── Volatile, RAM-only
       │
       ▼
   Unwrap encrypted DB key
       │
       ▼
   SQLCipher Database (AES-256)
```

**File:** `KeyManager.kt`
- `deriveKEK()` - Argon2id key derivation
- `wrapDatabaseKey()` / `unwrapDatabaseKey()` - KEK operations
- `checkDuress()` - Duress passphrase detection
- `clearKEK()` - Secure zero-wipe

### 3.2 Duress Protection
When duress passphrase entered:
1. Silently wipes all DataStore preferences
2. Redirects to Setup screen (appears as fresh install)
3. No error shown - indistinguishable from "never set up"

**File:** `UnlockViewModel.kt` (lines 80-97)

### 3.3 Secret-Derived Rendezvous
```
Both users enter: SAME shared secret
       │
       ▼
   HKDF(secret, epoch)
       │
       ▼
   Rendezvous Point (derived, not exchanged)
       │
       ▼
   Both poll same point via NYM mixnet
       │
       ▼
   Exchange ephemeral routing handles (encrypted)
```

**File:** `RendezvousManager.kt`
- 5-minute epoch rotation
- Constant-rate polling (10s ± 2s jitter)
- One-time use (consumed after handshake)

**File:** `RoutingHandleManager.kt`
- RAM-only ephemeral handles
- Rotate per message
- Secure wipe on exit/lock

---

## 4. Security Guardrails

All implementers MUST follow `SECURITY_GUARDRAILS.md`:

| Category | Key Rules |
|----------|-----------|
| **Rendezvous** | TTL ≤ 5 min, no reuse after handshake |
| **Polling** | Constant 10s ± 2s, no bursts |
| **Routing handles** | RAM-only, session-scoped, secure wipe |
| **UI exposure** | NEVER show addresses/handles/metadata |
| **Failures** | Silent fail, same error for auth fail & timeout |

---

## 5. Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Lazysodium | 5.1.0 | Argon2id, HKDF, SecretBox |
| SQLCipher | 4.6.1 | Encrypted database |
| Room | 2.6.1 | Database abstraction |
| Hilt | 2.48 | Dependency injection |
| Compose BOM | 2024.02.00 | UI framework |
| WebRTC | 1.0.7 | Relay-only video/audio |
| DataStore | 1.0.0 | Encrypted preferences |

---

## 6. What's NOT Implemented Yet

### Phase 2: NYM Mixnet Integration
- [ ] Rust toolchain for Android
- [ ] NYM UniFFI bindings
- [ ] Hook `RendezvousManager.pollRendezvous()` to actual NYM client
- [ ] Self-hosted gateway config

### Phase 3: WebRTC
- [ ] Relay-only mode (block host/srflx candidates)
- [ ] Oblivious TURN proxy
- [ ] SDP sanitization

### Phase 4: Encryption
- [ ] SPAKE2+ for authentication (replace placeholder)
- [ ] Session ratchet (ephemeral, not persisted)

See `TASKS.md` for full backlog.

---

## 7. How to Run

```bash
# Clone
git clone https://github.com/RedxHarshit/zerochat.git

# Open in Android Studio
# File → Open → select zerochat folder

# Sync Gradle
# File → Sync Project with Gradle Files

# Run
# Click ▶️ or Shift+F10
```

---

## 8. Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| No Android Keystore | Not trusted against device seizure |
| Argon2id 64MB | Balance: strong KDF, mobile-feasible |
| Secret-only connect | No user-visible identifiers |
| Relay-only WebRTC | Prevents IP disclosure |
| Ephemeral sessions | No long-term linkability |
| Dark theme only | Privacy-focused aesthetic |

---

## 9. API/Config Notes

- **No external APIs** required for Phase 1
- **Self-hosted infrastructure** planned for Phase 2:
  - 3+ NYM mix nodes
  - 1 NYM gateway
  - Oblivious TURN relay

---

## 10. Contact

For questions about this codebase, refer to:
1. `IMPLEMENTATION_PLAN.md` - Architecture details
2. `SECURITY_GUARDRAILS.md` - Security requirements
3. `TASKS.md` - Current progress and backlog
