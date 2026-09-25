package com.micahf.cameragps

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** A roll's frame log as CSV, for matching up scans. */
object Export {
    private val HEADER = listOf(
        "roll", "frame", "taken_at", "lat", "lon", "accuracy_m", "alt_m", "place", "approximate", "exposure_ms", "note",
    )

    /** Times are local with their offset, e.g. `2026-09-23T14:05:09-05:00`. */
    fun csv(roll: Roll, frames: List<Frame>, zone: ZoneId = ZoneId.systemDefault()): String {
        val rows = frames.map { f ->
            listOf(
                roll.name,
                f.number.toString(),
                f.takenAt?.let { DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(it).atZone(zone).withNano(0)) },
                f.lat?.let { "%.6f".format(it) },
                f.lon?.let { "%.6f".format(it) },
                f.accuracyM?.let { "%.0f".format(it) },
                f.altM?.let { "%.1f".format(it) },
                f.place,
                if (f.takenAt == null) null else f.approximate.toString(),
                f.exposureMs?.toString(),
                f.note,
            )
        }
        return (listOf(HEADER) + rows).joinToString("") { row -> row.joinToString(",") { field(it) } + "\r\n" }
    }

    /** Opens the share sheet with the roll's CSV. */
    fun share(context: Context, roll: Roll, frames: List<Frame>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = roll.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "roll" }
        val file = File(dir, "$safeName.csv").apply { writeText(csv(roll, frames)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/csv")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, roll.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Export ${roll.name}"))
    }

    private fun field(value: String?): String = when {
        value == null -> ""
        value.any { it == ',' || it == '"' || it == '\n' || it == '\r' } -> "\"" + value.replace("\"", "\"\"") + "\""
        else -> value
    }
}
