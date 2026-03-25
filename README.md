# ZeroChat

ZeroChat is an experimental, privacy-first messaging application designed to minimize metadata leakage. While traditional end-to-end encrypted messengers protect the content of messages, they often leak metadata—such as who is talking to whom, when, and how frequently. ZeroChat aims to obscure this communication graph from network observers.

## Why ZeroChat?

Modern messaging apps focus heavily on protecting message contents using End-to-End Encryption (E2EE). However, the metadata detailing who you talk securely to, at what times, and from which IP addresses remains visible to service providers and network observers. This metadata can be just as revealing as the messages themselves. ZeroChat is an attempt to tackle this specific problem by hiding the communication graph, not just the message payloads.

## Key Concepts

To protect metadata, ZeroChat utilizes several techniques:

*   **Mixnets**: Instead of routing messages directly from sender to receiver or through a centralized server, traffic is sent through a mix network (e.g., the Nym network). Mix nodes cryptographically transform and shuffle messages, making it harder to trace a packet's path from origin to destination.
*   **Metadata Resistance**: By decoupling the sender's identity from the receiver's identity at the network layer, we prevent observers from reconstructing the social graph.
*   **Traffic Shaping & Cover Traffic**: To defend against timing and volume analysis, the application generates uniform "cover traffic." This attempts to mask actual communication spikes so that an observer cannot easily infer when real data is being sent or received.
*   **Packet Padding**: All packets are padded to a uniform size. This prevents adversaries from fingerprinting specific actions or identifying message types based on packet length.
*   **Traffic Obfuscation**: The transport layer includes an obfuscation mechanism (similar to obfs4) to make the traffic less distinguishable from normal encrypted traffic, adding a layer of defense against Deep Packet Inspection (DPI).

## High-Level Architecture

The architecture is built on the principle of decoupling peers:

1.  **Rendezvous Points**: Peers do not connect directly to each other. Instead, they derive deterministic, shared identifiers based on an out-of-band shared secret (e.g., exchanged via QR code). These identifiers act as shared "mailboxes" on the mixnet.
2.  **Asymmetric Polling**: One peer acts as the Initiator and drops messages into the rendezvous point, while the Responder periodically polls it.
3.  **Transport Layer**: The underlying transport currently relies on mixnet clients (like the Nym Rust SDK) wrapped with JNI for Android. This layer handles the routing, packet padding, and mixnet Sphinx packet formatting.
4.  **I2P Integration (Experimental)**: For some connection mechanisms, an embedded I2P router (via `i2pd` and SAM Bridge) is utilized to provide an additional layer of routing anonymity. This integration is currently experimental and used primarily for exploring alternative hidden service transports.

## Current Status

**Work in Progress (Alpha / Experimental).** 

The project is currently under active development and is not yet suitable for production use or situations requiring strong operational security. We are actively debugging issues related to transport reliability, mixnet latency, and consistent state synchronization between the Rust core and the Android UI.

## Future Goals

*   Stabilize the transport layer and improve reliable delivery through the mixnet.
*   Improve the state machine handling for session establishment and teardown.
*   Refine battery utilization, as continuous traffic shaping is inherently resource-intensive for mobile devices.
*   Explore multi-party or group messaging over the current point-to-point rendezvous design.
