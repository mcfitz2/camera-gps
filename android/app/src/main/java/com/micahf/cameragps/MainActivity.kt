package com.micahf.cameragps

import android.Manifest
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.bluetooth.le.ScanFilter
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraRoll
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.micahf.cameragps.db.FilmDb
import com.micahf.cameragps.ui.CameraGpsTheme
import com.micahf.cameragps.ui.CameraScreen
import com.micahf.cameragps.ui.FilmScreen
import com.micahf.cameragps.ui.SetupScreen

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    /** Bumped to recompose after permission or association changes. */
    private val refresh = mutableIntStateOf(0)
    private val cameraName = mutableStateOf<String?>(null)
    private val shutterPaired = mutableStateOf(false)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    private val chooseDevice =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContent {
            CameraGpsTheme {
                refresh.intValue // read so permission changes recompose
                val missing = remember(refresh.intValue) { missingPermissions() }
                val background = remember(refresh.intValue) { hasBackgroundLocation() }
                if (missing.isNotEmpty() || !background) {
                    SetupScreen(
                        bluetooth = missing.none { it.startsWith("android.permission.BLUETOOTH") },
                        location = Manifest.permission.ACCESS_FINE_LOCATION !in missing && background,
                        notifications = Manifest.permission.POST_NOTIFICATIONS !in missing,
                        onContinue = {
                            if (missing.isNotEmpty()) {
                                requestPermissions.launch(missing.toTypedArray())
                            } else {
                                requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                            }
                        },
                        onOpenSettings = {
                            startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
                            )
                        },
                    )
                } else {
                    Home()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onChanged()
    }

    private fun onChanged() {
        cameraName.value = prefs.cameraAddress?.let { cameraDisplayName() ?: "Canon camera" }
        shutterPaired.value = prefs.shutterAddress != null
        refresh.intValue++
        if (missingPermissions().isEmpty() && hasBackgroundLocation()) Wake.enable(this)
    }

    /** The camera's name as the companion device manager saw it, e.g. "EOS R6". */
    private fun cameraDisplayName(): String? =
        getSystemService(CompanionDeviceManager::class.java).myAssociations
            .firstOrNull { it.id == prefs.associationId }
            ?.displayName?.toString()?.takeIf { it.isNotBlank() }

    @Composable
    private fun Home() {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        icon = { Icon(if (tab == 0) Icons.Filled.CameraRoll else Icons.Outlined.CameraRoll, null) },
                        label = { Text("Film") },
                    )
                    NavigationBarItem(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        icon = { Icon(if (tab == 1) Icons.Filled.PhotoCamera else Icons.Outlined.PhotoCamera, null) },
                        label = { Text("Camera") },
                    )
                }
            },
            // The screens' own app bars take the status bar inset.
            contentWindowInsets = WindowInsets(0),
        ) { padding ->
            val modifier = Modifier.padding(padding)
            when (tab) {
                0 -> FilmScreen(
                    dao = remember { FilmDb.get(this).film() },
                    loggerPaired = shutterPaired.value,
                    onPairLogger = ::associateShutter,
                    onForgetLogger = ::forgetShutter,
                    onCollect = { ShutterService.start(this) },
                    modifier = modifier,
                )
                else -> CameraScreen(
                    cameraName = cameraName.value,
                    onPair = ::associateCamera,
                    onConnect = { CameraService.start(this) },
                    onForget = ::forget,
                    modifier = modifier,
                )
            }
        }
    }

    private fun associateCamera() {
        val canon = ScanFilter.Builder().setManufacturerData(Canon.COMPANY_ID, ByteArray(0)).build()
        associate(canon) { mac, id ->
            prefs.cameraAddress = mac
            prefs.associationId = id
            prefs.approved = false
            onChanged()
            // Connect straight away to pair while the camera's pairing screen is up.
            CameraService.start(this@MainActivity)
        }
    }

    private fun associateShutter() {
        associate(Wake.shutterFilter(null)) { mac, id ->
            prefs.shutterAddress = mac
            prefs.shutterAssociationId = id
            onChanged()
            // Collect anything taken before pairing.
            ShutterService.start(this@MainActivity)
        }
    }

    /** Shows the system device chooser for devices matching [filter]. */
    private fun associate(filter: ScanFilter, onAssociated: (mac: String, id: Int) -> Unit) {
        val cdm = getSystemService(CompanionDeviceManager::class.java)
        val request = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothLeDeviceFilter.Builder().setScanFilter(filter).build())
            .setSingleDevice(false)
            .build()
        cdm.associate(request, mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                chooseDevice.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(info: AssociationInfo) {
                val mac = info.deviceMacAddress?.toString()?.uppercase() ?: return
                Log.i(TAG, "associated with $mac")
                onAssociated(mac, info.id)
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
            }
        })
    }

    private fun forgetShutter() {
        Wake.disableShutter(this)
        disassociate(prefs.shutterAssociationId)
        prefs.forgetShutter()
        onChanged()
    }

    private fun disassociate(id: Int) {
        if (id >= 0) {
            runCatching { getSystemService(CompanionDeviceManager::class.java).disassociate(id) }
        }
    }

    private fun forget() {
        Wake.disableCamera(this)
        disassociate(prefs.associationId)
        prefs.forgetCamera()
        stopService(Intent(this, CameraService::class.java))
        onChanged()
    }

    private fun missingPermissions(): List<String> = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    ).filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun hasBackgroundLocation() =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "MainActivity"
    }
}
