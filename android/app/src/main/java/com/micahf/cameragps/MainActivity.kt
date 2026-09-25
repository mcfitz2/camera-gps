package com.micahf.cameragps

import android.Manifest
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.bluetooth.le.ScanFilter
import android.content.IntentSender
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micahf.cameragps.db.FilmDb
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private lateinit var prefs: Prefs
    /** Bumped to recompose after permission or association changes. */
    private val refresh = mutableIntStateOf(0)
    private val cameraAddress = mutableStateOf<String?>(null)
    private val shutterAddress = mutableStateOf<String?>(null)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    private val requestBackgroundLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }
    private val chooseDevice =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = tab == 0,
                                onClick = { tab = 0 },
                                icon = { Icon(Icons.Filled.LocationOn, null) },
                                label = { Text("Canon") },
                            )
                            NavigationBarItem(
                                selected = tab == 1,
                                onClick = { tab = 1 },
                                icon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                label = { Text("Film") },
                            )
                        }
                    },
                ) { padding ->
                    Screen(tab, Modifier.padding(padding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        onChanged()
    }

    private fun onChanged() {
        cameraAddress.value = prefs.cameraAddress
        shutterAddress.value = prefs.shutterAddress
        refresh.intValue++
        if (missingPermissions().isEmpty() && hasBackgroundLocation()) Wake.enable(this)
    }

    @Composable
    private fun Screen(tab: Int, modifier: Modifier) {
        refresh.intValue // read so permission changes recompose
        val missing = remember(refresh.intValue) { missingPermissions() }
        val background = remember(refresh.intValue) { hasBackgroundLocation() }
        when {
            missing.isNotEmpty() -> Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Camera GPS", style = MaterialTheme.typography.headlineMedium)
                Text("Needs Bluetooth, location and notification access.")
                Button(onClick = { requestPermissions.launch(missing.toTypedArray()) }) {
                    Text("Grant permissions")
                }
            }
            !background -> Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Camera GPS", style = MaterialTheme.typography.headlineMedium)
                Text("Needs location \"Allow all the time\" to tag photos while the app is closed.")
                Button(onClick = {
                    requestBackgroundLocation.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                }) { Text("Allow background location") }
            }
            tab == 0 -> CanonScreen(modifier)
            else -> FilmScreen(
                dao = remember { FilmDb.get(this).film() },
                shutterAddress = shutterAddress.value,
                onChooseShutter = ::associateShutter,
                onForgetShutter = ::forgetShutter,
                onCollect = { ShutterService.start(this) },
                modifier = modifier,
            )
        }
    }

    @Composable
    private fun CanonScreen(modifier: Modifier) {
        val status by StatusStore.status.collectAsStateWithLifecycle()
        val address = cameraAddress.value

        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera GPS", style = MaterialTheme.typography.headlineMedium)
            when {
                address == null -> {
                    Text("Turn the camera on, enable Bluetooth pairing on it, then choose it here.")
                    Button(onClick = ::associateCamera) { Text("Choose camera") }
                }
                else -> {
                    Text("Camera $address")
                    Text("Status: ${status.link.label}")
                    status.message?.let { Text(it) }
                    status.lastSent?.let {
                        val at = DateFormat.getTimeInstance().format(Date(it.atMillis))
                        Text("Last sent %.5f, %.5f (±%.0f m) at %s".format(it.latitude, it.longitude, it.accuracyM, at))
                    }
                    Text("Sent this session: ${status.sendsThisSession}")
                    Button(onClick = { CameraService.start(this@MainActivity) }) { Text("Connect now") }
                    OutlinedButton(onClick = ::forget) { Text("Forget camera") }
                }
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
        stopService(android.content.Intent(this, CameraService::class.java))
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
