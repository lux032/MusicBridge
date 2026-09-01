package com.lux032.musicbridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small, app-specific window size model. Keeping the breakpoints in one place makes every
 * screen react to rotation and tablet/free-form window resizing in the same way.
 */
@Immutable
internal data class AppLayoutInfo(
    val width: Dp,
    val height: Dp,
) {
    val isTablet: Boolean get() = width >= 600.dp
    val isLandscape: Boolean get() = width > height
    val isWide: Boolean get() = width >= 840.dp && width > height
    val useNavigationRail: Boolean get() = isWide
    val horizontalPadding: Dp get() = if (isTablet) 24.dp else 16.dp
    val contentSpacing: Dp get() = if (isTablet) 20.dp else 16.dp
    val albumGridMinimumWidth: Dp
        get() = when {
            isWide -> 140.dp
            isTablet -> 152.dp
            else -> 148.dp
        }
    /**
     * Fixed column count for full-page album grids. Adaptive-with-minimum columns collapse
     * to a single stretched cover on phone-width screens once side padding and the index
     * bar reservation eat into the row, so these pages pin the count instead: 3 on phone
     * portrait (matching the artist detail page and home preview grids).
     */
    val albumGridColumns: Int
        get() = when {
            isWide -> 6
            isTablet -> if (isLandscape) 5 else 4
            isLandscape -> 5
            else -> 3
        }
    val artistGridMinimumWidth: Dp get() = if (isWide) 168.dp else 160.dp
    val detailArtworkMaximumWidth: Dp get() = if (isWide) 440.dp else 560.dp
    val albumDetailCompactArtworkSize: Dp
        get() = when {
            isTablet -> 160.dp
            isLandscape -> 128.dp
            else -> 112.dp
        }
    val homePreviewColumns: Int
        get() = when {
            isWide -> 6
            isTablet -> 4
            else -> 3
        }
    val homePreviewItemCount: Int
        get() = homePreviewColumns * if (isTablet) 2 else 3
    val recentAlbumCardWidth: Dp
        get() = when {
            isWide -> 196.dp
            isTablet -> 180.dp
            else -> 156.dp
        }
}

internal val LocalAppLayoutInfo = staticCompositionLocalOf {
    AppLayoutInfo(width = 360.dp, height = 640.dp)
}

@Composable
internal fun rememberAppLayoutInfo(): AppLayoutInfo {
    val configuration = LocalConfiguration.current
    return AppLayoutInfo(
        width = configuration.screenWidthDp.dp,
        height = configuration.screenHeightDp.dp,
    )
}
