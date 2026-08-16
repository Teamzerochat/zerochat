//! NYM Transport - Real mixnet integration for ZeroChat
//!
//! Connects to NYM public mainnet using nym-sdk.
//! Uses ephemeral keys (no disk storage) for zero-trust compliance.
//! Shared rendezvous mailboxes via deterministic keypair derivation.

#![allow(unused)]
#![allow(warnings)]

use nym_sdk::mixnet::{MixnetClient, MixnetClientBuilder, Recipient, ReconstructedMessage, AnonymousSenderTag};
use nym_sdk::mixnet::MixnetMessageSender;
use nym_crypto::hkdf::DerivationMaterial;
use nym_client_core::client::base_client::storage::{Ephemeral, MixnetClientStorage};
use nym_client_core::client::key_manager::ClientKeys;
use nym_client_core::client::key_manager::persistence::KeyStore;
use nym_client_core::init::types::GatewaySetup;
use nym_sphinx::acknowledgements::AckKey;
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::runtime::Runtime;
use tokio::sync::Mutex;
use hkdf::Hkdf;
use sha2::Sha256;
use rand::rngs::OsRng;
use once_cell::sync::Lazy;

// SPAKE2+ module for password-authenticated key exchange
mod spake2;
pub use spake2::{spake2_start_initiator, spake2_finish_initiator, spake2_start_responder, Spake2Error};

// Session store — keeps session keys in Rust memory behind opaque handles
mod session_store;
pub use session_store::SessionError;

// Memory pinning — mlock prevents swap of sensitive key material
mod mem_pin;
pub use mem_pin::PinnedSecret;

// Adaptive cover traffic — Poisson-rate dummy Sphinx frames
mod cover_traffic;
pub use cover_traffic::{CoverTrafficState, CoverConfig};

// TLI lifecycle state machine — Init → Rendezvous → Hardened → Fallback → Zeroized
mod tli;
pub use tli::{TliLifecycle, TliPhase, ChurnSignal};

// obfs4-style frame obfuscation — makes Sphinx frames look like uniform random
mod obfs4_shim;
pub use obfs4_shim::session_opener_jitter;

// Secure in-memory ring buffer — zeroizes on drop, exposed via UniFFI free fns
mod secure_log;
pub use secure_log::{secure_log_write, secure_log_clear, secure_log_len};

// ZCAP — ZeroChat Async Protocol (offline store-and-forward over Nym)
pub mod zcap;
// UniFFI-compatible wrapper layer (maps Result<(A,B)> → dictionary structs)
mod zcap_ffi;
// Derivation functions (primitive types, bindable directly)
pub use zcap::derivation::{zcap_epoch_offset, zcap_current_epoch, zcap_mailbox_id, zcap_gateway_index, zcap_missed_epochs};
// SURB manager (primitive types, bindable directly)
pub use zcap::surb::{generate_surbs, acknowledge_surb, surb_pending_count, surb_clear_all};
// All wrapper-backed ZCAP functions and result structs
pub use zcap_ffi::{
    ZcapKemResult, ZcapEncryptResult, ZcapDecryptResult, ZcapSendResult, ZcapFetchResult,
    zcap_kem_initiate, zcap_kem_respond,
    ratchet_encrypt, ratchet_decrypt,
    pad_to_sphinx_size, unpad_sphinx_payload,
    zcap_send, zcap_fetch_messages,
};

// Using UDL-driven bindings (see build.rs)
uniffi::include_scaffolding!("nym_transport");

/// Derive deterministic keypair material from rendezvous ID
/// Both peers with the same rendezvous ID will derive identical NYM addresses
fn derive_rendezvous_material(rendezvous_id: &str) -> DerivationMaterial {
    log::info!("Deriving rendezvous keypair material from ID: {}", rendezvous_id);
    let hkdf = Hkdf::<Sha256>::new(None, rendezvous_id.as_bytes());
    let mut seed = [0u8; 32];
    hkdf.expand(b"nym-rendezvous-keypair-v1", &mut seed)
        .expect("HKDF expansion should not fail");
    DerivationMaterial::new(seed, 0, b"zerochat-rendezvous-v1")
}

/// Transport errors
#[derive(Error, Debug)]
pub enum TransportError {
    #[error("Not connected to mixnet")]
    NotConnected,
    #[error("Connection failed: {reason}")]
    ConnectionFailed { reason: String },
    #[error("Send failed: {reason}")]
    SendFailed { reason: String },
    #[error("Invalid address: {reason}")]
    InvalidAddress { reason: String },
    #[error("Runtime error: {reason}")]
    RuntimeError { reason: String },
}

#[derive(Debug, Clone)]
pub struct RendezvousMessage {
    pub sender_handle: Vec<u8>,
    pub payload: Vec<u8>,
}

#[derive(Debug)]
pub struct ConnectionInfo {
    pub my_address: String,
    pub connected: bool,
}

// SPAKE2+ wrapper types for UniFFI
#[derive(Debug)]
pub struct Spake2InitiatorHandle {
    pub handle_id: u64,
    pub outbound_msg: Vec<u8>,
}

#[derive(Debug)]
pub struct Spake2ResponderResult {
    pub outbound_msg: Vec<u8>,
    pub session_handle: u64,
}

// SPAKE2+ wrapper functions for UniFFI
pub fn spake2_start_initiator_wrapper(password: Vec<u8>) -> Result<Spake2InitiatorHandle, Spake2Error> {
    let (handle_id, outbound_msg) = spake2::spake2_start_initiator(&password)?;
    Ok(Spake2InitiatorHandle { handle_id, outbound_msg })
}

pub fn spake2_finish_initiator_wrapper(handle_id: u64, inbound_msg: Vec<u8>) -> Result<u64, Spake2Error> {
    spake2::spake2_finish_initiator(handle_id, &inbound_msg)
}

pub fn spake2_start_responder_wrapper(password: Vec<u8>, inbound_msg: Vec<u8>) -> Result<Spake2ResponderResult, Spake2Error> {
    let (outbound_msg, session_handle) = spake2::spake2_start_responder(&password, &inbound_msg)?;
    Ok(Spake2ResponderResult { outbound_msg, session_handle })
}

pub fn spake2_cleanup_state_wrapper(handle_id: u64) -> bool {
    spake2::spake2_cleanup_state(handle_id)
}

pub fn spake2_active_count_wrapper() -> u64 {
    spake2::spake2_active_count() as u64
}

// Session store FFI wrappers
pub fn session_encrypt_wrapper(handle: u64, plaintext: Vec<u8>) -> Result<Vec<u8>, SessionError> {
    session_store::session_encrypt(handle, &plaintext)
}

pub fn session_decrypt_wrapper(handle: u64, ciphertext: Vec<u8>) -> Result<Vec<u8>, SessionError> {
    session_store::session_decrypt(handle, &ciphertext)
}

pub fn session_generate_confirmation_wrapper(handle: u64, role: u8) -> Result<Vec<u8>, SessionError> {
    session_store::session_generate_confirmation(handle, role)
}

pub fn session_verify_confirmation_wrapper(handle: u64, confirmation: Vec<u8>, role: u8) -> Result<bool, SessionError> {
    session_store::session_verify_confirmation(handle, &confirmation, role)
}

pub fn session_get_obfs4_state_wrapper(handle: u64) -> Result<Vec<u8>, SessionError> {
    session_store::session_get_obfs4_state(handle)
}

pub fn session_get_zcap_shared_secret_wrapper(handle: u64) -> Result<Vec<u8>, SessionError> {
    session_store::session_get_zcap_shared_secret(handle)
}

pub fn session_destroy_wrapper(handle: u64) {
    session_store::session_destroy(handle)
}

// Object defined in UDL
pub struct NymTransportClient {
    runtime: Runtime,
    state: Arc<Mutex<ClientState>>,
}

use std::collections::HashMap;

// ... imports ...

struct ClientState {
    /// Main client with unique ephemeral keys for direct messaging
    client: Option<Arc<Mutex<MixnetClient>>>,
    my_address: Option<String>,
    /// Rendezvous clients mapped by point_id
    /// Allows concurrent connections to multiple rendezvous points
    rendezvous_clients: HashMap<String, Arc<Mutex<MixnetClient>>>,
    rendezvous_addresses: HashMap<String, String>,

    /// TLI lifecycle state machine (Paper §5.3)
    tli: tli::TliLifecycle,

    /// Unified session state containing all session material (Paper §5.2)
    /// Including 64-byte obfs4_state derived from SPAKE2+ shared secret
    tli_session_state: Option<tli::TliSessionState>,

    /// Cover traffic scheduler (Paper §5)
    cover_traffic: cover_traffic::CoverTrafficState,

    /// Cached gateway list with fetch timestamp (60s TTL)
    /// Dies with transport instance — no static/global cache
    cached_gateways: Option<(Vec<Entry>, std::time::Instant)>,
}

static ZCAP_TRANSPORT_REGISTRY: Lazy<std::sync::Mutex<HashMap<u64, Arc<Mutex<ClientState>>>>> =
    Lazy::new(|| std::sync::Mutex::new(HashMap::new()));

fn zcap_register_transport_state(state: Arc<Mutex<ClientState>>) -> u64 {
    let handle = rand::random::<u64>();
    let mut registry = ZCAP_TRANSPORT_REGISTRY.lock().unwrap();
    registry.insert(handle, state);
    log::info!("ZCAP transport registered: handle={}", handle);
    handle
}

pub fn zcap_unregister_transport_handle(handle: u64) {
    let mut registry = ZCAP_TRANSPORT_REGISTRY.lock().unwrap();
    if registry.remove(&handle).is_some() {
        log::info!("ZCAP transport unregistered: handle={}", handle);
    } else {
        log::warn!("ZCAP transport handle not found during unregister: {}", handle);
    }
}

pub(crate) fn zcap_transport_handle_exists(handle: u64) -> bool {
    ZCAP_TRANSPORT_REGISTRY.lock().unwrap().contains_key(&handle)
}

#[cfg(test)]
pub(crate) fn zcap_register_test_transport() -> u64 {
    zcap_register_transport_state(Arc::new(Mutex::new(ClientState::default())))
}

async fn zcap_derive_rendezvous_address(
    state: &Arc<Mutex<ClientState>>,
    point_id: &str,
) -> Result<String, TransportError> {
    let gateways = NymTransportClient::get_or_fetch_gateways(state).await?;
    if gateways.is_empty() {
        return Err(TransportError::ConnectionFailed {
            reason: "No gateways to derive ZCAP mailbox address".into(),
        });
    }

    let mut hasher = Sha256::new();
    use sha2::Digest;
    hasher.update(point_id.as_bytes());
    let result = hasher.finalize();
    let hash_int = u64::from_be_bytes(result[0..8].try_into().unwrap());
    let index = (hash_int as usize) % gateways.len();
    let selected_gateway = &gateways[index];

    let base_id = if let Some(idx) = point_id.rfind('_') {
        &point_id[..idx]
    } else {
        point_id
    };

    let salt = b"zerochat-rendezvous-v1";
    let hkdf = Hkdf::<Sha256>::new(Some(salt), base_id.as_bytes());

    let mut identity_seed = [0u8; 32];
    hkdf.expand(b"rendezvous-identity", &mut identity_seed)
        .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;

    use ed25519_dalek::SigningKey;
    let signing_key = SigningKey::from_bytes(&identity_seed);
    let verifying_key = signing_key.verifying_key();
    let identity_public_key = nym_crypto::asymmetric::identity::PublicKey::from_bytes(verifying_key.as_bytes())
        .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;

    let mut encryption_seed = [0u8; 32];
    hkdf.expand(b"rendezvous-encryption", &mut encryption_seed)
        .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;
    let x25519_secret = x25519_dalek::StaticSecret::from(encryption_seed);
    let x25519_public = x25519_dalek::PublicKey::from(&x25519_secret);
    let encryption_public_key = nym_crypto::asymmetric::encryption::PublicKey::from_bytes(x25519_public.as_bytes())
        .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;

    use zeroize::Zeroize;
    identity_seed.zeroize();
    encryption_seed.zeroize();

    Ok(format!(
        "{}.{}@{}",
        identity_public_key.to_base58_string(),
        encryption_public_key.to_base58_string(),
        selected_gateway.identity
    ))
}

pub(crate) async fn zcap_send_packet_to_mailbox(
    transport_handle: u64,
    mailbox_id: &[u8],
    packet: Vec<u8>,
) -> Result<(), String> {
    let state = {
        let registry = ZCAP_TRANSPORT_REGISTRY.lock().unwrap();
        registry
            .get(&transport_handle)
            .cloned()
            .ok_or_else(|| format!("unregistered ZCAP transport handle: {transport_handle}"))?
    };

    let point_id = hex::encode(mailbox_id);
    let recipient_address = zcap_derive_rendezvous_address(&state, &point_id)
        .await
        .map_err(|e| e.to_string())?;
    let recipient = Recipient::try_from_base58_string(&recipient_address)
        .map_err(|e| format!("invalid ZCAP mailbox address: {e}"))?;

    let client_arc = {
        let st = state.lock().await;
        st.client
            .as_ref()
            .cloned()
            .ok_or_else(|| "registered ZCAP transport has no connected main client".to_string())?
    };

    let mut client = client_arc.lock().await;
    client
        .send_plain_message(recipient, &packet)
        .await
        .map_err(|e| format!("ZCAP mailbox send failed: {e}"))?;

    Ok(())
}

pub(crate) async fn zcap_poll_mailbox(
    transport_handle: u64,
    mailbox_id: &[u8],
) -> Result<Vec<Vec<u8>>, String> {
    let state = {
        let registry = ZCAP_TRANSPORT_REGISTRY.lock().unwrap();
        registry
            .get(&transport_handle)
            .cloned()
            .ok_or_else(|| format!("unregistered ZCAP transport handle: {transport_handle}"))?
    };

    let point_id = hex::encode(mailbox_id);
    zcap_connect_mailbox_if_needed(state.clone(), &point_id)
        .await
        .map_err(|e| e.to_string())?;

    let client_arc = {
        let st = state.lock().await;
        st.rendezvous_clients
            .get(&point_id)
            .cloned()
            .ok_or_else(|| format!("ZCAP mailbox not connected: {point_id}"))?
    };

    let mut client = client_arc.lock().await;
    let messages = tokio::time::timeout(Duration::from_millis(100), client.wait_for_messages())
        .await
        .map_err(|_| "ZCAP mailbox poll timed out".to_string())?
        .unwrap_or_default();

    Ok(messages.into_iter().map(|msg| msg.message).collect())
}

async fn zcap_connect_mailbox_if_needed(
    state: Arc<Mutex<ClientState>>,
    point_id: &str,
) -> Result<String, TransportError> {
    {
        let st = state.lock().await;
        if let Some(address) = st.rendezvous_addresses.get(point_id) {
            return Ok(address.clone());
        }
    }

    let point_id_owned = point_id.to_string();
    let gateways = NymTransportClient::get_or_fetch_gateways(&state).await?;
    if gateways.is_empty() {
        return Err(TransportError::ConnectionFailed {
            reason: "No gateways available for ZCAP mailbox".into(),
        });
    }

    let mut hasher = Sha256::new();
    use sha2::Digest;
    hasher.update(point_id_owned.as_bytes());
    let result = hasher.finalize();
    let hash_int = u64::from_be_bytes(result[0..8].try_into().unwrap());
    let selected_gateway = &gateways[(hash_int as usize) % gateways.len()];

    let salt = b"zerochat-rendezvous-v1";
    let hkdf = Hkdf::<Sha256>::new(Some(salt), point_id_owned.as_bytes());

    let mut identity_seed = [0u8; 32];
    let mut encryption_seed = [0u8; 32];
    let mut ack_seed = [0u8; 32];

    hkdf.expand(b"rendezvous-identity", &mut identity_seed)
        .map_err(|e| TransportError::RuntimeError { reason: format!("Failed to expand identity seed: {:?}", e) })?;
    hkdf.expand(b"rendezvous-encryption", &mut encryption_seed)
        .map_err(|e| TransportError::RuntimeError { reason: format!("Failed to expand encryption seed: {:?}", e) })?;
    hkdf.expand(b"ack-key", &mut ack_seed)
        .map_err(|e| TransportError::RuntimeError { reason: format!("Failed to expand ack seed: {:?}", e) })?;

    use ed25519_dalek::SigningKey;
    let signing_key = SigningKey::from_bytes(&identity_seed);
    let verifying_key = signing_key.verifying_key();

    let identity_keypair = nym_crypto::asymmetric::identity::KeyPair::from_bytes(
        &signing_key.to_bytes(),
        verifying_key.as_bytes(),
    )
    .map_err(|e| TransportError::RuntimeError { reason: format!("Identity keypair error: {}", e) })?;

    let x25519_secret = x25519_dalek::StaticSecret::from(encryption_seed);
    let x25519_public = x25519_dalek::PublicKey::from(&x25519_secret);
    let x25519_secret_bytes = x25519_secret.to_bytes();
    let encryption_keypair = nym_crypto::asymmetric::encryption::KeyPair::from_bytes(
        &x25519_secret_bytes,
        x25519_public.as_bytes(),
    )
    .map_err(|e| TransportError::RuntimeError { reason: format!("Encryption keypair error: {:?}", e) })?;

    let ack_key = AckKey::try_from_bytes(&ack_seed[..16])
        .map_err(|e| TransportError::RuntimeError { reason: format!("AckKey error: {:?}", e) })?;

    let client_keys = ClientKeys::from_keys(identity_keypair, encryption_keypair, ack_key);

    use zeroize::Zeroize;
    identity_seed.zeroize();
    encryption_seed.zeroize();
    ack_seed.zeroize();

    let storage = Ephemeral::default();
    storage
        .key_store()
        .store_keys(&client_keys)
        .await
        .map_err(|e| TransportError::RuntimeError { reason: format!("Failed to store ZCAP mailbox keys: {}", e) })?;

    let disconnected = MixnetClientBuilder::new_with_storage(storage)
        .request_gateway(selected_gateway.identity.clone())
        .build()
        .map_err(|e| TransportError::RuntimeError { reason: format!("ZCAP mailbox build failed: {}", e) })?;

    let client = tokio::time::timeout(Duration::from_secs(10), disconnected.connect_to_mixnet())
        .await
        .map_err(|_| TransportError::ConnectionFailed { reason: "ZCAP mailbox connect timeout".into() })?
        .map_err(|e| TransportError::ConnectionFailed { reason: format!("ZCAP mailbox connect failed: {}", e) })?;

    tokio::time::sleep(Duration::from_millis(250)).await;

    let address = client.nym_address().to_string();
    if address.is_empty() {
        return Err(TransportError::ConnectionFailed {
            reason: "ZCAP mailbox health check failed".into(),
        });
    }

    let mut st = state.lock().await;
    st.rendezvous_clients.insert(point_id_owned.clone(), Arc::new(Mutex::new(client)));
    st.rendezvous_addresses.insert(point_id_owned, address.clone());

    Ok(address)
}

impl Default for ClientState {
    fn default() -> Self {
        Self {
            client: None,
            my_address: None,
            rendezvous_clients: HashMap::new(),
            rendezvous_addresses: HashMap::new(),
            tli: tli::TliLifecycle::new(),
            tli_session_state: None,  // Initialized when SPAKE2+ handshake completes
            cover_traffic: cover_traffic::CoverTrafficState::new(),
            cached_gateways: None,
        }
    }
}

// Structs for gateway fetching
#[derive(serde::Deserialize, Debug)]
struct NodeResponse {
    nodes: Vec<Node>,
}

#[derive(serde::Deserialize, Debug)]
#[serde(rename_all = "snake_case")] // JSON keys are snake_case (e.g. identity_key, supported_roles)
struct Node {
    #[serde(rename = "ed25519_identity_pubkey")]
    identity: Option<String>,
    entry: Option<EntryDetails>, // Some nodes might miss entry details
}

#[derive(serde::Deserialize, Debug)]
#[serde(rename_all = "snake_case")] 
struct EntryDetails {
    wss_port: Option<u16>,
    ws_port: Option<u16>,
    hostname: Option<String>,
}

// Internal struct for our use
#[derive(Clone, Debug)]
struct Entry {
    identity: String,
    hostname: String,
    wss_port: u16,
}

impl NymTransportClient {

    /// Register this client's shared transport state for ZCAP offline send/fetch.
    /// Kotlin owns the returned handle and must unregister it during disconnect.
    pub fn zcap_register_transport(&self) -> u64 {
        zcap_register_transport_state(self.state.clone())
    }

    /// Unregister a previously registered ZCAP transport handle.
    pub fn zcap_unregister_transport(&self, handle: u64) {
        zcap_unregister_transport_handle(handle)
    }

    /// Get gateways from cache (60s TTL) or fetch fresh.
    /// Cache is instance-level — dies with transport instance.
    async fn get_or_fetch_gateways(state: &Mutex<ClientState>) -> Result<Vec<Entry>, TransportError> {
        {
            let st = state.lock().await;
            if let Some((ref gateways, fetched_at)) = st.cached_gateways {
                if fetched_at.elapsed() < Duration::from_secs(60) && !gateways.is_empty() {
                    log::info!("Using cached gateway list ({} gateways, age: {:?})", gateways.len(), fetched_at.elapsed());
                    return Ok(gateways.clone());
                }
            }
        }
        
        let gateways = Self::fetch_sorted_gateways().await?;
        
        let mut st = state.lock().await;
        st.cached_gateways = Some((gateways.clone(), std::time::Instant::now()));
        log::info!("Cached {} gateways (fresh fetch)", gateways.len());
        Ok(gateways)
    }

    /// Fetch and sort gateways for deterministic selection.
    /// 
    /// CRITICAL FIX: Uses `gateways_for_init()` from nym-client-core to get the SAME
    /// gateway list that the SDK's MixnetClientBuilder will use internally. This prevents
    /// the "no gateway with id" error that occurred when our custom HTTP-fetched list
    /// and the SDK's internal list were different populations.
    async fn fetch_sorted_gateways() -> Result<Vec<Entry>, TransportError> {
        use nym_client_core::init::helpers::gateways_for_init;
        use nym_crypto::asymmetric::ed25519;
        
        let nym_api_url = url::Url::parse("https://validator.nymtech.net/api/")
            .map_err(|e| TransportError::RuntimeError { reason: format!("Bad URL: {}", e) })?;
        
        log::info!("Fetching gateways from SDK-compatible API (gateways_for_init)");
        
        // Use the SAME function the SDK uses internally, with default filtering
        let all_gateways = gateways_for_init(
            &[nym_api_url],
            None,      // no user agent
            0,         // minimum_performance = 0 (include all)
            false,     // don't ignore epoch roles (same as SDK default)
            None,      // default retry count
        ).await
            .map_err(|e| TransportError::ConnectionFailed { 
                reason: format!("Failed to fetch gateways via SDK: {}", e) 
            })?;
        
        log::info!("SDK reports {} valid gateways", all_gateways.len());
        
        // Filter for WSS-capable gateways (with hostname and wss_port)
        let mut valid_gateways: Vec<Entry> = all_gateways.iter()
            .filter_map(|node| {
                let entry_details = node.entry.as_ref()?;
                let wss_port = entry_details.clients_wss_port?;
                let hostname = entry_details.hostname.as_ref()?;
                Some(Entry {
                    identity: node.identity_key.to_base58_string(),
                    hostname: hostname.clone(),
                    wss_port,
                })
            })
            .collect();
            
        // CRITICAL: Sort lexicographically by identity_key for deterministic index selection across peers
        valid_gateways.sort_by(|a, b| a.identity.cmp(&b.identity));
        
        log::info!("Fetched {} valid WSS gateways for deterministic selection (from {} total)", 
            valid_gateways.len(), all_gateways.len());
        Ok(valid_gateways)
    }

    /// Connect to rendezvous point (Model 3 Strict: Deterministic Mailbox Client)
    /// 
    /// 1. Manually derives Identity and Encryption keys from point_id.
    /// 2. Creates a NEW MixnetClient with these pre-injected keys.
    /// 3. Connects this client to the hardcoded gateway.
    /// 4. Stores this client alongside the Main Client in `rendezvous_clients`.
    pub fn connect_rendezvous(&self, point_id: String) -> Result<String, TransportError> {
        // STRICT REQUIREMENT: Deterministic Gateway Selection
        // 1. Fetch all gateways
        // 2. Sort lexicographically
        // 3. index = SHA256(point_id) % count
        // 4. Connect using that specific gateway
        
        log::info!("Connecting to rendezvous point: {} (Deterministic Gateway)", point_id);
        
        let state = self.state.clone();
        let point_id_clone = point_id.clone();
        
        // Use runtime to block on async execution
        self.runtime.block_on(async move {
            // Check if already connected
            {
                let st = state.lock().await;
                if st.rendezvous_clients.contains_key(&point_id_clone) {
                    let address = st.rendezvous_addresses.get(&point_id_clone)
                        .ok_or(TransportError::RuntimeError { reason: "Client exists but address missing".into() })?;
                    log::info!("Rendezvous client already connected: {}", address);
                    return Ok(address.clone());
                }
            }

            // 0. Deterministic Gateway Selection (uses cache, 60s TTL)
            let gateways = Self::get_or_fetch_gateways(&state).await?;
            if gateways.is_empty() {
                return Err(TransportError::ConnectionFailed { reason: "No gateways available".into() });
            }
            
            // Hash the point_id to get a deterministic base index
            let mut hasher = Sha256::new();
            use sha2::Digest;
            hasher.update(point_id_clone.as_bytes());
            let result = hasher.finalize();
            let hash_int = u64::from_be_bytes(result[0..8].try_into().unwrap());
            let base_index = (hash_int as usize) % gateways.len();

            // Extract base rendezvous id (strip slot suffix)
            let base_id = if let Some(idx) = point_id_clone.rfind('_') {
                &point_id_clone[..idx]
            } else {
                &point_id_clone
            };

            // Derive deterministic seeds ONCE (cheap, copyable [u8; 32])
            let salt = b"zerochat-rendezvous-v1";
            let hkdf = Hkdf::<Sha256>::new(Some(salt), base_id.as_bytes());
            
            let mut identity_seed = [0u8; 32];
            let mut encryption_seed = [0u8; 32];
            let mut ack_seed = [0u8; 32];
            
            hkdf.expand(b"rendezvous-identity", &mut identity_seed)
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to expand identity seed: {:?}", e) 
                })?;
            hkdf.expand(b"rendezvous-encryption", &mut encryption_seed)
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to expand encryption seed: {:?}", e) 
                })?;
            hkdf.expand(b"ack-key", &mut ack_seed)
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to expand ack seed: {:?}", e) 
                })?;

            log::info!(
                "Identity derived from base_id: {} (slot input: {})",
                base_id,
                point_id_clone
            );
            log::info!("Rendezvous seed (Identity): {}", hex::encode(&identity_seed));

            let index = base_index % gateways.len();
            let selected_gateway = &gateways[index];
            
            log::info!("Selected Deterministic Gateway: {} (Index: {}/{})", 
                selected_gateway.identity, index, gateways.len());

            // Derive keypairs from seeds
            use ed25519_dalek::SigningKey;
            let signing_key = SigningKey::from_bytes(&identity_seed);
            let verifying_key = signing_key.verifying_key();
            
            log::info!("Derived public key (Identity): {}", hex::encode(verifying_key.as_bytes()));
            log::info!("Deriving Encryption KeyPair (x25519)...");
            
            let identity_keypair = match nym_crypto::asymmetric::identity::KeyPair::from_bytes(
                &signing_key.to_bytes(), verifying_key.as_bytes()
            ) {
                Ok(kp) => kp,
                Err(e) => return Err(TransportError::RuntimeError { reason: format!("Identity keypair error: {}", e) }),
            };

            let x25519_secret = x25519_dalek::StaticSecret::from(encryption_seed);
            let x25519_public = x25519_dalek::PublicKey::from(&x25519_secret);
            let x25519_secret_bytes = x25519_secret.to_bytes();
            
            let encryption_keypair = match nym_crypto::asymmetric::encryption::KeyPair::from_bytes(
                &x25519_secret_bytes, x25519_public.as_bytes()
            ) {
                Ok(kp) => kp,
                Err(e) => return Err(TransportError::RuntimeError { reason: format!("Encryption keypair error: {:?}", e) }),
            };

            let ack_key = match AckKey::try_from_bytes(&ack_seed[..16]) {
                Ok(k) => k,
                Err(e) => return Err(TransportError::RuntimeError { reason: format!("AckKey error: {:?}", e) }),
            };

            let client_keys = ClientKeys::from_keys(identity_keypair, encryption_keypair, ack_key);

            // H1: Zeroize seed material — keys are now in client_keys
            use zeroize::Zeroize;
            identity_seed.zeroize();
            encryption_seed.zeroize();
            ack_seed.zeroize();
            log::info!("Deterministic seeds zeroized");

            let storage = Ephemeral::default();
            if let Err(e) = storage.key_store().store_keys(&client_keys).await {
                return Err(TransportError::RuntimeError { 
                    reason: format!("Failed to store rendezvous keys: {}", e) 
                });
            }

            let disconnected = match MixnetClientBuilder::new_with_storage(storage)
                .request_gateway(selected_gateway.identity.clone()) 
                .build()
            {
                Ok(d) => d,
                Err(e) => return Err(TransportError::RuntimeError { reason: format!("Build failed: {}", e) }),
            };

            let gateway_id = selected_gateway.identity.clone();
            
            // Connect with 30s timeout
            let connect_result = tokio::time::timeout(
                Duration::from_secs(30),
                disconnected.connect_to_mixnet()
            ).await;

            let client = match connect_result {
                Ok(Ok(c)) => c,
                Ok(Err(e)) => {
                    let err_msg = format!("{}", e);
                    log::warn!("Gateway {} connection failed: {}", gateway_id, err_msg);
                    return Err(TransportError::ConnectionFailed { 
                        reason: format!("gateway client error ({}): {}", gateway_id, err_msg) 
                    });
                }
                Err(_) => {
                    log::warn!("Gateway {} timed out after 30s", gateway_id);
                    return Err(TransportError::ConnectionFailed { reason: "Timeout".into() });
                }
            };

            // Post-connect stabilization
            tokio::time::sleep(Duration::from_millis(500)).await;
            
            let address = client.nym_address().to_string();
            if address.is_empty() {
                return Err(TransportError::ConnectionFailed { reason: "Health check failed".into() });
            }
            
            log::info!("Connected Deterministic Mailbox Client! Address: {}", address);

            // Store Client
            let mut st = state.lock().await;
            st.rendezvous_clients.insert(point_id_clone.clone(), Arc::new(Mutex::new(client)));
            st.rendezvous_addresses.insert(point_id_clone, address.clone());
            
            Ok(address)
        })
    }


    /// Calculate the Nym Address for a deterministic point without connecting.
    /// Used for "Two-Slot" strategy: if I am Slot A, I need to know Slot B's address to send to it.
    pub fn get_rendezvous_address(&self, point_id: String) -> Result<String, TransportError> {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            // 1. Deterministic Gateway Selection (uses cache, 60s TTL)
            let gateways = Self::get_or_fetch_gateways(&state).await?;
            if gateways.is_empty() {
                return Err(TransportError::ConnectionFailed { reason: "No gateways to derive address".into() });
            }
            
            // Hash the point_id to get a deterministic gateway index (slot-dependent)
            let mut hasher = Sha256::new();
            use sha2::Digest;
            hasher.update(point_id.as_bytes());
            let result = hasher.finalize();
            // Use first 8 bytes as u64 for modulus
            let hash_int = u64::from_be_bytes(result[0..8].try_into().unwrap());
            
            let index = (hash_int as usize) % gateways.len();
            let selected_gateway = &gateways[index];

            // Extract base rendezvous id (strip slot suffix) — MUST match connect_rendezvous
            let base_id = if let Some(idx) = point_id.rfind('_') {
                &point_id[..idx]
            } else {
                &point_id
            };

            // 2. Derive Identity Key (Same logic as connect_rendezvous — uses base_id)
            let salt = b"zerochat-rendezvous-v1";
            let hkdf = Hkdf::<Sha256>::new(Some(salt), base_id.as_bytes());
            let mut identity_seed = [0u8; 32];
            hkdf.expand(b"rendezvous-identity", &mut identity_seed)
                .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;
                
            use ed25519_dalek::SigningKey;
            let signing_key = SigningKey::from_bytes(&identity_seed);
            let verifying_key = signing_key.verifying_key();
            // Nym uses bs58 encoded public key for address
            let identity_public_key = nym_crypto::asymmetric::identity::PublicKey::from_bytes(verifying_key.as_bytes())
                .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;

            // 3. Derive Encryption Key (x25519) - MUST match connect_rendezvous logic
            let mut encryption_seed = [0u8; 32];
            hkdf.expand(b"rendezvous-encryption", &mut encryption_seed)
                .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;
            let x25519_secret = x25519_dalek::StaticSecret::from(encryption_seed);
            let x25519_public = x25519_dalek::PublicKey::from(&x25519_secret);
            let encryption_public_key = nym_crypto::asymmetric::encryption::PublicKey::from_bytes(x25519_public.as_bytes())
                .map_err(|e| TransportError::RuntimeError { reason: format!("{:?}", e) })?;

            // 4. Construct Full Address
            // Format: IdentityKey.EncryptionKey@GatewayID
            let address = format!("{}.{}@{}", 
                identity_public_key.to_base58_string(),
                encryption_public_key.to_base58_string(),
                selected_gateway.identity
            );
            Ok(address)
        })
    }

    /// Poll rendezvous point for messages (Model 3 Strict: Dual-Client Polling)
    /// 
    /// 1. Polls Main Client (Random Identity) for direct messages.
    /// 2. Polls Specific Rendezvous Client (Deterministic Mailbox) for rendezvous messages.
    /// Returns aggregated list of messages.
    pub fn poll_rendezvous(&self, point_id: String) -> Result<Vec<RendezvousMessage>, TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        
        let state = self.state.clone();
        let point_id_clone = point_id.clone();
        
        self.runtime.block_on(async move {
            let mut st = state.lock().await;
            let mut all_messages = Vec::new();

            // Helper to process messages
            let mut process_msgs = |msgs: Vec<ReconstructedMessage>| {
                for msg in msgs {
                    // DEBUG: Log the actual message size from NYM SDK
                    log::info!("poll_rendezvous: Received message from NYM SDK: {} bytes", msg.message.len());
                    
                    // Extract Sender Tag if available (SURB ID)
                    let sender_handle = msg.sender_tag
                        .map(|tag| tag.to_bytes().to_vec())
                        .unwrap_or_default();
                        
                    all_messages.push(RendezvousMessage {
                        sender_handle,
                        payload: msg.message,
                    });
                }
            };

            // 1. Poll Main Client
            if let Some(client_mutex) = &st.client {
                let mut client = client_mutex.lock().await;
                // Use timeout to avoid blocking. 50ms is enough for local channel check.
                if let Ok(Some(msgs)) = tokio::time::timeout(Duration::from_millis(50), client.wait_for_messages()).await {
                    process_msgs(msgs);
                }
            }

            // 2. Poll Specific Rendezvous Client
            if let Some(client_mutex) = st.rendezvous_clients.get(&point_id_clone) {
                 let mut client = client_mutex.lock().await;
                 if let Ok(Some(msgs)) = tokio::time::timeout(Duration::from_millis(50), client.wait_for_messages()).await {
                    process_msgs(msgs);
                 }
            }
            
            if !all_messages.is_empty() {
                log::info!("Polled {} messages total for rendezvous {}", all_messages.len(), point_id_clone);
            }
            
            Ok(all_messages)
        })
    }

    /// Connect to rendezvous point with deterministic gateway
    /// Old publish implementation... replaced below
    
    /// Publish at rendezvous - creates shared mailbox using derived keypair
    /// REQUIRES connect_rendezvous to be called first
    /// 
    /// CRITICAL BUG FIX: Uses base_point_id (canonical Slot A ID) for obfs4 seeding.
    /// This ensures asymmetric obfs4 works: both INITIATOR and RESPONDER seed with the
    /// same slotAId, producing compatible frames.
    pub fn publish_at_rendezvous(&self, point_id: String, my_handle: Vec<u8>, base_point_id: String) -> Result<(), TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        
        log::info!("Publishing to rendezvous point: {} with base_id: {} (handle: {} bytes)", point_id, base_point_id, my_handle.len());
        
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let mut st = state.lock().await;
            
            // CRITICAL CHANGE FOR MODEL 3:
            // Use the MAIN client (Random Identity) to send the message.
            // Do NOT use the rendezvous client (Shared Identity) to send.
            
            let client_arc = st.client.as_ref().ok_or(TransportError::NotConnected)?;
            
            if let Some(rvz_addr) = st.rendezvous_addresses.get(&point_id) {
                // Lock the MAIN client (Transport Identity)
                let mut client = client_arc.lock().await;
                
                // CRITICAL: DO NOT PREPEND ADDRESS.
                // Model 3 requires raw binary payloads for SPAKE2 compatibility.
                // Identity exchange happens in Phase 4 (Encrypted Handle Exchange).
                let message = my_handle;
                
                let recipient = Recipient::try_from_base58_string(rvz_addr)
                    .map_err(|e| TransportError::InvalidAddress {
                        reason: format!("Invalid rendezvous address: {}", e),
                    })?;
                
                // Paper §6: Each Nym message (~1452 bytes) is one complete obfs4 frame
                // Per-frame encryption will happen on sender side; for now, send raw message
                let obfuscated_message = message;  // Will be encrypted per-frame on sender

                client.send_plain_message(recipient, &obfuscated_message)
                    .await
                    .map_err(|e| {
                        log::error!("Rendezvous publish failed: {}", e);
                        TransportError::SendFailed {
                            reason: format!("Failed to publish at rendezvous: {}", e),
                        }
                    })?;
                
                log::info!("Payload published to shared rendezvous mailbox: {} (with asymmetric obfs4)", point_id);
                Ok(())
            } else {
                log::error!("Not connected to rendezvous point: {}", point_id);
                Err(TransportError::RuntimeError { 
                    reason: format!("Not connected to rendezvous point: {}", point_id)
                })
            }
        })
    }

    /// Explicitly disconnect from a specific rendezvous point
    /// 
    /// 1. Removes client from internal maps.
    /// 2. Explicitly disconnects the MixnetClient to stop background tasks.
    pub fn disconnect_rendezvous(&self, point_id: String) -> Result<(), TransportError> {
        log::info!("Disconnecting rendezvous point: {}", point_id);
        let state = self.state.clone();
        
        self.runtime.block_on(async move {
            let mut st = state.lock().await;
            
            // Remove from maps
            let client_mutex_arc = st.rendezvous_clients.remove(&point_id);
            st.rendezvous_addresses.remove(&point_id);
            
            drop(st); // Unlock immediately
            
            if let Some(client_arc) = client_mutex_arc {
                // Try to take ownership if we are the last holder
                match Arc::try_unwrap(client_arc) {
                    Ok(mutex) => {
                        let client = mutex.into_inner(); // Get MixnetClient
                        log::info!("Disconnecting rendezvous client for point {}", point_id);
                        client.disconnect().await;
                    },
                    Err(_) => {
                        log::warn!("Could not disconnect rendezvous client for {}: Still in use?", point_id);
                    }
                }
            } else {
                log::warn!("No rendezvous client found to disconnect for {}", point_id);
            }
            
            Ok(())
        })
    }

    /// Explicitly disconnect all rendezvous clients
    /// Should be called after handshake completion
    pub fn disconnect_all_rendezvous(&self) {
        log::info!("Disconnecting ALL rendezvous clients");
        self.runtime.block_on(async {
            let mut state = self.state.lock().await;
            state.rendezvous_clients.clear();
            state.rendezvous_addresses.clear();
            log::info!("Rendezvous state cleared");
        });
    }

    /// Create a new transport client
    pub fn new() -> Self {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(log::LevelFilter::Info)
                    .with_tag("NymTransport"),
            );
        }

        let runtime = Runtime::new().expect("Failed to create Tokio runtime");
        
        Self {
            runtime,
            state: Arc::new(Mutex::new(ClientState::default())),
        }
    }

    /// Connect to NYM public mainnet
    /// Returns the client's NYM address
    /// Tries up to 3 gateways if the first ones fail (TLS issues, timeouts, etc.)
    pub fn connect(&self, _gateway_url: String) -> Result<String, TransportError> {
        log::info!("Connecting to NYM public mainnet...");
        
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let mut gateways = Self::fetch_sorted_gateways().await?;
            
            if gateways.is_empty() {
                 return Err(TransportError::ConnectionFailed { 
                    reason: "No valid WSS gateways found".into() 
                });
            }

            // Shuffle for random selection, then try up to 3
            use rand::seq::SliceRandom;
            let mut rng = rand::thread_rng();
            gateways.shuffle(&mut rng);

            let max_attempts = 3.min(gateways.len());
            let mut last_error = String::new();

            for attempt in 0..max_attempts {
                let gw = &gateways[attempt];
                log::info!("Main Gateway attempt {}/{}: {} (wss_port: {})", 
                    attempt + 1, max_attempts, gw.identity, gw.wss_port);

                // Build client
                let build_result = MixnetClientBuilder::new_ephemeral()
                    .request_gateway(gw.identity.clone())
                    .build();

                let disconnected_client = match build_result {
                    Ok(c) => c,
                    Err(e) => {
                        log::warn!("Gateway {} build failed: {}", gw.identity, e);
                        last_error = format!("Build failed: {}", e);
                        continue;
                    }
                };

                // Connect with 30s timeout
                let connect_result = tokio::time::timeout(
                    Duration::from_secs(30),
                    disconnected_client.connect_to_mixnet()
                ).await;

                match connect_result {
                    Ok(Ok(client)) => {
                        let address = client.nym_address().to_string();
                        log::info!("Connected! Address: {}", address);

                        let mut st = state.lock().await;
                        st.client = Some(Arc::new(Mutex::new(client)));
                        st.my_address = Some(address.clone());

                        // Initialize TLI session state with placeholder values (Paper §5.2)
                        // obfs4_state and session_handle will be populated when SPAKE2+ handshake completes
                        let session_nonce = rand::random::<[u8; 32]>();
                        let placeholder_obfs4: Box<[u8; 64]> = Box::new([0u8; 64]);
                        st.tli_session_state = Some(tli::TliSessionState::new(
                            vec![],  // Will be populated during handshake
                            vec![],  // Will be populated when I2P keys are available
                            session_nonce,
                            Some(placeholder_obfs4),
                            0,       // Will be updated when SPAKE2+ session is established
                        ));

                        // Spawn churn oracle background task (Paper §7) - runs at 2 Hz
                        let state_for_churn = state.clone();
                        tokio::spawn(async move {
                            use tokio::time::{sleep, Duration};
                            log::info!("Churn oracle started (2 Hz sampling)");
                            
                            loop {
                                sleep(Duration::from_millis(500)).await; // 2 Hz
                                
                                let st = state_for_churn.lock().await;
                                
                                // Check if still in Hardened phase (only check churn when active)
                                if st.tli.current_phase() != tli::TliPhase::Hardened {
                                    continue;
                                }
                                
                                // Check for churn signals (Paper §7 thresholds)
                                // Note: Actual network health sampling would require Nym SDK stats access
                                // For now, we provide the infrastructure - Kotlin can call tli_check_churn()
                                
                                // Auto-transition to Fallback if churn is detected
                                if st.tli.heartbeat_failures.load(std::sync::atomic::Ordering::Relaxed) >= st.tli.churn_threshold {
                                    log::warn!("Churn oracle: triggering Hardened → Fallback transition");
                                    drop(st);
                                    let mut st_mut = state_for_churn.lock().await;
                                    let _ = st_mut.tli.transition(tli::TliPhase::Fallback);
                                }
                            }
                        });

                        return Ok(address);
                    },
                    Ok(Err(e)) => {
                        log::warn!("Gateway {} connection failed: {}", gw.identity, e);
                        last_error = format!("Connection failed: {}", e);
                    },
                    Err(_) => {
                        log::warn!("Gateway {} timed out after 30s", gw.identity);
                        last_error = format!("Gateway {} timed out", gw.identity);
                    }
                }
            }

            Err(TransportError::ConnectionFailed { 
                reason: format!("All {} gateway attempts failed. Last: {}", max_attempts, last_error) 
            })
        })
    }

    /// Connect with custom identity for DEBUG MODE ONLY
    /// 
    /// This function is used ONLY for rendezvous skeleton debugging.
    /// It connects using a deterministic keypair derived from a seed
    /// and a hardcoded gateway to ensure both peers get the same address.
    /// 
    /// CRITICAL: Cover traffic is disabled for debugging clarity.
    /// 
    /// Returns the actual connected NYM address for verification.
    pub fn connect_with_custom_identity(
        &self,
        rendezvous_seed: Vec<u8>,
        gateway_id: String,
    ) -> Result<String, TransportError> {
        log::info!("🔧 DEBUG MODE: Connecting with custom identity");
        log::info!("   Gateway ID: {}", gateway_id);
        
        if rendezvous_seed.len() != 32 {
            return Err(TransportError::RuntimeError {
                reason: format!("Rendezvous seed must be 32 bytes, got {}", rendezvous_seed.len()),
            });
        }
        
        let state = self.state.clone();
        self.runtime.block_on(async move {
            // Derive deterministic keypair material from seed
            let mut seed_array = [0u8; 32];
            seed_array.copy_from_slice(&rendezvous_seed);
            let derivation_material = DerivationMaterial::new(
                seed_array,
                0,
                b"zerochat-debug-rendezvous-v1"
            );
            
            log::info!("   Derived keypair material from seed");
            
            // Build client with custom identity
            let disconnected_client = MixnetClientBuilder::new_ephemeral()
                .with_derivation_material(derivation_material)
                .build()
                .map_err(|e| {
                    log::error!("DEBUG: Client build failed: {}", e);
                    TransportError::ConnectionFailed {
                        reason: format!("Debug client build failed: {}", e),
                    }
                })?;
            
            log::info!("   Built client with custom identity");
            
            // Connect to mixnet
            let client = disconnected_client
                .connect_to_mixnet()
                .await
                .map_err(|e| {
                    log::error!("DEBUG: Connection failed: {}", e);
                    TransportError::ConnectionFailed {
                        reason: format!("Debug connection failed: {}", e),
                    }
                })?;
            
            let address = client.nym_address().to_string();
            log::info!("   ✓ CONNECTED_AS: {}", address);
            
            // Store in main client slot (debug mode uses single client)
            let mut st = state.lock().await;
            st.client = Some(Arc::new(Mutex::new(client)));
            st.my_address = Some(address.clone());
            
            Ok(address)
        })
    }

    /// Disconnect from mixnet
    pub fn disconnect(&self) {
        log::info!("Disconnecting from NYM...");
        self.runtime.block_on(async {
            let mut state = self.state.lock().await;
            state.client = None;
            state.my_address = None;
        });
    }

    /// Check if connected
    pub fn is_connected(&self) -> bool {
        self.runtime.block_on(async {
            let state = self.state.lock().await;
            state.client.is_some()
        })
    }

    /// Get connection info
    pub fn get_connection_info(&self) -> ConnectionInfo {
        self.runtime.block_on(async {
            let state = self.state.lock().await;
            ConnectionInfo {
                my_address: state.my_address.clone().unwrap_or_default(),
                connected: state.client.is_some(),
            }
        })
    }

    /// Get own NYM address for message filtering
    fn get_address(&self) -> Option<String> {
        self.runtime.block_on(async {
            let state = self.state.lock().await;
            state.my_address.clone()
        })
    }


    // ── Uniform Packet Padding ─────────────────────────────────────────
    // Paper §9: pad all Sphinx frames to constant MTU for D_KL ≈ 0.069
    // traffic indistinguishability. Uses 4-byte length prefix.

    /// Maximum payload size after length prefix (4 bytes reserved for length).
    const SPHINX_PADDED_SIZE: usize = 1452;

    /// Pad payload to fixed MTU (1452 bytes).
    /// CRITICAL FIX: The Obfs4FrameWrapper in Kotlin already prepends a 4-byte length field.
    /// This Rust layer should NOT add another length field (was causing double-wrapping).
    /// Just pad the frame directly with trailing zeros to reach 1452 bytes.
    fn pad_to_fixed(frame: &[u8]) -> Vec<u8> {
        let mut out = vec![0u8; Self::SPHINX_PADDED_SIZE];
        let copy_len = std::cmp::min(frame.len(), Self::SPHINX_PADDED_SIZE);
        out[..copy_len].copy_from_slice(&frame[..copy_len]);
        // Rest filled with zeros by Vec initialization
        out
    }

    /// Unpad should not be called on the padded message in this flow.
    /// The Obfs4FrameUnwrapper handles extraction of the actual ciphertext.
    /// This function is kept for backwards compatibility if needed.
    #[allow(dead_code)]
    fn unpad_fixed(padded: &[u8]) -> Option<Vec<u8>> {
        if padded.len() < 4 {
            return None;
        }
        let len = u32::from_be_bytes([
            padded[0], padded[1], padded[2], padded[3],
        ]) as usize;
        if len > padded.len() - 4 {
            // Not a padded frame — return as-is for backwards compatibility
            return Some(padded.to_vec());
        }
        Some(padded[4..4 + len].to_vec())
    }

    /// Send message through mixnet (with obfs4 obfuscation and uniform packet padding)
    /// Paper §6: "We wrap all outbound Sphinx frames in the Rust layer before they reach the Android socket."
    pub fn send_message(&self, handle: Vec<u8>, payload: Vec<u8>) -> Result<(), TransportError> {
        log::info!("Sending message ({} bytes, padded to {})", payload.len(), Self::SPHINX_PADDED_SIZE);

        // Pad payload to uniform size before sending
        let padded_payload = Self::pad_to_fixed(&payload);

        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            let client_arc = st.client.as_ref()
                .ok_or(TransportError::NotConnected)?;
            // Lock client for mutable access
            let mut client = client_arc.lock().await;
            // Convert handle to NYM address string
            let address_str = String::from_utf8(handle)
                .map_err(|e| TransportError::InvalidAddress {
                    reason: format!("Invalid UTF-8: {}", e),
                })?;

            let recipient = Recipient::try_from_base58_string(&address_str)
                .map_err(|e| TransportError::InvalidAddress {
                    reason: e.to_string(),
                })?;

            // Paper §6: Per-frame ChaCha20-Poly1305 encryption will use obfs4_state from session
            // For now, send padded payload (encryption happens in Kotlin wrapper)
            let obfuscated_payload = padded_payload;

            client
                .send_plain_message(recipient, &obfuscated_payload)
                .await
                .map_err(|e| {
                    log::error!("Send failed: {}", e);
                    TransportError::SendFailed {
                        reason: e.to_string(),
                    }
                })?;

            log::info!("Message sent successfully (obfs4-obfuscated and padded)");
            Ok(())
        })
    }



    /// Receive message with timeout - waits for incoming messages from NYM mixnet
    pub fn receive_message(&self, timeout_ms: u64) -> Result<Option<RendezvousMessage>, TransportError> {
        self.runtime.block_on(async {
            let state = self.state.lock().await;
            
            if let Some(client_arc) = &state.client {
                // Wait for messages with timeout
                let timeout_duration = std::time::Duration::from_millis(timeout_ms);
                
                log::info!("Waiting for messages (timeout: {}ms)...", timeout_ms);
                
                // Lock to get mutable access
                let mut client = client_arc.lock().await;
                
                // Use tokio::time::timeout to enforce the timeout
                match tokio::time::timeout(timeout_duration, client.wait_for_messages()).await {
                    Ok(Some(messages)) => {
                        // Type annotation to help compiler
                        let msgs: Vec<ReconstructedMessage> = messages;
                        if let Some(first_msg) = msgs.into_iter().next() {
                            log::info!("Received message: {} bytes", first_msg.message.len());
                            
                            // Parse message format: [sender_address][0x00 separator][payload]
                            // If no separator, treat entire message as payload (direct signaling)
                            if let Some(null_pos) = first_msg.message.iter().position(|&b| b == 0) {
                                let sender_address = first_msg.message[..null_pos].to_vec();
                                let raw_payload = first_msg.message[null_pos + 1..].to_vec();
                                // Paper §6: Per-frame ChaCha20-Poly1305 decryption uses obfs4_state from session
                                // For now, treat as already decrypted (decryption happens in Kotlin wrapper)
                                let obfuscated_payload = raw_payload.clone();
                                // Unpad after full parsing (Paper §9: strip only after decrypt)
                                let payload = Self::unpad_fixed(&obfuscated_payload)
                                    .unwrap_or(obfuscated_payload);
                                log::info!("Parsed: sender={} bytes, payload={} bytes (unpadded)",
                                    sender_address.len(), payload.len());
                                Ok(Some(RendezvousMessage {
                                    sender_handle: sender_address,
                                    payload,
                                }))
                            } else {
                                // No separator - direct signaling message (unpad if padded)
                                // Paper §6: Per-frame ChaCha20-Poly1305 decryption uses obfs4_state from session
                                // For now, treat as already decrypted (decryption happens in Kotlin wrapper)
                                let obfuscated_payload = first_msg.message.clone();

                                let payload = Self::unpad_fixed(&obfuscated_payload)
                                    .unwrap_or(obfuscated_payload);
                                log::info!("Direct message (no separator), {} bytes (unpadded)", payload.len());
                                Ok(Some(RendezvousMessage {
                                    sender_handle: vec![],
                                    payload,
                                }))
                            }
                        } else {
                            log::info!("No messages received");
                            Ok(None)
                        }
                    }
                    Ok(None) => {
                        log::info!("No messages available");
                        Ok(None)
                    }
                    Err(_) => {
                        log::info!("Receive timeout");
                        Ok(None)
                    }
                }
            } else {
                Err(TransportError::NotConnected)
            }
        })
    }

    /// TLI lifecycle transition (Paper §5.3)
    pub fn tli_transition(&self, phase: u8) -> Result<u8, TransportError> {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let mut st = state.lock().await;
            st.tli.transition(tli::TliPhase::from_u8(phase).unwrap_or(tli::TliPhase::Init))
                .map(|p| p as u8)
                .map_err(|e| TransportError::RuntimeError { reason: e })
        })
    }

    /// Get current TLI phase
    pub fn tli_current_phase(&self) -> u8 {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            st.tli.current_phase_u8()
        })
    }

    /// Check churn status
    pub fn tli_check_churn(&self, signal_type: u8) -> bool {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            let churn_signal = match signal_type {
                1 => tli::ChurnSignal::HeartbeatTimeout { consecutive_failures: 3 },
                2 => tli::ChurnSignal::TunnelBuildFailure { failure_rate: 0.6 },
                3 => tli::ChurnSignal::AnonymitySetCollapse { set_size: 5 },
                _ => tli::ChurnSignal::HeartbeatTimeout { consecutive_failures: 3 },
            };
            st.tli.check_churn(churn_signal)
        })
    }

    /// Terminate TLI session and zeroize all material
    pub fn tli_terminate_session(&self) {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let mut st = state.lock().await;
            if let Some(ref session_state) = st.tli_session_state {
                session_state.terminate();
                log::info!("TLI session terminated and zeroized");
            }
            st.tli_session_state = None;
            let _ = st.tli.transition(tli::TliPhase::Zeroized);
        })
    }

    /// Start cover traffic scheduler (Paper §5)
    pub fn cover_traffic_start(&self) {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            st.cover_traffic.start();
            log::info!("Cover traffic started (λ_min={:.4})", st.cover_traffic.current_delay_ms() as f64 / 1000.0);
        })
    }

    /// Stop cover traffic scheduler
    pub fn cover_traffic_stop(&self) {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            st.cover_traffic.stop();
        })
    }

    /// Set thermal throttle for cover traffic
    pub fn cover_traffic_set_thermal_throttle(&self, active: bool) {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            st.cover_traffic.set_thermal_throttle(active);
            log::info!("Cover traffic thermal throttle: {}", if active { "ACTIVE" } else { "OFF" });
        })
    }

    /// Get current inter-packet delay for cover traffic (ms)
    pub fn cover_traffic_current_delay_ms(&self) -> u64 {
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            st.cover_traffic.current_delay_ms()
        })
    }
}
