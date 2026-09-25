//! Canon location packet encoding.

use crate::state::Fix;

pub const PACKET_LEN: usize = 20;

/// Encode a fix and UTC timestamp into the packet written to the camera's
/// location characteristic:
///
/// ```text
/// 0x04 | 'N'/'S' | f32 lat | 'E'/'W' | f32 lon | '+'/'-' | f32 alt | u32 epoch
/// ```
///
/// All multi-byte fields are little-endian; magnitudes are unsigned with the
/// sign carried in the preceding direction byte.
pub fn encode(fix: &Fix, epoch_secs: u32) -> [u8; PACKET_LEN] {
    let mut p = [0u8; PACKET_LEN];
    p[0] = 0x04;
    p[1] = if fix.latitude < 0.0 { b'S' } else { b'N' };
    p[2..6].copy_from_slice(&(fix.latitude.abs() as f32).to_le_bytes());
    p[6] = if fix.longitude < 0.0 { b'W' } else { b'E' };
    p[7..11].copy_from_slice(&(fix.longitude.abs() as f32).to_le_bytes());
    p[11] = if fix.altitude < 0.0 { b'-' } else { b'+' };
    p[12..16].copy_from_slice(&fix.altitude.abs().to_le_bytes());
    p[16..20].copy_from_slice(&epoch_secs.to_le_bytes());
    p
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn matches_packet_accepted_by_m50_mark_ii() {
        // Captured from the C++ proof of concept; the camera tagged a photo with it.
        let fix = Fix {
            latitude: 41.8781,
            longitude: -87.6298,
            altitude: 181.0,
        };
        let expected = [
            0x04, 0x4e, 0x2d, 0x83, 0x27, 0x42, 0x57, 0x75, 0x42, 0xaf, 0x42, 0x2b, 0x00, 0x00,
            0x35, 0x43, 0x42, 0x6d, 0xb4, 0x6a,
        ];
        assert_eq!(encode(&fix, 1_790_209_346), expected);
    }
}
