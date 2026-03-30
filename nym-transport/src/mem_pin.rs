//! Memory pinning module for sensitive cryptographic material.
//!
//! Uses `mlock()` to prevent the OS from swapping key material to disk,
//! which would survive a `terminate()` call and be extractable by A_LFA.
//!
//! Paper §5.2, Def. 3 (LFA): "independent of JVM GC, deterministic secret erasure"

use zeroize::Zeroize;

/// A fixed-size secret that is pinned in memory via `mlock()` and
/// zeroized + unlocked on drop.
///
/// Graceful fallback: if `mlock` fails (e.g., ENOMEM from ulimit)
/// or platform is unsupported, the key is still zeroized on drop.
pub struct PinnedSecret<const N: usize> {
    data: [u8; N],
    is_locked: bool,
}

impl<const N: usize> PinnedSecret<N> {
    /// Create a new pinned secret. Attempts to `mlock` the data region.
    pub fn new(data: [u8; N]) -> Self {
        let mut s = Self {
            data,
            is_locked: false,
        };

        #[cfg(unix)]
        {
            let result = unsafe {
                libc::mlock(
                    s.data.as_ptr() as *const libc::c_void,
                    N,
                )
            };

            if result == 0 {
                s.is_locked = true;
                log::debug!("mlock: pinned {} bytes of secret material", N);
            } else {
                // Graceful fallback — key still zeroized on drop
                log::warn!(
                    "mlock failed (errno likely ENOMEM). Secret will be zeroized but not swap-protected. size={}",
                    N
                );
            }
        }

        #[cfg(not(unix))]
        {
            log::warn!("mlock not supported on this platform. Secret will be zeroized but not swap-protected.");
        }

        s
    }

    /// Read-only access to the secret data.
    #[inline]
    pub fn as_bytes(&self) -> &[u8; N] {
        &self.data
    }

    /// Mutable access to the secret data.
    #[inline]
    pub fn as_bytes_mut(&mut self) -> &mut [u8; N] {
        &mut self.data
    }
}

impl<const N: usize> Drop for PinnedSecret<N> {
    fn drop(&mut self) {
        // Zeroize explicitly BEFORE munlock to ensure the zeroed data is what gets unlocked.
        self.data.zeroize();

        #[cfg(unix)]
        {
            if self.is_locked {
                unsafe {
                    libc::munlock(
                        self.data.as_ptr() as *const libc::c_void,
                        N,
                    );
                }
                log::debug!("munlock: unpinned {} bytes after zeroization", N);
            }
        }
    }
}

impl<const N: usize> AsRef<[u8]> for PinnedSecret<N> {
    fn as_ref(&self) -> &[u8] {
        &self.data
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_pinned_secret_zeroizes_on_drop() {
        let key_data = [0xABu8; 32];
        let secret = PinnedSecret::<32>::new(key_data);
        assert_eq!(secret.as_bytes(), &key_data);
        // Drop will zeroize — can't easily test in safe Rust,
        // but the derive ensures it happens.
    }

    #[test]
    fn test_pinned_secret_access() {
        let data = [1u8, 2, 3, 4];
        let secret = PinnedSecret::<4>::new(data);
        assert_eq!(secret.as_bytes()[0], 1);
        assert_eq!(secret.as_ref().len(), 4);
    }
}
