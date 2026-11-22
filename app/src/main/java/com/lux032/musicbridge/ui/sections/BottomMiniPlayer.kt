package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import kotlinx.coroutines.Job
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
import com.lux032.musicbridge.storage.containsDownloadedTrack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.lux032.musicbridge.music.AlbumDiscGroup
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.AlbumTracksResult
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.effectiveDiscCount
import com.lux032.musicbridge.music.groupByDisc
import com.lux032.musicbridge.music.isFavorite
import com.lux032.musicbridge.music.isMultiDiscAlbum
import com.lux032.musicbridge.music.playbackUrlIdentity
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.willTranscodeLocally
import com.lux032.musicbridge.music.Music032LocalTranscode
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import android.util.Log
import com.lux032.musicbridge.local.originalCacheKey
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


@Composable
internal fun BottomMiniPlayer(
    state: MiniPlayerState,
    modifier: Modifier = Modifier,
    isPlayerOpen: Boolean = false,
    volume: Int? = null,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
    onArtworkClick: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val currentTrack = state.tracks.getOrNull(state.currentIndex) ?: return
    val displayAlbum = currentTrack.toDisplayAlbum(state.album)
    val palette = rememberAnimatedArtworkPalette(displayAlbum.thumbUrl)
    val artistName = currentTrack.artistName ?: displayAlbum.artistName ?: Strings.unknownArtist
    val durationMillis = state.durationMillis ?: currentTrack.durationMillis
    val progress = if (durationMillis != null && durationMillis > 0L) {
        (state.currentPositionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }
    val isSonos = state.target is PlaybackTarget.Sonos
    val outputLine = buildString {
        append(state.target.displayName)
        if (volume != null) append(" · ").append(Strings.volume).append(' ').append(volume)
    }
    // Design D docked room bar: flush with the navigation bar below it, same deep dock colour,
    // progress as a hairline along its top edge, and the output room always legible.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .miniSharedBounds(PlayerSharedKeys.Container, visible = !isPlayerOpen)
            .background(dockColor())
            .then(
                if (isPlayerOpen) Modifier
                else Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onArtworkClick,
                )
            )
            .padding(bottom = bottomInset),
    ) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(KeyColors.Line),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(palette.accent),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .padding(start = 14.dp, end = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = displayAlbum.thumbUrl,
                title = displayAlbum.title,
                modifier = Modifier.size(44.dp),
                thumbnailSize = 52.dp,
                fit = ArtworkFit.Contain,
                alignment = Alignment.Center,
                shape = RoundedCornerShape(8.dp),
                imageModifier = Modifier.miniSharedElement(
                    PlayerSharedKeys.artwork(currentTrack),
                    visible = !isPlayerOpen,
                ),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = "${currentTrack.title} — $artistName",
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isSonos) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(palette.accent),
                        )
                    } else {
                        Icon(
                            Icons.Filled.PhoneAndroid,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Text(
                        text = outputLine,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Key(
                onClick = onTogglePause,
                modifier = Modifier.size(48.dp),
                style = KeyStyle.Bare,
                enabled = !isPlayerOpen && !state.isBuffering,
                contentColor = AppColors.TextPrimary,
                contentDescription = if (state.isPaused) Strings.play else Strings.pause,
            ) {
                if (state.isBuffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        color = AppColors.TextPrimary,
                        strokeWidth = 2.5.dp,
                    )
                } else {
                    PlayerIconGraphic(
                        icon = if (state.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
    }
}

internal suspend fun playAlbumSequentially(
    sonosController: SonosController,
    room: SonosRoom,
    trackResult: AlbumTracksResult,
    startIndex: Int = 0,
    initialPositionMillis: Long = 0L,
    prepareTrackUrl: suspend (MusicTrack) -> String = { it.streamUrl },
    getTracks: () -> List<MusicTrack> = { trackResult.tracks },
    getNextIndex: (Int) -> Int?,
    onTrackChanged: (Int, MusicTrack, Long) -> Unit,
    preparePlayback: (suspend (MusicTrack) -> SonosPlayback)? = null,
    onTrackPushed: (MusicTrack, SonosPlayback) -> Unit = { _, _ -> },
) {
    val initialTracks = getTracks()
    require(initialTracks.isNotEmpty()) { Strings.albumNoPlayableTracks }

    var index = startIndex.coerceIn(0, initialTracks.lastIndex)
    var isFirstTrack = true

    while (true) {
        val tracks = getTracks()
        if (tracks.isEmpty()) return
        index = index.coerceIn(0, tracks.lastIndex)
        val track = tracks[index]
        val startPositionMillis = if (isFirstTrack) initialPositionMillis.coerceAtLeast(0L) else 0L
        isFirstTrack = false
        val playback = if (preparePlayback != null) {
            preparePlayback(track)
        } else {
            val url = prepareTrackUrl(track)
            SonosPlayback(url, track.playbackMimeType, url.contains("/:/transcode/universal/start", ignoreCase = true))
        }
        val (playbackUrl, mimeType, positionIsEncodedInUrl) = playback
        sonosController.playTrack(
            room = room,
            trackUrl = playbackUrl,
            title = track.title,
            albumTitle = track.albumTitle ?: trackResult.album.title,
            mimeType = mimeType,
        )
        onTrackPushed(track, playback)
        // Live transcodes do not support reliable Sonos REL_TIME seeking; their source
        // offset is already encoded in playbackUrl.
        if (startPositionMillis > 0L && !positionIsEncodedInUrl) {
            sonosController.seek(room, (startPositionMillis / 1_000L).toInt())
        }
        onTrackChanged(index, track, startPositionMillis)

        delay(2_000)

        waitForTrackToFinish(
            sonosController = sonosController,
            room = room,
            expectedTrackUrl = playbackUrl,
            expectedDurationMillis = track.durationMillis,
        )

        val latestTracks = getTracks()
        val currentIndex = latestTracks.indexOfFirst {
            it.ratingKey == track.ratingKey && it.sourceId == track.sourceId
        }
            .takeIf { it >= 0 } ?: index.coerceIn(0, latestTracks.lastIndex)
        val nextIndex = getNextIndex(currentIndex)
        if (nextIndex == null) {
            return
        }

        index = nextIndex.coerceIn(0, latestTracks.lastIndex)
    }
}

internal suspend fun waitForTrackToFinish(
    sonosController: SonosController,
    room: SonosRoom,
    expectedTrackUrl: String,
    expectedDurationMillis: Long?,
) {
    val expectedDurationSeconds = expectedDurationMillis?.div(1_000)?.toInt()
    var stableProgressSeen = false
    var stoppedNoProgressCount = 0
    var stoppedAfterProgressCount = 0
    var uriMismatchCount = 0

    while (true) {
        delay(1_000)
        val status = sonosController.getPlaybackStatus(room)
        val currentUri = status.currentTrackUri.orEmpty()
        val normalizedExpected = playbackUrlIdentity(expectedTrackUrl)
        val normalizedCurrent = playbackUrlIdentity(currentUri)

        if (normalizedCurrent.isNotBlank() && normalizedCurrent != normalizedExpected) {
            uriMismatchCount += 1
            if (uriMismatchCount >= 2) {
                Log.w(
                    PLAYBACK_LOG_TAG,
                    "Sonos track URI changed state=${status.transportState} " +
                        "expected=$normalizedExpected current=$normalizedCurrent",
                )
                return
            }
        } else {
            uriMismatchCount = 0
        }

        val relTimeSeconds = status.relTimeSeconds
        // Sonos commonly reports 00:00:00 while a live transcode is starting. A zero
        // duration is unknown, not a completed zero-length track; prefer server metadata
        // until Sonos publishes a positive duration.
        val trackDurationSeconds = resolvePlaybackDurationSeconds(
            sonosDurationSeconds = status.trackDurationSeconds,
            expectedDurationSeconds = expectedDurationSeconds,
        )

        if (relTimeSeconds != null && relTimeSeconds > 0) {
            stableProgressSeen = true
            stoppedNoProgressCount = 0
            stoppedAfterProgressCount = 0
        }

        if (status.transportState.equals("STOPPED", ignoreCase = true)) {
            if (stableProgressSeen) {
                stoppedAfterProgressCount += 1
                if (stoppedAfterProgressCount >= 3) return
            } else {
                stoppedNoProgressCount += 1
                if (stoppedNoProgressCount >= 5) {
                    Log.w(
                        PLAYBACK_LOG_TAG,
                        "Sonos stream did not start expected=$normalizedExpected",
                    )
                    return
                }
            }
        } else {
            stoppedAfterProgressCount = 0
        }

        if (relTimeSeconds != null && trackDurationSeconds != null && relTimeSeconds >= trackDurationSeconds - 1) {
            return
        }
    }
}

