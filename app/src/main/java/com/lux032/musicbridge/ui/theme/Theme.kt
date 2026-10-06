package com.lux032.musicbridge.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.lux032.musicbridge.AppColors

// Single source of truth: the Material color scheme is derived from AppColors so Material
// components (buttons, text fields, dialogs, bottom sheets, dividers) match the app's
// hand-rolled neutral-dark palette. Previously most roles fell back to Material3's default
// baseline, which has a purple tint — visible on dialog containers, text-field outlines and
// error states. Mapping every role to AppColors removes that drift while keeping the tone.
private val DarkColorScheme = darkColorScheme(
    primary = AppColors.Accent,
    onPrimary = AppColors.SurfaceStrong,
    primaryContainer = AppColors.SurfaceMuted,
    onPrimaryContainer = AppColors.TextPrimary,
    secondary = AppColors.TextSecondary,
    onSecondary = AppColors.SurfaceStrong,
    secondaryContainer = AppColors.SurfaceMuted,
    onSecondaryContainer = AppColors.TextPrimary,
    tertiary = AppColors.TextSecondary,
    onTertiary = AppColors.SurfaceStrong,
    background = AppColors.BackgroundTop,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Surface,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceMuted,
    onSurfaceVariant = AppColors.TextSecondary,
    surfaceContainerLowest = AppColors.SurfaceStrong,
    surfaceContainerLow = AppColors.Surface,
    surfaceContainer = AppColors.Surface,
    surfaceContainerHigh = AppColors.SurfaceAlt,
    surfaceContainerHighest = AppColors.SurfaceMuted,
    outline = AppColors.BorderStrong,
    outlineVariant = AppColors.Border,
    error = AppColors.ErrorText,
    onError = AppColors.SurfaceStrong,
    errorContainer = AppColors.ErrorBg,
    onErrorContainer = AppColors.ErrorText,
    scrim = Color.Black,
)

@Composable
fun PlexToSonosPlayerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        // Squircle family shared with the key buttons (16dp at 48dp height).
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content
    )
}
