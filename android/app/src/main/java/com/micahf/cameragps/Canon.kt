package com.micahf.cameragps

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.abs

/**
 * Canon EOS "smartphone" BLE protocol, as used by the firmware in ../firmware
 * (itself from furble's `CanonEOSSmart`).
 */
object Canon {
    private fun uuid(prefix: String): UUID = UUID.fromString("$prefix-0000-1000-0000-d8492fffa821")

    val PRI_SVC: UUID = uuid("00010000")
    val CHR_NAME: UUID = uuid("00010006")
    val CHR_IDEN: UUID = uuid("0001000a")
    val MODE_SVC: UUID = uuid("00030000")
    val CHR_MODE: UUID = uuid("00030010")
    val GEO_SVC: UUID = uuid("00040000")
    val GEO_CHR: UUID = uuid("00040002")
    val GEO_IND: UUID = uuid("00040003")

    const val PAIR_ACCEPT: Byte = 0x02
    const val MODE_SHOOT: Byte = 0x02
    const val GEO_REQUEST: Byte = 0x03
    const val GEO_SUCCESS: Byte = 0x02
    const val GEO_ENABLE: Byte = 0x01

    /** Canon's Bluetooth SIG company identifier, in its advertising data. */
    const val COMPANY_ID = 0x01A9

    /**
     * Canon manufacturer data (after the company ID) seen from an M50 Mark II.
     * The last byte is the power state: 0x02 on, 0x05 off. While off the camera
     * keeps advertising (every ~1.2 s instead of ~60 ms) but drops connections.
     */
    val ADV_DATA_ON = byteArrayOf(0x01, 0xf9.toByte(), 0x32, 0xb2.toByte(), 0x09, 0x02)
    /** Only the power-state byte has to match. */
    val ADV_MASK_STATE = byteArrayOf(0, 0, 0, 0, 0, 0xff.toByte())

    const val LOCATION_PACKET_LEN = 20

    /**
     * Location packet for [GEO_CHR]:
     * `0x04 | 'N'/'S' | f32 lat | 'E'/'W' | f32 lon | '+'/'-' | f32 alt | u32 epoch`,
     * little-endian, magnitudes unsigned with the sign in the preceding byte.
     */
    fun encodeLocation(latitude: Double, longitude: Double, altitude: Double, epochSecs: Long): ByteArray =
        ByteBuffer.allocate(LOCATION_PACKET_LEN).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(0x04)
            put((if (latitude < 0) 'S' else 'N').code.toByte())
            putFloat(abs(latitude).toFloat())
            put((if (longitude < 0) 'W' else 'E').code.toByte())
            putFloat(abs(longitude).toFloat())
            put((if (altitude < 0) '-' else '+').code.toByte())
            putFloat(abs(altitude).toFloat())
            putInt(epochSecs.toInt())
        }.array()
}
