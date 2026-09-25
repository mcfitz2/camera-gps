//! Local UI: status display, buttons and battery monitoring. Each part is
//! compiled in only when the board has the hardware.

use std::sync::mpsc::Sender;
use std::thread;
use std::time::{Duration, Instant};

#[allow(unused_imports)] // Only used by some hardware features.
use log::{info, warn};

use crate::board::Hardware;
use crate::state::{Command, Shared};

const TICK: Duration = Duration::from_millis(50);
#[cfg(feature = "battery")]
const BATTERY_PERIOD: Duration = Duration::from_secs(5);
#[cfg(feature = "display")]
const REDRAW_PERIOD: Duration = Duration::from_millis(500);
#[cfg(feature = "buttons")]
const FORGET_HOLD: Duration = Duration::from_secs(3);

/// Whether this build has any UI hardware to drive.
pub const ENABLED: bool = cfg!(any(feature = "display", feature = "buttons", feature = "battery"));

pub fn spawn(hw: Hardware, shared: Shared, commands: Sender<Command>) -> anyhow::Result<()> {
    thread::Builder::new()
        .name("ui".into())
        .stack_size(8 * 1024)
        .spawn(move || run(hw, &shared, &commands))?;
    Ok(())
}

#[allow(unused_mut, unused_variables)]
fn run(mut hw: Hardware, shared: &Shared, commands: &Sender<Command>) {
    #[cfg(feature = "buttons")]
    let mut pressed_since: [Option<Instant>; 2] = [None; 2];
    let mut last_battery: Option<Instant> = None;
    let mut last_draw: Option<Instant> = None;
    let due = |t: Option<Instant>, period: Duration| t.is_none_or(|t| t.elapsed() >= period);

    loop {
        #[cfg(feature = "buttons")]
        for (i, button) in hw.buttons.iter().enumerate() {
            let down = button.is_low();
            match (down, pressed_since[i]) {
                (true, None) => pressed_since[i] = Some(Instant::now()),
                (false, Some(t)) => {
                    pressed_since[i] = None;
                    // USR1: tap sends now, long hold forgets the camera. USR2: reconnect.
                    let cmd = match i {
                        0 if t.elapsed() >= FORGET_HOLD => Command::Forget,
                        0 => Command::SendNow,
                        _ => Command::Reconnect,
                    };
                    info!("button {i}: {cmd:?}");
                    let _ = commands.send(cmd);
                }
                _ => {}
            }
        }

        #[cfg(feature = "battery")]
        if due(last_battery, BATTERY_PERIOD) {
            last_battery = Some(Instant::now());
            match hw.battery.read_mv() {
                Ok(mv) => shared.lock().battery_mv = Some(mv),
                Err(e) => warn!("battery read: {e}"),
            }
        }

        #[cfg(feature = "display")]
        if due(last_draw, REDRAW_PERIOD) {
            last_draw = Some(Instant::now());
            if let Err(e) = hw.display.draw(shared) {
                warn!("display: {e}");
            }
        }

        thread::sleep(TICK);
    }
}

#[cfg(feature = "display")]
pub use display::Display;

#[cfg(feature = "display")]
mod display {
    use core::convert::Infallible;
    use core::fmt::Write as _;

    use embedded_graphics::mono_font::ascii::{FONT_6X10, FONT_9X15_BOLD};
    use embedded_graphics::mono_font::MonoTextStyle;
    use embedded_graphics::pixelcolor::raw::RawU16;
    use embedded_graphics::pixelcolor::Rgb565;
    use embedded_graphics::prelude::*;
    use embedded_graphics::text::{Baseline, Text};
    use esp_idf_svc::hal::delay::FreeRtos;
    use esp_idf_svc::hal::gpio::{Output, PinDriver};
    use esp_idf_svc::hal::spi::{SpiDeviceDriver, SpiDriver};
    use esp_idf_svc::sys::EspError;

    use crate::state::{self, Link, Shared};

    const WIDTH: usize = 160;
    const HEIGHT: usize = 80;
    /// The panel sits 24 rows into the controller's RAM in landscape.
    const ROW_OFFSET: u16 = 24;
    /// Landscape (MX | MV), BGR.
    const MADCTL: u8 = 0x60 | 0x08;

    /// Controller setup from Seeed_GFX2's verified 80x160 ST7789 sequence.
    const INIT: &[(u8, &[u8], u32)] = &[
        (0x01, &[], 150),          // SWRESET
        (0x11, &[], 120),          // SLPOUT
        (0x3a, &[0x55], 0),        // COLMOD: 16-bit
        (0x36, &[MADCTL], 0),      // MADCTL
        (0xb0, &[0x00, 0xf0], 0),  // RAMCTRL
        (0xb2, &[0x0c, 0x0c, 0x00, 0x33, 0x33], 0), // PORCTRL
        (0xb7, &[0x35], 0),        // GCTRL
        (0xbb, &[0x19], 0),        // VCOMS
        (0xc0, &[0x2c], 0),        // LCMCTRL
        (0xc2, &[0x01], 0),        // VDVVRHEN
        (0xc3, &[0x12], 0),        // VRHS
        (0xc4, &[0x20], 0),        // VDVSET
        (0xc6, &[0x0f], 0),        // FRCTR2
        (0xd0, &[0xa4, 0xa1], 0),  // PWCTRL1
        (0xe0, &[0xf0, 0x09, 0x13, 0x12, 0x12, 0x2b, 0x3c, 0x44, 0x4b, 0x1b, 0x18, 0x17, 0x1d, 0x21], 0),
        (0xe1, &[0xf0, 0x09, 0x13, 0x0c, 0x0d, 0x27, 0x3b, 0x44, 0x4d, 0x0b, 0x17, 0x17, 0x1d, 0x21], 0),
        (0x13, &[], 10),           // NORON
        (0x20, &[], 0),            // INVOFF
        (0x29, &[], 0),            // DISPON
    ];

    /// RGB565 frame buffer, big-endian as the panel expects it.
    struct Frame(Vec<u8>);

    impl OriginDimensions for Frame {
        fn size(&self) -> Size {
            Size::new(WIDTH as u32, HEIGHT as u32)
        }
    }

    impl DrawTarget for Frame {
        type Color = Rgb565;
        type Error = Infallible;

        fn draw_iter<I>(&mut self, pixels: I) -> Result<(), Self::Error>
        where
            I: IntoIterator<Item = Pixel<Self::Color>>,
        {
            for Pixel(p, color) in pixels {
                let (Ok(x), Ok(y)) = (usize::try_from(p.x), usize::try_from(p.y)) else {
                    continue;
                };
                if x < WIDTH && y < HEIGHT {
                    let i = 2 * (y * WIDTH + x);
                    self.0[i..i + 2].copy_from_slice(&RawU16::from(color).into_inner().to_be_bytes());
                }
            }
            Ok(())
        }

        fn clear(&mut self, color: Self::Color) -> Result<(), Self::Error> {
            let [hi, lo] = RawU16::from(color).into_inner().to_be_bytes();
            for px in self.0.chunks_exact_mut(2) {
                px[0] = hi;
                px[1] = lo;
            }
            Ok(())
        }
    }

    /// 160x80 ST7789 status screen.
    pub struct Display {
        spi: SpiDeviceDriver<'static, SpiDriver<'static>>,
        dc: PinDriver<'static, Output>,
        // Held so the pins keep their levels.
        _rst: PinDriver<'static, Output>,
        _backlight: PinDriver<'static, Output>,
        frame: Frame,
        text: String,
        shown: String,
    }

    impl Display {
        pub fn new(
            spi: SpiDeviceDriver<'static, SpiDriver<'static>>,
            dc: PinDriver<'static, Output>,
            mut rst: PinDriver<'static, Output>,
            mut backlight: PinDriver<'static, Output>,
        ) -> anyhow::Result<Self> {
            backlight.set_low()?;
            rst.set_high()?;
            FreeRtos::delay_ms(10);
            rst.set_low()?;
            FreeRtos::delay_ms(10);
            rst.set_high()?;
            FreeRtos::delay_ms(120);

            let mut display = Self {
                spi,
                dc,
                _rst: rst,
                _backlight: backlight,
                frame: Frame(vec![0; WIDTH * HEIGHT * 2]),
                text: String::with_capacity(128),
                shown: String::with_capacity(128),
            };
            for &(cmd, data, delay_ms) in INIT {
                display.command(cmd, data)?;
                if delay_ms > 0 {
                    FreeRtos::delay_ms(delay_ms);
                }
            }
            display.flush()?;
            display._backlight.set_high()?;
            Ok(display)
        }

        pub fn draw(&mut self, shared: &Shared) -> anyhow::Result<()> {
            self.text.clear();
            let link_color;
            {
                let s = shared.lock();
                let (link, color) = match s.link {
                    Link::Disconnected => ("searching", Rgb565::RED),
                    Link::WaitingForFix => ("waiting GPS", Rgb565::MAGENTA),
                    Link::CameraOff => ("camera off", Rgb565::BLUE),
                    Link::Connecting => ("connecting", Rgb565::YELLOW),
                    Link::AwaitingApproval => ("OK on camera", Rgb565::CYAN),
                    Link::Connected { geo_enabled: false } => ("connected", Rgb565::YELLOW),
                    Link::Connected { geo_enabled: true } => ("sending GPS", Rgb565::GREEN),
                };
                link_color = color;
                let _ = writeln!(self.text, "{link}");
                match s.current_fix() {
                    Some(fix) => {
                        let _ = writeln!(self.text, "lat {:.5}", fix.latitude);
                        let _ = writeln!(self.text, "lon {:.5}", fix.longitude);
                    }
                    None => {
                        let _ = writeln!(self.text, "no position");
                        let _ = writeln!(self.text);
                    }
                }
                if !s.gps.receiving() {
                    let _ = writeln!(self.text, "GPS: no data");
                } else {
                    let _ = write!(self.text, "sats {}", s.gps.satellites.unwrap_or(0));
                    if let Some(hdop) = s.gps.hdop {
                        let _ = write!(self.text, " hdop {hdop:.1}");
                    }
                    let _ = writeln!(self.text);
                }
                let _ = writeln!(self.text, "sent {}", s.sends_ok);
                if state::utc_now().is_none() {
                    let _ = write!(self.text, "clock not set  ");
                }
                if let Some(mv) = s.battery_mv {
                    let _ = write!(self.text, "bat {}.{:02}V", mv / 1000, (mv % 1000) / 10);
                }
            }
            if self.text == self.shown {
                return Ok(());
            }

            let (title, body) = self.text.split_once('\n').unwrap_or((&self.text, ""));
            let _ = self.frame.clear(Rgb565::BLACK);
            let _ = Text::with_baseline(
                title,
                Point::new(2, 2),
                MonoTextStyle::new(&FONT_9X15_BOLD, link_color),
                Baseline::Top,
            )
            .draw(&mut self.frame);
            let _ = Text::with_baseline(
                body,
                Point::new(2, 22),
                MonoTextStyle::new(&FONT_6X10, Rgb565::WHITE),
                Baseline::Top,
            )
            .draw(&mut self.frame);
            self.flush()?;
            self.shown.clone_from(&self.text);
            Ok(())
        }

        fn flush(&mut self) -> Result<(), EspError> {
            let x_end = (WIDTH - 1) as u16;
            let (y_start, y_end) = (ROW_OFFSET, ROW_OFFSET + (HEIGHT - 1) as u16);
            let [xe_hi, xe_lo] = x_end.to_be_bytes();
            let [ys_hi, ys_lo] = y_start.to_be_bytes();
            let [ye_hi, ye_lo] = y_end.to_be_bytes();
            self.command(0x2a, &[0, 0, xe_hi, xe_lo])?; // CASET
            self.command(0x2b, &[ys_hi, ys_lo, ye_hi, ye_lo])?; // RASET
            self.dc.set_low()?;
            self.spi.write(&[0x2c])?; // RAMWR
            self.dc.set_high()?;
            self.spi.write(&self.frame.0)
        }

        fn command(&mut self, cmd: u8, data: &[u8]) -> Result<(), EspError> {
            self.dc.set_low()?;
            self.spi.write(&[cmd])?;
            if !data.is_empty() {
                self.dc.set_high()?;
                self.spi.write(data)?;
            }
            Ok(())
        }
    }
}
