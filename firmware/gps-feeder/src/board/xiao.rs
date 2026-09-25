//! Seeed "0.96 inch Display Powered by XIAO ESP32-S3 Plus": 80x160 ST7789 IPS
//! LCD, two buttons (USR1/USR2), battery sense and an external GPS on the
//! bottom expansion pads.
//!
//! Pins from the Seeed wiki and the XIAO_ESP32S3_Plus Arduino variant.

use esp_idf_svc::hal::adc::attenuation::DB_12;
use esp_idf_svc::hal::adc::oneshot::config::{AdcChannelConfig, Calibration};
use esp_idf_svc::hal::adc::oneshot::{AdcChannelDriver, AdcDriver};
use esp_idf_svc::hal::gpio::{AnyIOPin, PinDriver, Pull};
use esp_idf_svc::hal::peripherals::Peripherals;
use esp_idf_svc::hal::spi::config::{Config as SpiConfig, DriverConfig};
use esp_idf_svc::hal::spi::{Dma, SpiDeviceDriver, SpiDriver};
use esp_idf_svc::hal::uart::UartDriver;
use esp_idf_svc::hal::units::FromValueType;

use super::{Battery, Board, Hardware};

/// Battery divider on D16: 316k / 160k, so Vbat = Vadc * 476 / 160.
const BATTERY_DIVIDER_NUM: u32 = 476;
const BATTERY_DIVIDER_DEN: u32 = 160;

pub fn init(peripherals: Peripherals) -> anyhow::Result<Board> {
    let pins = peripherals.pins;

    // GPS on the bottom pads meant for I2S (3V3, GND, D11, D12, D13), which this
    // firmware doesn't use: GPS TX -> D11 (GPIO38), D12 (GPIO39) -> GPS RX.
    let gps = UartDriver::new(
        peripherals.uart1,
        pins.gpio39,
        pins.gpio38,
        Option::<AnyIOPin>::None,
        Option::<AnyIOPin>::None,
        &super::gps_uart_config(),
    )?;

    // LCD: SCK D8 (GPIO7), MOSI D10 (GPIO9), CS D2 (GPIO3), DC D3 (GPIO4),
    // RST D17 (GPIO13), backlight D18 (GPIO12).
    let bus = SpiDriver::new(
        peripherals.spi2,
        pins.gpio7,
        pins.gpio9,
        Option::<AnyIOPin>::None,
        &DriverConfig::new().dma(Dma::Auto(4096)),
    )?;
    let spi = SpiDeviceDriver::new(
        bus,
        Some(pins.gpio3),
        &SpiConfig::new().baudrate(10.MHz().into()),
    )?;
    let display = crate::ui::Display::new(
        spi,
        PinDriver::output(pins.gpio4)?,
        PinDriver::output(pins.gpio13)?,
        PinDriver::output(pins.gpio12)?,
    )?;

    // USR1 on D6 (GPIO43), USR2 on D7 (GPIO44), active low.
    let buttons = [
        PinDriver::input(pins.gpio43, Pull::Up)?,
        PinDriver::input(pins.gpio44, Pull::Up)?,
    ];

    // Battery divider on D16 (GPIO10, ADC1).
    let adc = AdcDriver::new(peripherals.adc1)?;
    let config = AdcChannelConfig {
        attenuation: DB_12,
        calibration: Calibration::Curve,
        ..Default::default()
    };
    let mut channel = AdcChannelDriver::new(adc, pins.gpio10, &config)?;
    let battery = Battery(Box::new(move || {
        let mv = u32::from(channel.read()?) * BATTERY_DIVIDER_NUM / BATTERY_DIVIDER_DEN;
        Ok(u16::try_from(mv).unwrap_or(u16::MAX))
    }));

    Ok(Board {
        gps,
        hardware: Hardware {
            display,
            buttons,
            battery,
        },
    })
}
