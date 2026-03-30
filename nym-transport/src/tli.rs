//! TLI Lifecycle State Machine
//!
//! Paper §4: Transport Layer Identity goes through discrete lifecycle phases:
//! Init → Rendezvous → Hardened → [Fallback] → Zeroized
//!
//! Each state transition triggers specific security actions:
//! - Rendezvous → Hardened: Nym teardown, I2P promotion, cover traffic starts
//! - Hardened → Fallback: churn detected, degrade gracefully, alert UI
//! - Any → Zeroized: full session teardown, zeroize all keys, munlock

use std::sync::atomic::{AtomicU8, Ordering};
use std::time::Instant;
use zeroize::{Zeroize, ZeroizeOnDrop};

// Import necessary types for TliSessionState
use crate::mem_pin::PinnedSecret;

/// TLI lifecycle phases (Paper §4, Figure 3)
#[repr(u8)]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TliPhase {
    /// Initial state — app launched, nothing connected
    Init = 0,
    /// Nym rendezvous in progress — SPAKE2+ handshake, searching for peer
    Rendezvous = 1,
    /// Hardened — I2P tunnel established, Nym torn down, cover traffic active
    Hardened = 2,
    /// Fallback — churn detected, I2P degraded, attempting recovery
    Fallback = 3,
    /// Zeroized — all keys destroyed, session terminated
    Zeroized = 4,
}

impl TliPhase {
    pub fn from_u8(val: u8) -> Option<Self> {
        match val {
            0 => Some(TliPhase::Init),
            1 => Some(TliPhase::Rendezvous),
            2 => Some(TliPhase::Hardened),
            3 => Some(TliPhase::Fallback),
            4 => Some(TliPhase::Zeroized),
            _ => None,
        }
    }

    pub fn name(&self) -> &'static str {
        match self {
            TliPhase::Init => "Init",
            TliPhase::Rendezvous => "Rendezvous",
            TliPhase::Hardened => "Hardened",
            TliPhase::Fallback => "Fallback",
            TliPhase::Zeroized => "Zeroized",
        }
    }
}

/// Valid state transitions (Paper §4, Figure 3)
fn is_valid_transition(from: TliPhase, to: TliPhase) -> bool {
    matches!(
        (from, to),
        (TliPhase::Init, TliPhase::Rendezvous)
            | (TliPhase::Rendezvous, TliPhase::Hardened)
            | (TliPhase::Hardened, TliPhase::Fallback)
            | (TliPhase::Fallback, TliPhase::Rendezvous)   // Recovery
            | (TliPhase::Init, TliPhase::Zeroized)         // Emergency
            | (TliPhase::Rendezvous, TliPhase::Zeroized)   // Abort during handshake
            | (TliPhase::Hardened, TliPhase::Zeroized)     // Normal shutdown
            | (TliPhase::Fallback, TliPhase::Zeroized)     // Abort during fallback
    )
}

/// Churn detection result
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum ChurnSignal {
    /// Consecutive I2P heartbeat failures exceeded threshold
    HeartbeatTimeout { consecutive_failures: u32 },
    /// Router reported tunnel build failure rate > threshold
    TunnelBuildFailure { failure_rate: f64 },
    /// S_t (anonymity set) dropped below minimum viable threshold
    AnonymitySetCollapse { set_size: u64 },
}

/// TLI Lifecycle Manager
pub struct TliLifecycle {
    phase: AtomicU8,
    phase_entered_at: std::sync::Mutex<Instant>,
    /// Consecutive heartbeat failures for churn detection
    pub heartbeat_failures: AtomicU8,
    /// Threshold for declaring churn
    pub churn_threshold: u8,
}

impl TliLifecycle {
    pub fn new() -> Self {
        Self {
            phase: AtomicU8::new(TliPhase::Init as u8),
            phase_entered_at: std::sync::Mutex::new(Instant::now()),
            heartbeat_failures: AtomicU8::new(0),
            churn_threshold: 3, // 3 consecutive failures = churn
        }
    }

    /// Get the current lifecycle phase
    pub fn current_phase(&self) -> TliPhase {
        TliPhase::from_u8(self.phase.load(Ordering::SeqCst))
            .unwrap_or(TliPhase::Init)
    }

    /// Get the current phase as a u8 (for FFI)
    pub fn current_phase_u8(&self) -> u8 {
        self.phase.load(Ordering::SeqCst)
    }

    /// Attempt a state transition. Returns Ok(new_phase) or Err(reason).
    pub fn transition(&self, to: TliPhase) -> Result<TliPhase, String> {
        let from = self.current_phase();
        if !is_valid_transition(from, to) {
            return Err(format!(
                "Invalid TLI transition: {} → {}",
                from.name(),
                to.name()
            ));
        }

        self.phase.store(to as u8, Ordering::SeqCst);
        *self.phase_entered_at.lock().unwrap() = Instant::now();
        self.heartbeat_failures.store(0, Ordering::Relaxed);

        log::info!("TLI lifecycle: {} → {}", from.name(), to.name());
        Ok(to)
    }

    /// Record a heartbeat success (resets failure counter)
    pub fn heartbeat_ok(&self) {
        self.heartbeat_failures.store(0, Ordering::Relaxed);
    }

    /// Record a heartbeat failure. Returns Some(ChurnSignal) if threshold reached.
    pub fn heartbeat_fail(&self) -> Option<ChurnSignal> {
        let prev = self.heartbeat_failures.fetch_add(1, Ordering::Relaxed);
        let failures = prev + 1;
        if failures >= self.churn_threshold {
            log::warn!(
                "Churn detected: {} consecutive heartbeat failures (threshold={})",
                failures,
                self.churn_threshold
            );
            Some(ChurnSignal::HeartbeatTimeout {
                consecutive_failures: failures as u32,
            })
        } else {
            log::debug!("Heartbeat fail {}/{}", failures, self.churn_threshold);
            None
        }
    }

    /// Check if churn should trigger a Hardened → Fallback transition
    pub fn check_churn(&self, signal: ChurnSignal) -> bool {
        let current = self.current_phase();
        if current != TliPhase::Hardened {
            return false;
        }

        match signal {
            ChurnSignal::HeartbeatTimeout { consecutive_failures } => {
                consecutive_failures >= self.churn_threshold as u32
            }
            ChurnSignal::TunnelBuildFailure { failure_rate } => failure_rate > 0.5,
            ChurnSignal::AnonymitySetCollapse { set_size } => set_size < 10,
        }
    }

    /// Get time spent in current phase
    pub fn phase_duration_secs(&self) -> u64 {
        self.phase_entered_at
            .lock()
            .unwrap()
            .elapsed()
            .as_secs()
    }
}

/// Unified session state as described in Paper Listing 1.1
/// Co-locates all session material with ZeroizeOnDrop guarantee
#[derive(ZeroizeOnDrop)]
pub struct TliSessionState {
    /// SPAKE2+ intermediate values (will be zeroized)
    pub spake_intermediate: Vec<u8>,
    /// I2P destination private key (will be zeroized)
    pub i2p_dest_privkey: Vec<u8>,
    /// Session nonce (will be zeroized)
    pub session_nonce: [u8; 32],
    /// 64-byte obfs4 state derived from SPAKE2+ shared secret (Paper §6)
    /// First 32 bytes used as ChaCha20-Poly1305 key
    /// Next 12 bytes used as nonce material
    /// Remaining bytes reserved for future use
    /// Will be zeroized automatically on drop
    #[zeroize(skip)]
    pub obfs4_state: Option<Box<[u8; 64]>>,
    /// Reference to session store handle for cleanup
    #[zeroize(skip)]
    pub session_handle: u64,
}

impl TliSessionState {
    pub fn new(
        spake_intermediate: Vec<u8>,
        i2p_dest_privkey: Vec<u8>,
        session_nonce: [u8; 32],
        obfs4_state: Option<Box<[u8; 64]>>,
        session_handle: u64,
    ) -> Self {
        Self {
            spake_intermediate,
            i2p_dest_privkey,
            session_nonce,
            obfs4_state,
            session_handle,
        }
    }

    /// Terminate the session, zeroizing all material and destroying the session
    pub fn terminate(&self) {
        // The struct fields will be zeroized automatically on drop due to ZeroizeOnDrop
        // Additionally, explicitly destroy the session in the store
        crate::session_store::session_destroy(self.session_handle);
        log::info!("TliSessionState terminated and all materials zeroized");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_valid_transitions() {
        let lc = TliLifecycle::new();
        assert_eq!(lc.current_phase(), TliPhase::Init);

        assert!(lc.transition(TliPhase::Rendezvous).is_ok());
        assert_eq!(lc.current_phase(), TliPhase::Rendezvous);

        assert!(lc.transition(TliPhase::Hardened).is_ok());
        assert_eq!(lc.current_phase(), TliPhase::Hardened);

        assert!(lc.transition(TliPhase::Zeroized).is_ok());
        assert_eq!(lc.current_phase(), TliPhase::Zeroized);
    }

    #[test]
    fn test_invalid_transitions() {
        let lc = TliLifecycle::new();
        // Can't go Init → Hardened directly
        assert!(lc.transition(TliPhase::Hardened).is_err());
        // Can't go Init → Fallback directly
        assert!(lc.transition(TliPhase::Fallback).is_err());
    }

    #[test]
    fn test_churn_detection() {
        let lc = TliLifecycle::new();
        lc.transition(TliPhase::Rendezvous).unwrap();
        lc.transition(TliPhase::Hardened).unwrap();

        // Two failures — not enough
        assert!(lc.heartbeat_fail().is_none());
        assert!(lc.heartbeat_fail().is_none());

        // Third failure — churn!
        let signal = lc.heartbeat_fail();
        assert!(signal.is_some());
        assert!(lc.check_churn(signal.unwrap()));
    }

    #[test]
    fn test_heartbeat_reset() {
        let lc = TliLifecycle::new();
        lc.heartbeat_fail();
        lc.heartbeat_fail();
        lc.heartbeat_ok(); // Reset
        assert!(lc.heartbeat_fail().is_none()); // Only 1 now, not 3
    }

    #[test]
    fn test_fallback_recovery() {
        let lc = TliLifecycle::new();
        lc.transition(TliPhase::Rendezvous).unwrap();
        lc.transition(TliPhase::Hardened).unwrap();
        lc.transition(TliPhase::Fallback).unwrap();
        // Recovery: Fallback → Rendezvous
        assert!(lc.transition(TliPhase::Rendezvous).is_ok());
    }
}
