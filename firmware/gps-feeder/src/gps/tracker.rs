//! Turns a stream of NMEA sentences into accepted position fixes.
//!
//! Aims to report *something* reasonable under poor reception rather than
//! nothing, while keeping obviously wrong positions away from the camera:
//!
//! - Only real receiver fixes are used (no dead reckoning, no "no fix").
//! - Fixes with very poor geometry (HDOP above [`MAX_HDOP`]) are dropped.
//! - Altitude is only taken from 3D fixes; 2D fixes keep the last good altitude.
//! - A fix that implies an impossible jump from the last accepted one is held
//!   back until [`CONFIRMATIONS`] consecutive fixes agree on the new location,
//!   so a single multipath glitch is ignored but genuine relocation (e.g. after
//!   a long outage) is picked up within a few seconds.
//! - If GGA sentences stop arriving, RMC alone is used as a fallback.
//!
//! Holding the last fix during outages is the caller's job; see
//! `state::FIX_MAX_AGE`.

use std::time::{Duration, Instant};

use crate::nmea::{Gga, Position, Rmc, Sentence};

/// Worse geometry than this is too inaccurate to tag photos with (~100 m).
pub const MAX_HDOP: f32 = 20.0;
/// Fewest satellites for a 3D fix, and so a trustworthy altitude.
const MIN_SATS_3D: u8 = 4;
/// Metres of error per unit of HDOP (typical user-equivalent range error).
const UERE_M: f64 = 5.0;
/// HDOP assumed when a fix comes without one (RMC fallback).
const UNKNOWN_HDOP: f32 = 5.0;
/// Fastest plausible movement; generous so photos from vehicles still work.
const MAX_SPEED_MPS: f64 = 300.0;
/// Slack added to every jump check.
const JUMP_MARGIN_M: f64 = 50.0;
/// Consecutive agreeing fixes needed to accept an apparent jump.
const CONFIRMATIONS: u8 = 3;
/// Use RMC positions when no GGA has been seen for this long.
const GGA_TIMEOUT: Duration = Duration::from_secs(5);

/// A position accepted for reporting.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Accepted {
    pub latitude: f64,
    pub longitude: f64,
    /// From the latest 3D fix, if there has been one.
    pub altitude: Option<f32>,
    pub hdop: Option<f32>,
}

/// Satellite status from the latest GGA.
#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct Sky {
    pub satellites: Option<u8>,
    pub hdop: Option<f32>,
}

/// What one sentence changed.
#[derive(Debug, Default, PartialEq)]
pub struct Output {
    pub fix: Option<Accepted>,
    /// UTC as Unix seconds from a valid RMC.
    pub utc: Option<i64>,
    pub sky: Option<Sky>,
}

#[derive(Debug, Clone, Copy)]
struct Candidate {
    position: Position,
    hdop: f32,
    at: Instant,
}

impl Candidate {
    /// Whether `next` could follow `self` without teleporting.
    fn reaches(&self, next: &Candidate) -> bool {
        let elapsed = next.at.saturating_duration_since(self.at).as_secs_f64();
        let error = UERE_M * f64::from(self.hdop + next.hdop);
        distance_m(&self.position, &next.position) <= MAX_SPEED_MPS * elapsed + error + JUMP_MARGIN_M
    }
}

#[derive(Debug, Default)]
pub struct Tracker {
    last: Option<Candidate>,
    /// A disputed position and how many consecutive fixes agree with it.
    pending: Option<(Candidate, u8)>,
    altitude: Option<f32>,
    last_gga: Option<Instant>,
}

impl Tracker {
    pub fn update(&mut self, sentence: &Sentence, now: Instant) -> Output {
        match sentence {
            Sentence::Gga(gga) => self.on_gga(gga, now),
            Sentence::Rmc(rmc) => self.on_rmc(rmc, now),
        }
    }

    fn on_gga(&mut self, gga: &Gga, now: Instant) -> Output {
        self.last_gga = Some(now);
        let sky = Some(Sky {
            satellites: gga.satellites,
            hdop: gga.hdop,
        });
        let (true, Some(position)) = (gga.has_fix(), gga.position) else {
            return Output { sky, ..Output::default() };
        };
        if gga.satellites.unwrap_or(0) >= MIN_SATS_3D {
            if let Some(alt) = gga.altitude {
                self.altitude = Some(alt);
            }
        }
        let fix = self.consider(position, gga.hdop, now);
        Output { fix, sky, utc: None }
    }

    fn on_rmc(&mut self, rmc: &Rmc, now: Instant) -> Output {
        if !rmc.valid {
            return Output::default();
        }
        let utc = rmc.epoch_secs().map(correct_week_rollover);
        let gga_missing = self.last_gga.is_none_or(|t| now.duration_since(t) > GGA_TIMEOUT);
        let fix = match rmc.position {
            Some(position) if gga_missing => self.consider(position, None, now),
            _ => None,
        };
        Output { fix, utc, sky: None }
    }

    fn consider(&mut self, position: Position, hdop: Option<f32>, now: Instant) -> Option<Accepted> {
        if hdop.is_some_and(|h| h > MAX_HDOP) {
            return None;
        }
        // Receivers sometimes emit 0,0 while acquiring.
        if position.latitude == 0.0 && position.longitude == 0.0 {
            return None;
        }
        let candidate = Candidate {
            position,
            hdop: hdop.unwrap_or(UNKNOWN_HDOP),
            at: now,
        };

        let plausible = self.last.is_none_or(|last| last.reaches(&candidate));
        if !plausible {
            let agreeing = match self.pending {
                Some((pending, n)) if pending.reaches(&candidate) => n + 1,
                _ => 1,
            };
            if agreeing < CONFIRMATIONS {
                self.pending = Some((candidate, agreeing));
                return None;
            }
        }

        self.pending = None;
        self.last = Some(candidate);
        Some(Accepted {
            latitude: position.latitude,
            longitude: position.longitude,
            altitude: self.altitude,
            hdop,
        })
    }
}

/// Days in one GPS week-number rollover period (1024 weeks).
const ROLLOVER_SECS: i64 = 1024 * 7 * 86_400;
/// Receivers with old firmware report dates 19.6 years in the past after a
/// week-number rollover; anything before this is assumed to be that.
const EARLIEST_PLAUSIBLE_UTC: i64 = 1_704_067_200; // 2024-01-01

fn correct_week_rollover(mut epoch: i64) -> i64 {
    while epoch < EARLIEST_PLAUSIBLE_UTC {
        epoch += ROLLOVER_SECS;
    }
    epoch
}

/// Equirectangular approximation; accurate enough over the distances checked.
fn distance_m(a: &Position, b: &Position) -> f64 {
    const EARTH_RADIUS_M: f64 = 6_371_000.0;
    let mean_lat = ((a.latitude + b.latitude) / 2.0).to_radians();
    let dx = (b.longitude - a.longitude).to_radians() * mean_lat.cos();
    let dy = (b.latitude - a.latitude).to_radians();
    EARTH_RADIUS_M * dx.hypot(dy)
}

#[cfg(test)]
mod tests {
    use super::*;

    const HOME: Position = Position {
        latitude: 41.8781,
        longitude: -87.6298,
    };

    fn offset(p: Position, north_m: f64) -> Position {
        Position {
            latitude: p.latitude + north_m / 111_195.0,
            ..p
        }
    }

    fn gga(position: Position, sats: u8, hdop: f32, alt: f32) -> Sentence {
        Sentence::Gga(Gga {
            time: Some(0.0),
            quality: 1,
            satellites: Some(sats),
            hdop: Some(hdop),
            position: Some(position),
            altitude: Some(alt),
        })
    }

    #[test]
    fn accepts_good_fix() {
        let mut t = Tracker::default();
        let out = t.update(&gga(HOME, 8, 1.0, 180.0), Instant::now());
        let fix = out.fix.unwrap();
        assert_eq!((fix.latitude, fix.longitude, fix.altitude), (HOME.latitude, HOME.longitude, Some(180.0)));
        assert_eq!(out.sky.unwrap().satellites, Some(8));
    }

    #[test]
    fn rejects_no_fix_and_bad_geometry() {
        let mut t = Tracker::default();
        let now = Instant::now();
        let mut none = Gga {
            time: None,
            quality: 0,
            satellites: Some(2),
            hdop: None,
            position: None,
            altitude: None,
        };
        assert_eq!(t.update(&Sentence::Gga(none), now).fix, None);
        none.quality = 6; // dead reckoning
        none.position = Some(HOME);
        assert_eq!(t.update(&Sentence::Gga(none), now).fix, None);
        assert_eq!(t.update(&gga(HOME, 3, 25.0, 0.0), now).fix, None);
    }

    #[test]
    fn two_d_fix_keeps_last_altitude() {
        let mut t = Tracker::default();
        let now = Instant::now();
        t.update(&gga(HOME, 6, 1.0, 180.0), now);
        let fix = t.update(&gga(HOME, 3, 4.0, 900.0), now + Duration::from_secs(1)).fix.unwrap();
        assert_eq!(fix.altitude, Some(180.0));
    }

    #[test]
    fn ignores_single_glitch_but_follows_real_move() {
        let mut t = Tracker::default();
        let t0 = Instant::now();
        let s = |n| t0 + Duration::from_secs(n);
        t.update(&gga(HOME, 8, 1.0, 180.0), s(0));

        // One 5 km multipath glitch, then back home.
        let far = offset(HOME, 5_000.0);
        assert_eq!(t.update(&gga(far, 5, 3.0, 180.0), s(1)).fix, None);
        assert!(t.update(&gga(HOME, 8, 1.0, 180.0), s(2)).fix.is_some());

        // Consistently somewhere else: accepted on the third agreeing fix.
        assert_eq!(t.update(&gga(far, 8, 1.0, 180.0), s(3)).fix, None);
        assert_eq!(t.update(&gga(far, 8, 1.0, 180.0), s(4)).fix, None);
        assert!(t.update(&gga(far, 8, 1.0, 180.0), s(5)).fix.is_some());
    }

    #[test]
    fn long_outage_allows_large_move() {
        let mut t = Tracker::default();
        let t0 = Instant::now();
        t.update(&gga(HOME, 8, 1.0, 180.0), t0);
        let moved = offset(HOME, 20_000.0);
        assert!(t.update(&gga(moved, 8, 1.0, 180.0), t0 + Duration::from_secs(600)).fix.is_some());
    }

    #[test]
    fn rmc_sets_time_and_is_fallback_only() {
        let mut t = Tracker::default();
        let now = Instant::now();
        let rmc = Sentence::Rmc(Rmc {
            time: Some(3600.0),
            valid: true,
            position: Some(HOME),
            date: Some(20_000),
        });
        // No GGA seen yet, so RMC's position is used.
        let out = t.update(&rmc, now);
        assert_eq!(out.utc, Some(20_000 * 86_400 + 3600));
        assert!(out.fix.is_some());
        // With GGA flowing, RMC only provides time.
        t.update(&gga(HOME, 8, 1.0, 180.0), now);
        assert_eq!(t.update(&rmc, now).fix, None);
    }

    #[test]
    fn corrects_week_rollover() {
        // 2026-09-23 reported as 1024 weeks earlier.
        assert_eq!(correct_week_rollover(1_790_208_000 - ROLLOVER_SECS), 1_790_208_000);
        assert_eq!(correct_week_rollover(1_790_208_000), 1_790_208_000);
    }
}
