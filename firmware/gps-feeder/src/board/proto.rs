//! Generic ESP32 (WROOM-32) dev board: BLE, serial console and GPS only.

use esp_idf_svc::hal::gpio::AnyIOPin;
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::uart::UartDriver;

use super::{Board, Hardware};

pub fn init(peripherals: Peripherals) -> anyhow::Result<Board> {
    let pins = peripherals.pins;

    // GPS on UART2: ESP TX GPIO17 -> GPS RX, GPS TX -> ESP RX GPIO16.
    let gps = UartDriver::new(
        peripherals.uart2,
        pins.gpio17,
        pins.gpio16,
        Option::<AnyIOPin>::None,
        Option::<AnyIOPin>::None,
        &super::gps_uart_config(),
    )?;

    Ok(Board {
        gps,
        hardware: Hardware {},
    })
}
