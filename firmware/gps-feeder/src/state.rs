//! State shared between the camera link, console and UI.

use std::sync::{Arc, Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// How long to keep reporting the last GPS fix after reception is lost.
/// Covers brief indoor visits and tunnels; past this the position may be far
/// off, and untagged photos are better than wrongly tagged ones.
pub const FIX_MAX_AGE: Duration = Duration::from_secs(10 * 60);
/// GPS counts as disconnected after this long without a valid sentence.
const GPS_SILENCE: Duration = Duration::from_secs(5);

/// A position fix to report to the camera.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Fix {
    /// Degrees, positive north.
    pub latitude: f64,
    /// Degrees, positive east.
    pub longitude: f64,
    /// Metres above sea level.
    pub altitude: f32,
}

/// Where the current fix came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FixSource {
    Gps { at: Instant },
    /// Set from the console; never goes stale.
    Manual,
}

/// Receiver status for display and diagnostics.
#[derive(Debug, Clone, Copy, Default)]
pub struct GpsStatus {
    pub satellites: Option<u8>,
    pub hdop: Option<f32>,
    /// When the last valid sentence arrived.
    pub last_data: Option<Instant>,
}

impl GpsStatus {
    pub fn receiving(&self) -> bool {
        self.last_data.is_some_and(|t| t.elapsed() < GPS_SILENCE)
    }
}

/// Where the camera connection currently stands.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Link {
    #[default]
    Disconnected,
    /// Not connecting yet: the camera drops links that don't deliver location.
    WaitingForFix,
    /// The camera is advertising that it is powered off.
    CameraOff,
    Connecting,
    /// Waiting for the user to accept the pairing prompt on the camera.
    AwaitingApproval,
    /// Connected; `geo_enabled` once the camera has accepted location data.
    Connected { geo_enabled: bool },
}

#[derive(Debug, Default)]
pub struct Status {
    /// Latest accepted position, possibly stale; see [`Status::current_fix`].
    pub fix: Option<(Fix, FixSource)>,
    pub gps: GpsStatus,
    pub link: Link,
    /// Location packets the camera has acknowledged this session.
    pub sends_ok: u32,
    pub battery_mv: Option<u16>,
}

impl Status {
    /// The fix to report, unless it is too old to trust.
    pub fn current_fix(&self) -> Option<Fix> {
        match self.fix? {
            (fix, FixSource::Manual) => Some(fix),
            (fix, FixSource::Gps { at }) => (at.elapsed() <= FIX_MAX_AGE).then_some(fix),
        }
    }
}

/// Requests from the console or buttons to the camera task.
#[derive(Debug, Clone, Copy)]
pub enum Command {
    SendNow,
    Reconnect,
    /// Delete the stored bond so the next connection pairs from scratch.
    Forget,
    /// Disconnect and log the camera's advertisements for this many seconds.
    Survey(u16),
}

#[derive(Clone, Default)]
pub struct Shared(Arc<Mutex<Status>>);

impl Shared {
    pub fn lock(&self) -> MutexGuard<'_, Status> {
        // A panic while holding the lock aborts the firmware, so poisoning can't be observed.
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Anything earlier than this means the clock was never set.
const EARLIEST_VALID_TIME: Duration = Duration::from_secs(1_704_067_200); // 2024-01-01

/// Current UTC time, if the system clock has been set.
pub fn utc_now() -> Option<Duration> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .filter(|t| *t >= EARLIEST_VALID_TIME)
}

/// Set the system clock to `epoch_secs` (UTC).
pub fn set_utc(epoch_secs: i64) -> anyhow::Result<()> {
    let tv = esp_idf_svc::sys::timeval {
        tv_sec: epoch_secs,
        tv_usec: 0,
    };
    // SAFETY: `tv` is a valid timeval and a null timezone is permitted.
    let rc = unsafe { esp_idf_svc::sys::settimeofday(&tv, core::ptr::null()) };
    anyhow::ensure!(rc == 0, "settimeofday failed: {rc}");
    Ok(())
}
