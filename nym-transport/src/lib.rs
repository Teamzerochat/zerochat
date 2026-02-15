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

// SPAKE2+ module for password-authenticated key exchange
mod spake2;
pub use spake2::{spake2_start_initiator, spake2_finish_initiator, spake2_start_responder, Spake2Error};

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
    pub shared_secret: Vec<u8>,
}

// SPAKE2+ wrapper functions for UniFFI
pub fn spake2_start_initiator_wrapper(password: Vec<u8>) -> Result<Spake2InitiatorHandle, Spake2Error> {
    let (handle_id, outbound_msg) = spake2::spake2_start_initiator(&password)?;
    Ok(Spake2InitiatorHandle { handle_id, outbound_msg })
}

pub fn spake2_finish_initiator_wrapper(handle_id: u64, inbound_msg: Vec<u8>) -> Result<Vec<u8>, Spake2Error> {
    spake2::spake2_finish_initiator(handle_id, &inbound_msg)
}

pub fn spake2_start_responder_wrapper(password: Vec<u8>, inbound_msg: Vec<u8>) -> Result<Spake2ResponderResult, Spake2Error> {
    let (outbound_msg, shared_secret) = spake2::spake2_start_responder(&password, &inbound_msg)?;
    Ok(Spake2ResponderResult { outbound_msg, shared_secret })
}

pub fn spake2_cleanup_state_wrapper(handle_id: u64) -> bool {
    spake2::spake2_cleanup_state(handle_id)
}

pub fn spake2_active_count_wrapper() -> u64 {
    spake2::spake2_active_count() as u64
}

// Object defined in UDL
pub struct NymTransportClient {
    runtime: Runtime,
    state: Arc<Mutex<ClientState>>,
}

use std::collections::HashMap;

// ... imports ...

#[derive(Default)]
struct ClientState {
    /// Main client with unique ephemeral keys for direct messaging
    client: Option<Arc<Mutex<MixnetClient>>>,
    my_address: Option<String>,
    
    /// Rendezvous clients mapped by point_id
    /// Allows concurrent connections to multiple rendezvous points
    rendezvous_clients: HashMap<String, Arc<Mutex<MixnetClient>>>,
    rendezvous_addresses: HashMap<String, String>,
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

            // 0. Deterministic Gateway Selection
            let gateways = Self::fetch_sorted_gateways().await?;
            if gateways.is_empty() {
                return Err(TransportError::ConnectionFailed { reason: "No gateways available".into() });
            }
            
            // Hash the point_id to get a deterministic number
            let mut hasher = Sha256::new();
            use sha2::Digest;
            hasher.update(point_id_clone.as_bytes());
            let result = hasher.finalize();
            // Use first 8 bytes as u64 for modulus
            let hash_int = u64::from_be_bytes(result[0..8].try_into().unwrap());
            
            let index = (hash_int as usize) % gateways.len();
            let selected_gateway = &gateways[index];
            
            log::info!("Selected Deterministic Gateway: {} (Index: {}/{})", selected_gateway.identity, index, gateways.len());

            // 1. Derive Keys with STRICT Context Separation
            let salt = b"zerochat-rendezvous-v1";
            let hkdf = Hkdf::<Sha256>::new(Some(salt), point_id_clone.as_bytes());
            
            let mut identity_seed = [0u8; 32];
            let mut encryption_seed = [0u8; 32];
            let mut ack_seed = [0u8; 32];
            
            // Context Label 1: Identity
            hkdf.expand(b"rendezvous-identity", &mut identity_seed)
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to expand identity seed: {:?}", e) 
                })?;
                
            // Context Label 2: Encryption
            hkdf.expand(b"rendezvous-encryption", &mut encryption_seed)
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to expand encryption seed: {:?}", e) 
                })?;

            // Context Label 3: AckKey (Deterministic)
            hkdf.expand(b"ack-key", &mut ack_seed)
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to expand ack seed: {:?}", e) 
                })?;

            log::info!("Rendezvous seed (Identity): {}", hex::encode(&identity_seed));

            // STRICT DETERMINISTIC ED25519 GENERATION (User Requirement)
            use ed25519_dalek::{SigningKey, VerifyingKey};
            
            let signing_key = SigningKey::from_bytes(&identity_seed);
            let verifying_key = signing_key.verifying_key();
            
            log::info!("Derived public key (Identity): {}", hex::encode(verifying_key.as_bytes()));
            
            let identity_keypair = nym_crypto::asymmetric::identity::KeyPair::from_bytes(&signing_key.to_bytes(), verifying_key.as_bytes())
                .map_err(|e| TransportError::RuntimeError {
                    reason: format!("Failed to convert identity keypair: {}", e)
                })?;

            // Derive Encryption KeyPair (x25519)
            log::info!("Deriving Encryption KeyPair (x25519)...");
            let x25519_secret = x25519_dalek::StaticSecret::from(encryption_seed);
            let x25519_public = x25519_dalek::PublicKey::from(&x25519_secret);
            let x25519_secret_bytes = x25519_secret.to_bytes();
            
            let encryption_keypair = nym_crypto::asymmetric::encryption::KeyPair::from_bytes(&x25519_secret_bytes, x25519_public.as_bytes())
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to derive encryption keypair: {:?}", e) 
                })?;

            // Deterministic AckKey
            // AckKey is 16 bytes (AES-128-CTR), so we use the first 16 bytes of the seed.
            let ack_key = AckKey::try_from_bytes(&ack_seed[..16])
                .map_err(|e| TransportError::RuntimeError {
                     reason: format!("Failed to derive ack key: {:?}", e)
                })?;

            // Construct ClientKeys
            let client_keys = ClientKeys::from_keys(
                identity_keypair,
                encryption_keypair,
                ack_key
            );

            // 2. Create Ephemeral Storage and Inject Keys
            let storage = Ephemeral::default();
            if let Err(e) = storage.key_store().store_keys(&client_keys).await {
                 return Err(TransportError::RuntimeError { 
                    reason: format!("Failed to store rendezvous keys: {}", e) 
                });
            }

            // 3. Build Client using custom storage and DETERMINISTIC GATEWAY
            let disconnected = MixnetClientBuilder::new_with_storage(storage)
                .request_gateway(selected_gateway.identity.clone()) 
                .build()
                .map_err(|e| TransportError::RuntimeError { 
                    reason: format!("Failed to build rendezvous client: {}", e) 
                })?;

            // Connect with 30s timeout to avoid hanging on bad gateways
            let client = tokio::time::timeout(
                Duration::from_secs(30),
                disconnected.connect_to_mixnet()
            ).await
                .map_err(|_| TransportError::ConnectionFailed { 
                    reason: format!("Rendezvous gateway {} timed out after 30s", selected_gateway.identity) 
                })?
                .map_err(|e| TransportError::ConnectionFailed { 
                    reason: format!("Failed to connect rendezvous client: {}", e) 
                })?;

            let address = client.nym_address().to_string();
            log::info!("Connected Deterministic Mailbox Client: {}", address);

            // 4. Store Client
            let mut st = state.lock().await;
            st.rendezvous_clients.insert(point_id_clone.clone(), Arc::new(Mutex::new(client)));
            st.rendezvous_addresses.insert(point_id_clone, address.clone());
            
            Ok(address)
        })
    }

    /// Calculate the Nym Address for a deterministic point without connecting.
    /// Used for "Two-Slot" strategy: if I am Slot A, I need to know Slot B's address to send to it.
    pub fn get_rendezvous_address(&self, point_id: String) -> Result<String, TransportError> {
        self.runtime.block_on(async move {
            // 1. Deterministic Gateway Selection (Same logic as connect)
            let gateways = Self::fetch_sorted_gateways().await?;
            if gateways.is_empty() {
                return Err(TransportError::ConnectionFailed { reason: "No gateways to derive address".into() });
            }
            
            // Hash the point_id to get a deterministic number
            let mut hasher = Sha256::new();
            use sha2::Digest;
            hasher.update(point_id.as_bytes());
            let result = hasher.finalize();
            // Use first 8 bytes as u64 for modulus
            let hash_int = u64::from_be_bytes(result[0..8].try_into().unwrap());
            
            let index = (hash_int as usize) % gateways.len();
            let selected_gateway = &gateways[index];

            // 2. Derive Identity Key (Same logic as connect_rendezvous)
            let salt = b"zerochat-rendezvous-v1";
            let hkdf = Hkdf::<Sha256>::new(Some(salt), point_id.as_bytes());
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
    pub fn publish_at_rendezvous(&self, point_id: String, my_handle: Vec<u8>) -> Result<(), TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        
        log::info!("Publishing to rendezvous point: {} (handle: {} bytes)", point_id, my_handle.len());
        
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
                
                client.send_plain_message(recipient, message)
                    .await
                    .map_err(|e| {
                        log::error!("Rendezvous publish failed: {}", e);
                        TransportError::SendFailed {
                            reason: format!("Failed to publish at rendezvous: {}", e),
                        }
                    })?;
                
                log::info!("Payload published to shared rendezvous mailbox: {}", point_id);
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



    /// Send message through mixnet
    pub fn send_message(&self, handle: Vec<u8>, payload: Vec<u8>) -> Result<(), TransportError> {
        log::info!("Sending message ({} bytes)", payload.len());
        
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

            client
                .send_plain_message(recipient, &payload)
                .await
                .map_err(|e| {
                    log::error!("Send failed: {}", e);
                    TransportError::SendFailed {
                        reason: e.to_string(),
                    }
                })?;

            log::info!("Message sent successfully");
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
                                let payload = first_msg.message[null_pos + 1..].to_vec();
                                log::info!("Parsed: sender={} bytes, payload={} bytes", 
                                    sender_address.len(), payload.len());
                                Ok(Some(RendezvousMessage {
                                    sender_handle: sender_address,
                                    payload,
                                }))
                            } else {
                                // No separator - direct signaling message (payload only)
                                log::info!("Direct message (no separator)");
                                Ok(Some(RendezvousMessage {
                                    sender_handle: vec![],
                                    payload: first_msg.message,
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
}
