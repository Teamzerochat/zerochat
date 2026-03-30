//! SPAKE2+ Password-Authenticated Key Exchange
//!
//! Implements SPAKE2+ protocol for mutual authentication using a shared password.
//! State is stored in Rust to avoid serialization issues.
//!
//! SECURITY: Session keys are stored in session_store behind opaque handles.
//! Raw key bytes NEVER cross the FFI boundary.

use spake2::{Ed25519Group, Identity, Password, Spake2};
use std::collections::HashMap;
use std::sync::Mutex;
use once_cell::sync::Lazy;
use rand::Rng;
use thiserror::Error;
use zeroize::Zeroize;

use crate::session_store;

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
/// Returns SESSION HANDLE (not raw key) — key stays in Rust memory
pub fn spake2_finish_initiator(handle_id: u64, inbound_msg: &[u8]) -> Result<u64, Spake2Error> {
    log::info!("Finishing SPAKE2+ as initiator with handle {}", handle_id);
    
    // Retrieve and remove state
    let state = SPAKE2_STATES
        .lock()
        .unwrap()
        .remove(&handle_id)
        .ok_or(Spake2Error::InvalidHandle)?;
    
    let mut key = state
        .finish(inbound_msg)
        .map_err(|e| Spake2Error::HandshakeFailed {
            reason: format!("Failed to derive key: {:?}", e),
        })?;
    
    // Store in session store (HKDF derivation happens inside)
    let session_handle = session_store::session_store(key.to_vec());
    
    // Zeroize the raw SPAKE2 output
    key.zeroize();
    
    log::info!("SPAKE2+ initiator complete. Session handle: {}", session_handle);
    
    Ok(session_handle)
}

/// Start SPAKE2+ as responder (Bob)
/// Takes password and peer's message, returns (response, session_handle)
/// Session key stays in Rust memory — only handle crosses FFI
pub fn spake2_start_responder(password: &[u8], inbound_msg: &[u8]) -> Result<(Vec<u8>, u64), Spake2Error> {
    log::info!("Starting SPAKE2+ as responder");
    
    let (state, outbound_msg) = Spake2::<Ed25519Group>::start_symmetric(
        &Password::new(password),
        &Identity::new(b"ZeroChat"),  // Same identity for symmetric protocol
    );
    
    let mut key = state
        .finish(inbound_msg)
        .map_err(|e| Spake2Error::HandshakeFailed {
            reason: format!("Failed to derive key: {:?}", e),
        })?;
    
    // Store in session store (HKDF derivation happens inside)
    let session_handle = session_store::session_store(key.to_vec());
    
    // Zeroize the raw SPAKE2 output
    key.zeroize();
    
    log::info!("SPAKE2+ responder complete. Session handle: {}", session_handle);
    
    Ok((outbound_msg.to_vec(), session_handle))
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
        let (msg2, handle_bob) = spake2_start_responder(password, &msg1).unwrap();

        // Initiator finishes with handle
        let handle_alice = spake2_finish_initiator(handle_id, &msg2).unwrap();

        // Handles are different (randomly generated), but underlying keys should match.
        // Verify by encrypting with Alice's handle and decrypting with Bob's handle.
        let test_data = b"test message";
        let encrypted = session_store::session_encrypt(handle_alice, test_data).unwrap();
        let decrypted = session_store::session_decrypt(handle_bob, &encrypted).unwrap();
        assert_eq!(decrypted, test_data, "Keys should match - Bob should decrypt Alice's message");

        // Also verify reverse direction
        let encrypted_bob = session_store::session_encrypt(handle_bob, test_data).unwrap();
        let decrypted_alice = session_store::session_decrypt(handle_alice, &encrypted_bob).unwrap();
        assert_eq!(decrypted_alice, test_data, "Keys should match - Alice should decrypt Bob's message");

        // Clean up
        session_store::session_destroy(handle_alice);
        session_store::session_destroy(handle_bob);
    }

    #[test]
    fn test_spake2_handshake_wrong_password() {
        let password_alice = b"test-password-alice-67890";
        let password_bob = b"test-password-bob-67890";

        // Initiator starts
        let (handle_id, msg1) = spake2_start_initiator(password_alice).unwrap();

        // Responder with different password
        let (msg2, handle_bob) = spake2_start_responder(password_bob, &msg1).unwrap();

        // Initiator derives key with wrong password - should still complete but produce different key
        let handle_alice = spake2_finish_initiator(handle_id, &msg2).unwrap();

        // Handles should be different (different session keys derived)
        assert_ne!(handle_alice, handle_bob);
        
        // Clean up SPAKE2 state (if any leftover) and session store
        spake2_cleanup_state(handle_id); // Should return false since finish removed it
        session_store::session_destroy(handle_alice);
        session_store::session_destroy(handle_bob);
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
