//! NMEA GPS receiver (GT-U7 / u-blox NEO-6M class) on a UART.
//!
//! Reads sentences, filters them through [`tracker::Tracker`], publishes
//! accepted fixes and satellite status, and keeps the system clock on GPS time.

pub mod tracker;

use std::thread;
use std::time::{Duration, Instant};

use esp_idf_svc::hal::delay::TickType;
use esp_idf_svc::hal::uart::UartDriver;
use esp_idf_svc::sys::ESP_ERR_TIMEOUT;
use log::{info, warn};

use crate::nmea;
use crate::state::{self, Fix, FixSource, Shared};
use tracker::Tracker;

/// GT-U7 factory default.
pub const BAUD: u32 = 9600;
/// Longest valid NMEA sentence is 82 characters; allow some slack.
const MAX_LINE: usize = 120;
const READ_TIMEOUT: Duration = Duration::from_millis(200);
/// Only step the system clock when it has drifted at least this far.
const CLOCK_TOLERANCE_SECS: i64 = 2;

pub fn spawn(uart: UartDriver<'static>, shared: Shared) -> anyhow::Result<()> {
    thread::Builder::new()
        .name("gps".into())
        .stack_size(6 * 1024)
        .spawn(move || run(&uart, &shared))?;
    Ok(())
}

fn run(uart: &UartDriver<'_>, shared: &Shared) {
    let timeout = TickType::from(READ_TIMEOUT).0;
    let mut tracker = Tracker::default();
    let mut buf = [0u8; 256];
    let mut line = Vec::with_capacity(MAX_LINE);
    let mut had_fix = false;
    // Assume data until proven otherwise, so a missing receiver is reported once.
    let mut was_receiving = true;
    let started = Instant::now();

    loop {
        let n = match uart.read(&mut buf, timeout) {
            Ok(n) => n,
            Err(e) if e.code() == ESP_ERR_TIMEOUT => 0,
            Err(e) => {
                warn!("gps uart: {e}");
                thread::sleep(READ_TIMEOUT);
                0
            }
        };

        for &b in &buf[..n] {
            match b {
                // Start of a sentence; drops any partial line lost to noise.
                b'$' => {
                    line.clear();
                    line.push(b);
                }
                b'\n' => {
                    if let Some(sentence) = nmea::parse(&line) {
                        let now = Instant::now();
                        let out = tracker.update(&sentence, now);
                        publish(shared, &out, now, &mut had_fix);
                    }
                    line.clear();
                }
                _ if line.is_empty() => {}
                _ if line.len() >= MAX_LINE || !(b == b'\r' || b.is_ascii_graphic() || b == b' ') => {
                    line.clear();
                }
                _ => line.push(b),
            }
        }

        let receiving = shared.lock().gps.receiving();
        if receiving != was_receiving && (receiving || started.elapsed() > READ_TIMEOUT * 25) {
            if receiving {
                info!("gps: receiving data");
            } else {
                warn!("gps: no data from receiver; check wiring");
            }
            was_receiving = receiving;
        }
    }
}

fn publish(shared: &Shared, out: &tracker::Output, now: Instant, had_fix: &mut bool) {
    {
        let mut s = shared.lock();
        s.gps.last_data = Some(now);
        if let Some(sky) = out.sky {
            s.gps.satellites = sky.satellites;
            s.gps.hdop = sky.hdop;
        }
        if let Some(fix) = out.fix {
            // Without any 3D fix yet, reuse a previous altitude before falling back to 0.
            let altitude = fix
                .altitude
                .or_else(|| s.fix.map(|(f, _)| f.altitude))
                .unwrap_or(0.0);
            s.fix = Some((
                Fix {
                    latitude: fix.latitude,
                    longitude: fix.longitude,
                    altitude,
                },
                FixSource::Gps { at: now },
            ));
        }
    }

    if let Some(fix) = out.fix {
        if !*had_fix {
            info!("gps: fix {:.6},{:.6} hdop {:?}", fix.latitude, fix.longitude, fix.hdop);
            *had_fix = true;
        }
    }

    if let Some(utc) = out.utc {
        let drift = state::utc_now().map_or(i64::MAX, |t| (t.as_secs() as i64 - utc).abs());
        if drift >= CLOCK_TOLERANCE_SECS {
            match state::set_utc(utc) {
                Ok(()) => info!("gps: clock set to {utc}"),
                Err(e) => warn!("gps: {e}"),
            }
        }
    }
}
