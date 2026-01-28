//! NYM Transport - Mixnet transport layer for ZeroChat
//!
//! Stub implementation for Android FFI.
//! Full NYM SDK integration pending.

use std::sync::{Arc, Mutex};
use thiserror::Error;

uniffi::setup_scaffolding!();

/// Transport errors
#[derive(Error, Debug, uniffi::Error)]
pub enum TransportError {
    #[error("Not connected to gateway")]
    NotConnected,
    #[error("Connection failed: {reason}")]
    ConnectionFailed { reason: String },
    #[error("Send failed: {reason}")]
    SendFailed { reason: String },
}

/// Message received from rendezvous poll
#[derive(Debug, Clone, uniffi::Record)]
pub struct RendezvousMessage {
    pub sender_handle: Vec<u8>,
    pub payload: Vec<u8>,
}

/// NYM Transport client
#[derive(uniffi::Object)]
pub struct NymTransportClient {
    connected: Mutex<bool>,
    gateway_url: Mutex<Option<String>>,
}

#[uniffi::export]
impl NymTransportClient {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            connected: Mutex::new(false),
            gateway_url: Mutex::new(None),
        })
    }

    pub fn connect(&self, gateway_url: String) -> Result<(), TransportError> {
        *self.gateway_url.lock().unwrap() = Some(gateway_url);
        *self.connected.lock().unwrap() = true;
        Ok(())
    }

    pub fn disconnect(&self) {
        *self.connected.lock().unwrap() = false;
        *self.gateway_url.lock().unwrap() = None;
    }

    pub fn is_connected(&self) -> bool {
        *self.connected.lock().unwrap()
    }

    pub fn poll_rendezvous(&self, _point_id: String) -> Result<Option<RendezvousMessage>, TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        Ok(None)
    }

    pub fn send_message(&self, handle: Vec<u8>, _payload: Vec<u8>) -> Result<(), TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        if handle.len() != 32 {
            return Err(TransportError::SendFailed {
                reason: "Invalid handle length".to_string(),
            });
        }
        Ok(())
    }

    pub fn publish_at_rendezvous(&self, _point_id: String, _my_handle: Vec<u8>) -> Result<(), TransportError> {
        if !self.is_connected() {
            return Err(TransportError::NotConnected);
        }
        Ok(())
    }
}
