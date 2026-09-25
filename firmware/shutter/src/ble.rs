//! Hands pending shots to the phone.
//!
//! The device advertises the shutter service; the phone connects, reads
//! `events` (see [`Log::encode`] for the format), and writes the last
//! sequence number it stored to `ack`. Nothing is bonded: the data isn't
//! secret and skipping pairing keeps the exchange to a few hundred ms.

use core::cell::RefCell;

use embassy_futures::select::{Either, select};
use embassy_sync::blocking_mutex::raw::NoopRawMutex;
use embassy_sync::signal::Signal;
use embassy_time::{Duration, Timer};
use esp_hal::gpio::Output;
use log::{info, warn};
use trouble_host::att::{AttClient, AttReq};
use trouble_host::prelude::*;

use crate::shots::{HEADER_LEN, Log, RECORD_LEN};

/// Service UUID, also advertised so the phone's background scan can match it.
const SERVICE_UUID: [u8; 16] = 0x8a1d0001_4f3c_4b8e_9a61_2c7e5b3d9f40u128.to_le_bytes();

/// Shots per read. Fits a 247-byte ATT MTU, which the phone asks for.
const EVENTS_PER_READ: usize = 16;
const EVENTS_LEN: usize = HEADER_LEN + EVENTS_PER_READ * RECORD_LEN;

/// Manufacturer data company ID, the one reserved for testing. The payload
/// is one byte, 1 while shots are pending, so the phone's background scan
/// can match on it and leave the logger alone otherwise.
const COMPANY_ID: u16 = 0xffff;

/// Advertising interval while shots are pending: often enough for the phone's
/// screen-off scan to catch it within a second or two.
const PENDING_INTERVAL: Duration = Duration::from_millis(100);
/// Advertising interval otherwise, only for pairing and "Collect now".
const IDLE_INTERVAL: Duration = Duration::from_millis(1000);

/// Drop a connection that sends nothing for this long, so an idle client
/// can't keep the logger from advertising. Well above the phone's 15 s wait
/// for a location fix between reading and acking.
const LINK_IDLE: Duration = Duration::from_secs(30);

pub const CONNECTIONS_MAX: usize = 1;
pub const L2CAP_CHANNELS_MAX: usize = 1;

#[gatt_server]
pub struct Server {
    shutter: ShutterService,
}

#[gatt_service(uuid = "8a1d0001-4f3c-4b8e-9a61-2c7e5b3d9f40")]
struct ShutterService {
    #[characteristic(uuid = "8a1d0002-4f3c-4b8e-9a61-2c7e5b3d9f40", read, value = [0; EVENTS_LEN])]
    events: [u8; EVENTS_LEN],
    #[characteristic(uuid = "8a1d0003-4f3c-4b8e-9a61-2c7e5b3d9f40", write)]
    ack: u32,
}

/// What the phone and the shutter share while awake.
pub struct Shared<'a, 'l> {
    pub log: &'a RefCell<&'l mut Log>,
    /// Signalled on each new shot, to update the advertisement.
    pub changed: &'a Signal<NoopRawMutex, ()>,
    pub now_us: &'a dyn Fn() -> u64,
}

/// Builds the GATT server. Call once: the macro keeps the `events` buffer in
/// a static, so a second server panics.
pub fn server() -> Option<Server<'static>> {
    Server::new_with_config(GapConfig::Peripheral(PeripheralConfig {
        name: "Shutter",
        appearance: &appearance::UNKNOWN,
    }))
    .inspect_err(|e| warn!("gatt server: {e:?}"))
    .ok()
}

/// Advertises and serves the phone, forever.
pub async fn serve<C: Controller>(
    stack: &Stack<'_, C, DefaultPacketPool>,
    server: &Server<'_>,
    shared: &Shared<'_, '_>,
    led: &mut Output<'_>,
) -> ! {
    let mut peripheral = stack.peripheral();

    let mut scan_data = [0; 31];
    let scan_len = AdStructure::encode_slice(&[AdStructure::CompleteLocalName(b"Shutter")], &mut scan_data)
        .unwrap_or(0);

    loop {
        let pending = shared.log.borrow().pending() > 0;
        let mut adv_data = [0; 31];
        let adv_len = AdStructure::encode_slice(
            &[
                AdStructure::Flags(LE_GENERAL_DISCOVERABLE | BR_EDR_NOT_SUPPORTED),
                AdStructure::CompleteServiceUuids128(&[SERVICE_UUID]),
                AdStructure::ManufacturerSpecificData { company_identifier: COMPANY_ID, payload: &[pending as u8] },
            ],
            &mut adv_data,
        )
        .unwrap_or(0);
        let interval = if pending { PENDING_INTERVAL } else { IDLE_INTERVAL };
        let params = AdvertisementParameters { interval_min: interval, interval_max: interval, ..Default::default() };

        let advertiser = match peripheral
            .advertise(
                &params,
                Advertisement::ConnectableScannableUndirected {
                    adv_data: &adv_data[..adv_len],
                    scan_data: &scan_data[..scan_len],
                },
            )
            .await
        {
            Ok(a) => a,
            Err(e) => {
                warn!("advertise: {e:?}");
                Timer::after_secs(1).await;
                continue;
            }
        };
        // A new shot restarts advertising with the flag set.
        let conn = match select(advertiser.accept(), shared.changed.wait()).await {
            Either::First(Ok(conn)) => conn,
            Either::First(Err(e)) => {
                warn!("accept: {e:?}");
                continue;
            }
            Either::Second(()) => continue,
        };
        let conn = match conn.with_attribute_server(server) {
            Ok(conn) => conn,
            Err(e) => {
                warn!("attribute server: {e:?}");
                continue;
            }
        };
        info!("phone connected");
        // The user LED is active-low.
        led.set_low();
        session(server, &conn, shared).await;
        led.set_high();
    }
}

async fn session(server: &Server<'_>, conn: &GattConnection<'_, '_, DefaultPacketPool>, shared: &Shared<'_, '_>) {
    let events = &server.shutter.events;
    let ack = &server.shutter.ack;
    loop {
        let event = match select(conn.next(), Timer::after(LINK_IDLE)).await {
            Either::First(event) => event,
            Either::Second(()) => {
                warn!("phone idle; disconnecting");
                conn.raw().disconnect();
                return;
            }
        };
        match event {
            GattConnectionEvent::Disconnected { reason } => {
                info!("phone disconnected: {reason:?}");
                return;
            }
            GattConnectionEvent::Gatt { event } => {
                // Refresh ages on a fresh read; blob reads continue the value
                // the first read produced.
                if let GattEvent::Read(read) = &event
                    && read.handle() == events.handle
                    && matches!(read.payload().incoming(), AttClient::Request(AttReq::Read { .. }))
                {
                    let mut value = [0u8; EVENTS_LEN];
                    shared.log.borrow().encode((shared.now_us)(), &mut value);
                    if let Err(e) = server.set(events, &value) {
                        warn!("set events: {e:?}");
                    }
                }
                let acked = matches!(&event, GattEvent::Write(write) if write.handle() == ack.handle);
                match event.accept() {
                    Ok(reply) => reply.send().await,
                    Err(e) => warn!("reply: {e:?}"),
                }
                if acked {
                    match server.get(ack) {
                        Ok(seq) => {
                            let mut log = shared.log.borrow_mut();
                            log.ack(seq);
                            info!("phone stored up to #{seq}; {} pending", log.pending());
                        }
                        Err(e) => warn!("read ack: {e:?}"),
                    }
                }
            }
            _ => {}
        }
    }
}
