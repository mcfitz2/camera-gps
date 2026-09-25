package com.micahf.cameragps

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.ParcelUuid
import android.util.Log

/**
 * Wakes the app when a device needs it, without the app running: system-held
 * BLE scans, filtered in the Bluetooth controller, deliver matches to a
 * receiver.
 *
 * - The camera: its address *and* its "powered on" advertising state, to
 *   [WakeReceiver].
 * - The hotshoe shutter logger: its address, service and "shots pending"
 *   advertising flag, to [ShutterWakeReceiver].
 */
object Wake {
    private const val TAG = "Wake"
    private const val REQUEST_CAMERA = 0
    private const val REQUEST_SHUTTER = 1

    /** Advertisements from [address] while the camera is on. */
    fun cameraOnFilter(address: String): ScanFilter = ScanFilter.Builder()
        .setDeviceAddress(address)
        .setManufacturerData(Canon.COMPANY_ID, Canon.ADV_DATA_ON, Canon.ADV_MASK_STATE)
        .build()

    /** Advertisements from the shutter logger; [address] null matches any. */
    fun shutterFilter(address: String?): ScanFilter = ScanFilter.Builder()
        .apply { if (address != null) setDeviceAddress(address) }
        .setServiceUuid(ParcelUuid(ShutterProtocol.SERVICE))
        .build()

    /** Advertisements from the shutter logger at [address] while it has shots. */
    fun shutterPendingFilter(address: String): ScanFilter = ScanFilter.Builder()
        .setDeviceAddress(address)
        .setServiceUuid(ParcelUuid(ShutterProtocol.SERVICE))
        .setManufacturerData(ShutterProtocol.COMPANY_ID, ShutterProtocol.PENDING, ShutterProtocol.PENDING)
        .build()

    /** (Re)start the background scans for the saved devices, if any. */
    fun enable(context: Context) {
        val prefs = Prefs(context)
        prefs.cameraAddress?.let {
            // The camera advertises for as long as it's on, so the slowest scan will do.
            start(context, cameraPendingIntent(context), cameraOnFilter(it), ScanSettings.SCAN_MODE_LOW_POWER)
        }
        prefs.shutterAddress?.let {
            // The frame's location is taken when it's collected, so collect soon.
            start(context, shutterPendingIntent(context), shutterPendingFilter(it), ScanSettings.SCAN_MODE_BALANCED)
        }
    }

    fun disableCamera(context: Context) = stop(context, cameraPendingIntent(context))

    fun disableShutter(context: Context) = stop(context, shutterPendingIntent(context))

    @SuppressLint("MissingPermission")
    private fun start(context: Context, intent: PendingIntent, filter: ScanFilter, mode: Int) {
        val scanner = scanner(context) ?: return
        // Registering the same PendingIntent twice would run two scans.
        runCatching { scanner.stopScan(intent) }
        val settings = ScanSettings.Builder()
            .setScanMode(mode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val result = runCatching { scanner.startScan(listOf(filter), settings, intent) }
        Log.i(TAG, "background scan for ${filter.deviceAddress}: ${result.exceptionOrNull() ?: result.getOrNull()}")
    }

    @SuppressLint("MissingPermission")
    private fun stop(context: Context, intent: PendingIntent) {
        runCatching { scanner(context)?.stopScan(intent) }
    }

    private fun scanner(context: Context): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner

    private fun cameraPendingIntent(context: Context) =
        pendingIntent(context, REQUEST_CAMERA, WakeReceiver::class.java)

    private fun shutterPendingIntent(context: Context) =
        pendingIntent(context, REQUEST_SHUTTER, ShutterWakeReceiver::class.java)

    private fun pendingIntent(context: Context, request: Int, receiver: Class<*>): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, receiver),
            // Mutable so the system can attach the scan results.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

    /** Whether a scan-result broadcast is actually a match rather than an error. */
    fun isMatch(intent: Intent): Boolean {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) Log.w(TAG, "background scan error $error")
        return error == 0
    }
}

/** Receives background scan matches: the camera is on. */
class WakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Wake.isMatch(intent)) CameraService.start(context)
    }
}

/** Receives background scan matches: the shutter logger has shots. */
class ShutterWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Wake.isMatch(intent)) ShutterService.start(context)
    }
}

/** The system drops registered scans on reboot and app update. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> Wake.enable(context)
        }
    }
}
