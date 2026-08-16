/// zcap/surb.rs — SURB (Single-Use Reply Block) handle generation and ACK tracking.
///
/// SURBs let the recipient send back an ACK without revealing their Nym address.
/// Each outgoing ZCAP packet carries a set of SURBs so the gateway can
/// acknowledge receipt even when the sender has already disconnected.
///
/// In this implementation we model SURBs as opaque byte handles that are
/// generated on the sender side and tracked by a `SurbTracker`.
use std::collections::HashMap;
use std::sync::Mutex;

use once_cell::sync::Lazy;
use rand::RngCore;

// ─── Types ───────────────────────────────────────────────────────────────────

/// An opaque handle for a single SURB.
#[derive(Debug, Clone)]
pub struct SurbHandle {
    /// Unique identifier for this SURB (16 random bytes, base16-encoded in logs).
    pub surb_id: Vec<u8>,
    /// The raw SURB bytes to include in the outgoing Sphinx packet header.
    /// In a real Nym integration, these are produced by the client's SURB generator.
    /// For this stub we use random bytes as a placeholder.
    pub surb_bytes: Vec<u8>,
}

/// Tracks pending (unacknowledged) SURBs keyed by `surb_id`.
pub struct SurbTracker {
    pending: Mutex<HashMap<Vec<u8>, SurbHandle>>,
}

impl SurbTracker {
    pub fn new() -> Self {
        Self {
            pending: Mutex::new(HashMap::new()),
        }
    }

    /// Register a set of SURBs as pending acknowledgement.
    pub fn track(&self, handles: &[SurbHandle]) {
        let mut map = self.pending.lock().unwrap();
        for h in handles {
            map.insert(h.surb_id.clone(), h.clone());
        }
    }

    /// Mark a SURB as acknowledged and remove it from the pending set.
    /// Returns `true` if the SURB was found (i.e. it was genuinely pending).
    pub fn acknowledge(&self, surb_id: &[u8]) -> bool {
        self.pending.lock().unwrap().remove(surb_id).is_some()
    }

    /// Return the number of pending (unacknowledged) SURBs.
    pub fn pending_count(&self) -> usize {
        self.pending.lock().unwrap().len()
    }

    /// Clear all pending SURBs — called on session wipe.
    pub fn clear(&self) {
        self.pending.lock().unwrap().clear();
    }
}

impl Default for SurbTracker {
    fn default() -> Self {
        Self::new()
    }
}

// ─── Global tracker ──────────────────────────────────────────────────────────

static SURB_TRACKER: Lazy<SurbTracker> = Lazy::new(SurbTracker::new);

// ─── Public free functions (UniFFI-exposed) ───────────────────────────────────

/// Generate `count` SURB handles. Each handle has a 16-byte random ID and a
/// 64-byte placeholder payload (real SURBs would be ~2 KiB Sphinx headers).
///
/// Returns a flat serialized blob: `[count * (16 + 4 + 64)]` bytes, where the
/// 4-byte field encodes the `surb_bytes` length as LE u32 for Kotlin to parse.
///
/// For Kotlin convenience, the IDs are returned as a list of 16-byte vecs.
pub fn generate_surbs(count: u32) -> Vec<Vec<u8>> {
    let mut rng = rand::thread_rng();
    let mut handles = Vec::with_capacity(count as usize);
    for _ in 0..count {
        let mut surb_id = vec![0u8; 16];
        let mut surb_bytes = vec![0u8; 64]; // stub — real Sphinx SURBs are larger
        rng.fill_bytes(&mut surb_id);
        rng.fill_bytes(&mut surb_bytes);
        let handle = SurbHandle {
            surb_id: surb_id.clone(),
            surb_bytes,
        };
        SURB_TRACKER.track(std::slice::from_ref(&handle));
        handles.push(surb_id);
    }
    handles
}

/// Acknowledge a SURB by its 16-byte ID.
/// Returns `true` if the SURB was found in the pending set.
pub fn acknowledge_surb(surb_id: Vec<u8>) -> bool {
    SURB_TRACKER.acknowledge(&surb_id)
}

/// Return the number of currently pending (unacknowledged) SURBs.
pub fn surb_pending_count() -> u32 {
    SURB_TRACKER.pending_count() as u32
}

/// Wipe all pending SURBs — called on session teardown.
pub fn surb_clear_all() {
    SURB_TRACKER.clear();
}

// ─── Unit tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generate_returns_correct_count() {
        let tracker = SurbTracker::new();
        let mut rng = rand::thread_rng();
        let mut ids = Vec::new();
        for _ in 0..3 {
            let mut id = vec![0u8; 16];
            let mut bytes = vec![0u8; 64];
            rng.fill_bytes(&mut id);
            rng.fill_bytes(&mut bytes);
            let h = SurbHandle { surb_id: id.clone(), surb_bytes: bytes };
            tracker.track(std::slice::from_ref(&h));
            ids.push(id);
        }
        assert_eq!(tracker.pending_count(), 3);
    }

    #[test]
    fn acknowledge_removes_pending() {
        let tracker = SurbTracker::new();
        let mut rng = rand::thread_rng();
        let mut id = vec![0u8; 16];
        rng.fill_bytes(&mut id);
        let h = SurbHandle { surb_id: id.clone(), surb_bytes: vec![0u8; 64] };
        tracker.track(std::slice::from_ref(&h));
        assert!(tracker.acknowledge(&id));
        assert_eq!(tracker.pending_count(), 0);
    }

    #[test]
    fn acknowledge_unknown_surb_returns_false() {
        let tracker = SurbTracker::new();
        assert!(!tracker.acknowledge(b"unknown_surb_id_"));
    }

    #[test]
    fn clear_empties_tracker() {
        let tracker = SurbTracker::new();
        let mut rng = rand::thread_rng();
        for _ in 0..5 {
            let mut id = vec![0u8; 16];
            rng.fill_bytes(&mut id);
            let h = SurbHandle { surb_id: id, surb_bytes: vec![0u8; 64] };
            tracker.track(std::slice::from_ref(&h));
        }
        tracker.clear();
        assert_eq!(tracker.pending_count(), 0);
    }
}
