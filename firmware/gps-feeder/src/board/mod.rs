//! Board selection. Each board provides `init`, returning the GPS UART plus
//! whatever optional hardware it has; features it lacks are compiled out.

#[cfg(all(feature = "board-proto", feature = "board-xiao"))]
compile_error!("select exactly one board feature");
#[cfg(not(any(feature = "board-proto", feature = "board-xiao")))]
compile_error!("select a board feature: board-proto or board-xiao");

#[cfg(feature = "board-proto")]
mod proto;
#[cfg(feature = "board-proto")]
pub use proto::init;

#[cfg(feature = "board-xiao")]
mod xiao;
#[cfg(feature = "board-xiao")]
pub use xiao::init;

/// Everything the firmware drives on a board.
pub struct Board {
    /// UART connected to the GPS receiver's TX (and RX, for future configuration).
    pub gps: esp_idf_svc::hal::uart::UartDriver<'static>,
    pub hardware: Hardware,
}

/// UART settings for the GPS receiver. A large RX buffer rides out the other
/// threads briefly hogging the CPU without dropping sentences.
pub fn gps_uart_config() -> esp_idf_svc::hal::uart::config::Config {
    use esp_idf_svc::hal::units::Hertz;
    esp_idf_svc::hal::uart::config::Config::new()
        .baudrate(Hertz(crate::gps::BAUD))
        .rx_fifo_size(2048)
}

/// Optional peripherals. Empty on boards without them.
pub struct Hardware {
    #[cfg(feature = "display")]
    pub display: crate::ui::Display,
    #[cfg(feature = "buttons")]
    pub buttons: [esp_idf_svc::hal::gpio::PinDriver<'static, esp_idf_svc::hal::gpio::Input>; 2],
    #[cfg(feature = "battery")]
    pub battery: Battery,
}

/// Battery voltage reader.
#[cfg(feature = "battery")]
pub struct Battery(Box<dyn FnMut() -> Result<u16, esp_idf_svc::sys::EspError> + Send>);

#[cfg(feature = "battery")]
impl Battery {
    /// Battery voltage in millivolts.
    pub fn read_mv(&mut self) -> Result<u16, esp_idf_svc::sys::EspError> {
        (self.0)()
    }
}
