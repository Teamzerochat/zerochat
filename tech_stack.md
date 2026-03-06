# ZeroChat — Tech Stack

## Overview

ZeroChat is a privacy-first Android messaging app that routes all communication through **two anonymous overlay networks** (Nym Mixnet & I2P), ensuring metadata-resistant, end-to-end encrypted chat with zero trust in intermediaries.

---

## Architecture

```
┌──────────────────────────────────────────────┐
│              Android App (Kotlin)             │
│  Jetpack Compose UI → ViewModels → Domain    │
│  Hilt DI · Room + SQLCipher · DataStore      │
├──────────────┬───────────────────────────────┤
│   I2P Layer  │     Nym Transport Layer       │
│  (libi2pd.so)│  (libuniffi_nym_transport.so) │
│   C++ / JNI  │       Rust / UniFFI           │
└──────┬───────┴───────────────┬───────────────┘
       │                       │
   I2P Network            Nym Mixnet
  (SAM bridge)        (Gateway → Mix nodes)
```

---

## Application Layer — Kotlin/Android

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Kotlin | 1.9.21 |
| Min SDK | Android 8.0 (API 26) | — |
| Target SDK | Android 14 (API 34) | — |
| Build System | Gradle (Kotlin DSL) | AGP 8.12.3 |
| UI Framework | Jetpack Compose + Material 3 | BOM 2024.02 |
| Navigation | Navigation Compose | 2.7.7 |
| DI | Dagger Hilt | 2.48 |
| Async | Kotlin Coroutines | 1.7.3 |
| Preferences | DataStore | 1.0.0 |
| JVM Target | 17 | — |

---

## Data Layer

| Component | Technology | Version |
|-----------|-----------|---------|
| Database | Room | 2.6.1 |
| Encryption at Rest | SQLCipher | 4.6.1 |
| Key Storage | Android Keystore (AES-GCM) | — |

---

## Cryptography

| Purpose | Library | Details |
|---------|---------|---------|
| E2E Message Encryption | Lazysodium (libsodium) | XSalsa20-Poly1305 |
| PAKE (Session Auth) | SPAKE2 (Rust) | Password-authenticated key exchange |
| Key Derivation | HKDF-SHA256 (Rust) | Deterministic rendezvous keys |
| Identity Keys | ed25519-dalek (Rust) | Deterministic from HKDF seed |
| Encryption Keys | x25519-dalek (Rust) | Diffie-Hellman key agreement |
| JNA Bridge | JNA | 5.14.0 |

---

## Transport — Nym Mixnet (Rust)

The Nym transport is a **Rust crate** cross-compiled to Android native libraries via the NDK.

| Component | Technology | Version |
|-----------|-----------|---------|
| Language | Rust | Edition 2021 |
| Nym SDK | nym-sdk / nym-crypto / nym-client-core / nym-sphinx | 1.20 |
| FFI Bindings | UniFFI | 0.25 |
| Async Runtime | Tokio (full) | 1.43 |
| HTTP Client | Reqwest (rustls-tls) | 0.11 |
| Serde | serde + serde_json | 1.0 |
| Error Handling | thiserror | 1.0 |
| Output | `cdylib` (.so) + `staticlib` | — |

### Cross-Compilation Targets

| ABI | Rust Target |
|-----|-------------|
| arm64-v8a | `aarch64-linux-android` |
| armeabi-v7a | `armv7-linux-androideabi` |
| x86_64 | `x86_64-linux-android` |

Toolchain: **Android NDK 28.2** with LLVM/Clang linkers configured in `.cargo/config.toml`.

### Local Patch

`nym-gateway-client` v1.20.4 is patched locally via `[patch.crates-io]` to replace `panic!()` in `packet_router.rs` with graceful error returns.

---

## Transport — I2P

| Component | Technology | Version |
|-----------|-----------|---------|
| I2P Daemon | i2pd (PurpleI2P) | 2.59.0 |
| Native Library | `libi2pd.so` | Prebuilt, loaded via JNI |
| Protocol | SAM v3 bridge | — |

---

## Project Structure

```
zerochat/
├── app/                          # Android application module
│   ├── src/main/java/.../
│   │   ├── ui/                   # Compose screens, viewmodels, theme
│   │   ├── domain/               # Business logic
│   │   │   ├── transport/        # Nym transport Kotlin wrappers
│   │   │   ├── i2p/              # I2P session management
│   │   │   ├── connection/       # TransportController state machine
│   │   │   ├── crypto/           # E2E encryption (Lazysodium)
│   │   │   ├── messaging/        # Message send/receive logic
│   │   │   ├── rendezvous/       # Two-slot rendezvous protocol
│   │   │   └── routing/          # Message routing decisions
│   │   ├── data/local/           # Room DB, DAOs, entities
│   │   └── di/                   # Hilt modules
│   └── src/main/jniLibs/         # Prebuilt .so files (i2pd + nym)
│       ├── arm64-v8a/
│       ├── armeabi-v7a/
│       └── x86_64/
├── nym-transport/                # Rust crate (Nym mixnet transport)
│   ├── src/lib.rs                # Core transport implementation
│   ├── Cargo.toml
│   ├── .cargo/config.toml        # Android NDK cross-compilation config
│   └── nym-gateway-client-patch/ # Patched upstream crate
└── docs/
```
