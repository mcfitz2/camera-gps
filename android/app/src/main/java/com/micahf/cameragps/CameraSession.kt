package com.micahf.cameragps

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.content.Context
import android.location.Location
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Why a session ended. */
sealed interface SessionEnd {
    data object Disconnected : SessionEnd
    data class Failed(val reason: String) : SessionEnd
}

/**
 * One connection to the camera: handshake, then location updates until the
 * link drops.
 */
class CameraSession(
    private val context: Context,
    private val device: BluetoothDevice,
    private val prefs: Prefs,
    private val location: StateFlow<Location?>,
    private val notifier: Notifier,
) {
    /** Whether "no accurate location" has been reported this session. */
    private var reportedNoFix = false

    private val pairResult = AtomicInteger(0)
    private val geoRequested = AtomicBoolean(false)
    private val geoEnabled = AtomicBoolean(false)

    suspend fun run(): SessionEnd {
        StatusStore.update { it.copy(link = Link.Connecting, sendsThisSession = 0, message = null) }
        val gatt = try {
            Gatt.connect(context, device, CONNECT_TIMEOUT_MS)
        } catch (e: Exception) {
            return SessionEnd.Failed("connect: ${e.message}")
        }
        try {
            handshake(gatt)
            Log.i(TAG, "camera connected")
            return feed(gatt)
        } catch (e: GattException) {
            return if (gatt.isConnected) SessionEnd.Failed(e.message ?: "gatt error") else SessionEnd.Disconnected
        } finally {
            gatt.close()
        }
    }

    private suspend fun handshake(gatt: Gatt) {
        // Fast connection interval while exchanging the ~20 handshake messages.
        gatt.requestPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        val approved = prefs.approved
        if (approved) pairResult.set(Canon.PAIR_ACCEPT.toInt())

        gatt.indicate(Canon.PRI_SVC, Canon.CHR_NAME) { data ->
            data.firstOrNull()?.let {
                Log.i(TAG, "pairing result 0x%02x".format(it))
                pairResult.set(it.toInt())
            }
        }
        val name = prefs.deviceName.toByteArray()
        gatt.write(Canon.PRI_SVC, Canon.CHR_NAME, byteArrayOf(0x01) + name)
        gatt.write(Canon.PRI_SVC, Canon.CHR_IDEN, byteArrayOf(0x03) + prefs.deviceUuid)
        gatt.write(Canon.PRI_SVC, Canon.CHR_IDEN, byteArrayOf(0x04) + name)
        gatt.write(Canon.PRI_SVC, Canon.CHR_IDEN, byteArrayOf(0x05, 0x02))

        if (!approved) {
            Log.i(TAG, "confirm pairing with ${prefs.deviceName} on the camera")
            StatusStore.update { it.copy(link = Link.AwaitingApproval) }
            withTimeoutOrNull(APPROVAL_TIMEOUT_MS) {
                while (pairResult.get() == 0) delay(POLL_MS)
            }
            val result = pairResult.get()
            if (result != Canon.PAIR_ACCEPT.toInt()) {
                notifier.problem("Camera pairing not accepted", "Pair again from the app with the camera in pairing mode")
                throw GattException("pairing not accepted (0x%02x)".format(result))
            }
            prefs.approved = true
        }

        gatt.indicate(Canon.GEO_SVC, Canon.GEO_IND) { data ->
            when (data.firstOrNull()) {
                Canon.GEO_REQUEST -> geoRequested.set(true)
                Canon.GEO_SUCCESS -> geoEnabled.set(true)
                else -> Log.i(TAG, "location indication ${data.toHex()}")
            }
        }
        gatt.write(Canon.PRI_SVC, Canon.CHR_IDEN, byteArrayOf(0x01))
        gatt.write(Canon.MODE_SVC, Canon.CHR_MODE, byteArrayOf(Canon.MODE_SHOOT))
        gatt.requestPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
    }

    private suspend fun feed(gatt: Gatt): SessionEnd {
        StatusStore.update { it.copy(link = Link.Connected) }
        var lastSend = 0L
        var writeFailures = 0
        while (gatt.isConnected) {
            if (geoRequested.getAndSet(false)) {
                Log.i(TAG, "camera requested location; enabling")
                runCatching { gatt.write(Canon.GEO_SVC, Canon.GEO_CHR, byteArrayOf(Canon.GEO_ENABLE)) }
                    .onFailure { Log.w(TAG, "enable location: ${it.message}") }
            }
            val now = System.currentTimeMillis()
            if (geoEnabled.get() && now - lastSend >= SEND_INTERVAL_MS) {
                val fix = usableFix(now)
                if (fix != null) {
                    lastSend = now
                    try {
                        send(gatt, fix, now)
                        writeFailures = 0
                    } catch (e: GattException) {
                        if (!gatt.isConnected) break
                        writeFailures++
                        Log.w(TAG, "location write failed ($writeFailures/$MAX_WRITE_FAILURES): ${e.message}")
                        if (writeFailures >= MAX_WRITE_FAILURES) {
                            return SessionEnd.Failed("camera keeps rejecting location")
                        }
                    }
                }
            }
            delay(POLL_MS)
        }
        return SessionEnd.Disconnected
    }

    /** Latest fix if it's recent and accurate enough to tag photos with. */
    private fun usableFix(now: Long): Location? {
        val fix = location.value ?: return null
        val tooOld = now - fix.time > MAX_FIX_AGE_MS
        val tooRough = !fix.hasAccuracy() || fix.accuracy > MAX_ACCURACY_M
        if (tooOld || tooRough) {
            StatusStore.update { it.copy(message = "waiting for an accurate location") }
            if (!reportedNoFix) {
                reportedNoFix = true
                notifier.problem("No accurate location", "Photos won't be tagged until the phone gets a location fix")
            }
            return null
        }
        return fix
    }

    private suspend fun send(gatt: Gatt, fix: Location, now: Long) {
        val altitude = when {
            fix.hasMslAltitude() -> fix.mslAltitudeMeters
            fix.hasAltitude() -> fix.altitude
            else -> 0.0
        }
        gatt.write(
            Canon.GEO_SVC,
            Canon.GEO_CHR,
            Canon.encodeLocation(fix.latitude, fix.longitude, altitude, now / 1000),
        )
        if (StatusStore.status.value.sendsThisSession == 0) {
            notifier.connected()
            notifier.clearProblem()
        } else if (reportedNoFix) {
            notifier.clearProblem()
        }
        reportedNoFix = false
        Log.i(TAG, "sent %.6f,%.6f ±%.0f m alt %.1f".format(fix.latitude, fix.longitude, fix.accuracy, altitude))
        StatusStore.update {
            it.copy(
                link = Link.Sending,
                lastSent = Sent(fix.latitude, fix.longitude, fix.accuracy, now),
                sendsThisSession = it.sendsThisSession + 1,
                message = null,
            )
        }
    }

    private companion object {
        const val TAG = "CameraSession"
        /** A connect to an advertising camera normally takes well under a second. */
        const val CONNECT_TIMEOUT_MS = 5_000L
        const val APPROVAL_TIMEOUT_MS = 60_000L
        const val SEND_INTERVAL_MS = 10_000L
        const val POLL_MS = 100L
        /** Consecutive rejected writes before reconnecting; the M50 II sometimes needs a fresh link. */
        const val MAX_WRITE_FAILURES = 3
        /** Older fixes may be from somewhere else. */
        const val MAX_FIX_AGE_MS = 10 * 60_000L
        /** Worse than this (e.g. a cell-tower-only fix) isn't worth tagging photos with. */
        const val MAX_ACCURACY_M = 200f
    }
}

private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it) }
