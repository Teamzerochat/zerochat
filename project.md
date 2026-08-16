# ZeroChat Codebase Map

This document outlines the architecture and purpose of the key files in the ZeroChat Android project. It serves as a guide for understanding the "mixnet-first, I2P-hybrid" anonymous messaging flow.

## 1. Core Domain Layer (`app/src/main/java/com/zerochat/app/domain/`)

### 1.1 `connection/`
*   **`ConnectionManager.kt`**: The core state machine of the app. It orchestrates the entire establishment flow: deterministically computing the Nym Rendezvous Point, performing the SPAKE2+ handshake via polling, securely exchanging I2P Destination handles, establishing the I2P tunnel, and managing connection states for the UI. It also handles "Churn Detection" to fallback to Nym if I2P fails.

### 1.2 `transport/`
*   **`TransportController.kt`**: A panic-safe wrapper around the Nym Mixnet transport. If the underlying Rust library crashes, this controller safely destroys the stale instance and rebuilds it transparently. It is the sole access point to the mixnet.
*   **`NymTransport.kt` / `RealNymTransport.kt`**: The interface and implementation connecting to the UniFFI Rust bridge for the Nym Mixnet. Includes packet padding, frame decryption, cover traffic toggles, and gateway authentication logic.
*   **`HybridTransport.kt`**: Implements the traffic routing logic for dual-transport execution. It calculates routing probabilities to enact stochastic delays and seamless cross-fades between the Nym mixnet and the I2P tunnel to prevent identifiable traffic anomalies.

### 1.3 `i2p/`
*   **`I2PRouterService.kt`**: An Android foreground service that manages the lifecycle of the bundled C++ `i2pd` daemon via JNI. It polls the internal HTTP dashboard (`http://127.0.0.1:7070`) to confirm router bootstrap readiness.
*   **`SamClient.kt`**: A Kotlin TCP client implementing the I2P SAM v3 specification. It communicates with the internal `i2pd` daemon on port `7656` to create anonymous sessions, generate dynamic destination handles, and open stream sockets to peers.
*   **`I2PStream.kt` / `EncryptedChannel.kt`**: Provides a standard `InputStream`/`OutputStream` wrapper over the SAM socket, wrapped in a framing protocol for reading and decrypting complete messages.

### 1.4 `rendezvous/`
*   **`RendezvousManager.kt`**: Central component for deriving deterministic Nym network "mailboxes" based on the user's out-of-band shared secret (e.g., QR code). It manages the publish-and-poll loop required for peers to locate each other on the mixnet before any direct connection is established.

### 1.5 `messaging/`
*   **`MessageQueue.kt`**: In-memory manager for chat messages. Enqueues outbound user messages, handles retry logic, prevents replay attacks using nonces, routes packets to the correct transport, and exposes the read-only chat history to the ViewModel.
*   **`MessageProtocol.kt`**: The serialization protocol defining the payload byte structure (e.g., `[Type][Len][Payload]`).

### 1.6 `crypto/`
*   **`HandshakeManager.kt`**: Integrates the SPAKE2+ Password-Authenticated Key Exchange (PAKE) to generate a strong symmetric context from a weak out-of-band secret.
*   **`KeyManager.kt`**: Symmetric encryption helper classes.

## 2. UI & Architecture (`app/src/main/java/com/zerochat/app/ui/`)
Built with modern Android Jetpack Compose.
*   **`viewmodels/`**: State holders linking the Domain layer to the UI. Key file is `ChatViewModel.kt`, which listens to `MessageQueue` flows.
*   **`screens/`**: UI implementations corresponding to navigation logic (`ChatScreen.kt`, `ConnectScreen.kt`).

## 3. Native code (`app/src/main/cpp/` and Rust)
*   The application relies heavily on two native bridges:
    1.  **Nym Rust Client**: Handled via UniFFI (`nym_transport` namespace). Exposes the mixnet SDK, SPAKE2+ logic, and AEAD cryptography.
    2.  **i2pd C++ Daemon**: Hosted locally on the device as a foreground process to facilitate hidden service routing. Configured via `app/src/main/assets/i2pd.conf`.
