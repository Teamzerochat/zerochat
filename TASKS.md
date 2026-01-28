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
- [/] Integrate with actual NYM mixnet (pending Phase 2)

## Phase 2: Self-Hosted NYM Integration  
- [x] Set up Rust toolchain for Android (see docs/RUST_SETUP.md)
- [x] Create NYM UniFFI Rust bindings (nym-transport/)
- [x] Build native libs (cargo ndk) - arm64-v8a, armeabi-v7a, x86_64
- [x] Transport abstraction layer (NymTransport interface)
- [x] Hook rendezvous polling to NYM

## Phase 3: Relay-Only WebRTC
- [x] Configure WebRTC in relay-only mode
- [x] Implement ICE candidate filtering (relay only)
- [x] SDP sanitization
- [ ] Oblivious TURN proxy integration (coturn setup)

## Phase 4: Session-Scoped Encryption
- [ ] Implement SPAKE2+ for authentication
- [ ] Build ephemeral session ratchet
- [ ] Memory wipe for session keys

## Phase 5: Anti-Metadata Design
- [ ] Remove: read receipts, typing indicators, online status
- [ ] Ephemeral session-only data model

## Phase 6: UI Refinements
- [x] Duress passphrase setup flow
- [x] Secret-only connect screen
- [ ] Session ephemeral indicators
- [ ] Connection failure dialogs

---

**Note:** Dead-drop offline messaging deferred to Phase 2
