//! Canon EOS "smartphone" BLE link: pairing, reconnection and location updates.
//!
//! Protocol from furble (https://github.com/gkoh/furble), `CanonEOSSmart`.

use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::mpsc::Receiver;
use std::sync::Arc;
use std::time::{Duration, Instant};

use esp32_nimble::enums::{AuthReq, PowerLevel, PowerType, ScanFilterPolicy, SecurityIOCap};
use esp32_nimble::utilities::BleUuid;
use esp32_nimble::{uuid128, BLEAddress, BLEAddressType, BLEClient, BLEDevice, BLEError, BLEScan};
use esp_idf_svc::hal::delay::FreeRtos;
use embassy_futures::select::{select, Either};
use esp_idf_svc::hal::task::block_on;
use esp_idf_svc::timer::{EspAsyncTimer, EspTaskTimerService};
use log::{info, warn};

use crate::geo;
use crate::identity::Identity;
use crate::state::{self, Command, Link, Shared};

/// The camera to follow. Hardcoded until there's a UI for choosing one.
const CAMERA_ADDR: &str = "40:f8:df:a0:ac:b8";

const PRI_SVC: BleUuid = uuid128!("00010000-0000-1000-0000-d8492fffa821");
const CHR_NAME: BleUuid = uuid128!("00010006-0000-1000-0000-d8492fffa821");
const CHR_IDEN: BleUuid = uuid128!("0001000a-0000-1000-0000-d8492fffa821");
const MODE_SVC: BleUuid = uuid128!("00030000-0000-1000-0000-d8492fffa821");
const CHR_MODE: BleUuid = uuid128!("00030010-0000-1000-0000-d8492fffa821");
const GEO_SVC: BleUuid = uuid128!("00040000-0000-1000-0000-d8492fffa821");
const GEO_CHR: BleUuid = uuid128!("00040002-0000-1000-0000-d8492fffa821");
const GEO_IND: BleUuid = uuid128!("00040003-0000-1000-0000-d8492fffa821");

const PAIR_ACCEPT: u8 = 0x02;
const MODE_SHOOT: u8 = 0x02;
const GEO_REQUEST: u8 = 0x03;
const GEO_SUCCESS: u8 = 0x02;
const GEO_ENABLE: u8 = 0x01;

/// Canon's Bluetooth SIG company identifier, in its advertising data.
const CANON_COMPANY_ID: u16 = 0x01A9;
/// Last byte of Canon's manufacturer data while the camera is powered off.
/// It keeps advertising (every ~1.2 s instead of ~60 ms) but drops any
/// connection after ~3 s. 0x02 is seen while on; anything else is tried.
const STATE_OFF: u8 = 0x05;

/// Low-duty passive scan while waiting for the camera to turn on. The camera
/// advertises every ~60 ms when on, so a window this long almost always
/// catches it, and at 10% duty it is found within a second or two. The
/// interval shares no large factor with the ~1200 ms off-state period, so
/// those advertisements drift through the window instead of always missing it.
const SCAN_INTERVAL_MS: u16 = 770;
const SCAN_WINDOW_MS: u16 = 80;
/// Scan in slices this long so commands and a lost fix are noticed.
const SCAN_SLICE_MS: i32 = 2_000;
/// The low-duty scan catches only ~1 in 10 of the off camera's slow
/// advertisements (seen 40-60 s apart), so remember "off" for this long.
const OFF_MEMORY: Duration = Duration::from_secs(120);

/// Pause after a failed session so a camera that keeps refusing us isn't
/// hammered; disconnects (the camera turning off) rescan immediately.
const RETRY_DELAY_MS: u32 = 1_000;
/// A connect to an advertising camera completes in ~200 ms, but some attempts
/// never complete, even 20 s after the camera powered on (and NimBLE's own
/// timeout has been seen not to fire). A fresh attempt usually succeeds, so
/// give up quickly and try again.
const CONNECT_TIMEOUT: Duration = Duration::from_millis(1_500);
/// Connection interval requested for the handshake, in 1.25 ms units. Every
/// GATT round trip costs about two intervals and the handshake needs ~25 of
/// them; NimBLE's default (30-50 ms) made it take ~3 s. The camera requests
/// its own, slower, interval once set up.
const CONN_ITVL_MIN: u16 = 6;
const CONN_ITVL_MAX: u16 = 12;
/// Supervision timeout, in 10 ms units (NimBLE's default).
const CONN_SUPERVISION_TIMEOUT: u16 = 256;
/// Scan while initiating, in 0.625 ms units: continuous (NimBLE's default).
const CONN_SCAN_ITVL: u16 = 16;
const CONN_SCAN_WINDOW: u16 = 16;
const APPROVAL_TIMEOUT: Duration = Duration::from_secs(60);
const SEND_INTERVAL: Duration = Duration::from_secs(10);
const POLL_MS: u32 = 100;
/// Consecutive rejected writes before dropping the link and reconnecting.
/// The M50 II starts rejecting writes in some states; a fresh connection clears it.
const MAX_WRITE_FAILURES: u32 = 3;

#[derive(Debug)]
enum SessionEnd {
    ConnectTimedOut,
    Disconnected,
    Requested,
    Failed(String),
}

impl From<BLEError> for SessionEnd {
    fn from(e: BLEError) -> Self {
        Self::Failed(format!("{e:?}"))
    }
}

/// Flags set from NimBLE callbacks and consumed by the session loop.
#[derive(Default)]
struct Events {
    pair_result: AtomicU8,
    geo_requested: AtomicBool,
    geo_enabled: AtomicBool,
}

/// Connect to the camera forever, feeding it location while connected.
pub fn run(shared: Shared, commands: Receiver<Command>) -> ! {
    let identity = Identity::from_chip();
    info!("device name {}", identity.name);
    let camera = BLEAddress::from_str(CAMERA_ADDR, BLEAddressType::Public)
        .expect("CAMERA_ADDR is a valid address literal");

    let device = BLEDevice::take();
    // Only the camera's advertisements reach the host, so the CPU stays idle
    // while scanning. Connecting by address doesn't use the list.
    if let Err(e) = device.set_white_list(&[camera]) {
        warn!("set white list: {e:?}");
    }
    if let Err(e) = BLEDevice::set_device_name(&identity.name) {
        warn!("set device name: {e:?}");
    }
    if let Err(e) = device.set_power(PowerType::Default, PowerLevel::P9) {
        warn!("set tx power: {e:?}");
    }
    device
        .security()
        .set_auth(AuthReq::all())
        .set_io_cap(SecurityIOCap::DisplayYesNo);

    let timers = EspTaskTimerService::new().expect("timer service");
    let mut timer = timers.timer_async().expect("async timer");
    let mut client = device.new_client();
    client.set_connection_params(
        CONN_ITVL_MIN,
        CONN_ITVL_MAX,
        0,
        CONN_SUPERVISION_TIMEOUT,
        CONN_SCAN_ITVL,
        CONN_SCAN_WINDOW,
    );
    client.on_confirm_pin(|pin| {
        info!("confirming passkey {pin:06}");
        true
    });

    let mut waiting_logged = false;
    let mut last_off = None;
    loop {
        // The camera terminates links that don't deliver location within a few
        // seconds, and then ignores connection attempts for a while.
        if !ready_to_send(&shared) {
            if !waiting_logged {
                info!("waiting for a GPS fix before connecting");
                waiting_logged = true;
            }
            shared.lock().link = Link::WaitingForFix;
            handle_offline_commands(device, &camera, &commands);
            FreeRtos::delay_ms(POLL_MS * 10);
            continue;
        }
        waiting_logged = false;

        if !wait_until_on(device, &camera, &shared, &commands, &mut last_off) {
            continue;
        }

        shared.lock().link = Link::Connecting;
        let end = block_on(session(device, &mut client, &mut timer, &camera, &identity, &shared, &commands));
        match &end {
            SessionEnd::Failed(e) => info!("camera session ended: {e}"),
            other => info!("camera session ended: {other:?}"),
        }
        if client.connected() {
            let _ = client.disconnect();
        }
        {
            let mut s = shared.lock();
            s.link = Link::Disconnected;
            s.sends_ok = 0;
        }
        handle_offline_commands(device, &camera, &commands);
        if matches!(end, SessionEnd::Failed(_)) {
            FreeRtos::delay_ms(RETRY_DELAY_MS);
        }
    }
}

/// Scan for up to one slice. Returns true once the camera advertises that it
/// is on; false when the slice ends without that, to let the caller recheck
/// its preconditions.
fn wait_until_on(
    device: &BLEDevice,
    camera: &BLEAddress,
    shared: &Shared,
    commands: &Receiver<Command>,
    last_off: &mut Option<Instant>,
) -> bool {
    let mut scan = BLEScan::new();
    scan.active_scan(false)
        .filter_duplicates(false)
        .filter_policy(ScanFilterPolicy::UseWl)
        .interval(SCAN_INTERVAL_MS)
        .window(SCAN_WINDOW_MS);
    let found = block_on(scan.start(device, SCAN_SLICE_MS, |dev, data| {
        if dev.addr().as_le_bytes() != camera.as_le_bytes() {
            return None;
        }
        let state = data
            .manufacture_data()
            .filter(|m| m.company_identifier == CANON_COMPANY_ID)
            .and_then(|m| m.payload.last().copied());
        if state == Some(STATE_OFF) {
            *last_off = Some(Instant::now());
            None
        } else {
            Some(state)
        }
    }));

    let on = match found {
        Ok(Some(state)) => {
            info!("camera is on (state {state:02x?}); connecting");
            true
        }
        Ok(None) => false,
        Err(e) => {
            warn!("scan: {e:?}");
            FreeRtos::delay_ms(RETRY_DELAY_MS);
            false
        }
    };
    if !on {
        let off = last_off.is_some_and(|t| t.elapsed() < OFF_MEMORY);
        let link = if off { Link::CameraOff } else { Link::Disconnected };
        let mut s = shared.lock();
        if s.link != link {
            info!("camera {}", if off { "is off" } else { "not seen" });
        }
        s.link = link;
    }
    handle_offline_commands(device, camera, commands);
    on
}

fn ready_to_send(shared: &Shared) -> bool {
    shared.lock().current_fix().is_some() && state::utc_now().is_some()
}

/// Handle anything queued while not connected, e.g. `forget`.
fn handle_offline_commands(device: &BLEDevice, camera: &BLEAddress, commands: &Receiver<Command>) {
    while let Ok(cmd) = commands.try_recv() {
        match cmd {
            Command::Forget => forget(device, camera),
            Command::Survey(secs) => survey(device, camera, secs),
            _ => {}
        }
    }
}

/// Log every advertisement from the camera, for tuning scan parameters.
fn survey(device: &BLEDevice, camera: &BLEAddress, secs: u16) {
    info!("survey: scanning {secs} s for {camera:?}");
    let start = Instant::now();
    let mut last: Option<Instant> = None;
    let mut count = 0u32;
    let mut scan = BLEScan::new();
    scan.active_scan(false).filter_duplicates(false).interval(100).window(100);
    let result = block_on(scan.start(device, i32::from(secs) * 1000, |dev, data| {
        if dev.addr().as_le_bytes() == camera.as_le_bytes() {
            let now = Instant::now();
            let gap = last.map_or(0, |t| now.duration_since(t).as_millis());
            last = Some(now);
            count += 1;
            info!(
                "survey: t={} gap={gap} {:?} rssi={} {:02x?}",
                start.elapsed().as_millis(),
                dev.adv_type(),
                dev.rssi(),
                data.payload()
            );
        }
        None::<()>
    }));
    info!("survey: done, {count} advertisements ({result:?})");
}

fn forget(device: &BLEDevice, camera: &BLEAddress) {
    match device.delete_bond(camera) {
        Ok(()) => info!("bond deleted; camera must be put in pairing mode"),
        Err(e) => warn!("delete bond: {e:?}"),
    }
}

fn is_bonded(device: &BLEDevice, camera: &BLEAddress) -> bool {
    device
        .bonded_addresses()
        .map(|addrs| addrs.iter().any(|a| a.as_le_bytes() == camera.as_le_bytes()))
        .unwrap_or(false)
}

async fn session(
    device: &BLEDevice,
    client: &mut BLEClient,
    timer: &mut EspAsyncTimer,
    camera: &BLEAddress,
    identity: &Identity,
    shared: &Shared,
    commands: &Receiver<Command>,
) -> SessionEnd {
    let events = Arc::new(Events::default());
    if let Err(end) = handshake(device, client, timer, camera, identity, shared, &events).await {
        return end;
    }
    info!("camera connected");
    shared.lock().link = Link::Connected { geo_enabled: false };

    let mut last_send: Option<Instant> = None;
    let mut write_failures = 0;
    loop {
        if !client.connected() {
            return SessionEnd::Disconnected;
        }

        let mut send_now = false;
        while let Ok(cmd) = commands.try_recv() {
            match cmd {
                Command::SendNow => send_now = true,
                Command::Reconnect => return SessionEnd::Requested,
                Command::Survey(secs) => {
                    let _ = client.disconnect();
                    // Let the disconnect complete so the camera resumes advertising.
                    FreeRtos::delay_ms(500);
                    survey(device, camera, secs);
                    return SessionEnd::Requested;
                }
                Command::Forget => {
                    let _ = client.disconnect();
                    forget(device, camera);
                    return SessionEnd::Requested;
                }
            }
        }

        if events.geo_requested.swap(false, Ordering::AcqRel) {
            info!("camera requested location; enabling");
            if let Err(e) = write_geo(client, &[GEO_ENABLE]).await {
                warn!("enable location: {e:?}");
            }
        }

        let geo_enabled = events.geo_enabled.load(Ordering::Acquire);
        shared.lock().link = Link::Connected { geo_enabled };

        let due = last_send.is_none_or(|t| t.elapsed() >= SEND_INTERVAL);
        if geo_enabled && (due || send_now) {
            last_send = Some(Instant::now());
            match send_location(client, shared).await {
                Ok(true) => write_failures = 0,
                Ok(false) => {}
                Err(e) => {
                    write_failures += 1;
                    warn!("location write failed ({write_failures}/{MAX_WRITE_FAILURES}): {e:?}");
                    if write_failures >= MAX_WRITE_FAILURES {
                        return SessionEnd::Failed("camera keeps rejecting location".into());
                    }
                }
            }
        }

        FreeRtos::delay_ms(POLL_MS);
    }
}

/// Connect, secure, identify, and (first time only) wait for the user to
/// approve pairing on the camera.
async fn handshake(
    device: &BLEDevice,
    client: &mut BLEClient,
    timer: &mut EspAsyncTimer,
    camera: &BLEAddress,
    identity: &Identity,
    shared: &Shared,
    events: &Arc<Events>,
) -> Result<(), SessionEnd> {
    let bonded = is_bonded(device, camera);
    if bonded {
        events.pair_result.store(PAIR_ACCEPT, Ordering::Release);
    }

    match select(client.connect(camera), timer.after(CONNECT_TIMEOUT)).await {
        Either::First(result) => result?,
        Either::Second(_) => {
            // SAFETY: plain FFI call; fails harmlessly if no connect is pending.
            unsafe { esp_idf_svc::sys::ble_gap_conn_cancel() };
            return Err(SessionEnd::ConnectTimedOut);
        }
    }
    client.secure_connection().await?;

    {
        let ev = Arc::clone(events);
        let chr = client.get_service(PRI_SVC).await?.get_characteristic(CHR_NAME).await?;
        chr.on_notify(move |data| {
            if let Some(&result) = data.first() {
                info!("pairing result 0x{result:02x}");
                ev.pair_result.store(result, Ordering::Release);
            }
        });
        chr.subscribe_indicate(true).await?;
    }

    let name = identity.name.as_bytes();
    write_prefixed(client, CHR_NAME, 0x01, name).await?;
    write_prefixed(client, CHR_IDEN, 0x03, &identity.uuid).await?;
    write_prefixed(client, CHR_IDEN, 0x04, name).await?;
    write_prefixed(client, CHR_IDEN, 0x05, &[0x02]).await?;

    if !bonded {
        info!("confirm pairing with {} on the camera", identity.name);
        shared.lock().link = Link::AwaitingApproval;
        let start = Instant::now();
        while events.pair_result.load(Ordering::Acquire) == 0 && start.elapsed() < APPROVAL_TIMEOUT {
            FreeRtos::delay_ms(POLL_MS);
        }
        let result = events.pair_result.load(Ordering::Acquire);
        if result != PAIR_ACCEPT {
            forget(device, camera);
            return Err(SessionEnd::Failed(format!("pairing not accepted (0x{result:02x})")));
        }
    }

    {
        let ev = Arc::clone(events);
        let chr = client.get_service(GEO_SVC).await?.get_characteristic(GEO_IND).await?;
        chr.on_notify(move |data| match data.first() {
            Some(&GEO_REQUEST) => ev.geo_requested.store(true, Ordering::Release),
            Some(&GEO_SUCCESS) => ev.geo_enabled.store(true, Ordering::Release),
            other => info!("location indication {other:02x?}"),
        });
        chr.subscribe_indicate(true).await?;
    }

    client
        .get_service(PRI_SVC)
        .await?
        .get_characteristic(CHR_IDEN)
        .await?
        .write_value(&[0x01], true)
        .await?;
    client
        .get_service(MODE_SVC)
        .await?
        .get_characteristic(CHR_MODE)
        .await?
        .write_value(&[MODE_SHOOT], true)
        .await?;
    Ok(())
}

async fn write_prefixed(
    client: &mut BLEClient,
    chr: BleUuid,
    prefix: u8,
    data: &[u8],
) -> Result<(), BLEError> {
    let mut buf = Vec::with_capacity(data.len() + 1);
    buf.push(prefix);
    buf.extend_from_slice(data);
    client
        .get_service(PRI_SVC)
        .await?
        .get_characteristic(chr)
        .await?
        .write_value(&buf, true)
        .await
}

async fn write_geo(client: &mut BLEClient, data: &[u8]) -> Result<(), BLEError> {
    client
        .get_service(GEO_SVC)
        .await?
        .get_characteristic(GEO_CHR)
        .await?
        .write_value(data, true)
        .await
}

/// Send the current fix. Returns `Ok(false)` if skipped for lack of a usable
/// fix or clock.
async fn send_location(client: &mut BLEClient, shared: &Shared) -> Result<bool, BLEError> {
    let Some(fix) = shared.lock().current_fix() else {
        info!("no usable GPS fix; skipping location update");
        return Ok(false);
    };
    let Some(now) = state::utc_now() else {
        warn!("clock not set; skipping location update");
        return Ok(false);
    };
    let epoch = u32::try_from(now.as_secs()).unwrap_or(u32::MAX);
    write_geo(client, &geo::encode(&fix, epoch)).await?;
    shared.lock().sends_ok += 1;
    info!(
        "sent {:.6},{:.6} alt {:.1} t={epoch}",
        fix.latitude, fix.longitude, fix.altitude
    );
    Ok(true)
}
