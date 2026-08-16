/// zcap/sphinx_pad.rs — ZCAP-specific fixed-size Sphinx packet padding.
///
/// All ZCAP packets are padded to exactly `SPHINX_PACKET_SIZE` bytes before
/// being handed to the Nym mixnet. This provides uniform traffic analysis
/// resistance by ensuring every packet looks identical in size.
///
/// # Layout
/// ```text
/// ┌──────────────────────────────────────┬──────────────────────┐
/// │  payload (variable, up to 30716 B)   │  length tag (4 B LE) │
/// │  followed by zero padding            │                      │
/// └──────────────────────────────────────┴──────────────────────┘
/// ```
/// The last 4 bytes encode the true payload length as a little-endian `u32`.

use zeroize::Zeroizing;

/// Fixed Sphinx packet size in bytes (30 KiB).
pub const SPHINX_PACKET_SIZE: usize = 30720;

/// Number of bytes reserved for the length tag at the end of the packet.
const LENGTH_TAG_SIZE: usize = 4;

/// Maximum allowed payload length (packet minus the 4-byte length tag).
pub const MAX_PAYLOAD_SIZE: usize = SPHINX_PACKET_SIZE - LENGTH_TAG_SIZE;

/// Error type for padding/unpadding operations.
#[derive(Debug, PartialEq, Eq)]
pub enum PadError {
    /// Payload exceeds `MAX_PAYLOAD_SIZE`.
    PayloadTooLarge { len: usize },
    /// Input buffer is not exactly `SPHINX_PACKET_SIZE` bytes.
    InvalidPacketSize { len: usize },
    /// The embedded length tag exceeds `MAX_PAYLOAD_SIZE`.
    CorruptLengthTag { tag: u32 },
}

impl std::fmt::Display for PadError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            PadError::PayloadTooLarge { len } => {
                write!(f, "payload too large: {len} > {MAX_PAYLOAD_SIZE}")
            }
            PadError::InvalidPacketSize { len } => {
                write!(f, "invalid packet size: {len} != {SPHINX_PACKET_SIZE}")
            }
            PadError::CorruptLengthTag { tag } => {
                write!(f, "corrupt length tag: {tag} > {MAX_PAYLOAD_SIZE}")
            }
        }
    }
}

/// Pad `payload` to exactly `SPHINX_PACKET_SIZE` bytes.
///
/// # Errors
/// Returns [`PadError::PayloadTooLarge`] if `payload.len() > MAX_PAYLOAD_SIZE`.
pub fn pad_to_sphinx_size(payload: &[u8]) -> Result<Vec<u8>, PadError> {
    let payload_len = payload.len();
    if payload_len > MAX_PAYLOAD_SIZE {
        return Err(PadError::PayloadTooLarge { len: payload_len });
    }

    // Allocate zeroed packet (zero-initialised = constant-time padding).
    let mut packet = Zeroizing::new(vec![0u8; SPHINX_PACKET_SIZE]);

    // Copy payload into the front of the packet.
    packet[..payload_len].copy_from_slice(payload);

    // Write length tag into the last 4 bytes as little-endian u32.
    let tag = (payload_len as u32).to_le_bytes();
    packet[SPHINX_PACKET_SIZE - LENGTH_TAG_SIZE..].copy_from_slice(&tag);

    Ok(packet.to_vec())
}

/// Recover the original payload from a padded Sphinx packet.
///
/// Reads the 4-byte little-endian length tag from the tail of `packet`, then
/// slices the leading bytes accordingly.
///
/// # Errors
/// - [`PadError::InvalidPacketSize`] — packet is not `SPHINX_PACKET_SIZE` bytes.
/// - [`PadError::CorruptLengthTag`]  — length tag exceeds `MAX_PAYLOAD_SIZE`.
pub fn unpad_sphinx_payload(packet: &[u8]) -> Result<Vec<u8>, PadError> {
    if packet.len() != SPHINX_PACKET_SIZE {
        return Err(PadError::InvalidPacketSize { len: packet.len() });
    }

    // Read length tag from the last 4 bytes.
    let tag_bytes: [u8; 4] = packet[SPHINX_PACKET_SIZE - LENGTH_TAG_SIZE..]
        .try_into()
        .unwrap();
    let payload_len = u32::from_le_bytes(tag_bytes) as usize;

    if payload_len > MAX_PAYLOAD_SIZE {
        return Err(PadError::CorruptLengthTag { tag: payload_len as u32 });
    }

    Ok(packet[..payload_len].to_vec())
}

// ─── Unit tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_empty_payload() {
        let padded = pad_to_sphinx_size(&[]).unwrap();
        assert_eq!(padded.len(), SPHINX_PACKET_SIZE);
        let recovered = unpad_sphinx_payload(&padded).unwrap();
        assert_eq!(recovered, &[] as &[u8]);
    }

    #[test]
    fn roundtrip_small_payload() {
        let payload = b"hello zcap";
        let padded = pad_to_sphinx_size(payload).unwrap();
        assert_eq!(padded.len(), SPHINX_PACKET_SIZE);
        let recovered = unpad_sphinx_payload(&padded).unwrap();
        assert_eq!(recovered, payload);
    }

    #[test]
    fn roundtrip_max_payload() {
        let payload = vec![0xFFu8; MAX_PAYLOAD_SIZE];
        let padded = pad_to_sphinx_size(&payload).unwrap();
        assert_eq!(padded.len(), SPHINX_PACKET_SIZE);
        let recovered = unpad_sphinx_payload(&padded).unwrap();
        assert_eq!(recovered, payload);
    }

    #[test]
    fn error_on_oversized_payload() {
        let payload = vec![0u8; MAX_PAYLOAD_SIZE + 1];
        assert_eq!(
            pad_to_sphinx_size(&payload),
            Err(PadError::PayloadTooLarge { len: MAX_PAYLOAD_SIZE + 1 })
        );
    }

    #[test]
    fn error_on_wrong_packet_size() {
        let short = vec![0u8; 100];
        assert_eq!(
            unpad_sphinx_payload(&short),
            Err(PadError::InvalidPacketSize { len: 100 })
        );
    }

    #[test]
    fn error_on_corrupt_length_tag() {
        // Build a packet where the length tag is MAX_PAYLOAD_SIZE + 1
        let mut packet = vec![0u8; SPHINX_PACKET_SIZE];
        let bad_len = (MAX_PAYLOAD_SIZE as u32 + 1).to_le_bytes();
        packet[SPHINX_PACKET_SIZE - LENGTH_TAG_SIZE..].copy_from_slice(&bad_len);
        assert!(matches!(
            unpad_sphinx_payload(&packet),
            Err(PadError::CorruptLengthTag { .. })
        ));
    }
}
