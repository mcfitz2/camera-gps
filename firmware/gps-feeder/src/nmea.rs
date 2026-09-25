//! Minimal NMEA 0183 parser for the sentences needed to report a position:
//! RMC (validity, date/time, position) and GGA (quality, satellites, HDOP,
//! altitude). Any talker (GP, GN, GL, ...) is accepted.
//!
//! Receivers under poor reception emit truncated or corrupted lines, so every
//! sentence must carry a valid checksum and malformed fields make the whole
//! sentence invalid rather than being guessed at.

/// A decoded sentence.
#[derive(Debug, Clone, Copy, PartialEq)]
pub enum Sentence {
    Rmc(Rmc),
    Gga(Gga),
}

/// Recommended minimum data.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rmc {
    /// Seconds since midnight UTC.
    pub time: Option<f64>,
    /// Status `A` and, when present, a mode that isn't estimated or invalid.
    pub valid: bool,
    pub position: Option<Position>,
    /// Days since the Unix epoch.
    pub date: Option<i64>,
}

impl Rmc {
    /// UTC as Unix seconds, when both date and time are present.
    pub fn epoch_secs(&self) -> Option<i64> {
        Some(self.date? * 86_400 + self.time? as i64)
    }
}

/// Fix data.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Gga {
    /// Seconds since midnight UTC.
    pub time: Option<f64>,
    /// 0 = none, 1 = GPS, 2 = DGPS, 6 = estimated; others are rare.
    pub quality: u8,
    pub satellites: Option<u8>,
    pub hdop: Option<f32>,
    pub position: Option<Position>,
    /// Metres above mean sea level.
    pub altitude: Option<f32>,
}

impl Gga {
    /// Whether the receiver reports a real (not dead-reckoned) fix.
    pub fn has_fix(&self) -> bool {
        matches!(self.quality, 1 | 2 | 4 | 5)
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Position {
    /// Degrees, positive north.
    pub latitude: f64,
    /// Degrees, positive east.
    pub longitude: f64,
}

/// Parse one line (with or without the trailing CR/LF). Returns `None` for
/// unsupported sentences and anything malformed.
pub fn parse(line: &[u8]) -> Option<Sentence> {
    let line = core::str::from_utf8(line).ok()?.trim_end();
    let body = line.strip_prefix('$')?;
    let (body, checksum) = body.split_once('*')?;
    let expected = u8::from_str_radix(checksum.get(..2)?, 16).ok()?;
    if body.bytes().fold(0, |acc, b| acc ^ b) != expected {
        return None;
    }

    let mut fields = body.split(',');
    let kind = fields.next()?;
    let kind = kind.get(2..).filter(|_| kind.len() == 5)?;
    let f: Vec<&str> = fields.collect();
    match kind {
        "RMC" => parse_rmc(&f).map(Sentence::Rmc),
        "GGA" => parse_gga(&f).map(Sentence::Gga),
        _ => None,
    }
}

/// `hhmmss.sss,A,llll.ll,a,yyyyy.yy,a,x.x,x.x,ddmmyy,x.x,a[,m[,s]]`
fn parse_rmc(f: &[&str]) -> Option<Rmc> {
    if f.len() < 11 {
        return None;
    }
    let status_ok = f[1] == "A";
    // NMEA 2.3+ mode: A autonomous, D differential; E estimated, N invalid.
    let mode_ok = f.get(11).is_none_or(|m| !matches!(*m, "E" | "N"));
    Some(Rmc {
        time: optional(f[0], parse_time)?,
        valid: status_ok && mode_ok,
        position: position(f[2], f[3], f[4], f[5])?,
        date: optional(f[8], parse_date)?,
    })
}

/// `hhmmss.ss,llll.ll,a,yyyyy.yy,a,q,nn,h.h,a.a,M,g.g,M,...`
fn parse_gga(f: &[&str]) -> Option<Gga> {
    if f.len() < 10 {
        return None;
    }
    Some(Gga {
        time: optional(f[0], parse_time)?,
        quality: optional(f[5], |s| s.parse().ok())?.unwrap_or(0),
        satellites: optional(f[6], |s| s.parse().ok())?,
        hdop: optional(f[7], parse_finite)?,
        position: position(f[1], f[2], f[3], f[4])?,
        altitude: optional(f[8], parse_finite)?,
    })
}

/// Empty fields are `Some(None)`; present but unparseable ones are `None`.
fn optional<T>(s: &str, parse: impl FnOnce(&str) -> Option<T>) -> Option<Option<T>> {
    if s.is_empty() {
        Some(None)
    } else {
        parse(s).map(Some)
    }
}

fn parse_finite(s: &str) -> Option<f32> {
    s.parse::<f32>().ok().filter(|v| v.is_finite())
}

fn position(lat: &str, ns: &str, lon: &str, ew: &str) -> Option<Option<Position>> {
    if lat.is_empty() && lon.is_empty() {
        return Some(None);
    }
    let latitude = match ns {
        "N" => parse_coordinate(lat, 2)?,
        "S" => -parse_coordinate(lat, 2)?,
        _ => return None,
    };
    let longitude = match ew {
        "E" => parse_coordinate(lon, 3)?,
        "W" => -parse_coordinate(lon, 3)?,
        _ => return None,
    };
    if latitude.abs() > 90.0 || longitude.abs() > 180.0 {
        return None;
    }
    Some(Some(Position { latitude, longitude }))
}

/// `ddmm.mmmm` / `dddmm.mmmm` to decimal degrees.
fn parse_coordinate(s: &str, degree_digits: usize) -> Option<f64> {
    let (deg, min) = (s.get(..degree_digits)?, s.get(degree_digits..)?);
    if !deg.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let deg: f64 = deg.parse().ok()?;
    let min: f64 = min.parse().ok().filter(|m: &f64| (0.0..60.0).contains(m))?;
    Some(deg + min / 60.0)
}

/// `hhmmss[.sss]` to seconds since midnight.
fn parse_time(s: &str) -> Option<f64> {
    let (h, m, sec) = (s.get(..2)?, s.get(2..4)?, s.get(4..)?);
    let (h, m): (u8, u8) = (h.parse().ok()?, m.parse().ok()?);
    let sec: f64 = sec.parse().ok()?;
    // 60 allows for a leap second.
    if h > 23 || m > 59 || !(0.0..61.0).contains(&sec) {
        return None;
    }
    Some(f64::from(h) * 3600.0 + f64::from(m) * 60.0 + sec)
}

/// `ddmmyy` to days since 1970-01-01. Two-digit years are 2000-2099.
fn parse_date(s: &str) -> Option<i64> {
    if s.len() != 6 || !s.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    let d: u32 = s[0..2].parse().ok()?;
    let m: u32 = s[2..4].parse().ok()?;
    let y: i64 = 2000 + s[4..6].parse::<i64>().ok()?;
    if !(1..=12).contains(&m) || !(1..=31).contains(&d) {
        return None;
    }
    Some(days_from_civil(y, m, d))
}

/// Howard Hinnant's `days_from_civil`.
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let m = i64::from(m);
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + i64::from(d) - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146_097 + doe - 719_468
}

#[cfg(test)]
mod tests {
    use super::*;

    fn with_checksum(body: &str) -> String {
        let sum = body.bytes().fold(0u8, |acc, b| acc ^ b);
        format!("${body}*{sum:02X}\r\n")
    }

    #[test]
    fn parses_neo6_rmc() {
        let line = b"$GPRMC,083559.00,A,4717.11437,N,00833.91522,E,0.004,77.52,091202,,,A*57";
        let Some(Sentence::Rmc(rmc)) = parse(line) else { panic!("not parsed") };
        assert!(rmc.valid);
        let p = rmc.position.unwrap();
        assert!((p.latitude - 47.285_239_5).abs() < 1e-6);
        assert!((p.longitude - 8.565_253_7).abs() < 1e-6);
        // 2002-12-09 08:35:59 UTC
        assert_eq!(rmc.epoch_secs(), Some(1_039_422_959));
    }

    #[test]
    fn parses_neo6_gga() {
        let line = b"$GPGGA,092725.00,4717.11399,N,00833.91590,E,1,08,1.01,499.6,M,48.0,M,,*5B";
        let Some(Sentence::Gga(gga)) = parse(line) else { panic!("not parsed") };
        assert!(gga.has_fix());
        assert_eq!(gga.satellites, Some(8));
        assert_eq!(gga.hdop, Some(1.01));
        assert_eq!(gga.altitude, Some(499.6));
    }

    #[test]
    fn southern_western_hemispheres_are_negative() {
        let line = with_checksum("GNGGA,000000,3351.000,S,15112.000,W,1,05,2.0,10.0,M,,M,,");
        let Some(Sentence::Gga(gga)) = parse(line.as_bytes()) else { panic!("not parsed") };
        let p = gga.position.unwrap();
        assert!((p.latitude + 33.85).abs() < 1e-9);
        assert!((p.longitude + 151.2).abs() < 1e-9);
    }

    #[test]
    fn no_fix_sentences_parse_with_empty_fields() {
        let Some(Sentence::Rmc(rmc)) = parse(with_checksum("GPRMC,,V,,,,,,,,,,N").as_bytes()) else {
            panic!("not parsed")
        };
        assert!(!rmc.valid);
        assert_eq!(rmc.position, None);
        let Some(Sentence::Gga(gga)) = parse(with_checksum("GPGGA,,,,,,0,00,99.99,,,,,,").as_bytes()) else {
            panic!("not parsed")
        };
        assert!(!gga.has_fix());
        assert_eq!(gga.satellites, Some(0));
    }

    #[test]
    fn dead_reckoning_is_not_valid() {
        let Some(Sentence::Rmc(rmc)) =
            parse(with_checksum("GPRMC,120000,A,4717.1,N,00833.9,E,0,0,010125,,,E").as_bytes())
        else {
            panic!("not parsed")
        };
        assert!(!rmc.valid);
    }

    #[test]
    fn rejects_corruption() {
        // Flipped digit, checksum unchanged.
        assert_eq!(
            parse(b"$GPGGA,092725.00,4717.11399,N,00833.91590,E,1,08,1.01,499.7,M,48.0,M,,*5B"),
            None
        );
        // Truncated mid-sentence.
        assert_eq!(parse(b"$GPGGA,092725.00,4717.11399,N,0083"), None);
        // Garbage fields with a matching checksum.
        assert_eq!(parse(with_checksum("GPGGA,0927xx,4717.1,N,00833.9,E,1,08,1.0,1.0,M,,M,,").as_bytes()), None);
        assert_eq!(parse(with_checksum("GPGGA,092725,4717.1,Q,00833.9,E,1,08,1.0,1.0,M,,M,,").as_bytes()), None);
        assert_eq!(parse(with_checksum("GPGGA,092725,4775.0,N,00833.9,E,1,08,1.0,1.0,M,,M,,").as_bytes()), None);
    }

    #[test]
    fn ignores_other_sentences() {
        assert_eq!(parse(with_checksum("GPGSV,1,1,00").as_bytes()), None);
    }
}
