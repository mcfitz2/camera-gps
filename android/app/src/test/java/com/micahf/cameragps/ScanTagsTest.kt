package com.micahf.cameragps

import androidx.exifinterface.media.ExifInterface
import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ScanTagsTest {
    private val zone = ZoneId.of("America/Chicago")
    private val roll = Roll(id = 1, name = "Portra, Chicago", stock = "Portra 400", iso = 400, loadedAt = 0)
    private val frame = Frame(
        rollId = 1, number = 1, takenAt = 1_790_209_346_000, lat = 41.8781, lon = -87.6298,
        accuracyM = 6f, altM = 181.04, place = "Loop", exposureMs = 4_000, note = "the \"bean\"",
    )

    @Test
    fun tagsForALocatedFrame() {
        val expected = mapOf(
            ExifInterface.TAG_IMAGE_DESCRIPTION to "Portra, Chicago / frame 1 / Portra 400 / Loop / the \"bean\"",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "400",
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:09:23 19:22:26",
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "-05:00",
            ExifInterface.TAG_EXPOSURE_TIME to "4000/1000",
            ExifInterface.TAG_GPS_LATITUDE to "41/1,52/1,411600/10000",
            ExifInterface.TAG_GPS_LATITUDE_REF to "N",
            ExifInterface.TAG_GPS_LONGITUDE to "87/1,37/1,472800/10000",
            ExifInterface.TAG_GPS_LONGITUDE_REF to "W",
            ExifInterface.TAG_GPS_ALTITUDE to "181040/1000",
            ExifInterface.TAG_GPS_ALTITUDE_REF to "0",
            ExifInterface.TAG_GPS_H_POSITIONING_ERROR to "6000/1000",
            ExifInterface.TAG_GPS_DATESTAMP to "2026:09:24",
            ExifInterface.TAG_GPS_TIMESTAMP to "0/1,22/1,26/1",
        )
        assertEquals(expected, ScanTags.tags(roll, frame, zone))
    }

    @Test
    fun southernEasternAndBelowSeaLevel() {
        val southern = frame.copy(lat = -33.8688, lon = 151.2093, altM = -2.5)
        val tags = ScanTags.tags(roll, southern, zone)
        assertEquals("33/1,52/1,76800/10000", tags[ExifInterface.TAG_GPS_LATITUDE])
        assertEquals("S", tags[ExifInterface.TAG_GPS_LATITUDE_REF])
        assertEquals("151/1,12/1,334800/10000", tags[ExifInterface.TAG_GPS_LONGITUDE])
        assertEquals("E", tags[ExifInterface.TAG_GPS_LONGITUDE_REF])
        assertEquals("2500/1000", tags[ExifInterface.TAG_GPS_ALTITUDE])
        assertEquals("1", tags[ExifInterface.TAG_GPS_ALTITUDE_REF])
    }

    @Test
    fun blankFrameGetsOnlyADescription() {
        val expected = mapOf(
            ExifInterface.TAG_IMAGE_DESCRIPTION to "Portra, Chicago / frame 2 / Portra 400",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "400",
        )
        assertEquals(expected, ScanTags.tags(roll, Frame(rollId = 1, number = 2, takenAt = null), zone))
    }

    @Test
    fun frameWithoutLocationHasNoGps() {
        val tags = ScanTags.tags(roll, frame.copy(lat = null, lon = null), zone)
        assertTrue(tags.containsKey(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertTrue(tags.keys.none { it.startsWith("GPS") })
    }

    @Test
    fun descriptionIsAscii() {
        assertEquals("Zurich ? cafe", ScanTags.ascii("Zürich — café"))
        val tags = ScanTags.tags(roll, frame.copy(approximate = true), zone)
        assertTrue(tags[ExifInterface.TAG_IMAGE_DESCRIPTION]!!.endsWith(" / approximate location"))
    }

    @Test
    fun namesSortNaturally() {
        val expected = listOf("a.jpg", "Scan1.JPG", "scan2.jpg", "scan10.jpg")
        assertEquals(expected, listOf("scan10.jpg", "scan2.jpg", "Scan1.JPG", "a.jpg").sortedWith(ScanTags.natural))
    }

    @Test
    fun matchesByPosition() {
        val frames = (1..3).map { Frame(rollId = 1, number = it, takenAt = it.toLong()) }
        val expected1 = listOf("b9.jpg" to 1, "b10.jpg" to 2, "b11.jpg" to 3, "b12.jpg" to null)
        assertEquals(
            expected1,
            ScanTags.match(listOf("b10.jpg", "b9.jpg", "b11.jpg", "b12.jpg"), frames, 1).map { it.name to it.frame?.number },
        )
        val expected2 = listOf("x0.jpg" to null, "x1.jpg" to 1)
        assertEquals(
            expected2,
            ScanTags.match(listOf("x0.jpg", "x1.jpg"), frames, 0).map { it.name to it.frame?.number },
        )
    }

    @Test
    fun zipHelpers() {
        val entries = listOf("roll/", "__MACOSX/roll/._a.jpg", "roll/._a.jpg", "roll/.DS_Store", "roll/a.jpg")
        assertEquals(listOf(true, true, true, true, false), entries.map(ScanTags::skip))

        val names = listOf("a.JPG", "b.jpeg", "c.tif", "d")
        assertEquals(listOf(true, true, false, false), names.map(ScanTags::isJpeg))

        val taken = mutableSetOf<String>()
        val expected = listOf("a.jpg", "A (2).jpg", "a (3).jpg", "noext")
        assertEquals(expected, listOf("a.jpg", "A.jpg", "a.jpg", "noext").map { ScanTags.unique(it, taken) })
    }
}
