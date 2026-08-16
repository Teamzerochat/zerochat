/// zcap/swarm.rs — Gateway swarm dispatch and ZCAP send pipeline.
///
/// `zcap_send` is the top-level entry point called by Kotlin's ZcapStateManager.
/// It:
///   1. Deserializes the ratchet state from the DB blob.
///   2. Encrypts the plaintext payload using the Double Ratchet.
///   3. Pads the ciphertext to `SPHINX_PACKET_SIZE`.
///   4. Dispatches the padded packet to 3 gateway replicas concurrently.
///   5. Returns the updated serialized ratchet state for the caller to persist.
///
/// The parallel dispatch uses `tokio::join!` so all 3 sends are in-flight
/// simultaneously, improving reliability without extra latency.
use crate::zcap::ratchet::{ratchet_encrypt, RatchetError};
use crate::zcap::sphinx_pad::{pad_to_sphinx_size, PadError};

// ─── Errors ──────────────────────────────────────────────────────────────────

#[derive(Debug)]
pub enum SwarmError {
    Ratchet(RatchetError),
    Pad(PadError),
    /// All replicas failed to dispatch.
    AllReplicasFailed(Vec<String>),
    /// At least one replica succeeded (partial success is still a success).
    PartialSuccess { succeeded: u32, failed: u32 },
}

impl std::fmt::Display for SwarmError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SwarmError::Ratchet(e) => write!(f, "ratchet error: {e}"),
            SwarmError::Pad(e) => write!(f, "padding error: {e}"),
            SwarmError::AllReplicasFailed(errs) => {
                write!(f, "all replicas failed: {}", errs.join("; "))
            }
            SwarmError::PartialSuccess { succeeded, failed } => {
                write!(f, "partial success: {succeeded} ok, {failed} failed")
            }
        }
    }
}

impl From<RatchetError> for SwarmError {
    fn from(e: RatchetError) -> Self {
        SwarmError::Ratchet(e)
    }
}

impl From<PadError> for SwarmError {
    fn from(e: PadError) -> Self {
        SwarmError::Pad(e)
    }
}

// ─── Gateway replica send (stub) ─────────────────────────────────────────────

/// Represents a resolved gateway endpoint for one replica.
#[derive(Debug, Clone)]
pub struct GatewayEndpoint {
    pub identity: String,
    pub mailbox_id: Vec<u8>,
}

/// Attempt to deliver `packet` to a single gateway replica.
///
/// In a full implementation this calls `NymTransportClient::send_message`.
/// Here it is a no-op stub that always succeeds, returning the gateway identity.
async fn send_to_replica(
    transport_handle: u64,
    endpoint: GatewayEndpoint,
    packet: Vec<u8>,
) -> Result<String, String> {
    log::debug!(
        "zcap::swarm: dispatching {} bytes to gateway {} mailbox {}",
        packet.len(),
        endpoint.identity,
        hex::encode(&endpoint.mailbox_id[..4])
    );
    crate::zcap_send_packet_to_mailbox(transport_handle, &endpoint.mailbox_id, packet).await?;
    Ok(endpoint.identity)
}

/// Dispatch `packet` to all three gateway replicas concurrently.
///
/// Returns:
/// - `Ok(count)` — the number of replicas that succeeded (1–3).
/// - `Err(SwarmError::AllReplicasFailed)` — all replicas failed.
pub async fn send_to_swarm(
    transport_handle: u64,
    replicas: [GatewayEndpoint; 3],
    packet: Vec<u8>,
) -> Result<u32, SwarmError> {
    let r0 = send_to_replica(transport_handle, replicas[0].clone(), packet.clone());
    let r1 = send_to_replica(transport_handle, replicas[1].clone(), packet.clone());
    let r2 = send_to_replica(transport_handle, replicas[2].clone(), packet.clone());

    let (res0, res1, res2) = tokio::join!(r0, r1, r2);
    let results = [res0, res1, res2];

    let succeeded = results.iter().filter(|r| r.is_ok()).count() as u32;
    let failed_msgs: Vec<String> = results
        .iter()
        .filter_map(|r| r.as_ref().err().cloned())
        .collect();

    if succeeded == 0 {
        Err(SwarmError::AllReplicasFailed(failed_msgs))
    } else {
        if !failed_msgs.is_empty() {
            log::warn!(
                "zcap::swarm: partial success — {succeeded}/3 replicas ok, {} failed",
                failed_msgs.len()
            );
        }
        Ok(succeeded)
    }
}

// ─── Top-level ZCAP send (UniFFI-exposed) ─────────────────────────────────────

/// Full ZCAP send pipeline: encrypt → pad → swarm dispatch.
///
/// Accepts a serialized ratchet state blob (from the DB), encrypts `plaintext`,
/// pads the result to Sphinx size, and dispatches to all replicas.
///
/// Returns `(updated_serialized_state, succeeded_replica_count)`.
///
/// The caller (ZcapStateManager) MUST persist `updated_serialized_state` to
/// `ZcapRatchetDao` before the next send.
pub fn zcap_send(
    transport_handle: u64,
    serialized_state: Vec<u8>,
    plaintext: Vec<u8>,
    replicas: Vec<Vec<u8>>,        // 3 x mailbox_id blobs
    gateway_identities: Vec<String>, // 3 x gateway identity strings
) -> Result<(Vec<u8>, u32), String> {
    if replicas.len() < 3 || gateway_identities.len() < 3 {
        return Err("zcap_send requires exactly 3 replicas and 3 gateway identities".into());
    }

    // Build endpoint array.
    let endpoints: [GatewayEndpoint; 3] = [
        GatewayEndpoint { identity: gateway_identities[0].clone(), mailbox_id: replicas[0].clone() },
        GatewayEndpoint { identity: gateway_identities[1].clone(), mailbox_id: replicas[1].clone() },
        GatewayEndpoint { identity: gateway_identities[2].clone(), mailbox_id: replicas[2].clone() },
    ];

    // Encrypt with ratchet.
    let (ciphertext, new_state) = ratchet_encrypt(serialized_state, plaintext)
        .map_err(|e| format!("ratchet_encrypt: {e}"))?;

    // Pad to Sphinx size.
    let packet = pad_to_sphinx_size(&ciphertext)
        .map_err(|e| format!("pad_to_sphinx_size: {e}"))?;

    // Dispatch to swarm via a fresh Tokio runtime (we're called from blocking Kotlin thread).
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;

    let succeeded = rt
        .block_on(send_to_swarm(transport_handle, endpoints, packet))
        .map_err(|e| format!("send_to_swarm: {e}"))?;

    Ok((new_state, succeeded))
}

// ─── Unit tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use crate::zcap::ratchet::{RatchetState, serialize_state};

    fn make_state_blob() -> Vec<u8> {
        let state = RatchetState::from_shared_secret(b"test-shared-secret-32-bytes-long").unwrap();
        serialize_state(&state).unwrap()
    }

    fn make_endpoints() -> [GatewayEndpoint; 3] {
        [
            GatewayEndpoint { identity: "gw0".into(), mailbox_id: vec![0u8; 32] },
            GatewayEndpoint { identity: "gw1".into(), mailbox_id: vec![1u8; 32] },
            GatewayEndpoint { identity: "gw2".into(), mailbox_id: vec![2u8; 32] },
        ]
    }

    #[test]
    fn zcap_send_requires_connected_main_client() {
        let handle = crate::zcap_register_test_transport();
        let blob = make_state_blob();
        let result = zcap_send(
            handle,
            blob,
            b"hello swarm".to_vec(),
            vec![vec![0u8; 32], vec![1u8; 32], vec![2u8; 32]],
            vec!["gw0".into(), "gw1".into(), "gw2".into()],
        );
        crate::zcap_unregister_transport_handle(handle);

        assert!(result.is_err());
    }

    #[test]
    fn zcap_send_rejects_unregistered_transport() {
        let blob = make_state_blob();
        let result = zcap_send(
            u64::MAX,
            blob,
            b"hello swarm".to_vec(),
            vec![vec![0u8; 32], vec![1u8; 32], vec![2u8; 32]],
            vec!["gw0".into(), "gw1".into(), "gw2".into()],
        );

        assert!(result.is_err());
    }
}
