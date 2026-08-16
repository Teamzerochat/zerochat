/// zcap/lewes.rs — Lewes Protocol: offline message fetching across missed epochs.
///
/// `fetch_offline_messages` scans the last `EPOCH_LOOKBACK` epochs, querying
/// each mailbox replica with exponential backoff on transient failures.
///
/// `zcap_fetch_messages` is the UniFFI-exposed entry point. It accepts a
/// serialized ratchet state blob, decrypts all recovered payloads, and
/// returns both the decrypted messages and the updated ratchet state.
use crate::zcap::derivation::{zcap_missed_epochs, zcap_mailbox_id, zcap_gateway_index, REPLICA_COUNT};
use crate::zcap::ratchet::{ratchet_decrypt, RatchetError};
use crate::zcap::sphinx_pad::{unpad_sphinx_payload, SPHINX_PACKET_SIZE};
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Semaphore;
use tokio::task::JoinSet;

// ─── Backoff configuration ───────────────────────────────────────────────────

/// Initial backoff delay in milliseconds.
const INITIAL_BACKOFF_MS: u64 = 200;

/// Maximum backoff delay in milliseconds (caps at ~3.2 s after 4 doublings).
const MAX_BACKOFF_MS: u64 = 3200;

/// Maximum number of retry attempts per mailbox slot.
const MAX_RETRIES: u32 = 4;

/// Maximum concurrent mailbox rendezvous connections during one Lewes scan.
const MAX_CONCURRENT_MAILBOX_POLLS: usize = 5;

// ─── Mailbox fetch (stub) ────────────────────────────────────────────────────

/// Represents one recovered ciphertext blob from a gateway mailbox.
#[derive(Debug, Clone)]
pub struct MailboxPayload {
    pub epoch: u64,
    pub replica: u8,
    pub ciphertext: Vec<u8>,
}

/// Fetch all available payloads from a specific mailbox slot.
///
/// In a real implementation this calls `NymTransportClient::poll_rendezvous`
/// using the mailbox_id as the rendezvous point. This stub returns an empty
/// list (no offline messages) to allow the rest of the pipeline to compile.
async fn fetch_mailbox(
    transport_handle: u64,
    mailbox_id: &[u8],
    gateway_identity: &str,
    epoch: u64,
    replica: u8,
) -> Result<Vec<MailboxPayload>, String> {
    log::debug!(
        "zcap::lewes: fetching mailbox epoch={epoch} replica={replica} gw={gateway_identity} id={}",
        hex::encode(&mailbox_id[..4.min(mailbox_id.len())])
    );
    let payloads = crate::zcap_poll_mailbox(transport_handle, mailbox_id)
        .await
        .map_err(|e| format!("poll mailbox via {gateway_identity}: {e}"))?;

    Ok(payloads
        .into_iter()
        .map(|ciphertext| MailboxPayload {
            epoch,
            replica,
            ciphertext,
        })
        .collect())
}

/// Attempt to fetch a mailbox slot with exponential backoff on failure.
async fn fetch_with_backoff(
    transport_handle: u64,
    mailbox_id: Vec<u8>,
    gateway_identity: String,
    epoch: u64,
    replica: u8,
) -> Vec<MailboxPayload> {
    let mut backoff_ms = INITIAL_BACKOFF_MS;
    for attempt in 0..MAX_RETRIES {
        match fetch_mailbox(transport_handle, &mailbox_id, &gateway_identity, epoch, replica).await {
            Ok(payloads) => return payloads,
            Err(err) => {
                log::warn!(
                    "zcap::lewes: attempt {}/{} for epoch={epoch} replica={replica} failed: {err}",
                    attempt + 1,
                    MAX_RETRIES
                );
                tokio::time::sleep(Duration::from_millis(backoff_ms)).await;
                backoff_ms = (backoff_ms * 2).min(MAX_BACKOFF_MS);
            }
        }
    }
    vec![]
}

// ─── Public fetch pipeline ────────────────────────────────────────────────────

/// Scan all missed epochs and collect raw ciphertext payloads from the swarm.
///
/// For each of the last `EPOCH_LOOKBACK` epochs, we query all `REPLICA_COUNT`
/// mailbox replicas concurrently and aggregate results.
///
/// `gateway_identities` must be sorted lexicographically (same order used by
/// `zcap_gateway_index`).
pub async fn fetch_offline_messages(
    transport_handle: u64,
    k_shared: Vec<u8>,
    utc_now_secs: u64,
    gateway_identities: Vec<String>,
) -> Vec<MailboxPayload> {
    let epochs = zcap_missed_epochs(k_shared.clone(), utc_now_secs);
    let gw_count = gateway_identities.len() as u32;

    let mut all_payloads = Vec::new();
    let semaphore = Arc::new(Semaphore::new(MAX_CONCURRENT_MAILBOX_POLLS));

    for epoch in epochs {
        let mut epoch_tasks = JoinSet::new();

        for replica in 0..REPLICA_COUNT {
            let mailbox_id = zcap_mailbox_id(k_shared.clone(), epoch, replica);
            let gw_idx = zcap_gateway_index(
                k_shared.clone(),
                epoch,
                replica,
                gw_count,
            );
            let gw_identity = match gw_idx {
                Some(idx) => gateway_identities
                    .get(idx as usize)
                    .cloned()
                    .unwrap_or_default(),
                None => continue,
            };

            let permit_source = semaphore.clone();
            epoch_tasks.spawn(async move {
                let _permit = permit_source
                    .acquire_owned()
                    .await
                    .map_err(|_| "mailbox poll semaphore closed".to_string())?;
                Ok::<Vec<MailboxPayload>, String>(
                    fetch_with_backoff(transport_handle, mailbox_id, gw_identity, epoch, replica).await
                )
            });
        }

        while let Some(joined) = epoch_tasks.join_next().await {
            match joined {
                Ok(Ok(payloads)) if !payloads.is_empty() => {
                    all_payloads.extend(payloads);
                    epoch_tasks.abort_all();
                    break;
                }
                Ok(Ok(_)) => {}
                Ok(Err(err)) => {
                    log::warn!("zcap::lewes: mailbox task failed for epoch={epoch}: {err}");
                }
                Err(err) => {
                    log::warn!("zcap::lewes: mailbox task join failed for epoch={epoch}: {err}");
                }
            }
        }
    }

    all_payloads
}

// ─── UniFFI-exposed entry point ───────────────────────────────────────────────

/// Fetch offline messages and decrypt them using the Double Ratchet.
///
/// Returns `(decrypted_messages, updated_serialized_state)`.
///
/// The caller (ZcapStateManager) MUST persist `updated_serialized_state` to
/// `ZcapRatchetDao` after this call.
///
/// `gateway_identities` — sorted gateway identity list (from cached gateway fetch).
pub fn zcap_fetch_messages(
    transport_handle: u64,
    serialized_state: Vec<u8>,
    k_shared: Vec<u8>,
    utc_now_secs: u64,
    gateway_identities: Vec<String>,
) -> Result<(Vec<Vec<u8>>, Vec<u8>), String> {
    let rt = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| format!("runtime: {e}"))?;

    let payloads = rt.block_on(fetch_offline_messages(
        transport_handle,
        k_shared,
        utc_now_secs,
        gateway_identities,
    ));

    if payloads.is_empty() {
        // Nothing to decrypt — return state unchanged.
        return Ok((vec![], serialized_state));
    }

    // Decrypt each payload with the ratchet, chaining the state forward.
    let mut current_state = serialized_state;
    let mut decrypted_messages = Vec::new();

    for payload in payloads {
        // Each fetched ciphertext is a full padded Sphinx packet.
        let ciphertext = if payload.ciphertext.len() == SPHINX_PACKET_SIZE {
            match unpad_sphinx_payload(&payload.ciphertext) {
                Ok(c) => c,
                Err(e) => {
                    log::warn!("zcap::lewes: unpad failed for epoch={} replica={}: {e}",
                        payload.epoch, payload.replica);
                    continue;
                }
            }
        } else {
            payload.ciphertext
        };

        match ratchet_decrypt(current_state.clone(), ciphertext) {
            Ok((plaintext, new_state)) => {
                decrypted_messages.push(plaintext);
                current_state = new_state;
            }
            Err(e) => {
                log::warn!("zcap::lewes: decrypt failed for epoch={} replica={}: {e}",
                    payload.epoch, payload.replica);
                // Continue — next message might succeed (e.g. skipped key).
            }
        }
    }

    Ok((decrypted_messages, current_state))
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

    #[tokio::test]
    async fn fetch_offline_messages_empty_when_no_gateways() {
        let k = vec![0xABu8; 32];
        let results = fetch_offline_messages(u64::MAX, k, 100_000, vec![]).await;
        assert!(results.is_empty());
    }

    #[tokio::test]
    async fn fetch_offline_messages_empty_when_gateway_list_empty() {
        let k = vec![0xCDu8; 32];
        let results = fetch_offline_messages(u64::MAX, k, 100_000, vec![]).await;
        assert!(results.is_empty());
    }

    #[test]
    fn zcap_fetch_messages_no_op_on_empty() {
        let blob = make_state_blob();
        let (msgs, new_blob) = zcap_fetch_messages(
            u64::MAX,
            blob.clone(),
            vec![0xABu8; 32],
            100_000,
            vec![],
        )
        .unwrap();
        assert!(msgs.is_empty());
        // State should be unchanged when there are no offline messages.
        assert_eq!(blob, new_blob);
    }
}
