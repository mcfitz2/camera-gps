//! Hotshoe shutter logger for a film camera.
//!
//! Always on: watches the flash-sync contact, records each shot in RTC
//! memory, and advertises so the phone can collect it (see [`ble`]). Shots
//! the phone hasn't acknowledged stay flagged in the advertisement until it
//! does. Only the BLE radio is started; Wi-Fi and 802.15.4 stay powered down.
//!
//! Wiring (XIAO ESP32-C6): hotshoe centre contact to D0 (GPIO0), shoe rail to
//! GND. The internal pull-up holds D0 high until the shutter fires. The user
//! LED lights while the phone is connected.

#![no_std]
#![no_main]
#![deny(
    clippy::mem_forget,
    reason = "mem::forget is generally not safe to do with esp_hal types, especially those \
    holding buffers for the duration of a data transfer."
)]

mod ble;
mod shots;

use core::cell::RefCell;

use bt_hci::controller::ExternalController;
use embassy_executor::Spawner;
use embassy_futures::select::select3;
use embassy_sync::signal::Signal;
use embassy_time::{Duration, Timer};
use esp_backtrace as _;
use esp_hal::Persistable;
use esp_hal::clock::CpuClock;
use esp_hal::gpio::{Input, InputConfig, Level, Output, OutputConfig, Pull};
use esp_hal::rng::{Rng, Trng, TrngSource};
use esp_hal::rtc_cntl::Rtc;
use esp_hal::timer::timg::TimerGroup;
use esp_radio::ble::controller::BleConnector;
use log::{info, warn};
use trouble_host::prelude::*;

use shots::{Log, Shot};

extern crate alloc;

esp_bootloader_esp_idf::esp_app_desc!();

/// Contact bounce: a closure this soon after the contact opened is the same shot.
const DEBOUNCE: Duration = Duration::from_millis(150);

// SAFETY: plain integers and arrays of them; every bit pattern is a valid
// value, and `Log::is_valid` rejects garbage.
unsafe impl Persistable for Shot {}
unsafe impl Persistable for Log {}

#[esp_hal::ram(unstable(rtc_fast, persistent))]
static mut LOG: Log = Log::EMPTY;

#[esp_rtos::main]
async fn main(_spawner: Spawner) -> ! {
    esp_println::logger::init_logger_from_env();
    // The slowest clock the BLE controller runs at.
    let peripherals = esp_hal::init(esp_hal::Config::default().with_cpu_clock(CpuClock::_80MHz));
    let rtc = Rtc::new(peripherals.RTC_TIMER);
    let now_us = || rtc.time_since_power_up().as_micros();

    // SAFETY: the only reference to this static, taken once before anything
    // else can run.
    #[allow(clippy::deref_addrof, reason = "`&mut LOG` trips static_mut_refs")]
    let log = unsafe { &mut *&raw mut LOG };
    if log.is_valid() {
        info!("restart; {} pending", log.pending());
    } else {
        let boot_id = boot_id(peripherals.RNG, peripherals.ADC1);
        log.reset(boot_id);
        info!("power-up; boot id {boot_id:08x}");
    }

    esp_alloc::heap_allocator!(#[esp_hal::ram(reclaimed)] size: 65536);
    let timg0 = TimerGroup::new(peripherals.TIMG0);
    esp_rtos::start(timg0.timer0, peripherals.FROM_CPU_INTR0);

    let mut shutter = Input::new(peripherals.GPIO0, InputConfig::default().with_pull(Pull::Up));
    let mut led = Output::new(peripherals.GPIO15, Level::High, OutputConfig::default());
    let log = RefCell::new(log);
    let changed = Signal::new();
    let shared = ble::Shared { log: &log, changed: &changed, now_us: &now_us };

    let transport = match BleConnector::new(peripherals.BT, Default::default()) {
        Ok(t) => t,
        Err(e) => {
            // Keep recording shots; the phone gets them after a reset.
            warn!("bluetooth: {e:?}");
            watch(&mut shutter, &shared).await
        }
    };
    let controller = ExternalController::<_, 1>::new(transport);
    let mut resources: HostResources<_, DefaultPacketPool, { ble::CONNECTIONS_MAX }, { ble::L2CAP_CHANNELS_MAX }> =
        HostResources::new();
    let address = Address::random(static_address());
    info!("address {address:?}");
    let stack = trouble_host::new(controller, &mut resources).set_random_address(address).build();
    let mut runner = stack.runner();
    let server = ble::server();

    select3(
        async {
            if let Err(e) = runner.run().await {
                warn!("ble host: {e:?}");
            }
        },
        async {
            match &server {
                Some(server) => ble::serve(&stack, server, &shared, &mut led).await,
                None => core::future::pending().await,
            }
        },
        watch(&mut shutter, &shared),
    )
    .await;
    // Only the BLE host stops; a reset keeps the pending shots.
    warn!("restarting");
    esp_hal::system::software_reset()
}

/// Records shots.
async fn watch(shutter: &mut Input<'_>, shared: &ble::Shared<'_, '_>) -> ! {
    loop {
        if shutter.is_low() {
            shutter.wait_for_high().await;
            shared.log.borrow_mut().release((shared.now_us)());
            Timer::after(DEBOUNCE).await;
        }
        shutter.wait_for_low().await;
        {
            let mut log = shared.log.borrow_mut();
            let seq = log.record((shared.now_us)());
            info!("shot #{seq}; {} pending", log.pending());
        }
        shared.changed.signal(());
        Timer::after(DEBOUNCE).await;
    }
}

/// A random boot id. The plain RNG is only pseudo-random until the radio
/// runs, and a repeated id makes the phone drop new shots as duplicates, so
/// this briefly borrows ADC noise as an entropy source.
fn boot_id(rng: esp_hal::peripherals::RNG<'_>, adc: esp_hal::peripherals::ADC1<'_>) -> u32 {
    let _source = TrngSource::new(rng, adc);
    match Trng::try_new() {
        Ok(trng) => trng.random(),
        Err(e) => {
            warn!("trng: {e:?}");
            Rng::new().random()
        }
    }
}

/// A static random address derived from the chip's MAC, so the phone sees
/// the same device every time. Little-endian; the top two bits mark it static.
fn static_address() -> [u8; 6] {
    let mac = esp_hal::efuse::base_mac_address();
    let mut addr = [0u8; 6];
    for (a, m) in addr.iter_mut().zip(mac.as_bytes().iter().rev()) {
        *a = *m;
    }
    addr[5] |= 0xc0;
    addr
}
