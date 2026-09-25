package com.micahf.cameragps

import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

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
}
