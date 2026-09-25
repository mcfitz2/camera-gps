package com.micahf.cameragps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * First run: what the app needs and why, ticked off as each is granted.
 * [onContinue] asks for the next missing permission.
 */
@Composable
fun SetupScreen(
    bluetooth: Boolean,
    location: Boolean,
    notifications: Boolean,
    onContinue: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            IconBadge(
                Icons.Outlined.Camera,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                size = 96.dp,
            )
            Spacer(Modifier.height(24.dp))
            Text("Camera GPS", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Tags your camera's photos with where they were taken, and logs every frame on a roll of film.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Requirement(Icons.Outlined.Bluetooth, "Nearby devices", "To find your camera and shutter logger", bluetooth)
            Requirement(
                Icons.Outlined.LocationOn,
                "Location, all the time",
                "So photos are tagged while the app is closed",
                location,
            )
            Requirement(Icons.Outlined.Notifications, "Notifications", "To tell you when frames are logged", notifications)
            Spacer(Modifier.height(32.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) { Text("Continue") }
            TextButton(onClick = onOpenSettings) { Text("Open app settings") }
        }
    }
}

@Composable
private fun Requirement(icon: ImageVector, title: String, why: String, granted: Boolean) {
    ListItem(
        leadingContent = { Icon(icon, null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(why) },
        trailingContent = {
            if (granted) Icon(Icons.Filled.CheckCircle, "Granted", tint = MaterialTheme.colorScheme.primary)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
