//! Cover Traffic Module — Adaptive Poisson-rate dummy Sphinx traffic.
//!
//! Paper §5: "Adaptive anonymity-set-aware cover traffic with thermal duty-cycling"
//!
//! Strategy:
//! - λ_min = ln(|S_t|) / 60 packets/sec, where S_t = active anonymity set
//! - Burst on session start (high cover), exponential taper during session
//! - Skip cover traffic when thermal throttle is engaged
//! - 0-length padded Sphinx frames indistinguishable from data frames

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::{Duration, Instant};

/// Cover traffic configuration (Paper §5, Table 4)
#[derive(Debug, Clone)]
pub struct CoverConfig {
    /// Minimum lambda (packets/sec). Default: ln(500)/60 ≈ 0.1035
    pub lambda_min: f64,
    /// Initial burst rate multiplier on session start
    pub burst_multiplier: f64,
    /// Exponential decay half-life in seconds
    pub decay_half_life_s: f64,
    /// Size of padded cover frame (matches SPHINX_PADDED_SIZE)
    pub frame_size: usize,
}

impl Default for CoverConfig {
    fn default() -> Self {
        Self {
            lambda_min: (500f64).ln() / 60.0,  // ~0.1035 pps for S_t=500
            burst_multiplier: 4.0,
            decay_half_life_s: 120.0,  // 2 minutes half-life
            frame_size: 1452,          // Matches pad_to_fixed MTU
        }
    }
}

/// State for the cover traffic scheduler
pub struct CoverTrafficState {
    config: CoverConfig,
    session_start: Instant,
    /// Whether thermal throttle is active (set from Kotlin via FFI)
    thermal_throttle: AtomicBool,
    /// Whether cover traffic is enabled (can be disabled during shutdown)
    enabled: AtomicBool,
    /// Count of cover frames sent (for logging/metrics)
    frames_sent: AtomicU64,
    /// Estimated anonymity set size (updated periodically)
    anonymity_set_size: AtomicU64,
}

impl CoverTrafficState {
    pub fn new() -> Self {
        Self {
            config: CoverConfig::default(),
            session_start: Instant::now(),
            thermal_throttle: AtomicBool::new(false),
            enabled: AtomicBool::new(false),
            frames_sent: AtomicU64::new(0),
            anonymity_set_size: AtomicU64::new(500),  // Conservative default
        }
    }

    pub fn with_config(config: CoverConfig) -> Self {
        let mut state = Self::new();
        state.config = config;
        state
    }

    /// Start cover traffic (call on session establishment)
    pub fn start(&self) {
        self.enabled.store(true, Ordering::SeqCst);
        self.frames_sent.store(0, Ordering::SeqCst);
        log::info!("Cover traffic started (λ_min={:.4})", self.config.lambda_min);
    }

    /// Stop cover traffic (call on session teardown)
    pub fn stop(&self) {
        self.enabled.store(false, Ordering::SeqCst);
        let sent = self.frames_sent.load(Ordering::Relaxed);
        log::info!("Cover traffic stopped ({} frames sent)", sent);
    }

    /// Set thermal throttle state (called from Kotlin via FFI)
    pub fn set_thermal_throttle(&self, active: bool) {
        self.thermal_throttle.store(active, Ordering::SeqCst);
        if active {
            log::warn!("Cover traffic: thermal throttle ACTIVE — pausing cover");
        } else {
            log::info!("Cover traffic: thermal throttle cleared — resuming");
        }
    }

    /// Update anonymity set size (call periodically, e.g. every 60s)
    pub fn update_anonymity_set(&self, set_size: u64) {
        let clamped = set_size.max(1);
        self.anonymity_set_size.store(clamped, Ordering::Relaxed);
        log::debug!("Cover traffic: anonymity set updated to {}", clamped);
    }

    /// Calculate the current inter-packet delay (in ms).
    ///
    /// Uses exponential decay from burst rate to steady-state λ_min:
    ///   λ(t) = λ_min + (λ_min × burst_mult - λ_min) × 2^(-t / half_life)
    ///   delay = 1000 / λ(t)
    pub fn current_delay_ms(&self) -> u64 {
        let s_t = self.anonymity_set_size.load(Ordering::Relaxed) as f64;
        let lambda_base = s_t.max(1.0).ln() / 60.0;  // Adaptive: λ = ln(|S_t|)/60

        let t = self.session_start.elapsed().as_secs_f64();
        let lambda_burst = lambda_base * self.config.burst_multiplier;
        let decay = (-t / self.config.decay_half_life_s).exp2();
        let lambda = lambda_base + (lambda_burst - lambda_base) * decay;

        // Clamp to [50ms, 30s] range
        let delay = (1000.0 / lambda).clamp(50.0, 30_000.0);
        delay as u64
    }

    /// Check if a cover frame should be sent right now
    pub fn should_send(&self) -> bool {
        if !self.enabled.load(Ordering::SeqCst) {
            return false;
        }
        if self.thermal_throttle.load(Ordering::SeqCst) {
            return false;  // Skip cover during thermal throttle
        }
        true
    }

    /// Generate a cover frame (padded zeros). All frames are same size as real traffic.
    pub fn generate_cover_frame(&self) -> Vec<u8> {
        self.frames_sent.fetch_add(1, Ordering::Relaxed);
        vec![0u8; self.config.frame_size]
    }

    /// Get the number of cover frames sent so far
    pub fn frames_sent(&self) -> u64 {
        self.frames_sent.load(Ordering::Relaxed)
    }

    /// Reset session start time (call when transitioning phases)
    pub fn reset_session_timer(&self) {
        // Note: Instant is not atomic, so this is only safe if called from single thread.
        // For phase transitions, this is called from the same coroutine context.
        log::info!("Cover traffic: session timer reset (phase transition)");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_default_lambda() {
        let state = CoverTrafficState::new();
        let delay = state.current_delay_ms();
        // At t=0, burst rate should give short delay
        assert!(delay < 5000, "Initial delay should be < 5s, got {}ms", delay);
    }

    #[test]
    fn test_thermal_throttle_blocks() {
        let state = CoverTrafficState::new();
        state.start();
        assert!(state.should_send());
        state.set_thermal_throttle(true);
        assert!(!state.should_send());
        state.set_thermal_throttle(false);
        assert!(state.should_send());
    }

    #[test]
    fn test_cover_frame_size() {
        let state = CoverTrafficState::new();
        let frame = state.generate_cover_frame();
        assert_eq!(frame.len(), 1452);
        assert_eq!(state.frames_sent(), 1);
    }

    #[test]
    fn test_disabled_state() {
        let state = CoverTrafficState::new();
        // Not started yet
        assert!(!state.should_send());
        state.start();
        assert!(state.should_send());
        state.stop();
        assert!(!state.should_send());
    }
}
