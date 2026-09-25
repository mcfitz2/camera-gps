//! Line-based serial console for testing without a GPS receiver.

use std::io::Read;
use std::sync::mpsc::Sender;
use std::thread;
use std::time::Duration;

use log::{info, warn};

use crate::state::{self, Command, Fix, FixSource, Shared};

const HELP: &str =
    "commands: status | gps <lat> <lon> [alt] | time <epoch> | send | reconnect | forget | survey <secs>";

pub fn spawn(shared: Shared, commands: Sender<Command>) -> anyhow::Result<()> {
    thread::Builder::new()
        .name("console".into())
        .stack_size(6 * 1024)
        .spawn(move || run(&shared, &commands))?;
    Ok(())
}

fn run(shared: &Shared, commands: &Sender<Command>) {
    let mut stdin = std::io::stdin();
    let mut line = String::new();
    let mut byte = [0u8; 1];
    loop {
        // The ESP-IDF console is non-blocking; poll it.
        match stdin.read(&mut byte) {
            Ok(1) => match byte[0] {
                b'\r' | b'\n' => {
                    if !line.trim().is_empty() {
                        if let Err(e) = handle(line.trim(), shared, commands) {
                            warn!("{e}");
                        }
                    }
                    line.clear();
                }
                b => line.push(char::from(b)),
            },
            _ => thread::sleep(Duration::from_millis(20)),
        }
    }
}

fn handle(line: &str, shared: &Shared, commands: &Sender<Command>) -> anyhow::Result<()> {
    let mut args = line.split_whitespace();
    match args.next().unwrap_or_default() {
        "status" => {
            let s = shared.lock();
            info!(
                "link={:?} sends_ok={} fix={:?} usable={} gps={:?} receiving={} utc={:?} battery_mv={:?}",
                s.link,
                s.sends_ok,
                s.fix,
                s.current_fix().is_some(),
                s.gps,
                s.gps.receiving(),
                state::utc_now().map(|t| t.as_secs()),
                s.battery_mv
            );
        }
        "gps" => {
            let latitude = args.next().ok_or_else(|| anyhow::anyhow!("usage: gps <lat> <lon> [alt]"))?.parse()?;
            let longitude = args.next().ok_or_else(|| anyhow::anyhow!("usage: gps <lat> <lon> [alt]"))?.parse()?;
            let mut s = shared.lock();
            let previous = s.fix.map_or(0.0, |(f, _)| f.altitude);
            let altitude = args.next().map(str::parse).transpose()?.unwrap_or(previous);
            let fix = Fix { latitude, longitude, altitude };
            // The GPS replaces this as soon as it has a fix of its own.
            s.fix = Some((fix, FixSource::Manual));
            info!("fix set to {fix:?}");
        }
        "time" => {
            let epoch = args.next().ok_or_else(|| anyhow::anyhow!("usage: time <epoch>"))?.parse()?;
            state::set_utc(epoch)?;
            info!("clock set to {epoch}");
        }
        "send" => commands.send(Command::SendNow)?,
        "reconnect" => commands.send(Command::Reconnect)?,
        "forget" => commands.send(Command::Forget)?,
        "survey" => {
            let secs = args.next().ok_or_else(|| anyhow::anyhow!("usage: survey <secs>"))?.parse()?;
            commands.send(Command::Survey(secs))?;
        }
        _ => info!("{HELP}"),
    }
    Ok(())
}
