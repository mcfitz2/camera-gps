package com.micahf.cameragps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.automirrored.outlined.BluetoothSearching
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micahf.cameragps.Link
import com.micahf.cameragps.Places
import com.micahf.cameragps.Sent
import com.micahf.cameragps.StatusStore
import kotlinx.coroutines.delay

/** The Camera tab: pairing, then what the link to the camera is doing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    cameraName: String?,
    onPair: () -> Unit,
    onConnect: () -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by StatusStore.status.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    val paired = cameraName != null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Camera") },
                actions = {
                    if (paired) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Forget camera") },
                                onClick = {
                                    menu = false
                                    onForget()
                                },
                            )
                        }
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!paired) {
                Spacer(Modifier.height(48.dp))
                EmptyState(
                    Icons.Outlined.PhotoCamera,
                    "Pair your camera",
                    "Turn on Bluetooth pairing on the camera, then choose it here. After that, photos are " +
                        "tagged with your location whenever the camera is on.",
                ) {
                    Button(onClick = onPair) { Text("Pair camera") }
                }
                return@Column
            }

            Spacer(Modifier.height(32.dp))
            LinkHero(status.link, cameraName!!, status.message)
            if (status.link == Link.Idle) {
                Spacer(Modifier.height(20.dp))
                FilledTonalButton(onClick = onConnect) { Text("Connect now") }
            }
            status.lastSent?.let {
                Spacer(Modifier.height(32.dp))
                LastFix(it, status.sendsThisSession)
            }
        }
    }
}

@Composable
private fun LinkHero(link: Link, name: String, message: String?) {
    val colors = MaterialTheme.colorScheme
    val (container, content, dot) = when (link) {
        Link.Idle -> Triple(colors.surfaceContainerHighest, colors.onSurfaceVariant, colors.outline)
        Link.Connecting, Link.AwaitingApproval ->
            Triple(colors.tertiaryContainer, colors.onTertiaryContainer, colors.tertiary)
        Link.Connected, Link.Sending -> Triple(colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
    }
    val icon = when (link) {
        Link.Idle -> Icons.Outlined.PhotoCamera
        Link.Connecting -> Icons.AutoMirrored.Outlined.BluetoothSearching
        Link.AwaitingApproval -> Icons.Outlined.TouchApp
        Link.Connected, Link.Sending -> Icons.Outlined.MyLocation
    }
    IconBadge(icon, container, content, size = 120.dp)
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(dot, pulse = link == Link.Connecting || link == Link.Sending)
        Spacer(Modifier.width(8.dp))
        Text(name, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
    Text(link.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
    Spacer(Modifier.height(8.dp))
    Text(
        message?.replaceFirstChar(Char::uppercase) ?: link.detail,
        style = MaterialTheme.typography.bodyLarge,
        color = colors.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Where the camera was last told it is, and how long ago. */
@Composable
private fun LastFix(sent: Sent, updates: Int) {
    val context = LocalContext.current
    var place by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(sent.latitude, sent.longitude) {
        place = Places.name(context, sent.latitude, sent.longitude)
    }
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(30_000)
            value = System.currentTimeMillis()
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.LocationOn, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Last location sent", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(place ?: coordinates(sent.latitude, sent.longitude), style = MaterialTheme.typography.titleMedium)
                Text(
                    "±%.0f m · %s".format(sent.accuracyM, ago(sent.atMillis, maxOf(now, sent.atMillis)).lowercase()) +
                        if (updates > 0) " · ${if (updates == 1) "1 update" else "$updates updates"}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
