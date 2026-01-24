# ZeroChat - Journalist-Grade Anonymous Messaging (Phase 1)

**Threat Model**: State-level adversaries, device seizure, coercion  
**User Profile**: Journalists, activists, whistleblowers  
**Design Philosophy**: Anonymity over availability, unlinkability over convenience

---

## ⚠️ Critical Security Posture (Phase 1)

> [!CAUTION]
> **This is NOT a general-purpose messaging app.**
> - **Both users must be online** - No offline messaging in Phase 1
> - No delivery guarantees
> - No persistent sessions
> - No direct P2P connections (relay-only)
> - Connection failure preferred over metadata leakage
> - Designed for short-lived, high-risk communication only

---

## Threat Model

| Adversary Capability | Mitigation |
|---------------------|------------|
| **Global passive surveillance** | NYM mixnet prevents traffic analysis |
| **ISP/network monitoring** | Oblivious relay prevents IP correlation |
| **Device seizure** | Passphrase-based encryption, Keystore NOT trusted |
| **Coercion/duress** | Duress passphrase triggers key destruction |
| **Metadata analysis** | No persistent state, no delivery receipts |
| **Long-term correlation** | Session-scoped encryption only, no ratchet persistence |
| **Compromised TURN** | Strict metadata separation, connection fails if violated |
| **IP address disclosure** | WebRTC relay-only mode, no direct P2P |

---

## Architecture Decisions

| Component | Choice | Security Rationale |
|-----------|--------|-------------------|
| **Mixnet** | Self-hosted NYM (open source) | No dependency on paid services, full control |
| **WebRTC Mode** | **RELAY-ONLY** (no P2P) | Prevents IP disclosure to peers |
| **ICE Candidates** | Relay candidates ONLY | Host/srflx candidates blocked |
| **Relay** | Oblivious TURN or FAIL | Metadata separation enforced, no IP leakage |
| **Encryption** | Session-scoped ratchet | No persistent state = no long-term metadata |
| **Key Storage** | Passphrase → Argon2id → KEK | Android Keystore NOT trusted as anchor |
| **Offline** | **NOT IMPLEMENTED** (Phase 2) | Live communication only for now |
| **Duress** | Alternative passphrase → key wipe | Coercion resistance |

---

## Technology Stack

### Core Platform
- **Language**: Kotlin 1.9+
- **Build**: Gradle 8.x with Kotlin DSL
- **Min SDK**: API 26 (Android 8.0) 
- **Architecture**: Clean Architecture + MVVM
- **DI**: Hilt (Dagger-based)
- **Concurrency**: Kotlin Coroutines + Flow

### UI Layer
- **Framework**: Jetpack Compose
- **Material**: Material 3 (dark mode only)
- **Navigation**: Compose Navigation
- **Design**: Privacy-focused (deep purples, dark grays)

### Anonymity & Transport
- **Mixnet**: Nym Rust SDK (self-hosted)
  - Compiled to Android via UniFFI
  - Custom mixnode topology support
- **Fallback**: Tor integration (if Nym unavailable)
- **WebRTC**: Google libwebrtc (relay-only mode)
  - ICE transport policy: RELAY
  - Host/srflx candidates blocked

### Cryptography
- **Library**: Lazysodium-android (libsodium wrapper)
- **KDF**: Argon2id 
  - Memory: 512MB
  - Iterations: 3
- **E2E**: Session-scoped ratchet (NOT persistent)
  - X3DH for initial key agreement
  - Symmetric ratchet within session only
- **Key Storage**: Passphrase-based (NOT Android Keystore)

### Storage
- **Database**: Room + SQLCipher
  - AES-256-GCM encryption
  - Key wrapped by passphrase-derived KEK
- **Entities**: Messages (ephemeral), Sessions (temporary)
- **NO persistent**: Contact lists, ratchet state, metadata

### Self-Hosted Infrastructure
- **NYM Mixnet**: 3+ self-hosted mix nodes
- **Oblivious TURN**: Self-hosted coturn + proxy
- **Cost**: ~$30/month for 4 VPS (or free with Oracle Cloud)

---

## Complete Working Flow - Phase 1 (Live Chat Only)

```mermaid
sequenceDiagram
    participant UserA as User A
    participant AppA as ZeroChat App A
    participant KDF_A as Argon2id KDF
    participant NymA as Nym Client A
    participant Mixnet as NYM Mixnet (Self-hosted)
    participant NymB as Nym Client B
    participant AppB as ZeroChat App B
    participant UserB as User B
    participant Relay as Oblivious TURN (Relay-only)

    Note over UserA,UserB: Phase 1: Unlock and Initialize
    
    UserA->>AppA: Enter passphrase
    AppA->>KDF_A: Derive key (Argon2id 512MB)
    KDF_A-->>AppA: KEK (volatile RAM only)
    AppA->>AppA: Unwrap SQLCipher key
    AppA->>AppA: Open encrypted database
    
    Note over UserA: Duress passphrase would destroy keys instead
    
    AppA->>NymA: Initialize mixnet client
    NymA->>Mixnet: Connect to self-hosted gateway
    Mixnet-->>NymA: Nym address assigned
    NymA-->>AppA: Return address
    AppA-->>UserA: Display Nym address
    
    UserB->>AppB: Enter passphrase
    AppB->>AppB: Argon2id KEK DB unlock
    AppB->>NymB: Initialize mixnet client
    NymB->>Mixnet: Connect
    Mixnet-->>NymB: Nym address assigned
    AppB-->>UserB: Display Nym address
    
    Note over UserA,UserB: Users exchange Nym addresses (Signal, PGP email, QR code)
    
    UserA->>AppA: Enter peer Nym address
    UserB->>AppB: Enter peer Nym address
    
    UserA->>AppA: Enter shared secret
    UserB->>AppB: Enter SAME shared secret
    
    Note over UserA,UserB: Phase 2: SPAKE2+ Authentication via Mixnet
    
    AppA->>AppA: Derive auth commitment
    AppA->>NymA: Send commitment
    NymA->>Mixnet: Route through 3 mix nodes
    Mixnet->>Mixnet: Mix traffic
    Mixnet->>NymB: Deliver (anonymous)
    NymB->>AppB: Receive commitment
    
    AppB->>AppB: Verify and compute response
    AppB->>NymB: Send response
    NymB->>Mixnet: Route anonymously
    Mixnet->>NymA: Deliver
    NymA->>AppA: Receive response
    
    AppA->>AppA: Derive session key
    AppB->>AppB: Derive session key
    
    Note over AppA,AppB: Mutual authentication complete - Session keys match
    
    Note over UserA,UserB: Phase 3: WebRTC Signaling (Relay-Only)
    
    AppA->>AppA: Configure RELAY-ONLY mode
    AppA->>AppA: Block host and srflx candidates
    AppA->>AppA: Create PeerConnection
    AppA->>AppA: Generate SDP offer
    
    AppA->>NymA: Send encrypted SDP
    NymA->>Mixnet: Anonymous routing
    Mixnet->>NymB: Deliver
    NymB->>AppB: Receive SDP offer
    
    AppB->>AppB: Configure RELAY-ONLY mode
    AppB->>AppB: Process offer, create answer
    
    AppB->>NymB: Send encrypted SDP
    NymB->>Mixnet: Anonymous routing
    Mixnet->>NymA: Deliver
    NymA->>AppA: Receive SDP answer
    
    Note over AppA,AppB: ICE Candidate Exchange
    AppA->>AppA: Filter candidates (relay ONLY)
    AppB->>AppB: Filter candidates (relay ONLY)
    
    AppA->>NymA: Relay candidates
    AppB->>NymB: Relay candidates
    Note over NymA,NymB: Host/srflx BLOCKED - Only relay exchanged
    
    Note over UserA,UserB: Phase 4: Oblivious TURN Connection
    
    AppA->>Relay: WebRTC via NYM gateway proxy
    Note right of AppA: TURN sees proxy IP NOT User A real IP
    
    AppB->>Relay: WebRTC via NYM gateway proxy
    Note right of AppB: TURN sees proxy IP NOT User B real IP
    
    Relay->>Relay: Cannot correlate User A IP with User B identity
    
    Note over AppA,Relay: DataChannel established (IP-safe relay-only)
    
    Note over UserA,UserB: Phase 5: Live Encrypted Chat
    
    UserA->>AppA: Type message
    AppA->>AppA: Encrypt (session ratchet)
    AppA->>Relay: Send ciphertext
    Relay->>AppB: Relay (cannot decrypt)
    AppB->>AppB: Decrypt (session ratchet)
    AppB-->>UserB: Display message
    AppB->>AppB: Save to SQLCipher
    
    UserB->>AppB: Type reply
    AppB->>AppB: Encrypt (session ratchet)
    AppB->>Relay: Send ciphertext
    Relay->>AppA: Relay
    AppA->>AppA: Decrypt
    AppA-->>UserA: Display message
    AppA->>AppA: Save to SQLCipher
    
    Note over UserA,UserB: All traffic E2E encrypted - Relay cannot read content
    
    Note over UserA,UserB: Phase 6: Session End (Ephemeral)
    
    UserA->>AppA: Close chat / Exit app
    AppA->>AppA: DESTROY ratchet state
    AppA->>AppA: Clear KEK from RAM
    AppA->>Relay: Close DataChannel
    AppA->>NymA: Disconnect
    
    Note over AppA: No persistent state - No ratchet on disk - No metadata trail
```

---

## Implementation Phases

### Phase 1: Project Bootstrap
- Create Android project (Kotlin + Compose)
- Set up Rust toolchain for Android
- Configure Gradle for UniFFI integration

### Phase 2-7: As per TASKS.md

### Future: Offline Messaging (Phase 2)
Dead-drop implementation deferred to Phase 2

---

**Ready to begin Phase 1 implementation?**
