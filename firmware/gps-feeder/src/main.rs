//! Standalone GPS feeder for Canon EOS cameras.
//!
//! Reads position and time from a GPS receiver, keeps trying to connect to a
//! hardcoded camera over BLE and, once connected, pushes location + UTC time
//! the same way Canon's Camera Connect app does.

mod board;
mod camera;
mod console;
mod geo;
mod gps;
mod identity;
mod nmea;
mod state;
mod ui;

use std::sync::mpsc;

use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::sys::{esp, esp_pm_config_t, esp_pm_configure};
use log::{info, warn};

/// CPU clock while busy, and while idle. 80 MHz keeps APB (and so UART baud
/// rates) fixed, so the GPS link survives frequency changes.
const CPU_MAX_MHZ: i32 = 160;
const CPU_MIN_MHZ: i32 = 80;

fn main() -> anyhow::Result<()> {
    esp_idf_svc::sys::link_patches();
    esp_idf_svc::log::EspLogger::initialize_default();

    if let Err(e) = configure_power() {
        warn!("power management: {e}");
    }

    let shared = state::Shared::default();
    let (tx, rx) = mpsc::channel();

    let board = board::init(Peripherals::take()?)?;
    gps::spawn(board.gps, shared.clone())?;
    console::spawn(shared.clone(), tx.clone())?;
    if ui::ENABLED {
        ui::spawn(board.hardware, shared.clone(), tx)?;
    }

    info!("camera-gps started");
    camera::run(shared, rx)
}

/// Scale the CPU clock down when idle. No automatic light sleep: it would
/// drop GPS bytes arriving on the UART.
fn configure_power() -> anyhow::Result<()> {
    let config = esp_pm_config_t {
        max_freq_mhz: CPU_MAX_MHZ,
        min_freq_mhz: CPU_MIN_MHZ,
        light_sleep_enable: false,
    };
    // SAFETY: the config is only read during the call.
    esp!(unsafe { esp_pm_configure(core::ptr::from_ref(&config).cast()) })?;
    Ok(())
}
