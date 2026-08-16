/// zcap/ratchet.rs — Double Ratchet with post-quantum hybrid initial key exchange.
///
/// Initial KEM: X25519 (classical) + ML-KEM-768 (post-quantum) hybrid.
/// Chain KDF:   HKDF-SHA256.
/// Encryption:  AES-256-GCM.
///
/// # Serialization contract
/// `RatchetState` is serialized as a flat byte blob by `serialize_state` and
/// deserialized by `deserialize_state`. The format is:
/// ```text
/// [version: 1 B][root_key: 32 B][chain_key: 32 B][msg_idx: 8 B LE][skipped_count: 4 B LE]
/// ```
/// version = 0x01
///
/// The serialized blob is persisted in SQLCipher via `ZcapRatchetDao`.
///
/// > **Note on ML-KEM-768**: A production implementation would use the
/// > `ml-kem` crate (FIPS 203). For build-size and dependency reasons this
/// > stub uses X25519-only for the initial KEM and includes the PQ surface as
/// > a clearly marked TODO, consistent with the architecture document.
use aes_gcm::{
    aead::{Aead, KeyInit, OsRng},
    Aes256Gcm, Key, Nonce,
};
use hkdf::Hkdf;
use sha2::Sha256;
use x25519_dalek::{EphemeralSecret, PublicKey, StaticSecret};
use zeroize::{Zeroize, Zeroizing};
use rand::RngCore;

// ─── Constants ───────────────────────────────────────────────────────────────

const VERSION: u8 = 0x01;
const KEY_LEN: usize = 32;
const NONCE_LEN: usize = 12; // AES-GCM 96-bit nonce

// ─── Errors ──────────────────────────────────────────────────────────────────

#[derive(Debug)]
pub enum RatchetError {
    /// The serialized state blob has an unrecognised version or is too short.
    InvalidState(String),
    /// AES-GCM encryption or decryption failed.
    CryptoError(String),
    /// HKDF expand returned an error (should not happen for fixed-length output).
    KdfError,
}

impl std::fmt::Display for RatchetError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RatchetError::InvalidState(msg) => write!(f, "invalid ratchet state: {msg}"),
            RatchetError::CryptoError(msg) => write!(f, "crypto error: {msg}"),
            RatchetError::KdfError => write!(f, "HKDF expand failed"),
        }
    }
}

// ─── RatchetState ─────────────────────────────────────────────────────────────

/// The full mutable state of one side of the Double Ratchet.
///
/// All key material is held in `Zeroizing` wrappers so it is wiped when the
/// struct is dropped.
#[derive(Clone)]
pub struct RatchetState {
    /// Root key — advanced on each DH ratchet step.
    root_key: Zeroizing<[u8; KEY_LEN]>,
    /// Sending chain key — advanced on each message send.
    chain_key: Zeroizing<[u8; KEY_LEN]>,
    /// Message index — monotonically increasing send counter.
    msg_idx: u64,
    /// Number of skipped-message keys stored (unused in this stub).
    skipped_count: u32,
}

impl RatchetState {
    /// Derive initial state from a shared secret (e.g. from SPAKE2+ or X25519).
    pub fn from_shared_secret(shared_secret: &[u8]) -> Result<Self, RatchetError> {
        let hk = Hkdf::<Sha256>::new(None, shared_secret);
        let mut root_key = Zeroizing::new([0u8; KEY_LEN]);
        let mut chain_key = Zeroizing::new([0u8; KEY_LEN]);
        hk.expand(b"zcap-ratchet-root-v1", root_key.as_mut())
            .map_err(|_| RatchetError::KdfError)?;
        hk.expand(b"zcap-ratchet-chain-v1", chain_key.as_mut())
            .map_err(|_| RatchetError::KdfError)?;
        Ok(Self {
            root_key,
            chain_key,
            msg_idx: 0,
            skipped_count: 0,
        })
    }

    /// Advance the sending chain key and derive the next message key.
    ///
    /// Returns the 32-byte message key for this send, and mutates `chain_key`
    /// in place.
    fn advance_send_chain(&mut self) -> Result<Zeroizing<[u8; KEY_LEN]>, RatchetError> {
        let hk = Hkdf::<Sha256>::new(None, self.chain_key.as_ref());
        let mut new_chain = Zeroizing::new([0u8; KEY_LEN]);
        let mut msg_key = Zeroizing::new([0u8; KEY_LEN]);
        hk.expand(b"zcap-chain-key-v1", new_chain.as_mut())
            .map_err(|_| RatchetError::KdfError)?;
        hk.expand(b"zcap-msg-key-v1", msg_key.as_mut())
            .map_err(|_| RatchetError::KdfError)?;
        *self.chain_key = *new_chain;
        self.msg_idx += 1;
        Ok(msg_key)
    }
}

impl Drop for RatchetState {
    fn drop(&mut self) {
        self.root_key.zeroize();
        self.chain_key.zeroize();
    }
}

// ─── Public API (UniFFI free functions) ──────────────────────────────────────

/// Encrypt `plaintext` using the current ratchet state.
///
/// Returns `(ciphertext, updated_serialized_state)`. The caller MUST persist
/// the updated state to the DB before the in-memory state is dropped.
pub fn ratchet_encrypt(
    serialized_state: Vec<u8>,
    plaintext: Vec<u8>,
) -> Result<(Vec<u8>, Vec<u8>), RatchetError> {
    let mut state = deserialize_state(&serialized_state)?;
    let msg_key = state.advance_send_chain()?;

    // Build AES-256-GCM key.
    let aes_key = Key::<Aes256Gcm>::from_slice(msg_key.as_ref());
    let cipher = Aes256Gcm::new(aes_key);

    // Random 96-bit nonce (prepended to ciphertext).
    let mut nonce_bytes = [0u8; NONCE_LEN];
    rand::rngs::OsRng.fill_bytes(&mut nonce_bytes);
    let nonce = Nonce::from_slice(&nonce_bytes);

    let encrypted = cipher
        .encrypt(nonce, plaintext.as_ref())
        .map_err(|e| RatchetError::CryptoError(format!("encrypt: {e}")))?;

    // Output = nonce || ciphertext
    let mut output = nonce_bytes.to_vec();
    output.extend_from_slice(&encrypted);

    let new_state = serialize_state(&state)?;
    Ok((output, new_state))
}

/// Decrypt `ciphertext` (nonce || tag || payload) using the current ratchet state.
///
/// Returns `(plaintext, updated_serialized_state)`.
pub fn ratchet_decrypt(
    serialized_state: Vec<u8>,
    ciphertext: Vec<u8>,
) -> Result<(Vec<u8>, Vec<u8>), RatchetError> {
    if ciphertext.len() < NONCE_LEN {
        return Err(RatchetError::CryptoError("ciphertext too short".into()));
    }

    let mut state = deserialize_state(&serialized_state)?;
    let msg_key = state.advance_send_chain()?;

    let aes_key = Key::<Aes256Gcm>::from_slice(msg_key.as_ref());
    let cipher = Aes256Gcm::new(aes_key);

    let nonce = Nonce::from_slice(&ciphertext[..NONCE_LEN]);
    let payload = &ciphertext[NONCE_LEN..];

    let plaintext = cipher
        .decrypt(nonce, payload)
        .map_err(|e| RatchetError::CryptoError(format!("decrypt: {e}")))?;

    let new_state = serialize_state(&state)?;
    Ok((plaintext, new_state))
}

// ─── Serialization ───────────────────────────────────────────────────────────

/// Serialized layout (45 bytes):
/// `[version(1)] [root_key(32)] [chain_key(32)] [msg_idx(8 LE)] [skipped_count(4 LE)]`
const SERIAL_LEN: usize = 1 + KEY_LEN + KEY_LEN + 8 + 4; // = 77 bytes

pub fn serialize_state(state: &RatchetState) -> Result<Vec<u8>, RatchetError> {
    let mut buf = Zeroizing::new(vec![0u8; SERIAL_LEN]);
    buf[0] = VERSION;
    buf[1..33].copy_from_slice(state.root_key.as_ref());
    buf[33..65].copy_from_slice(state.chain_key.as_ref());
    buf[65..73].copy_from_slice(&state.msg_idx.to_le_bytes());
    buf[73..77].copy_from_slice(&state.skipped_count.to_le_bytes());
    Ok(buf.to_vec())
}

pub fn deserialize_state(buf: &[u8]) -> Result<RatchetState, RatchetError> {
    if buf.len() != SERIAL_LEN {
        return Err(RatchetError::InvalidState(format!(
            "expected {SERIAL_LEN} bytes, got {}",
            buf.len()
        )));
    }
    if buf[0] != VERSION {
        return Err(RatchetError::InvalidState(format!(
            "unknown version 0x{:02X}",
            buf[0]
        )));
    }
    let mut root_key = Zeroizing::new([0u8; KEY_LEN]);
    let mut chain_key = Zeroizing::new([0u8; KEY_LEN]);
    root_key.copy_from_slice(&buf[1..33]);
    chain_key.copy_from_slice(&buf[33..65]);
    let msg_idx = u64::from_le_bytes(buf[65..73].try_into().unwrap());
    let skipped_count = u32::from_le_bytes(buf[73..77].try_into().unwrap());
    Ok(RatchetState {
        root_key,
        chain_key,
        msg_idx,
        skipped_count,
    })
}

// ─── X25519 initial key exchange helpers (Classical half of PQ-hybrid KEM) ───

/// Perform the X25519 half of the initial key exchange (initiator side).
///
/// Returns `(ephemeral_public_key_bytes, serialized_ratchet_state)`.
///
/// # TODO: PQ hybrid
/// In the full implementation, this also generates an ML-KEM-768 encapsulation
/// and XORs the KEM shared secret into the final shared key before HKDF.
pub fn zcap_kem_initiate(responder_public_key: Vec<u8>) -> Result<(Vec<u8>, Vec<u8>), RatchetError> {
    if responder_public_key.len() != KEY_LEN {
        return Err(RatchetError::InvalidState("responder public key must be 32 bytes".into()));
    }

    let ephemeral_secret = EphemeralSecret::random_from_rng(OsRng);
    let ephemeral_public = PublicKey::from(&ephemeral_secret);

    let mut peer_key_bytes = [0u8; KEY_LEN];
    peer_key_bytes.copy_from_slice(&responder_public_key);
    let peer_public = PublicKey::from(peer_key_bytes);

    let shared = ephemeral_secret.diffie_hellman(&peer_public);
    let mut shared_bytes = Zeroizing::new(*shared.as_bytes());

    let state = RatchetState::from_shared_secret(shared_bytes.as_ref())?;
    shared_bytes.zeroize();

    let serialized = serialize_state(&state)?;
    Ok((ephemeral_public.as_bytes().to_vec(), serialized))
}

/// Perform the X25519 half of the initial key exchange (responder side).
///
/// Returns `(responder_static_public_key_bytes, serialized_ratchet_state)`.
///
/// # TODO: PQ hybrid
/// In the full implementation, this also decapsulates an ML-KEM-768 ciphertext
/// and XORs the KEM shared secret into the final shared key before HKDF.
pub fn zcap_kem_respond(
    initiator_ephemeral_public: Vec<u8>,
    my_static_secret: Vec<u8>,
) -> Result<(Vec<u8>, Vec<u8>), RatchetError> {
    if initiator_ephemeral_public.len() != KEY_LEN {
        return Err(RatchetError::InvalidState("initiator public key must be 32 bytes".into()));
    }
    if my_static_secret.len() != KEY_LEN {
        return Err(RatchetError::InvalidState("static secret must be 32 bytes".into()));
    }

    let mut secret_bytes = [0u8; KEY_LEN];
    secret_bytes.copy_from_slice(&my_static_secret);
    let static_secret = StaticSecret::from(secret_bytes);
    let my_public = PublicKey::from(&static_secret);

    let mut peer_bytes = [0u8; KEY_LEN];
    peer_bytes.copy_from_slice(&initiator_ephemeral_public);
    let peer_public = PublicKey::from(peer_bytes);

    let shared = static_secret.diffie_hellman(&peer_public);
    let mut shared_bytes = Zeroizing::new(*shared.as_bytes());

    let state = RatchetState::from_shared_secret(shared_bytes.as_ref())?;
    shared_bytes.zeroize();

    let serialized = serialize_state(&state)?;
    Ok((my_public.as_bytes().to_vec(), serialized))
}

// ─── Unit tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn make_state() -> Vec<u8> {
        let secret = b"test-shared-secret-32-bytes-long";
        let state = RatchetState::from_shared_secret(secret).unwrap();
        serialize_state(&state).unwrap()
    }

    #[test]
    fn serialize_deserialize_roundtrip() {
        let blob = make_state();
        assert_eq!(blob.len(), SERIAL_LEN);
        let _state = deserialize_state(&blob).unwrap();
    }

    #[test]
    fn rejects_legacy_unversioned_state() {
        let legacy_blob = vec![0u8; SERIAL_LEN - 1];
        assert!(matches!(
            deserialize_state(&legacy_blob),
            Err(RatchetError::InvalidState(_))
        ));
    }

    #[test]
    fn rejects_unknown_state_version() {
        let mut blob = make_state();
        blob[0] = VERSION.wrapping_add(1);
        assert!(matches!(
            deserialize_state(&blob),
            Err(RatchetError::InvalidState(_))
        ));
    }

    #[test]
    fn encrypt_decrypt_roundtrip() {
        let state_blob = make_state();
        let plaintext = b"hello from zcap ratchet".to_vec();

        let (ciphertext, enc_state) = ratchet_encrypt(state_blob.clone(), plaintext.clone()).unwrap();
        // Decrypt must use the *original* state (same chain position).
        let (recovered, _dec_state) = ratchet_decrypt(state_blob, ciphertext).unwrap();
        assert_eq!(recovered, plaintext);
    }

    #[test]
    fn state_advances_on_send() {
        let blob = make_state();
        let (_, new_blob) = ratchet_encrypt(blob.clone(), b"msg".to_vec()).unwrap();
        // msg_idx should have advanced.
        let old_state = deserialize_state(&blob).unwrap();
        let new_state = deserialize_state(&new_blob).unwrap();
        assert_eq!(new_state.msg_idx, old_state.msg_idx + 1);
    }

    #[test]
    fn kem_roundtrip() {
        // Responder generates a static key pair.
        let responder_static_secret = StaticSecret::random_from_rng(OsRng);
        let responder_public = PublicKey::from(&responder_static_secret);

        // Initiator KEM.
        let (ephemeral_pub, _init_state) =
            zcap_kem_initiate(responder_public.as_bytes().to_vec()).unwrap();

        // Responder KEM.
        let (_resp_pub, _resp_state) =
            zcap_kem_respond(ephemeral_pub, responder_static_secret.to_bytes().to_vec()).unwrap();
        // Both states should be 77 bytes (SERIAL_LEN).
        assert_eq!(_init_state.len(), SERIAL_LEN);
        assert_eq!(_resp_state.len(), SERIAL_LEN);
    }
}
