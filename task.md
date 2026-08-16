# Group Messaging Implementation Tasks

## Domain Layer (`com.zerochat.app.domain.group`)

- [x] `GroupSlotMatrix.kt` — 50-slot derivation, HKDF slot seeds, gateway mapping, random slot selection with jitter
- [x] `GroupCryptoManager.kt` — Multi-party Curve25519 DH, HKDF group key derivation, AES-256-GCM encrypt/decrypt, volatile zeroization
- [x] `GroupDiscoveryManager.kt` — Swarm announcement broadcast, inbox polling, nonce sorting, Continuous Swarm Guard
- [x] `GroupMessageQueue.kt` — Fan-out egress, vector clock sync, cover traffic, deduplication, causal ordering
- [x] `GroupManager.kt` — Top-level coordinator, state machine (IDLE→PROBING→CLAIMED→ANNOUNCING→SEALED→ACTIVE→TERMINATED), lifecycle orchestration

## UI Layer

- [x] `GroupSessionState.kt` — Sealed class for group UI states (embedded in `GroupManager.kt`)
- [x] `GroupViewModel.kt` — State management, session coordination, SAS verification display
- [x] `GroupSetupScreen.kt` — Code + group size entry, connection progress UI, SAS verification card, security alerts
- [x] `GroupChatScreen.kt` — Group chat UI with participant indicators, message bubbles, security alert banners

## Integration (Minimal Touchpoints)

- [x] `Navigation.kt` — Added GroupSetup and GroupChat routes with clean back-stack handling
- [x] `AppModule.kt` — Registered GroupManager as singleton in Hilt DI
- [x] `ConnectScreen.kt` — Added Group Session navigation trigger
