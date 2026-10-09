package com.lux032.musicbridge

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.storage.DownloadedAudioTrack
import kotlin.math.sin

/*
 * Now-playing markers (Design D).
 *
 *  - Number slot (album / playlist / offline / queue rows): the track number becomes accent
 *    [PlayingBars] and the title turns accent + SemiBold.
 *  - Small artwork (≤ 64dp thumbnails): [NowPlayingArtworkScrim] dims the art and draws white
 *    bars on top — accent would vanish into busy covers.
 *  - Large album covers (grid cards): [NowPlayingCoverBadge], a dark round chip in the
 *    bottom-start corner with white bars. White on purpose: the accent is sampled from this very
 *    cover, so an accent chip would blend into it.
 *
 * Bars bounce while playing, settle into a still silhouette when paused and only ripple while
 * buffering, so the marker never pretends audio is flowing.
 */

/**
 * Minimal now-playing snapshot for lists. Deliberately excludes playback position so readers
 * only recompose when the current track or play/pause state changes.
 */
@Immutable
internal data class NowPlayingMarker(
    val sourceId: String,
    val ratingKey: String,
    val albumRatingKey: String?,
    val isPlaying: Boolean,
    val isBuffering: Boolean,
) {
    fun matches(track: PlexTrackStream): Boolean = matches(track.sourceId, track.ratingKey)

    fun matches(track: DownloadedAudioTrack): Boolean = matches(track.sourceId, track.ratingKey)

    fun matches(trackSourceId: String, trackRatingKey: String): Boolean =
        trackRatingKey == ratingKey && trackSourceId == sourceId

    fun matchesAlbum(album: PlexAlbum): Boolean = matchesAlbum(album.sourceId, album.ratingKey)

    fun matchesAlbum(albumSourceId: String, albumKey: String): Boolean =
        albumRatingKey != null && albumKey.isNotBlank() &&
            albumKey == albumRatingKey && albumSourceId == sourceId
}

internal fun MiniPlayerState?.toNowPlayingMarker(): NowPlayingMarker? {
    val state = this ?: return null
    val track = state.tracks.getOrNull(state.currentIndex) ?: return null
    return NowPlayingMarker(
        sourceId = track.sourceId,
        ratingKey = track.ratingKey,
        // Only the track's own album: state.album may be a synthetic playlist/offline album
        // whose key (e.g. a 032 playlist id) can collide with a real album id.
        albumRatingKey = track.albumRatingKey?.takeIf { it.isNotBlank() },
        isPlaying = !state.isPaused,
        isBuffering = state.isBuffering,
    )
}

/** Provided once at the app root; null when nothing is playing. */
internal val LocalNowPlaying = compositionLocalOf<NowPlayingMarker?> { null }

/**
 * Three rounded bars that bounce while audio is playing and settle into a still low/high/mid
 * silhouette when paused. Animation values are only read in the draw phase, so the bars redraw
 * every frame without recomposing the surrounding row.
 */
@Composable
internal fun PlayingBars(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    color: Color = LocalArtworkPalette.current.accent,
) {
    val energy by animateFloatAsState(
        targetValue = when {
            isPlaying && !isBuffering -> 1f
            isPlaying -> 0.3f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 420),
        label = "playingBarsEnergy",
    )
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                phase.floatValue += (now - last) / 1_000_000_000f
                last = now
            }
        }
    }
    val description = if (isPlaying) Strings.nowPlaying else Strings.nowPaused
    // Caller-provided size wins: an outer size constraint takes precedence over this default.
    Canvas(
        modifier = modifier
            .size(width = 16.dp, height = 14.dp)
            .semantics { contentDescription = description },
    ) {
        val barCount = 3
        val gap = size.width * 0.18f
        val barWidth = (size.width - gap * (barCount - 1)) / barCount
        val radius = CornerRadius(barWidth / 2f, barWidth / 2f)
        val t = phase.floatValue
        val e = energy
        for (i in 0 until barCount) {
            // Resting silhouette when paused; each bar breathes at its own speed while playing.
            val rest = PlayingBarsRest[i]
            val wave = (sin(t * PlayingBarsSpeed[i] + PlayingBarsOffset[i]) + 1f) / 2f
            val fraction = (rest + (0.25f + 0.75f * wave - rest) * e).coerceIn(0.18f, 1f)
            val barHeight = size.height * fraction
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = radius,
            )
        }
    }
}

private val PlayingBarsRest = floatArrayOf(0.35f, 0.75f, 0.5f)
private val PlayingBarsSpeed = floatArrayOf(7.1f, 9.3f, 5.9f)
private val PlayingBarsOffset = floatArrayOf(0f, 1.9f, 3.7f)

/** Leading number slot of a track row: shows [text], or [PlayingBars] for the current track. */
@Composable
internal fun TrackNumberSlot(
    text: String,
    nowPlaying: NowPlayingMarker?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    contentAlignment: Alignment = Alignment.Center,
) {
    Box(modifier = modifier, contentAlignment = contentAlignment) {
        if (nowPlaying != null) {
            PlayingBars(isPlaying = nowPlaying.isPlaying, isBuffering = nowPlaying.isBuffering)
        } else {
            Text(
                text = text,
                color = AppColors.TextTertiary,
                style = style,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Title colour for a track row: accent for the current track. */
@Composable
internal fun nowPlayingTitleColor(isCurrent: Boolean, default: Color = AppColors.TextPrimary): Color =
    if (isCurrent) LocalArtworkPalette.current.accent else default

/** Overlay for small (≤ 64dp) artwork: dims the art and centres white bars. Place inside the
 *  artwork's clipped Box. */
@Composable
internal fun BoxScope.NowPlayingArtworkScrim(nowPlaying: NowPlayingMarker?) {
    if (nowPlaying == null) return
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        PlayingBars(
            isPlaying = nowPlaying.isPlaying,
            isBuffering = nowPlaying.isBuffering,
            color = Color.White,
        )
    }
}

/** Corner chip for large album covers. Place inside the cover's Box; aligns bottom-start. */
@Composable
internal fun BoxScope.NowPlayingCoverBadge(nowPlaying: NowPlayingMarker?) {
    if (nowPlaying == null) return
    Box(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(8.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        PlayingBars(
            isPlaying = nowPlaying.isPlaying,
            isBuffering = nowPlaying.isBuffering,
            color = Color.White,
            modifier = Modifier.size(width = 13.dp, height = 11.dp),
        )
    }
}
