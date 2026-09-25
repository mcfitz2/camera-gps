package com.micahf.cameragps

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CanonTest {
    @Test
    fun matchesPacketAcceptedByM50MarkII() {
        // Same vector as the firmware's geo.rs test; the camera tagged a photo with it.
        val expected = intArrayOf(
            0x04, 0x4e, 0x2d, 0x83, 0x27, 0x42, 0x57, 0x75, 0x42, 0xaf, 0x42, 0x2b, 0x00, 0x00,
            0x35, 0x43, 0x42, 0x6d, 0xb4, 0x6a,
        ).map { it.toByte() }.toByteArray()
        assertArrayEquals(expected, Canon.encodeLocation(41.8781, -87.6298, 181.0, 1_790_209_346))
    }
}
