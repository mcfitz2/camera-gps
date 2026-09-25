package com.micahf.cameragps

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import java.nio.ByteBuffer
import java.util.UUID

/** Persistent settings. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("camera", Context.MODE_PRIVATE)

    /** MAC address of the associated camera, if any. */
    var cameraAddress: String?
        get() = prefs.getString(KEY_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_ADDRESS, value) }

    /** Companion device association for [cameraAddress]. */
    var associationId: Int
        get() = prefs.getInt(KEY_ASSOCIATION, -1)
        set(value) = prefs.edit { putInt(KEY_ASSOCIATION, value) }

    /** Whether the camera has accepted this phone's pairing request. */
    var approved: Boolean
        get() = prefs.getBoolean(KEY_APPROVED, false)
        set(value) = prefs.edit { putBoolean(KEY_APPROVED, value) }

    /** Address of the associated hotshoe shutter logger, if any. */
    var shutterAddress: String?
        get() = prefs.getString(KEY_SHUTTER_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_SHUTTER_ADDRESS, value) }

    /** Companion device association for [shutterAddress]. */
    var shutterAssociationId: Int
        get() = prefs.getInt(KEY_SHUTTER_ASSOCIATION, -1)
        set(value) = prefs.edit { putInt(KEY_SHUTTER_ASSOCIATION, value) }

    /** When the phone last read the shutter logger, or 0. */
    var shutterLastSeen: Long
        get() = prefs.getLong(KEY_SHUTTER_LAST_SEEN, 0)
        set(value) = prefs.edit { putLong(KEY_SHUTTER_LAST_SEEN, value) }

    /** Name shown on the camera's pairing prompt and device list. */
    val deviceName: String = Build.MODEL

    /** Stable identity presented to the camera, created on first use. */
    val deviceUuid: ByteArray
        get() {
            val stored = prefs.getString(KEY_UUID, null)
            val uuid = stored?.let(UUID::fromString) ?: UUID.randomUUID().also {
                prefs.edit { putString(KEY_UUID, it.toString()) }
            }
            return ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
        }

    fun forgetCamera() = prefs.edit {
        remove(KEY_ADDRESS)
        remove(KEY_ASSOCIATION)
        remove(KEY_APPROVED)
    }

    fun forgetShutter() = prefs.edit {
        remove(KEY_SHUTTER_ADDRESS)
        remove(KEY_SHUTTER_ASSOCIATION)
        remove(KEY_SHUTTER_LAST_SEEN)
    }

    private companion object {
        const val KEY_ADDRESS = "address"
        const val KEY_ASSOCIATION = "association"
        const val KEY_APPROVED = "approved"
        const val KEY_UUID = "uuid"
        const val KEY_SHUTTER_ADDRESS = "shutter_address"
        const val KEY_SHUTTER_ASSOCIATION = "shutter_association"
        const val KEY_SHUTTER_LAST_SEEN = "shutter_last_seen"
    }
}
