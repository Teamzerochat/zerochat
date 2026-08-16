/// zcap_ffi.rs — UniFFI-compatible wrapper layer for ZCAP result types.
///
/// UniFFI UDL dictionaries map to Rust structs with matching field names.
/// All ZCAP functions that return tuples need wrapper structs and thin
/// adapter functions to bridge the gap.
use crate::zcap::ratchet::{RatchetError, ratchet_encrypt as ratchet_encrypt_inner, ratchet_decrypt as ratchet_decrypt_inner, zcap_kem_initiate as kem_initiate_inner, zcap_kem_respond as kem_respond_inner};
use crate::zcap::sphinx_pad::{pad_to_sphinx_size as pad_inner, unpad_sphinx_payload as unpad_inner, PadError};
use crate::zcap::swarm::zcap_send as zcap_send_inner;
use crate::zcap::lewes::zcap_fetch_messages as fetch_inner;

// ─── Result structs (must match UDL dictionary names exactly) ─────────────────

pub struct ZcapKemResult {
    pub public_key_bytes: Vec<u8>,
    pub serialized_state: Vec<u8>,
}

pub struct ZcapEncryptResult {
    pub ciphertext: Vec<u8>,
    pub updated_state: Vec<u8>,
}

pub struct ZcapDecryptResult {
    pub plaintext: Vec<u8>,
    pub updated_state: Vec<u8>,
}

pub struct ZcapSendResult {
    pub updated_state: Vec<u8>,
    pub succeeded_replicas: u32,
}

pub struct ZcapFetchResult {
    pub decrypted_messages: Vec<Vec<u8>>,
    pub updated_state: Vec<u8>,
}

// ─── Wrapper functions ────────────────────────────────────────────────────────

/// KEM initiation wrapper — maps RatchetError to a String (panics on error for now).
pub fn zcap_kem_initiate(responder_public_key: Vec<u8>) -> ZcapKemResult {
    let (pk, state) = kem_initiate_inner(responder_public_key)
        .expect("zcap_kem_initiate failed");
    ZcapKemResult { public_key_bytes: pk, serialized_state: state }
}

/// KEM respond wrapper.
pub fn zcap_kem_respond(initiator_ephemeral_public: Vec<u8>, my_static_secret: Vec<u8>) -> ZcapKemResult {
    let (pk, state) = kem_respond_inner(initiator_ephemeral_public, my_static_secret)
        .expect("zcap_kem_respond failed");
    ZcapKemResult { public_key_bytes: pk, serialized_state: state }
}

/// Ratchet encrypt wrapper.
pub fn ratchet_encrypt(serialized_state: Vec<u8>, plaintext: Vec<u8>) -> ZcapEncryptResult {
    let (ct, state) = ratchet_encrypt_inner(serialized_state, plaintext)
        .expect("ratchet_encrypt failed");
    ZcapEncryptResult { ciphertext: ct, updated_state: state }
}

/// Ratchet decrypt wrapper.
pub fn ratchet_decrypt(serialized_state: Vec<u8>, ciphertext: Vec<u8>) -> ZcapDecryptResult {
    let (pt, state) = ratchet_decrypt_inner(serialized_state, ciphertext)
        .expect("ratchet_decrypt failed");
    ZcapDecryptResult { plaintext: pt, updated_state: state }
}

/// Sphinx padding wrapper (panics on error — caller should ensure payload fits).
pub fn pad_to_sphinx_size(payload: Vec<u8>) -> Vec<u8> {
    pad_inner(&payload).expect("pad_to_sphinx_size failed")
}

/// Sphinx unpadding wrapper (panics on malformed input — caller must validate).
pub fn unpad_sphinx_payload(packet: Vec<u8>) -> Vec<u8> {
    unpad_inner(&packet).expect("unpad_sphinx_payload failed")
}

/// ZCAP swarm send wrapper.
pub fn zcap_send(
    transport_handle: u64,
    serialized_state: Vec<u8>,
    plaintext: Vec<u8>,
    replicas: Vec<Vec<u8>>,
    gateway_identities: Vec<String>,
) -> ZcapSendResult {
    let (state, count) = zcap_send_inner(transport_handle, serialized_state, plaintext, replicas, gateway_identities)
        .expect("zcap_send failed");
    ZcapSendResult { updated_state: state, succeeded_replicas: count }
}

/// ZCAP offline fetch wrapper.
pub fn zcap_fetch_messages(
    transport_handle: u64,
    serialized_state: Vec<u8>,
    k_shared: Vec<u8>,
    utc_now_secs: u64,
    gateway_identities: Vec<String>,
) -> ZcapFetchResult {
    let (msgs, state) = fetch_inner(transport_handle, serialized_state, k_shared, utc_now_secs, gateway_identities)
        .expect("zcap_fetch_messages failed");
    ZcapFetchResult { decrypted_messages: msgs, updated_state: state }
}
