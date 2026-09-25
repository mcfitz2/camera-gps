# Camera GPS

Geotagging for cameras that can't do it themselves:

- **Canon EOS (digital):** the Android app feeds the phone's location and time to the camera over Bluetooth LE, the way Canon's Camera Connect app does, so photos are geotagged in-camera.
- **Film cameras:** a small ESP32-C6 in the hotshoe detects each shutter release. The app collects the shots, adds time and location to each one, and stores them as numbered frames on a named roll, which you can export to CSV to match up with scans.

## Layout

| Path | What |
|---|---|
| [`android/`](android) | The Android app (Kotlin, Jetpack Compose, Room). |
| [`firmware/shutter/`](firmware/shutter) | Hotshoe shutter logger for a Seeed XIAO ESP32-C6 (Rust, `no_std`, esp-hal, trouble-host). |

## Android app

Written for a single phone (Pixel 8 Pro, Android 14+; `minSdk` 34).

- The camera and the shutter logger are paired through the companion device manager. After that, system-held Bluetooth scans wake the app when either one needs it, so nothing has to stay running.
- **Film tab:** the roll in the camera and past rolls, with the shutter logger behind the chip in the app bar. Frames are numbered per roll and named by place (reverse-geocoded), rolls can be edited and finished, frames can be annotated, inserted or deleted, and each roll exports to CSV.
- **Camera tab:** pair the Canon camera once. The app then connects whenever the camera is on and sends location updates until it turns off.

Build:

```sh
cd android
./gradlew assembleDebug testDebugUnitTest
```

### Releases

Pushing a tag like `v1.0.0` runs [`android-release.yml`](.github/workflows/android-release.yml), which publishes the APK as a GitHub release.

To sign releases with a stable key, add these repository secrets:

- `RELEASE_KEYSTORE_BASE64` (the keystore, base64-encoded)
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

Without them the release APK is debug-signed.

## Shutter logger (`firmware/shutter`)

**Hardware:**

- Seeed XIAO ESP32-C6, powered over USB-C.
- Hotshoe centre contact to **D0**, shoe rail to **GND**.
- The internal pull-up holds D0 high until the flash-sync contact closes.

**How it works:**

- Always on, with only the BLE radio running. It records every contact closure as a shot, with a sequence number and how long the contact stayed closed.
- Shots are kept in RTC memory until the phone acknowledges them, so resets and reflashes don't lose them.
- The advertisement carries a "shots pending" flag, and the app's background scan only matches while it's set.
- There's no clock on the device. The phone reads each shot's *age* and works out the time from that.
- The user LED lights while the phone is connected.

**BLE protocol:**

- Service `8a1d0001-4f3c-4b8e-9a61-2c7e5b3d9f40`.
- `…0002` events (read): `boot_id u32, count u8`, then per shot `seq u32, age_ms u32, contact_ms u32`, all little-endian.
- `…0003` ack (write): `seq u32`, which drops that shot and every earlier one.
- Manufacturer data, company `0xFFFF`: one byte, `1` while shots are pending.

Build and flash (stable Rust; `rust-toolchain.toml` adds the target):

```sh
cd firmware/shutter
cargo run --release              # builds, flashes with espflash, opens the monitor
cd host-tests && cargo test --target "$(rustc -vV | sed -n 's/host: //p')"
```

## Credits

The Canon BLE protocol follows [furble](https://github.com/gkoh/furble).
