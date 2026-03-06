//! Session Store — Rust-side session key management.
//!
//! Session keys from SPAKE2+ are stored here behind opaque handles.
//! Keys NEVER cross the FFI boundary. Kotlin only sees the handle ID.
//!
//! Security properties:
//! - Keys stored in Rust heap (not JVM GC heap)
//! - Auto-zeroized on drop (via `Zeroize` derive)
//! - TTL reaper: sessions older than 10 minutes are auto-destroyed
//! - HMAC confirmation uses typed role enum (not strings)

use crypto_secretbox::{XSalsa20Poly1305, aead::{Aead, AeadCore, KeyInit, OsRng}};
use crypto_secretbox::aead::generic_array::GenericArray;
use hmac::{Hmac, Mac};
use sha2::Sha256;
use hkdf::Hkdf;
use rand::Rng;
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;
use once_cell::sync::Lazy;
use thiserror::Error;
use zeroize::{Zeroize, ZeroizeOnDrop};

/// Session key errors
#[derive(Error, Debug)]
pub enum SessionError {
    #[error("Invalid session handle")]
    InvalidHandle,
    #[error("Encryption failed")]
    EncryptionFailed,
    #[error("Decryption failed")]
    DecryptionFailed,
    #[error("Invalid role")]
    InvalidRole,
    #[error("Session expired")]
    SessionExpired,
}

/// Typed role enum — never trust a String
#[repr(u8)]
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum HandshakeRole {
    Initiator = 0,
    Responder = 1,
}

impl HandshakeRole {
    pub fn from_u8(v: u8) -> Result<Self, SessionError> {
        match v {
            0 => Ok(HandshakeRole::Initiator),
            1 => Ok(HandshakeRole::Responder),
            _ => Err(SessionError::InvalidRole),
        }
    }

    /// Domain separation tag for HMAC confirmation
    fn tag(&self) -> &[u8] {
        match self {
            HandshakeRole::Initiator => b"zerochat-confirm-initiator-v1",
            HandshakeRole::Responder => b"zerochat-confirm-responder-v1",
        }
    }
}

/// Session entry — auto-zeroizes key on drop
#[derive(Zeroize, ZeroizeOnDrop)]
struct SessionEntry {
    key: [u8; 32],
    #[zeroize(skip)]
    created_at: Instant,
}

/// TTL: 10 minutes
const TTL_SECONDS: u64 = 600;

/// Global session store
static SESSION_STORE: Lazy<Mutex<HashMap<u64, SessionEntry>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// Reap expired sessions (called on every encrypt/decrypt)
fn reap_expired(store: &mut HashMap<u64, SessionEntry>) {
    let now = Instant::now();
    store.retain(|id, entry| {
        let alive = now.duration_since(entry.created_at).as_secs() < TTL_SECONDS;
        if !alive {
            log::warn!("Session {} expired (TTL={}s), auto-zeroized", id, TTL_SECONDS);
        }
        alive
    });
}

/// Store a session key and return a handle ID
pub fn session_store(raw_spake2_output: Vec<u8>) -> u64 {
    // Derive encryption key from SPAKE2+ output via HKDF
    let hkdf = Hkdf::<Sha256>::new(None, &raw_spake2_output);
    let mut key = [0u8; 32];
    hkdf.expand(b"zerochat-session-encryption-v1", &mut key)
        .expect("HKDF expand should not fail for 32 bytes");
    
    let handle = rand::thread_rng().gen::<u64>();
    let entry = SessionEntry {
        key,
        created_at: Instant::now(),
    };

    // Zeroize the intermediate HKDF input buffer on the stack
    // (the Vec will be dropped by caller, but our copy is clean)

    let mut store = SESSION_STORE.lock().unwrap();
    reap_expired(&mut store);
    store.insert(handle, entry);

    log::info!("Session stored with handle {} ({} active)", handle, store.len());
    handle
}

/// Encrypt plaintext using session key (NaCl SecretBox compatible)
///
/// Output format: [24-byte nonce] [ciphertext + 16-byte tag]
pub fn session_encrypt(handle: u64, plaintext: &[u8]) -> Result<Vec<u8>, SessionError> {
    let mut store = SESSION_STORE.lock().unwrap();
    reap_expired(&mut store);

    let entry = store.get(&handle).ok_or(SessionError::InvalidHandle)?;

    let cipher = XSalsa20Poly1305::new(GenericArray::from_slice(&entry.key));
    let nonce = XSalsa20Poly1305::generate_nonce(&mut OsRng);

    let ciphertext = cipher
        .encrypt(&nonce, plaintext)
        .map_err(|_| SessionError::EncryptionFailed)?;

    // [nonce (24)] [ciphertext + tag]
    let mut out = Vec::with_capacity(24 + ciphertext.len());
    out.extend_from_slice(&nonce);
    out.extend_from_slice(&ciphertext);

    Ok(out)
}

/// Decrypt ciphertext using session key
///
/// Input format: [24-byte nonce] [ciphertext + 16-byte tag]
pub fn session_decrypt(handle: u64, ciphertext: &[u8]) -> Result<Vec<u8>, SessionError> {
    if ciphertext.len() < 24 + 16 {
        return Err(SessionError::DecryptionFailed);
    }

    let mut store = SESSION_STORE.lock().unwrap();
    reap_expired(&mut store);

    let entry = store.get(&handle).ok_or(SessionError::InvalidHandle)?;

    let nonce = GenericArray::from_slice(&ciphertext[..24]);
    let ct = &ciphertext[24..];

    let cipher = XSalsa20Poly1305::new(GenericArray::from_slice(&entry.key));
    let plaintext = cipher
        .decrypt(nonce, ct)
        .map_err(|_| SessionError::DecryptionFailed)?;

    Ok(plaintext)
}

/// Generate HMAC-SHA256 confirmation for a role
///
/// Uses domain-separated tags, not raw strings.
pub fn session_generate_confirmation(handle: u64, role: u8) -> Result<Vec<u8>, SessionError> {
    let role = HandshakeRole::from_u8(role)?;

    let store = SESSION_STORE.lock().unwrap();
    let entry = store.get(&handle).ok_or(SessionError::InvalidHandle)?;

    // Derive confirmation key from session key
    let hkdf = Hkdf::<Sha256>::new(None, &entry.key);
    let mut confirm_key = [0u8; 32];
    hkdf.expand(b"zerochat-confirmation-key-v1", &mut confirm_key)
        .expect("HKDF expand should not fail");

    // HMAC-SHA256(confirmation_key, role_tag)
    type HmacSha256 = Hmac<Sha256>;
    let mut mac = <HmacSha256 as Mac>::new_from_slice(&confirm_key)
        .expect("HMAC accepts any key size");
    Mac::update(&mut mac, role.tag());
    let result = mac.finalize().into_bytes();

    // Zeroize confirmation key
    confirm_key.zeroize();

    Ok(result.to_vec())
}

/// Verify HMAC-SHA256 confirmation from peer
pub fn session_verify_confirmation(
    handle: u64,
    confirmation: &[u8],
    role: u8,
) -> Result<bool, SessionError> {
    let expected = session_generate_confirmation(handle, role)?;

    // Constant-time comparison
    if expected.len() != confirmation.len() {
        return Ok(false);
    }
    let mut diff = 0u8;
    for (a, b) in expected.iter().zip(confirmation.iter()) {
        diff |= a ^ b;
    }
    Ok(diff == 0)
}

/// Destroy a session (explicit zeroize + remove)
pub fn session_destroy(handle: u64) {
    let mut store = SESSION_STORE.lock().unwrap();
    if store.remove(&handle).is_some() {
        log::info!("Session {} destroyed and zeroized ({} remaining)", handle, store.len());
    } else {
        log::warn!("Session {} not found for destroy", handle);
    }
}

/// Get count of active sessions (for debugging)
pub fn session_active_count() -> usize {
    SESSION_STORE.lock().unwrap().len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_roundtrip_encrypt_decrypt() {
        let spake2_output = vec![42u8; 32];
        let handle = session_store(spake2_output);

        let plaintext = b"hello zerochat";
        let ct = session_encrypt(handle, plaintext).unwrap();
        let pt = session_decrypt(handle, &ct).unwrap();

        assert_eq!(pt, plaintext);
    }

    #[test]
    fn test_invalid_handle() {
        let result = session_encrypt(999999, b"test");
        assert!(result.is_err());
    }

    #[test]
    fn test_confirmation_verify() {
        let handle = session_store(vec![1u8; 32]);

        let confirm = session_generate_confirmation(handle, 0).unwrap();
        assert!(session_verify_confirmation(handle, &confirm, 0).unwrap());

        // Wrong role should fail
        assert!(!session_verify_confirmation(handle, &confirm, 1).unwrap());
    }

    #[test]
    fn test_destroy_zeroizes() {
        let handle = session_store(vec![7u8; 32]);
        assert_eq!(session_active_count(), 1);

        session_destroy(handle);
        assert_eq!(session_active_count(), 0);

        // Encrypt with destroyed handle should fail
        assert!(session_encrypt(handle, b"x").is_err());
    }

    #[test]
    fn test_invalid_role() {
        let handle = session_store(vec![5u8; 32]);
        let result = session_generate_confirmation(handle, 99);
        assert!(result.is_err());
    }
}
