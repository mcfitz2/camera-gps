package com.micahf.cameragps

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.location.altitude.AltitudeConverter
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Runs while the camera is on: tracks location and keeps a session with the
 * camera going. Stops itself once the camera has stopped advertising that it
 * is on; [Wake] starts it again next time.
 */
class CameraService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private var started = false
    private val location = MutableStateFlow<Location?>(null)
    private lateinit var locations: LocationManager
    private lateinit var notifier: Notifier
    private val locationListener = LocationListener { fix -> onLocation(fix) }
    /** Loads the geoid model once; conversions run one at a time on it. */
    private val altitude by lazy { AltitudeConverter() }
    private val converting = Dispatchers.IO.limitedParallelism(1)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locations = getSystemService(LocationManager::class.java)
        notifier = Notifier(this)
        val notifications = getSystemService(NotificationManager::class.java)
        val types = startForegroundFor(
            Notifier.ID_ONGOING,
            notifier.ongoing(StatusStore.status.value),
            withLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        )
        started = types != null
        if (!started) return
        if (types!! and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION != 0) startLocationUpdates()
        else Log.w(TAG, "no location type; photos won't be tagged")
        scope.launch {
            StatusStore.status.collect { s ->
                notifications.notify(Notifier.ID_ONGOING, notifier.ongoing(s))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            loop = scope.launch {
                try {
                    runSessions()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "sessions: ${e.message}")
                } finally {
                    // Leaving the foreground matters most when something went wrong.
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        locations.removeUpdates(locationListener)
        scope.cancel()
        StatusStore.update { it.copy(link = Link.Idle, message = null) }
        super.onDestroy()
    }

    private suspend fun runSessions() {
        val prefs = Prefs(this)
        val address = prefs.cameraAddress ?: return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT) || !granted(Manifest.permission.BLUETOOTH_SCAN)) {
            Log.w(TAG, "missing Bluetooth permissions")
            return
        }
        val device = adapter.getRemoteDevice(address)
        var failures = 0
        var sends = 0
        while (waitForCameraOn(address)) {
            val end = CameraSession(this, device, prefs, location, notifier).run()
            Log.i(TAG, "session ended: $end")
            sends += StatusStore.status.value.sendsThisSession
            StatusStore.update { it.copy(link = Link.Idle) }
            if (end is SessionEnd.Failed) {
                StatusStore.update { it.copy(message = end.reason) }
                // Single failures are routine (e.g. the camera still booting).
                if (++failures == FAILURES_TO_REPORT) notifier.problem("Can't connect to camera", end.reason)
                delay(RETRY_DELAY_MS)
            } else {
                failures = 0
            }
        }
        if (sends > 0) notifier.disconnected(sends)
        Log.i(TAG, "camera is off or out of range; stopping")
    }

    /** Scan until the camera advertises that it is on, or give up. */
    @SuppressLint("MissingPermission")
    private suspend fun waitForCameraOn(address: String): Boolean {
        val scanner = getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner ?: return false
        return withTimeoutOrNull(CAMERA_GONE_MS) {
            suspendCancellableCoroutine { cont ->
                val callback = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        scanner.stopScan(this)
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onScanFailed(errorCode: Int) {
                        Log.w(TAG, "scan failed: $errorCode")
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                scanner.startScan(listOf(Wake.cameraOnFilter(address)), settings, callback)
                cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
            }
        } ?: false
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        locations.getLastKnownLocation(LocationManager.FUSED_PROVIDER)?.let(::onLocation)
        val request = LocationRequest.Builder(LOCATION_INTERVAL_MS)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .build()
        locations.requestLocationUpdates(LocationManager.FUSED_PROVIDER, request, mainExecutor, locationListener)
    }

    /** Publish a fix, adding sea-level altitude (what cameras record) when missing. */
    private fun onLocation(fix: Location) {
        if (fix.hasMslAltitude() || !fix.hasAltitude()) {
            publish(fix)
            return
        }
        scope.launch(converting) {
            runCatching { altitude.addMslAltitudeToLocation(this@CameraService, fix) }
                .onFailure { Log.w(TAG, "altitude conversion: ${it.message}") }
            publish(fix)
        }
    }

    /** A slow conversion mustn't replace a newer fix with an older one. */
    private fun publish(fix: Location) {
        location.update { current -> if (current != null && current.time > fix.time) current else fix }
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CameraService"
        private const val RETRY_DELAY_MS = 1_000L
        /** Stop once the camera hasn't advertised "on" for this long. */
        private const val CAMERA_GONE_MS = 20_000L
        private const val LOCATION_INTERVAL_MS = 5_000L
        /** Consecutive failed sessions before telling the user. */
        private const val FAILURES_TO_REPORT = 3

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, CameraService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start from the background: ${e.message}")
            }
        }
    }
}
