package com.micahf.cameragps

import androidx.exifinterface.media.ExifInterface
import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/** Matches film scans to frames and works out the EXIF tags to write into them. */
object ScanTags {
    private val CHUNK = Regex("\\d+|\\D+")
    private val LOCAL = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val OFFSET = DateTimeFormatter.ofPattern("xxx")
    private val GPS_DATE = DateTimeFormatter.ofPattern("yyyy:MM:dd")

    /** File-name order a person expects: "scan2" before "scan10", ignoring case. */
    val natural: Comparator<String> = Comparator { a, b ->
        val x = CHUNK.findAll(a.lowercase()).map { it.value }.toList()
        val y = CHUNK.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val m = p.trimStart('0')
                val n = q.trimStart('0')
                if (m.length != n.length) m.length - n.length else m.compareTo(n)
            } else {
                p.compareTo(q)
            }
            if (c != 0) return@Comparator c
        }
        if (x.size != y.size) x.size - y.size else a.compareTo(b)
    }

    /** A scan and the frame it shows, or null if there's no frame at its position. */
    data class Match(val name: String, val frame: Frame?)

    /**
     * Pairs scans with frames by position: in name order, the first scan is
     * frame [firstFrame] and each next scan the next frame. Lab file names
     * don't reliably carry frame numbers, but the scan order follows the film.
     */
    fun match(names: List<String>, frames: List<Frame>, firstFrame: Int): List<Match> {
        val byNumber = frames.associateBy { it.number }
        return names.sortedWith(natural).mapIndexed { i, name -> Match(name, byNumber[firstFrame + i]) }
    }

    /** EXIF tags for a scan of [frame], as [ExifInterface.setAttribute] takes them. */
    fun tags(roll: Roll, frame: Frame, zone: ZoneId = ZoneId.systemDefault()): Map<String, String> {
        val tags = linkedMapOf<String, String>()
        tags[ExifInterface.TAG_IMAGE_DESCRIPTION] = description(roll, frame)
        roll.iso?.let { tags[ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY] = it.toString() }
        val takenAt = frame.takenAt ?: return tags
        val local = Instant.ofEpochMilli(takenAt).atZone(zone)
        tags[ExifInterface.TAG_DATETIME_ORIGINAL] = LOCAL.format(local)
        tags[ExifInterface.TAG_OFFSET_TIME_ORIGINAL] = OFFSET.format(local)
        frame.exposureMs?.let { tags[ExifInterface.TAG_EXPOSURE_TIME] = "$it/1000" }
        val lat = frame.lat ?: return tags
        val lon = frame.lon ?: return tags
        tags[ExifInterface.TAG_GPS_LATITUDE] = dms(lat)
        tags[ExifInterface.TAG_GPS_LATITUDE_REF] = if (lat < 0) "S" else "N"
        tags[ExifInterface.TAG_GPS_LONGITUDE] = dms(lon)
        tags[ExifInterface.TAG_GPS_LONGITUDE_REF] = if (lon < 0) "W" else "E"
        frame.altM?.let {
            tags[ExifInterface.TAG_GPS_ALTITUDE] = "${abs(it * 1000).roundToLong()}/1000"
            tags[ExifInterface.TAG_GPS_ALTITUDE_REF] = if (it < 0) "1" else "0"
        }
        frame.accuracyM?.let { tags[ExifInterface.TAG_GPS_H_POSITIONING_ERROR] = "${(it * 1000).roundToLong()}/1000" }
        // GPS time is always UTC.
        val utc = local.withZoneSameInstant(ZoneOffset.UTC)
        tags[ExifInterface.TAG_GPS_DATESTAMP] = GPS_DATE.format(utc)
        tags[ExifInterface.TAG_GPS_TIMESTAMP] = "${utc.hour}/1,${utc.minute}/1,${utc.second}/1"
        return tags
    }

    /** Degrees, minutes and seconds as EXIF rationals, to 1/10000 of a second (about 3 mm). */
    internal fun dms(degrees: Double): String {
        // Integer ten-thousandths of an arcsecond, so rounding can't give 60 seconds.
        val total = (abs(degrees) * 36_000_000).roundToLong()
        return "${total / 36_000_000}/1,${total % 36_000_000 / 600_000}/1,${total % 600_000}/10000"
    }

    /** Roll, frame, stock, place and note, e.g. "Portra, Chicago / frame 1 / Portra 400 / Loop". */
    internal fun description(roll: Roll, frame: Frame): String = ascii(
        listOfNotNull(
            roll.name,
            "frame ${frame.number}",
            roll.stock,
            frame.place,
            frame.note,
            if (frame.approximate) "approximate location" else null,
        ).joinToString(" / "),
    )

    /** EXIF text is ASCII: drop accents ("Zürich" → "Zurich") and replace anything else with "?". */
    internal fun ascii(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace(Regex("[^\\x20-\\x7e]"), "?")

    /** Whether a file name is a JPEG, the one scan format EXIF can be written to here. */
    fun isJpeg(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg")

    /** ZIP entries that aren't scans: folders and macOS metadata. */
    fun skip(entry: String): Boolean {
        val base = entry.substringAfterLast('/')
        return entry.endsWith("/") || entry.startsWith("__MACOSX/") || base.isEmpty() || base.startsWith("._") || base == ".DS_Store"
    }

    /** [name], or "name (2).jpg" and so on if [taken] already has it; records the result in [taken]. */
    fun unique(name: String, taken: MutableSet<String>): String {
        var candidate = name
        var n = 2
        while (!taken.add(candidate.lowercase())) {
            val dot = name.lastIndexOf('.')
            candidate = if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)"
            n++
        }
        return candidate
    }
}
