//! NYM Transport - Real mixnet integration for ZeroChat
//!
//! Connects to NYM public mainnet using nym-sdk.
//! Uses ephemeral keys (no disk storage) for zero-trust compliance.

#![allow(unused)]
#![allow(warnings)]

use nym_sdk::mixnet::{MixnetClient, MixnetClientBuilder, Recipient, ReconstructedMessage, AnonymousSenderTag};
use nym_sdk::mixnet::MixnetMessageSender;
use std::sync::Arc;
use std::time::Duration;
use thiserror::Error;
use tokio::runtime::Runtime;
use tokio::sync::Mutex;
use hkdf::Hkdf;
use sha2::Sha256;

// Using UDL-driven bindings (see build.rs)
uniffi::include_scaffolding!("nym_transport");

/// Derive a deterministic NYM Recipient address from rendezvous ID  
/// Both peers with the same rendezvous ID derive the same address
fn derive_rendezvous_recipient(rendezvous_id: &str) -> Result<Recipient, TransportError> {
    log::info!("Deriving rendezvous recipient from ID: {}", rendezvous_id);
    let hkdf = Hkdf::<Sha256>::new(None, rendezvous_id.as_bytes());
    let mut derived_bytes = [0u8; 32];
    hkdf.expand(b"nym-rendezvous-address-v1", &mut derived_bytes)
        .map_err(|e| TransportError::RuntimeError {
            reason: format!("HKDF expansion failed: {}", e),
        })?;
    let base58_address = bs58::encode(&derived_bytes).into_string();
    log::info!("Derived base58 address: {}", base58_address);
    Recipient::try_from_base58_string(&base58_address)
        .map_err(|e| {
            log::error!("Failed to create Recipient: {}", e);
            TransportError::InvalidAddress {
                reason: format!("Invalid derived address: {}", e),
            }
        })
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

// Object defined in UDL
pub struct NymTransportClient {
    runtime: Runtime,
    state: Arc<Mutex<ClientState>>,
}

#[derive(Default)]
struct ClientState {
    client: Option<Arc<Mutex<MixnetClient>>>,
    my_address: Option<String>,
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

    /// Poll rendezvous point (stub for now)
    pub fn poll_rendezvous(&self, _point_id: String) -> Result<Option<RendezvousMessage>, TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        Ok(None)
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

    /// Publish at rendezvous - sends your routing handle to the rendezvous point
    pub fn publish_at_rendezvous(&self, point_id: String, my_handle: Vec<u8>) -> Result<(), TransportError> {
        self.runtime.block_on(async {
            let state = self.state.lock().await;
            
            if let Some(client) = &state.client {
                // Derive a deterministic NYM address from the rendezvous point ID
                // Both peers with the same rendezvous ID will derive the same address
                // This creates a shared ephemeral mailbox
                let rendezvous_address = derive_rendezvous_recipient(&point_id)?;
                let mut client_guard = client.lock().await;
                
                log::info!("Publishing handle ({} bytes) at rendezvous: {}", my_handle.len(), point_id);
                
                // Send the handle through the mixnet
                client_guard
                    .send_plain_message(rendezvous_address, my_handle.clone())
                    .await
                    .map_err(|e| TransportError::SendFailed {
                        reason: format!("Failed to publish at rendezvous: {}", e),
                    })?;
                
                log::info!("Handle published successfully");
                Ok(())
            } else {
                Err(TransportError::NotConnected)
            }
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
