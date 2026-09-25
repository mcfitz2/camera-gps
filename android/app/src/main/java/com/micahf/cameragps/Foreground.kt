package com.micahf.cameragps

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.pm.ServiceInfo
import android.util.Log

/**
 * Enters the foreground as a connected device, with location too when
 * [withLocation]. Android refuses the location type to a service started from
 * the background unless location is allowed all the time, so this falls back
 * to Bluetooth alone.
 *
 * @return the types granted, or null if Android refused both.
 */
internal fun Service.startForegroundFor(id: Int, notification: Notification, withLocation: Boolean): Int? {
    val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
    val attempts = if (withLocation) listOf(device or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, device) else listOf(device)
    for (types in attempts) {
        try {
            startForeground(id, notification, types)
            return types
        } catch (e: SecurityException) {
            Log.w("Foreground", "types $types refused: ${e.message}")
        } catch (e: ForegroundServiceStartNotAllowedException) {
            Log.w("Foreground", "not allowed: ${e.message}")
            return null
        }
    }
    return null
}
