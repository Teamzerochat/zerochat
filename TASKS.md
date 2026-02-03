# ZeroChat - Phase 1 Implementation Tasks

**Focus:** Live communication only (no offline/dead-drop messaging yet)

## Phase 1: Core Security Foundation
- [x] Implement Argon2id KDF (64MB memory, 3 iterations)
- [x] Create passphrase-based key hierarchy (Pass → KEK → DB key)
- [x] Add duress passphrase detection + irreversible key wipe
- [x] Implement volatile KEK (RAM-only, secure wipe on exit)
- [x] SQLCipher integration with wrapped key

## Phase 1.5: Secret-Derived Rendezvous
- [x] Remove NYM address input from UI (secret-only)
- [x] Implement RendezvousManager (HKDF derivation)
- [x] Implement epoch-based rotation (5-min TTL)
- [x] Implement constant-rate polling (10s ± 2s jitter)
- [x] Implement RoutingHandleManager (ephemeral, RAM-only)
- [x] Document security guardrails (SECURITY_GUARDRAILS.md)
- [x] Integrate with actual NYM mixnet (completed in Phase 2)

## Phase 2: NYM Public Mixnet Integration  
- [x] Set up Rust toolchain for Android (see docs/RUST_SETUP.md)
- [x] Create NYM UniFFI Rust bindings (nym-transport/)
- [x] Build native libs (cargo ndk) - arm64-v8a, armeabi-v7a, x86_64
- [x] Transport abstraction layer (NymTransport interface)
- [x] Hook rendezvous polling to NYM
- [x] Real NYM SDK 1.20.4 with public mainnet (ephemeral keys)
- [x] Full MixnetClient: connect, send, receive, rendezvous
- [x] UniFFI rebuild completed

## Phase 3: Relay-Only WebRTC
- [x] Configure WebRTC in relay-only mode
- [x] Implement ICE candidate filtering (relay only)
- [x] SDP sanitization
- [ ] Oblivious TURN proxy integration (coturn setup)

## Phase 4: Session-Scoped Encryption
- [x] Implement SPAKE2+ for authentication
  - [x] **FIXED:** Handle-based state management in Rust (no serialization issues)
  - [x] Create `HandshakeManager.kt` with handle ID support
  - [x] Implement commitment/response message exchange
  - [x] All 4 Rust tests passing
- [x] Build ephemeral session ratchet
  - [x] HKDF key derivation from SPAKE2+ shared secret
  - [x] Session key rotation logic
- [x] Memory wipe for session keys
  - [x] Secure wiping in `KeyManager.kt`
  - [x] Cleanup methods in `HandshakeManager.kt`

## Phase 5: Anti-Metadata Design
- [x] Remove: read receipts, typing indicators, online status
  - [x] No metadata features in UI
  - [x] Fixed-size messages (1024 bytes) for traffic analysis resistance
- [x] Ephemeral session-only data model
  - [x] RAM-only routing handles
  - [x] Handle rotation per message
  - [x] Wipe on background >30s (RH-03)
  - [x] Wipe on screen lock (RH-04)

## Phase 6: UI Components
- [x] Duress passphrase setup flow
- [x] Secret-only connect screen
  - [x] `ConnectScreen.kt` with shared secret input
  - [x] Connection status indicators
  - [x] Initiator/responder buttons
- [x] Chat interface
  - [x] `ChatScreen.kt` with message list
  - [x] Message bubbles, timestamps, status indicators
  - [x] Material Design 3 theming
- [x] Session ephemeral indicators
- [ ] Connection failure dialogs (future)
- [ ] QR code generation/scanning (future)

---

---

## Recent Achievements (2026-02-02)

### SPAKE2+ Handle-Based Implementation
**Problem:** `spake2` crate doesn't support state serialization

**Solution:**
- Global `HashMap<u64, Spake2State>` in Rust with thread-safe `Mutex`
- `spake2_start_initiator()` returns `(handle_id, message)`
- `spake2_finish_initiator(handle_id, peer_msg)` completes handshake
- Cleanup functions: `spake2_cleanup_state()`, `spake2_active_count()`

**Result:** ✅ All 4 Rust tests passing, clean Kotlin integration

### Message Protocol & Encryption
- Fixed-size messages (1024 bytes) with padding
- End-to-end encryption with libsodium
- Replay protection (1000-message sliding window)
- Message queue with retry logic (max 3 retries, 2s delay)

### Security & Lifecycle
- `AppLifecycleObserver.kt` - Background/lock detection
- Screen lock detection in `MainActivity.kt`
- Secure data wiping for handles and keys

---

## Progress Summary

**Completed:** Phases 1-5 (~75%)
**Remaining:** Testing & Android build

**Next Steps:**
1. Build Rust native libraries (`cargo ndk build --release`)
2. Test on device/emulator
3. Complete remaining Phase 6-7 tasks

---

**Note:** Dead-drop offline messaging deferred to Phase 2
