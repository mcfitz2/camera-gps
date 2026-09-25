package com.micahf.cameragps

import com.micahf.cameragps.ShutterProtocol.Events
import com.micahf.cameragps.ShutterProtocol.Shot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ShutterProtocolTest {
    private fun bytes(vararg b: Int) = b.map { it.toByte() }.toByteArray()

    @Test
    fun parsesFirmwareEncoding() {
        // Same vector as the firmware's shots.rs `encodes_ages` test.
        val value = bytes(
            0xef, 0xbe, 0xad, 0xde, 2,
            0, 0, 0, 0, 0x28, 0x23, 0, 0, 4, 0, 0, 0,
            1, 0, 0, 0, 0x58, 0x1b, 0, 0, 0, 0, 0, 0,
        )
        assertEquals(
            Events(0xdeadbeefL, listOf(Shot(0, 9000, 4), Shot(1, 7000, 0))),
            ShutterProtocol.parseEvents(value),
        )
    }

    @Test
    fun ignoresTrailingPadding() {
        // The characteristic is fixed-size; bytes past the shots are zero.
        val value = bytes(1, 0, 0, 0, 0) + ByteArray(192)
        assertEquals(Events(1, emptyList()), ShutterProtocol.parseEvents(value))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncated() {
        ShutterProtocol.parseEvents(bytes(1, 0, 0, 0, 1, 0, 0, 0))
    }

    @Test
    fun encodesAck() {
        assertArrayEquals(bytes(0x2a, 0, 0, 0), ShutterProtocol.encodeAck(42))
        assertArrayEquals(bytes(0xff, 0xff, 0xff, 0xff), ShutterProtocol.encodeAck(0xffffffffL))
    }
}
