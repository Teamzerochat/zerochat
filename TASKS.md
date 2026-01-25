# ZeroChat - Phase 1 Implementation Tasks

**Focus:** Live communication only (no offline/dead-drop messaging yet)

## Phase 1: Core Security Foundation
- [x] Implement Argon2id KDF (512MB memory, 3 iterations)
- [x] Create passphrase-based key hierarchy (Pass → KEK → DB key)
- [x] Add duress passphrase detection + irreversible key wipe
- [x] Implement volatile KEK (RAM-only, secure wipe on exit)
- [x] SQLCipher integration with wrapped key

## Phase 2: Self-Hosted NYM Integration  
- [ ] Set up Rust toolchain for Android
- [ ] Create NYM UniFFI Rust bindings
- [ ] NYM client wrapper (self-hosted gateway)
- [ ] Transport abstraction layer (NYM/Tor fallback)
- [ ] Configuration for custom mixnode topology

## Phase 3: Relay-Only WebRTC
- [ ] Configure WebRTC in relay-only mode (iceTransportsType: RELAY)
- [ ] Implement ICE candidate filtering
  - [ ] Block host candidates
  - [ ] Block server-reflexive candidates
  - [ ] Allow relay candidates only
- [ ] Add candidate filtering validation on ICE gathering complete
- [ ] Oblivious TURN proxy integration
- [ ] Connection refusal when secure relay unavailable
- [ ] SDP sanitization (remove IP information)

## Phase 4: Session-Scoped Encryption (Live Only)
- [ ] Implement X3DH for session initialization
- [ ] Build ephemeral session ratchet (NOT persistent)
- [ ] Ratchet state destruction on session end
- [ ] Ensure NO ratchet state written to disk
- [ ] Memory wipe for session keys

## Phase 5: Anti-Metadata Design
- [ ] Remove: read receipts
- [ ] Remove: typing indicators
- [ ] Remove: last seen / online status
- [ ] Remove: persistent contact lists
- [ ] Ephemeral session-only data model (live messages only)

## Phase 6: UI - Live Chat Only
- [ ] Prominent "both users must be online" warning
- [ ] Explicit limitation disclosures
- [ ] Duress passphrase setup flow
- [ ] Connection failure explanation dialogs
- [ ] Session ephemeral nature indicators
- [ ] "No offline messaging" notice

## Phase 7: Self-Hosted Infrastructure
- [ ] Deploy 3+ self-hosted NYM mix nodes
- [ ] Deploy self-hosted NYM gateway
- [ ] Set up oblivious TURN relay with proxy
- [ ] Document deployment procedures

## Phase 8: Security Audit & Validation
- [ ] Threat model validation against state-level adversaries
- [ ] Metadata leak testing (Wireshark, network analysis)
- [ ] Coercion resistance verification (duress passphrase)
- [ ] ICE candidate filtering validation
- [ ] Oblivious relay metadata separation test
- [ ] Passphrase-only unlock verification
- [ ] Session ephemeral state validation

---

**Note:** Dead-drop offline messaging deferred to Phase 2
