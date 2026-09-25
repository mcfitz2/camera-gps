//! Stable per-chip identity presented to the camera during pairing.
//!
//! Derived the same way as the C++ proof of concept so a camera paired with
//! that firmware recognises this device.

pub struct Identity {
    /// Shown on the camera's pairing prompt, e.g. `camgps-31bd3`.
    pub name: String,
    pub uuid: [u8; 16],
}

impl Identity {
    pub fn from_chip() -> Self {
        let mut mac = [0u8; 6];
        // SAFETY: `mac` is a valid 6-byte buffer as the API requires.
        unsafe { esp_idf_svc::sys::esp_efuse_mac_get_default(mac.as_mut_ptr()) };
        Self::from_mac(mac)
    }

    fn from_mac(mac: [u8; 6]) -> Self {
        let mut x = u32::from_be_bytes([mac[2], mac[3], mac[4], mac[5]]);
        let mut uuid = [0u8; 16];
        for chunk in uuid.chunks_exact_mut(4) {
            x ^= x << 13;
            x ^= x >> 17;
            x ^= x << 5;
            chunk.copy_from_slice(&x.to_le_bytes());
        }
        Self {
            name: format!("camgps-{:05x}", x & 0xf_ffff),
            uuid,
        }
    }
}
