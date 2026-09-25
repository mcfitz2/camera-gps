package com.micahf.cameragps

import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.Locale

class ExportTest {
    @Test
    fun writesFramesAndBlanks() {
        val roll = Roll(id = 1, name = "Portra, Chicago", loadedAt = 0)
        val frames = listOf(
            Frame(
                rollId = 1, number = 1, takenAt = 1_790_209_346_000, lat = 41.8781, lon = -87.6298,
                accuracyM = 6f, altM = 181.04, place = "Loop", exposureMs = 4_000, note = "the \"bean\"",
            ),
            Frame(rollId = 1, number = 2, takenAt = null),
            Frame(rollId = 1, number = 3, takenAt = 1_790_209_400_000, approximate = true),
        )
        val expected = "roll,frame,taken_at,lat,lon,accuracy_m,alt_m,place,approximate,exposure_ms,note\r\n" +
            "\"Portra, Chicago\",1,2026-09-23T19:22:26-05:00,41.878100,-87.629800,6,181.0,Loop,false,4000,\"the \"\"bean\"\"\"\r\n" +
            "\"Portra, Chicago\",2,,,,,,,,,\r\n" +
            "\"Portra, Chicago\",3,2026-09-23T19:23:20-05:00,,,,,,true,,\r\n"
        assertEquals(expected, Export.csv(roll, frames, ZoneId.of("America/Chicago")))
    }

    @Test
    fun numbersIgnoreDeviceLocale() {
        val savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.GERMANY)
        try {
            val roll = Roll(id = 1, name = "Portra", loadedAt = 0)
            val frames = listOf(
                Frame(rollId = 1, number = 1, takenAt = null, lat = 41.8781, lon = -87.6298, accuracyM = 6f, altM = 181.04),
            )
            val csv = Export.csv(roll, frames, ZoneId.of("America/Chicago"))
            assertTrue(csv.contains("41.878100,-87.629800,6,181.0"))
        } finally {
            Locale.setDefault(savedLocale)
        }
    }

    @Test
    fun textCellsCannotStartFormulas() {
        val roll = Roll(id = 1, name = "=1+1", loadedAt = 0)
        val frames = listOf(
            Frame(rollId = 1, number = 1, takenAt = null, lon = -87.6298, place = "@SUM(A1)", note = "+cmd"),
            Frame(rollId = 1, number = 2, takenAt = null, note = "-cmd"),
        )
        val csv = Export.csv(roll, frames, ZoneId.of("America/Chicago"))
        val rows = csv.substringAfter("\r\n").split("\r\n").filter { it.isNotEmpty() }
        assertTrue(rows[0].startsWith("'=1+1,1,"))
        assertTrue(rows[0].contains(",'@SUM(A1),"))
        assertTrue(rows[0].endsWith(",'+cmd"))
        assertTrue(csv.contains(",-87.629800,"))
        assertTrue(rows[1].endsWith(",'-cmd"))
    }
}
