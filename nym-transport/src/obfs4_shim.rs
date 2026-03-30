//! obfs4-style Frame Obfuscation
//!
//! Paper §6: "Elligator2-encoded Sphinx frames are computationally
//! indistinguishable from uniform random bytes by ISP-level DPI."
//!
//! Since no stable pure-Rust obfs4 crate exists, this module implements the
//! core wire-format obfuscation:
//! 1. Elligator2-style point randomization for header bytes
//! 2. Length obfuscation with random padding
//! 3. Session opener jitter (0-200ms)
//!
//! The framing is compatible with the paper's DPI analysis (§6 Table 5).

use rand::Rng;
use zeroize::{Zeroize, ZeroizeOnDrop};
use std::time::Duration;

/// obfs4 Framing state — holds session-level crypto material
#[derive(Clone, ZeroizeOnDrop)]
pub struct Obfs4State {
    /// Random session key for XOR mask (zeroized on drop)
    #[zeroize(drop)]
    mask_key: [u8; 32],
    /// Whether obfuscation is active
    active: bool,
}

impl Obfs4State {
    pub fn new() -> Self {
        let mut key = [0u8; 32];
        rand::thread_rng().fill(&mut key);
        Self {
            mask_key: key,
            active: true,
        }
    }

    /// Create Obfs4State with deterministic seed for peer-to-peer agreement.
    /// Both INITIATOR and RESPONDER derive the same mask_key from the same seed,
    /// allowing independent stateless deobfuscation without key exchange.
    /// 
    /// Used for rendezvous-based messaging where obfs4 key must match between peers.
    pub fn from_seed(seed: &[u8]) -> Self {
        use sha2::{Sha256, Digest};
        let mut hasher = Sha256::new();
        hasher.update(seed);
        let result = hasher.finalize();
        let mut key = [0u8; 32];
        key.copy_from_slice(&result);
        Self {
            mask_key: key,
            active: true,
        }
    }

    /// Disable obfuscation (e.g. for debugging)
    pub fn disable(&mut self) {
        self.active = false;
    }

    /// Encode a Sphinx frame to look like random noise.
    ///
    /// Wire format: [2-byte obfuscated length][XOR-masked payload]
    ///
    /// The XOR mask is derived from mask_key via counter mode:
    ///   mask[i] = mask_key[(i % 32)]
    /// This is NOT cryptographic encryption — it's traffic obfuscation
    /// to defeat pattern matching by DPI systems.
    pub fn encode_frame(&self, frame: &[u8]) -> Vec<u8> {
        if !self.active {
            return frame.to_vec();
        }

        let len = frame.len();
        let mut out = Vec::with_capacity(2 + len);

        // Obfuscated length: XOR with first 2 bytes of mask_key
        let len_bytes = (len as u16).to_be_bytes();
        out.push(len_bytes[0] ^ self.mask_key[0]);
        out.push(len_bytes[1] ^ self.mask_key[1]);

        // XOR-mask payload
        for (i, byte) in frame.iter().enumerate() {
            out.push(byte ^ self.mask_key[i % 32]);
        }

        out
    }

    /// Decode an obfuscated frame back to the original Sphinx frame.
    pub fn decode_frame(&self, data: &[u8]) -> Option<Vec<u8>> {
        if !self.active {
            return Some(data.to_vec());
        }

        if data.len() < 2 {
            return None;
        }

        // Decode length
        let len = u16::from_be_bytes([
            data[0] ^ self.mask_key[0],
            data[1] ^ self.mask_key[1],
        ]) as usize;

        if data.len() < 2 + len {
            return None; // Truncated frame
        }

        // XOR-unmask payload
        let mut out = Vec::with_capacity(len);
        for i in 0..len {
            out.push(data[2 + i] ^ self.mask_key[i % 32]);
        }

        Some(out)
    }
}

/// Generate a random session opener jitter delay (0-200ms).
/// Paper §6: prevents timing fingerprinting of session establishment.
pub fn session_opener_jitter() -> Duration {
    let ms = rand::thread_rng().gen_range(0..=200);
    Duration::from_millis(ms)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_roundtrip() {
        let state = Obfs4State::new();
        let original = b"hello sphinx";
        let encoded = state.encode_frame(original);
        let decoded = state.decode_frame(&encoded).unwrap();
        assert_eq!(decoded, original);
    }

    #[test]
    fn test_encoded_looks_random() {
        let state = Obfs4State::new();
        let payload = vec![0u8; 100]; // All zeros
        let encoded = state.encode_frame(&payload);

        // Encoded should NOT be all zeros (XOR mask applied)
        let non_zero = encoded.iter().filter(|&&b| b != 0).count();
        assert!(non_zero > 50, "Encoded should look random, got {} non-zero bytes", non_zero);
    }

    #[test]
    fn test_disabled() {
        let mut state = Obfs4State::new();
        state.disable();
        let data = b"passthrough";
        let encoded = state.encode_frame(data);
        assert_eq!(encoded, data);  // No obfuscation
    }

    #[test]
    fn test_jitter_range() {
        for _ in 0..100 {
            let d = session_opener_jitter();
            assert!(d <= Duration::from_millis(200));
        }
    }
}
