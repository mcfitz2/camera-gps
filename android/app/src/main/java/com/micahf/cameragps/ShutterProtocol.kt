package com.micahf.cameragps

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * The hotshoe shutter logger's GATT protocol (firmware in `shutter/`).
 *
 * The device keeps shots until the phone acknowledges them. The phone reads
 * [EVENTS] (see [parseEvents]) and writes the last stored sequence number to
 * [ACK] as a little-endian u32, which drops that shot and every earlier one.
 */
object ShutterProtocol {
    val SERVICE: UUID = UUID.fromString("8a1d0001-4f3c-4b8e-9a61-2c7e5b3d9f40")
    val EVENTS: UUID = UUID.fromString("8a1d0002-4f3c-4b8e-9a61-2c7e5b3d9f40")
    val ACK: UUID = UUID.fromString("8a1d0003-4f3c-4b8e-9a61-2c7e5b3d9f40")

    /**
     * The device advertises manufacturer data under this company ID (the one
     * reserved for testing): one byte, [PENDING] while it has shots to hand over.
     */
    const val COMPANY_ID = 0xffff
    val PENDING = byteArrayOf(1)

    /** One read carries up to 16 shots, which needs a 247-byte ATT MTU. */
    const val MTU = 247

    private const val HEADER_LEN = 5
    private const val RECORD_LEN = 12

    /**
     * A shot the device hasn't had acknowledged.
     *
     * @property ageMs time since the shot, as of the read.
     * @property contactMs how long the flash contact stayed closed; 0 if unknown
     *   (still closed, or too short to measure).
     */
    data class Shot(val seq: Long, val ageMs: Long, val contactMs: Long)

    /** @property bootId changes whenever the device loses power, restarting [Shot.seq]. */
    data class Events(val bootId: Long, val shots: List<Shot>)

    /**
     * Parses the events value: `boot_id u32, count u8`, then per shot
     * `seq u32, age_ms u32, contact_ms u32`, all little-endian.
     */
    fun parseEvents(value: ByteArray): Events {
        require(value.size >= HEADER_LEN) { "events too short (${value.size} bytes)" }
        val buf = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        val bootId = buf.int.toUInt().toLong()
        val count = buf.get().toUByte().toInt()
        require(value.size >= HEADER_LEN + count * RECORD_LEN) { "events truncated: $count shots in ${value.size} bytes" }
        val shots = List(count) {
            Shot(buf.int.toUInt().toLong(), buf.int.toUInt().toLong(), buf.int.toUInt().toLong())
        }
        return Events(bootId, shots)
    }

    fun encodeAck(seq: Long): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq.toInt()).array()
}
