package com.micahf.cameragps

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.location.altitude.AltitudeConverter
import android.os.CancellationSignal
import android.os.IBinder
import android.util.Log
import com.micahf.cameragps.db.FilmDb
import com.micahf.cameragps.db.Frame
import com.micahf.cameragps.db.Roll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume

/**
 * Collects shots from the hotshoe shutter logger: connects while getting a
 * fresh location, stores the shots as the next frames of the loaded roll,
 * acknowledges them, and stops. [Wake] starts it when the logger advertises.
 */
class ShutterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var started = false
    private lateinit var notifier: Notifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        started = startForegroundFor(
            Notifier.ID_SHUTTER,
            notifier.collecting(),
            withLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION),
        ) != null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Scan matches keep arriving while the logger advertises; one run collects everything.
        if (job?.isActive != true) {
            job = scope.launch {
                runCatching { collect() }.onFailure { Log.w(TAG, "collect: ${it.message}") }
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun collect() {
        val address = Prefs(this).shutterAddress ?: return
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
            Log.w(TAG, "missing Bluetooth permission")
            return
        }
        // The logger uses a static random address, which getRemoteDevice would take as public.
        val device = adapter.getRemoteLeDevice(address, BluetoothDevice.ADDRESS_TYPE_RANDOM)
        val fix = scope.async { currentLocation() }
        for (attempt in 1..CONNECT_ATTEMPTS) {
            try {
                session(device, fix)
                return
            } catch (e: GattException) {
                Log.w(TAG, "attempt $attempt: ${e.message}")
                delay(RETRY_DELAY_MS)
            }
        }
    }

    private suspend fun session(device: BluetoothDevice, fix: Deferred<Location?>) {
        val gatt = Gatt.connect(this, device, CONNECT_TIMEOUT_MS, bond = false)
        try {
            runCatching { gatt.requestMtu(ShutterProtocol.MTU) }
                .onFailure { Log.w(TAG, "MTU: ${it.message}") }
            // Each read carries up to 16 shots; the logger drops them once acknowledged.
            while (true) {
                val value = gatt.read(ShutterProtocol.SERVICE, ShutterProtocol.EVENTS)
                val receivedAt = System.currentTimeMillis()
                val events = ShutterProtocol.parseEvents(value)
                Prefs(this).shutterLastSeen = receivedAt
                Log.i(TAG, "boot ${events.bootId.toString(16)}: ${events.shots}")
                if (events.shots.isEmpty()) return
                store(events, receivedAt, fix.await())
                gatt.write(ShutterProtocol.SERVICE, ShutterProtocol.ACK, ShutterProtocol.encodeAck(events.shots.maxOf { it.seq }))
            }
        } finally {
            gatt.close()
        }
    }

    private suspend fun store(events: ShutterProtocol.Events, receivedAt: Long, fix: Location?) {
        val shots = ShotFrames.frames(events, receivedAt, fix?.let {
            ShotFrames.Fix(
                time = it.time,
                lat = it.latitude,
                lon = it.longitude,
                accuracyM = it.takeIf { f -> f.hasAccuracy() }?.accuracy,
                altM = if (it.hasMslAltitude()) it.mslAltitudeMeters else null,
            )
        })
        val now = System.currentTimeMillis()
        val untitled = "Untitled roll " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now))
        val dao = FilmDb.get(this).film()
        val (roll, added) = dao.addShots(shots, untitled, now)
        if (roll == null || added.isEmpty()) return
        // One fix covers the batch, so one lookup names them all.
        val place = fix?.let { Places.name(this, it.latitude, it.longitude) }
        val named = if (place == null) added else added.map { it.copy(place = place) }
        if (place != null) dao.setPlace(added.map { it.id }, place)
        report(roll, named, newRoll = roll.loadedAt == now)
    }

    private fun report(roll: Roll, added: List<Frame>, newRoll: Boolean) {
        val first = added.first().number
        val last = added.last().number
        val frames = if (first == last) "Frame $last" else "Frames $first–$last"
        val place = added.last().let {
            when {
                it.lat == null -> "no location"
                it.approximate -> it.place?.let { p -> "near $p" } ?: "approximate location"
                else -> it.place ?: it.accuracyM?.let { m -> "±%.0f m".format(m) }
            }
        }
        val text = listOfNotNull(place, if (newRoll) "new roll started, rename it in the app" else null)
        notifier.frames("$frames · ${roll.name}", text.joinToString(" · ").ifEmpty { null })
        Log.i(TAG, "$frames on ${roll.name}")
        if (last >= roll.capacity) {
            notifier.problem("Roll full", "${roll.name} is at frame $last of ${roll.capacity}. Load a new roll in the app.")
        }
    }

    /** A fresh fix with sea-level altitude, or the last known one if none comes in time. */
    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        val locations = getSystemService(LocationManager::class.java)
        val request = LocationRequest.Builder(0)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setDurationMillis(LOCATION_TIMEOUT_MS)
            .build()
        val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cancel = CancellationSignal()
                cont.invokeOnCancellation { cancel.cancel() }
                locations.getCurrentLocation(LocationManager.FUSED_PROVIDER, request, cancel, mainExecutor) {
                    if (cont.isActive) cont.resume(it)
                }
            }
        } ?: locations.getLastKnownLocation(LocationManager.FUSED_PROVIDER) ?: return null
        if (!fix.hasMslAltitude() && fix.hasAltitude()) {
            withContext(Dispatchers.IO) {
                runCatching { AltitudeConverter().addMslAltitudeToLocation(this@ShutterService, fix) }
                    .onFailure { Log.w(TAG, "altitude conversion: ${it.message}") }
            }
        }
        return fix
    }

    private fun granted(permission: String) =
        checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "ShutterService"
        private const val CONNECT_TIMEOUT_MS = 5_000L
        private const val CONNECT_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_000L
        private const val LOCATION_TIMEOUT_MS = 15_000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, ShutterService::class.java))
            } catch (e: ForegroundServiceStartNotAllowedException) {
                Log.w(TAG, "not allowed to start from the background: ${e.message}")
            }
        }
    }
}
