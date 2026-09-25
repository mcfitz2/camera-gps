//! Shots waiting for the phone, kept in RTC memory so a reset or reflash
//! doesn't lose them.
//!
//! Every shot gets a sequence number. The phone acknowledges by sequence
//! number, which drops that shot and everything before it, so the pending
//! shots are always the contiguous range `first..next`.
//!
//! Times are microseconds since power-up from the RTC timer, which keeps
//! counting through resets. The phone is told each shot's *age* rather than a
//! time, so the device never needs a real clock.
//!
//! No hardware dependencies, so it is unit-tested on the host (see
//! `host-tests/`).

/// More than a 36-exposure roll, so a whole roll shot out of range of the
/// phone still arrives.
pub const CAPACITY: usize = 64;

/// Marks an initialised log. RTC memory holds garbage after power loss.
const MAGIC: u32 = 0x5348_5532; // "SHU2"

/// Bytes before the shot records in [`Log::encode`]: boot id and count.
pub const HEADER_LEN: usize = 5;
/// Bytes per shot in [`Log::encode`]: seq, age and contact time.
pub const RECORD_LEN: usize = 12;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct Shot {
    pub at_us: u64,
    /// How long the flash contact stayed closed; 0 while unknown.
    pub contact_ms: u32,
    _pad: u32,
}

#[repr(C)]
#[derive(Debug)]
pub struct Log {
    magic: u32,
    /// Random per power-up, so the phone can tell a restarted sequence from a
    /// repeated one.
    boot_id: u32,
    /// Oldest unacknowledged shot.
    first: u32,
    /// Sequence number for the next shot.
    next: u32,
    /// `seq + 1` of a shot whose contact is still closed, or 0.
    open: u32,
    shots: [Shot; CAPACITY],
}

impl Log {
    pub const EMPTY: Self = Self {
        magic: 0,
        boot_id: 0,
        first: 0,
        next: 0,
        open: 0,
        shots: [Shot { at_us: 0, contact_ms: 0, _pad: 0 }; CAPACITY],
    };

    /// Whether this holds a log from before the last reset, rather than
    /// garbage left by a power loss.
    pub fn is_valid(&self) -> bool {
        self.magic == MAGIC
            && self.first <= self.next
            && (self.next - self.first) as usize <= CAPACITY
            && (self.open == 0 || (self.first..self.next).contains(&(self.open - 1)))
    }

    pub fn reset(&mut self, boot_id: u32) {
        *self = Self::EMPTY;
        self.magic = MAGIC;
        self.boot_id = boot_id;
    }

    pub fn pending(&self) -> u32 {
        self.next - self.first
    }

    /// Records a shot with its contact still closed, dropping the oldest
    /// pending shot if the log is full. Returns its sequence number.
    pub fn record(&mut self, at_us: u64) -> u32 {
        let seq = self.next;
        if self.pending() as usize == CAPACITY {
            self.first += 1;
        }
        self.shots[seq as usize % CAPACITY] = Shot { at_us, ..Shot::default() };
        self.next += 1;
        self.open = seq + 1;
        seq
    }

    /// The flash contact of the latest shot opened again.
    pub fn release(&mut self, at_us: u64) {
        if self.open == 0 {
            return;
        }
        let shot = &mut self.shots[(self.open - 1) as usize % CAPACITY];
        let ms = at_us.saturating_sub(shot.at_us) / 1000;
        shot.contact_ms = u32::try_from(ms).unwrap_or(u32::MAX).max(1);
        self.open = 0;
    }

    /// Drops `seq` and every shot before it.
    pub fn ack(&mut self, seq: u32) {
        if seq < self.first || seq >= self.next {
            return;
        }
        self.first = seq + 1;
        if self.open != 0 && self.open - 1 <= seq {
            self.open = 0;
        }
    }

    #[cfg(test)]
    pub fn shot(&self, seq: u32) -> Option<Shot> {
        (self.first..self.next)
            .contains(&seq)
            .then(|| self.shots[seq as usize % CAPACITY])
    }

    /// Encodes the oldest pending shots that fit in `out`, as the phone reads
    /// them: `boot_id: u32, count: u8`, then per shot
    /// `seq: u32, age_ms: u32, contact_ms: u32`, all little-endian.
    pub fn encode(&self, now_us: u64, out: &mut [u8]) -> usize {
        let room = out.len().saturating_sub(HEADER_LEN) / RECORD_LEN;
        let count = (self.pending() as usize).min(room).min(u8::MAX as usize);
        out[..4].copy_from_slice(&self.boot_id.to_le_bytes());
        out[4] = count as u8;
        for (i, seq) in (self.first..).take(count).enumerate() {
            let shot = self.shots[seq as usize % CAPACITY];
            let age_ms = u32::try_from(now_us.saturating_sub(shot.at_us) / 1000).unwrap_or(u32::MAX);
            let record = &mut out[HEADER_LEN + i * RECORD_LEN..][..RECORD_LEN];
            record[..4].copy_from_slice(&seq.to_le_bytes());
            record[4..8].copy_from_slice(&age_ms.to_le_bytes());
            record[8..].copy_from_slice(&shot.contact_ms.to_le_bytes());
        }
        HEADER_LEN + count * RECORD_LEN
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn log() -> Log {
        let mut log = Log::EMPTY;
        log.reset(0xdead_beef);
        log
    }

    #[test]
    fn garbage_is_invalid() {
        let mut log = Log::EMPTY;
        assert!(!log.is_valid());
        log.reset(1);
        assert!(log.is_valid());
        log.first = 5;
        assert!(!log.is_valid());
    }

    #[test]
    fn records_and_acks() {
        let mut log = log();
        assert_eq!(log.record(1_000), 0);
        assert_eq!(log.record(2_000), 1);
        assert_eq!(log.record(3_000), 2);
        log.ack(1);
        assert_eq!(log.pending(), 1);
        assert_eq!(log.shot(1), None);
        assert_eq!(log.shot(2).unwrap().at_us, 3_000);
        // Stale and future acks are ignored.
        log.ack(0);
        log.ack(9);
        assert_eq!(log.pending(), 1);
    }

    #[test]
    fn full_log_drops_oldest() {
        let mut log = log();
        for i in 0..CAPACITY as u64 + 3 {
            log.record(i);
        }
        assert_eq!(log.pending() as usize, CAPACITY);
        assert_eq!(log.shot(2), None);
        assert_eq!(log.shot(3).unwrap().at_us, 3);
        assert!(log.is_valid());
    }

    #[test]
    fn release_sets_contact_time_of_latest_shot() {
        let mut log = log();
        let seq = log.record(1_000_000);
        log.release(13_500_000);
        assert_eq!(log.shot(seq).unwrap().contact_ms, 12_500);
        // Nothing open any more.
        log.release(20_000_000);
        assert_eq!(log.shot(seq).unwrap().contact_ms, 12_500);
    }

    #[test]
    fn short_contact_is_at_least_one_ms() {
        let mut log = log();
        let seq = log.record(1_000);
        log.release(1_200);
        assert_eq!(log.shot(seq).unwrap().contact_ms, 1);
    }

    #[test]
    fn ack_closes_open_shot() {
        let mut log = log();
        let seq = log.record(0);
        log.ack(seq);
        assert!(log.is_valid());
        log.release(5_000_000);
        assert_eq!(log.pending(), 0);
    }

    #[test]
    fn encodes_ages() {
        let mut log = log();
        log.record(1_000_000);
        log.release(1_004_000);
        log.record(3_000_000);
        let mut out = [0u8; 64];
        let len = log.encode(10_000_000, &mut out);
        assert_eq!(len, HEADER_LEN + 2 * RECORD_LEN);
        assert_eq!(
            &out[..len],
            &[
                0xef, 0xbe, 0xad, 0xde, 2, //
                0, 0, 0, 0, 0x28, 0x23, 0, 0, 4, 0, 0, 0, // seq 0, 9000 ms, 4 ms
                1, 0, 0, 0, 0x58, 0x1b, 0, 0, 0, 0, 0, 0, // seq 1, 7000 ms, open
            ]
        );
    }

    #[test]
    fn encode_truncates_to_buffer() {
        let mut log = log();
        for i in 0..5 {
            log.record(i);
        }
        let mut out = [0u8; HEADER_LEN + 2 * RECORD_LEN + 5];
        assert_eq!(log.encode(10, &mut out), HEADER_LEN + 2 * RECORD_LEN);
        assert_eq!(out[4], 2);
    }
}
