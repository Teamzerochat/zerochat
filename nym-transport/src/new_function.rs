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
