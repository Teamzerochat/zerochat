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
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::runtime::Runtime;
use tokio::sync::Mutex;
use hkdf::Hkdf;
use sha2::Sha256;

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

#[derive(Default)]
struct ClientState {
    /// Main client with unique ephemeral keys for direct messaging
    client: Option<Arc<Mutex<MixnetClient>>>,
    my_address: Option<String>,
    /// Rendezvous client with derived keypair - shared mailbox for peer discovery
    rendezvous_client: Option<Arc<Mutex<MixnetClient>>>,
    rendezvous_address: Option<String>,
}

impl NymTransportClient {
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
    pub fn connect(&self, _gateway_url: String) -> Result<String, TransportError> {
        log::info!("Connecting to NYM public mainnet...");
        
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let disconnected_client = MixnetClientBuilder::new_ephemeral()
                .build()
                .map_err(|e: nym_sdk::Error| {
                    log::error!("NYM client build failed: {}", e);
                    TransportError::ConnectionFailed {
                        reason: e.to_string(),
                    }
                })?;

            let client = disconnected_client
                .connect_to_mixnet()
                .await
                .map_err(|e| {
                    log::error!("NYM connection failed: {}", e);
                    TransportError::ConnectionFailed {
                        reason: e.to_string(),
                    }
                })?;

            let address = client.nym_address().to_string();
            log::info!("Connected! Address: {}", address);

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

    /// Poll rendezvous point for messages
    /// This checks if any peer has published at the shared rendezvous mailbox
    pub fn poll_rendezvous(&self, point_id: String) -> Result<Option<RendezvousMessage>, TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        
        log::info!("Polling rendezvous point: {}", point_id);
        
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let st = state.lock().await;
            
            // Check if we have a rendezvous client connected
            if let Some(rvz_client) = &st.rendezvous_client {
                let mut client = rvz_client.lock().await;
                
                // Poll with 1 second timeout
                let timeout_duration = std::time::Duration::from_millis(1000);
                
                match tokio::time::timeout(timeout_duration, client.wait_for_messages()).await {
                    Ok(Some(messages)) => {
                        let msgs: Vec<ReconstructedMessage> = messages;
                        if let Some(first_msg) = msgs.into_iter().next() {
                            log::info!("Found message at rendezvous ({} bytes)", first_msg.message.len());
                            
                            // Parse message: <peer_address>\0<handle>
                            // The peer's main NYM address is in sender_handle
                            // The routing handle is in payload
                            if let Some(null_pos) = first_msg.message.iter().position(|&b| b == 0) {
                                let peer_address = first_msg.message[..null_pos].to_vec();
                                let routing_handle = first_msg.message[null_pos + 1..].to_vec();
                                
                                log::info!("Parsed peer address ({} bytes) and handle ({} bytes)", 
                                    peer_address.len(), routing_handle.len());
                                
                                return Ok(Some(RendezvousMessage {
                                    sender_handle: peer_address, // Peer's main NYM address
                                    payload: routing_handle,     // Routing handle for handshake
                                }));
                            } else {
                                // Fallback: treat entire message as handle
                                return Ok(Some(RendezvousMessage {
                                    sender_handle: first_msg.message.clone(),
                                    payload: first_msg.message,
                                }));
                            }
                        }
                    }
                    Ok(None) => {
                        log::debug!("No messages at rendezvous");
                    }
                    Err(_) => {
                        log::debug!("Rendezvous poll timeout");
                    }
                }
                
                Ok(None)
            } else {
                log::warn!("No rendezvous client - call publish_at_rendezvous first");
                Ok(None)
            }
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

    /// Publish at rendezvous - creates shared mailbox using derived keypair
    /// 
    /// Both peers with the same rendezvous ID (derived from shared secret) will:
    /// 1. Derive the same deterministic keypair
    /// 2. Connect to NYM with that keypair, getting the same address
    /// 3. Send their routing handle to that shared address
    /// 4. Poll for peer's handle at poll_rendezvous
    pub fn publish_at_rendezvous(&self, point_id: String, my_handle: Vec<u8>) -> Result<(), TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        
        log::info!("Creating shared rendezvous mailbox for point: {} (handle: {} bytes)", 
            point_id, my_handle.len());
        
        let state = self.state.clone();
        self.runtime.block_on(async move {
            let mut st = state.lock().await;
            
            // Create rendezvous client with derived keypair if not already connected
            if st.rendezvous_client.is_none() {
                log::info!("Connecting rendezvous client with derived keypair...");
                
                // Derive deterministic keypair from rendezvous point ID
                let derivation_material = derive_rendezvous_material(&point_id);
                
                // Build client with derived keys - both peers get the same address
                let disconnected_client = MixnetClientBuilder::new_ephemeral()
                    .with_derivation_material(derivation_material)
                    .build()
                    .map_err(|e| {
                        log::error!("Rendezvous client build failed: {}", e);
                        TransportError::ConnectionFailed {
                            reason: format!("Rendezvous setup failed: {}", e),
                        }
                    })?;
                
                let client = disconnected_client
                    .connect_to_mixnet()
                    .await
                    .map_err(|e| {
                        log::error!("Rendezvous connection failed: {}", e);
                        TransportError::ConnectionFailed {
                            reason: format!("Rendezvous connection failed: {}", e),
                        }
                    })?;
                
                let rendezvous_address = client.nym_address().to_string();
                log::info!("Rendezvous connected! Shared address: {}", rendezvous_address);
                
                st.rendezvous_client = Some(Arc::new(Mutex::new(client)));
                st.rendezvous_address = Some(rendezvous_address);
            }
            
            // Send our handle to the shared rendezvous address
            if let (Some(rvz_client), Some(rvz_addr)) = (&st.rendezvous_client, &st.rendezvous_address) {
                let mut client = rvz_client.lock().await;
                
                // Include our main address in the handle so peer can message us directly
                let main_address = st.my_address.clone().unwrap_or_default();
                let mut message = main_address.as_bytes().to_vec();
                message.push(0); // Null separator
                message.extend(my_handle);
                
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
                
                log::info!("Handle published to shared rendezvous mailbox");
            }
            
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
                            
                            // For rendezvous, we expect the message to contain:
                            // - sender_handle (the NYM address of sender)
                            // - payload (the routing handle)
                            // For simplicity, we'll treat the entire message as the handle
                            Ok(Some(RendezvousMessage {
                                sender_handle: first_msg.message.clone(),
                                payload: first_msg.message,
                            }))
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
