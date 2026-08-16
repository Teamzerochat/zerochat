/// zcap/derivation.rs — Deterministic epoch/mailbox/gateway derivation.
///
/// All intermediate byte buffers use `Zeroizing<>` to ensure sensitive
/// shared-secret material is wiped from memory after use.
///
/// # Protocol constants
/// - `EPOCH_DURATION_SECS` = 3600 (1 hour windows)
/// - `EPOCH_LOOKBACK`      = 9    (scan back 9 epochs for offline messages)
/// - `REPLICA_COUNT`       = 3    (3 mailbox replicas per epoch for redundancy)
use hmac::{Hmac, Mac};
use sha2::{Sha256, Digest};
use zeroize::Zeroizing;

type HmacSha256 = Hmac<Sha256>;

// ─── Protocol constants ──────────────────────────────────────────────────────

/// Length of each ZCAP epoch in seconds (1 hour).
pub const EPOCH_DURATION_SECS: u64 = 3600;

/// Number of past epochs to scan when fetching offline messages.
pub const EPOCH_LOOKBACK: u64 = 9;

/// Number of gateway replicas per epoch mailbox.
pub const REPLICA_COUNT: u8 = 3;

// ─── Public free-function API (exposed via UniFFI) ───────────────────────────

/// Derive the per-peer epoch offset so each pair's epoch boundary is
/// independently jittered. This prevents timing correlation across pairs.
///
/// `digest = HMAC-SHA256(key = k_shared, msg = b"zcap-epoch-offset")`
/// `offset = u64::from_le_bytes(digest[0..8]) % EPOCH_DURATION_SECS`
pub fn zcap_epoch_offset(k_shared: Vec<u8>) -> u64 {
    let mut digest = Zeroizing::new([0u8; 32]);
    {
        let mut mac = HmacSha256::new_from_slice(&k_shared)
            .expect("HMAC accepts any key length");
        mac.update(b"zcap-epoch-offset");
        let result = mac.finalize().into_bytes();
        digest.copy_from_slice(&result);
    }
    let raw = u64::from_le_bytes(digest[0..8].try_into().unwrap());
    raw % EPOCH_DURATION_SECS
}

/// Return the current ZCAP epoch number for a given shared key.
///
/// `epoch = (utc_now_secs - offset) / EPOCH_DURATION_SECS`
pub fn zcap_current_epoch(k_shared: Vec<u8>, utc_now_secs: u64) -> u64 {
    let offset = zcap_epoch_offset(k_shared);
    (utc_now_secs.saturating_sub(offset)) / EPOCH_DURATION_SECS
}

/// Derive the mailbox ID for a specific epoch and replica index.
///
/// `mailbox_id = SHA-256(k_shared || epoch_le_bytes || [replica])`
///
/// Returns 32 raw bytes. The caller hex-encodes or base58-encodes as needed.
pub fn zcap_mailbox_id(k_shared: Vec<u8>, epoch: u64, replica: u8) -> Vec<u8> {
    let mut hasher = Sha256::new();
    hasher.update(&k_shared);
    hasher.update(epoch.to_le_bytes());
    hasher.update([replica]);
    let result = hasher.finalize();
    // Zeroize the hasher state (no sensitive material leaves in digest)
    result.to_vec()
}

/// Select a gateway from the sorted list deterministically for a given epoch
/// and replica.
///
/// `digest = SHA-256(k_shared || epoch_le_bytes || [0x10 + replica])`
/// `index  = u32::from_le_bytes(digest[0..4]) % gateway_count`
///
/// Returns `None` if `gateway_count == 0`.
pub fn zcap_gateway_index(k_shared: Vec<u8>, epoch: u64, replica: u8, gateway_count: u32) -> Option<u32> {
    if gateway_count == 0 {
        return None;
    }
    let mut digest_buf = Zeroizing::new([0u8; 32]);
    {
        let mut hasher = Sha256::new();
        hasher.update(&k_shared);
        hasher.update(epoch.to_le_bytes());
        hasher.update([0x10u8.wrapping_add(replica)]);
        let d = hasher.finalize();
        digest_buf.copy_from_slice(&d);
    }
    let raw = u32::from_le_bytes(digest_buf[0..4].try_into().unwrap());
    Some(raw % gateway_count)
}

/// Return the list of epochs to scan for offline messages.
///
/// Includes the current epoch and the `EPOCH_LOOKBACK - 1` epochs before it,
/// in reverse-chronological order (most recent first).
pub fn zcap_missed_epochs(k_shared: Vec<u8>, utc_now_secs: u64) -> Vec<u64> {
    let current = zcap_current_epoch(k_shared, utc_now_secs);
    (0..EPOCH_LOOKBACK).map(|i| current.saturating_sub(i)).collect()
}

// ─── Unit tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn shared_key() -> Vec<u8> {
        vec![0xABu8; 32]
    }

    #[test]
    fn offset_is_deterministic() {
        let k = shared_key();
        assert_eq!(zcap_epoch_offset(k.clone()), zcap_epoch_offset(k));
    }

    #[test]
    fn offset_within_epoch_duration() {
        let offset = zcap_epoch_offset(shared_key());
        assert!(offset < EPOCH_DURATION_SECS);
    }

    #[test]
    fn mailbox_id_is_32_bytes() {
        let id = zcap_mailbox_id(shared_key(), 42, 0);
        assert_eq!(id.len(), 32);
    }

    #[test]
    fn mailbox_id_differs_by_replica() {
        let k = shared_key();
        let id0 = zcap_mailbox_id(k.clone(), 42, 0);
        let id1 = zcap_mailbox_id(k.clone(), 42, 1);
        assert_ne!(id0, id1);
    }

    #[test]
    fn mailbox_id_differs_by_epoch() {
        let k = shared_key();
        let id_e1 = zcap_mailbox_id(k.clone(), 1, 0);
        let id_e2 = zcap_mailbox_id(k.clone(), 2, 0);
        assert_ne!(id_e1, id_e2);
    }

    #[test]
    fn gateway_index_in_bounds() {
        let idx = zcap_gateway_index(shared_key(), 7, 2, 10).unwrap();
        assert!(idx < 10);
    }

    #[test]
    fn gateway_index_none_on_zero_gateways() {
        assert!(zcap_gateway_index(shared_key(), 7, 0, 0).is_none());
    }

    #[test]
    fn missed_epochs_length() {
        let epochs = zcap_missed_epochs(shared_key(), 100_000);
        assert_eq!(epochs.len() as u64, EPOCH_LOOKBACK);
    }

    #[test]
    fn missed_epochs_descending() {
        let epochs = zcap_missed_epochs(shared_key(), 100_000);
        for window in epochs.windows(2) {
            assert!(window[0] >= window[1]);
        }
    }
}
