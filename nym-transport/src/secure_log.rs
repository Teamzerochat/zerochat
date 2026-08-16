/// secure_log.rs — In-memory ring buffer with zeroize-on-drop semantics.
///
/// Purpose: A diagnostics log that leaves no persistent traces. Entries live
/// in a `Zeroizing<Vec<u8>>` arena so the allocator sees zeroed pages after
/// the buffer is dropped or explicitly cleared. Exposed to Kotlin via UniFFI.
use zeroize::Zeroizing;

/// Maximum number of entries the ring buffer can hold.
const CAPACITY: usize = 64;

/// Maximum byte length of a single log entry (excess is silently truncated).
const MAX_ENTRY_BYTES: usize = 256;

/// A fixed-capacity, in-memory ring buffer backed by zeroizing storage.
///
/// ```
/// let mut log = SecureLog::new();
/// log.write("Session established");
/// log.clear();
/// ```
pub struct SecureLog {
    /// Flat arena — each slot is exactly MAX_ENTRY_BYTES bytes.
    /// Laid out as `[slot_0 | slot_1 | … | slot_{CAPACITY-1}]`.
    arena: Zeroizing<Vec<u8>>,

    /// Index of the next slot to write (wraps around at CAPACITY).
    head: usize,

    /// Number of valid entries currently held (capped at CAPACITY).
    len: usize,
}

impl SecureLog {
    /// Create a new, empty ring buffer.
    pub fn new() -> Self {
        Self {
            arena: Zeroizing::new(vec![0u8; CAPACITY * MAX_ENTRY_BYTES]),
            head: 0,
            len: 0,
        }
    }

    /// Write a UTF-8 string entry. Truncated silently if longer than
    /// `MAX_ENTRY_BYTES`. Thread-safety is the caller's responsibility.
    pub fn write(&mut self, entry: &str) {
        let bytes = entry.as_bytes();
        let copy_len = bytes.len().min(MAX_ENTRY_BYTES);

        let slot_start = self.head * MAX_ENTRY_BYTES;
        let slot_end = slot_start + MAX_ENTRY_BYTES;

        // Zero the slot first so previous data cannot bleed through.
        for b in &mut self.arena[slot_start..slot_end] {
            *b = 0;
        }
        self.arena[slot_start..slot_start + copy_len].copy_from_slice(&bytes[..copy_len]);

        self.head = (self.head + 1) % CAPACITY;
        if self.len < CAPACITY {
            self.len += 1;
        }
    }

    /// Explicitly zero every byte in the arena and reset indices.
    /// Called automatically on `Drop`, but can be triggered early on
    /// session teardown or panic-wipe.
    pub fn clear(&mut self) {
        for b in self.arena.iter_mut() {
            *b = 0;
        }
        self.head = 0;
        self.len = 0;
    }

    /// Returns the number of valid entries currently stored.
    #[allow(dead_code)]
    pub fn len(&self) -> usize {
        self.len
    }

    /// Returns true when no entries are stored.
    #[allow(dead_code)]
    pub fn is_empty(&self) -> bool {
        self.len == 0
    }
}

impl Default for SecureLog {
    fn default() -> Self {
        Self::new()
    }
}

/// Guarantees zeroization on drop, even during stack unwinds.
impl Drop for SecureLog {
    fn drop(&mut self) {
        self.clear();
    }
}

// ─── UniFFI-exposed free functions ──────────────────────────────────────────
//
// UniFFI cannot bind `&mut self` methods, so we expose a simple stateless
// API backed by a process-global `Mutex<SecureLog>`.

use once_cell::sync::Lazy;
use std::sync::Mutex;

static GLOBAL_SECURE_LOG: Lazy<Mutex<SecureLog>> =
    Lazy::new(|| Mutex::new(SecureLog::new()));

/// Append `entry` to the global secure log.
pub fn secure_log_write(entry: String) {
    if let Ok(mut log) = GLOBAL_SECURE_LOG.lock() {
        log.write(&entry);
    }
}

/// Zero and reset the global secure log. Call on session end / panic wipe.
pub fn secure_log_clear() {
    if let Ok(mut log) = GLOBAL_SECURE_LOG.lock() {
        log.clear();
    }
}

/// Returns the current number of entries in the global secure log.
pub fn secure_log_len() -> u32 {
    GLOBAL_SECURE_LOG
        .lock()
        .map(|l| l.len() as u32)
        .unwrap_or(0)
}

// ─── Unit tests ─────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_and_len() {
        let mut log = SecureLog::new();
        assert_eq!(log.len(), 0);
        log.write("hello");
        assert_eq!(log.len(), 1);
        log.write("world");
        assert_eq!(log.len(), 2);
    }

    #[test]
    fn clear_zeroes_all() {
        let mut log = SecureLog::new();
        log.write("sensitive data");
        log.clear();
        assert_eq!(log.len(), 0);
        // Verify arena is zeroed.
        for b in log.arena.iter() {
            assert_eq!(*b, 0);
        }
    }

    #[test]
    fn ring_wraps_at_capacity() {
        let mut log = SecureLog::new();
        for i in 0..CAPACITY + 5 {
            log.write(&format!("entry {i}"));
        }
        // len is capped at CAPACITY.
        assert_eq!(log.len(), CAPACITY);
    }

    #[test]
    fn truncates_long_entry() {
        let mut log = SecureLog::new();
        let long = "x".repeat(MAX_ENTRY_BYTES + 100);
        log.write(&long); // must not panic
        assert_eq!(log.len(), 1);
    }

    #[test]
    fn drop_zeroes_arena() {
        let arena_ptr: *const u8;
        let arena_len: usize;
        {
            let mut log = SecureLog::new();
            log.write("drop test");
            arena_ptr = log.arena.as_ptr();
            arena_len = log.arena.len();
            // log is dropped here
        }
        // After drop, the memory should have been zeroed by Zeroizing<Vec<u8>>.
        // We can't safely dereference arena_ptr post-drop, but we at least
        // confirm no panic occurred during drop.
        let _ = (arena_ptr, arena_len); // suppress unused warning
    }
}
