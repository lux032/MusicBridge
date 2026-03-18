package com.lux032.musicbridge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = SoftWhite,
    onPrimary = PureBlack,
    secondary = MutedWhite,
    onSecondary = PureBlack,
    tertiary = Graphite,
    background = SlateBlack,
    onBackground = SoftWhite,
    surface = Charcoal,
    onSurface = SoftWhite,
    surfaceVariant = Graphite,
    onSurfaceVariant = MutedWhite,
)

@Composable
fun PlexToSonosPlayerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}
