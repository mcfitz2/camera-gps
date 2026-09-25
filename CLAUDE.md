# CLAUDE.md

## Layout

- `android/`: Android app. Kotlin, Jetpack Compose, Room 2.7 with KSP, minSdk 34.
- `firmware/shutter/`: ESP32-C6 shutter logger. Rust `no_std`, esp-hal, trouble-host.

## Commands

| Purpose | Command | Notes |
|---|---|---|
| Android CI gate | `./gradlew assembleDebug testDebugUnitTest lintDebug` | Run from `android/`. |
| Android JDK | `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` | Needed on the maintainer's Mac when no system JDK is set up. |
| Firmware build | `cargo build --release` | Run from `firmware/shutter/`. |
| Firmware lint | `cargo clippy --release -- -D warnings` | Run from `firmware/shutter/`. |
| Firmware flash | `cargo run --release` | Flashes the board with espflash and opens the monitor. |
| Firmware host tests | `cargo test --target "$(rustc -vV \| sed -n 's/host: //p')"` | Run from `firmware/shutter/host-tests`. The target must be explicit because `firmware/shutter/.cargo/config.toml` defaults to `riscv32imac-unknown-none-elf`. `host-tests/src/lib.rs` includes `../../src/shots.rs` via `#[path]`, so the unit tests inside `shots.rs` run on the host. |

## Conventions

- KDoc on every public class and function, written as a single plain sentence.
- Comments explain *why*, not what.
- No ViewModels: composables take `FilmDao` directly and launch with `rememberCoroutineScope()`.
- Commit messages are a plain imperative sentence with no prefix, e.g. "Tidy the film stock pickers".

## Gotchas

- Room schemas are exported to `android/app/schemas/`. A schema change needs a version bump plus an AutoMigration or a manual migration, and the new schema JSON must be committed.
- Database tests (`FilmDaoTest`, `MigrationTest`) run on the JVM under Robolectric, pinned to SDK 34 in `app/src/test/resources/robolectric.properties`. `MigrationTest` builds old databases from the exported schema JSON.
- `ShutterProtocolTest` (`android/app/src/test/.../ShutterProtocolTest.kt`) and the `encodes_ages` test in `firmware/shutter/src/shots.rs` share a byte vector. Change both together.
- The firmware `Log` lives in RTC memory, so its layout must stay stable across reflashes. `MAGIC` in `shots.rs` guards it, so change `MAGIC` if the layout changes.
