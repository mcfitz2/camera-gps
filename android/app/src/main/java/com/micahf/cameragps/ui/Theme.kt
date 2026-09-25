package com.micahf.cameragps.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle

/** Wallpaper colours (every supported phone has them), light or dark with the system. */
@Composable
fun CameraGpsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** Fixed-width digits, so counters and frame numbers don't jitter or misalign. */
fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

private val typography = Typography().run {
    copy(
        displayLarge = displayLarge.tabular(),
        displayMedium = displayMedium.tabular(),
        displaySmall = displaySmall.tabular(),
    )
}
