//! SPAKE2+ Password-Authenticated Key Exchange
//!
//! Implements SPAKE2+ protocol for mutual authentication using a shared password.
//! State is stored in Rust to avoid serialization issues.

use spake2::{Ed25519Group, Identity, Password, Spake2};
use std::collections::HashMap;
use std::sync::Mutex;
use once_cell::sync::Lazy;
use rand::Rng;
use thiserror::Error;

/// SPAKE2+ errors
#[derive(Error, Debug)]
pub enum Spake2Error {
    #[error("Invalid handle ID")]
    InvalidHandle,
    #[error("Handshake failed: {reason}")]
    HandshakeFailed { reason: String },
}

/// Global state storage for SPAKE2+ handshakes
static SPAKE2_STATES: Lazy<Mutex<HashMap<u64, Spake2<Ed25519Group>>>> = 
    Lazy::new(|| Mutex::new(HashMap::new()));

/// Generate a random handle ID
fn generate_handle_id() -> u64 {
    rand::thread_rng().gen()
}

/// Start SPAKE2+ as initiator (Alice)
/// Returns (handle_id, outbound_message)
/// The handle_id must be used to finish the handshake
pub fn spake2_start_initiator(password: &[u8]) -> Result<(u64, Vec<u8>), Spake2Error> {
    log::info!("Starting SPAKE2+ as initiator");
    
    let (state, outbound_msg) = Spake2::<Ed25519Group>::start_symmetric(
        &Password::new(password),
        &Identity::new(b"ZeroChat"),  // Same identity for symmetric protocol
    );
    
    let handle_id = generate_handle_id();
    
    // Store state for later
    SPAKE2_STATES.lock().unwrap().insert(handle_id, state);
    
    log::info!("SPAKE2+ initiator state stored with handle {}", handle_id);
    
    Ok((handle_id, outbound_msg.to_vec()))
}

/// Finish SPAKE2+ as initiator
/// Takes handle_id from start_initiator and peer's response
/// Returns shared secret and removes state
pub fn spake2_finish_initiator(handle_id: u64, inbound_msg: &[u8]) -> Result<Vec<u8>, Spake2Error> {
    log::info!("Finishing SPAKE2+ as initiator with handle {}", handle_id);
    
    // Retrieve and remove state
    let state = SPAKE2_STATES
        .lock()
        .unwrap()
        .remove(&handle_id)
        .ok_or(Spake2Error::InvalidHandle)?;
    
    let key = state
        .finish(inbound_msg)
        .map_err(|e| Spake2Error::HandshakeFailed {
            reason: format!("Failed to derive key: {:?}", e),
        })?;
    
    log::info!("SPAKE2+ initiator handshake complete");
    
    Ok(key.to_vec())
}

/// Start SPAKE2+ as responder (Bob)
/// Takes password and peer's message, returns (response, shared_secret)
/// Responder completes in one step, no state storage needed
pub fn spake2_start_responder(password: &[u8], inbound_msg: &[u8]) -> Result<(Vec<u8>, Vec<u8>), Spake2Error> {
    log::info!("Starting SPAKE2+ as responder");
    
    let (state, outbound_msg) = Spake2::<Ed25519Group>::start_symmetric(
        &Password::new(password),
        &Identity::new(b"ZeroChat"),  // Same identity for symmetric protocol
    );
    
    let key = state
        .finish(inbound_msg)
        .map_err(|e| Spake2Error::HandshakeFailed {
            reason: format!("Failed to derive key: {:?}", e),
        })?;
    
    log::info!("SPAKE2+ responder handshake complete");
    
    Ok((outbound_msg.to_vec(), key.to_vec()))
}

/// Clean up expired or abandoned handshake states
/// Should be called periodically to prevent memory leaks
pub fn spake2_cleanup_state(handle_id: u64) -> bool {
    SPAKE2_STATES.lock().unwrap().remove(&handle_id).is_some()
}

/// Get count of active handshake states (for debugging)
pub fn spake2_active_count() -> usize {
    SPAKE2_STATES.lock().unwrap().len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_spake2_handshake_success() {
        let password = b"test-password-success-12345";
        
        // Initiator starts and gets handle + message
        let (handle_id, msg1) = spake2_start_initiator(password).unwrap();
        assert!(handle_id > 0);
        
        // Responder receives msg1 and responds
        let (msg2, key_bob) = spake2_start_responder(password, &msg1).unwrap();
        
        // Initiator finishes with handle
        let key_alice = spake2_finish_initiator(handle_id, &msg2).unwrap();
        
        // Both should have the same key
        assert_eq!(key_alice, key_bob);
        assert_eq!(key_alice.len(), 32); // Ed25519 produces 32-byte keys
    }

    #[test]
    fn test_spake2_handshake_wrong_password() {
        let password_alice = b"test-password-alice-67890";
        let password_bob = b"test-password-bob-67890";
        
        // Initiator starts
        let (handle_id, msg1) = spake2_start_initiator(password_alice).unwrap();
        
        // Responder with different password
        let (msg2, key_bob) = spake2_start_responder(password_bob, &msg1).unwrap();
        
        // Initiator derives key
        let key_alice = spake2_finish_initiator(handle_id, &msg2).unwrap();
        
        // Keys should be different
        assert_ne!(key_alice, key_bob);
    }
    
    #[test]
    fn test_spake2_invalid_handle() {
        let invalid_handle = 99999;
        let msg = vec![0u8; 32];
        
        let result = spake2_finish_initiator(invalid_handle, &msg);
        assert!(result.is_err());
        assert!(matches!(result.unwrap_err(), Spake2Error::InvalidHandle));
    }
    
    #[test]
    fn test_spake2_cleanup() {
        let password = b"test-password-cleanup-11111";
        
        // Clear any existing states first
        let initial_count = spake2_active_count();
        
        let (handle_id, _msg) = spake2_start_initiator(password).unwrap();
        assert_eq!(spake2_active_count(), initial_count + 1);
        
        // Cleanup the state
        assert!(spake2_cleanup_state(handle_id));
        assert_eq!(spake2_active_count(), initial_count);
        
        // Cleanup again should return false
        assert!(!spake2_cleanup_state(handle_id));
    }
}
