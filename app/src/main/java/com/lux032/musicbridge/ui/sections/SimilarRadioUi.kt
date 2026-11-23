package com.lux032.musicbridge

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack

/**
 * Root-provided bridge to start a 032 Similar Track Radio from any track menu without
 * threading callbacks through every intermediate composable.
 */
@Stable
internal class SimilarRadioLauncher(
    val canStart: (MusicTrack) -> Boolean,
    val startFromTrack: (MusicTrack, MusicAlbum?) -> Unit,
)

internal val LocalSimilarRadioLauncher = staticCompositionLocalOf<SimilarRadioLauncher?> { null }

/** Returns a "开启相似电台" callback for [track], or null when unsupported. */
@Composable
internal fun startSimilarRadioAction(
    track: MusicTrack,
    fallbackAlbum: MusicAlbum? = null,
): (() -> Unit)? {
    val launcher = LocalSimilarRadioLauncher.current ?: return null
    if (!launcher.canStart(track)) return null
    return { launcher.startFromTrack(track, fallbackAlbum) }
}
