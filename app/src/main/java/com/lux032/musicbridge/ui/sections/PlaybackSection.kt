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
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.plex.playbackUrlIdentity
import com.lux032.musicbridge.plex.PlexLyricLine
import com.lux032.musicbridge.plex.PlexLyrics
import com.lux032.musicbridge.plex.fetchPlexLyrics
import com.lux032.musicbridge.plex.resolvePlexLyricUrl
import com.lux032.musicbridge.plex.willTranscodeLocally
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PlaybackDetailSection(
    state: MiniPlayerState?,
    rooms: List<SonosRoom>,
    isRoomScanning: Boolean,
    playbackMode: PlaybackMode,
    sleepTimerState: SleepTimerState,
    localPlaybackPolicy: LocalPlaybackPolicy,
    cachedOriginalTrackKeys: Set<String>,
    isLoading: Boolean,
    isFavoriteLoading: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTogglePause: (MiniPlayerState) -> Unit,
    onSeek: (MiniPlayerState, Long) -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSelectPlaybackMode: (PlaybackMode) -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefreshRooms: () -> Unit,
    onStartSleepTimer: (Int, Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    sonosVolume: Float = 0f,
    hasLoadedSonosVolume: Boolean = false,
    onVolumeChange: ((Float) -> Unit)? = null,
    // The playback screen lays out edge-to-edge; these insets keep foreground content inside
    // the safe area while the backdrop paints behind the transparent system bars.
    topSystemBarInset: androidx.compose.ui.unit.Dp = 0.dp,
    bottomSystemBarInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val currentState = state
    val currentTrack = currentState?.tracks?.getOrNull(currentState.currentIndex)
    if (currentState == null || currentTrack == null) {
        EmptyStateCard(
            title = Strings.noPlayback,
            body = Strings.noPlaybackDesc,
            actionLabel = Strings.backToHome,
            onAction = onBack,
        )
        return
    }
    val displayAlbum = currentTrack.toDisplayAlbum(currentState.album)
    // Year defense: only trust the fallback album's year when the track actually belongs to
    // that album (or has no album key at all), avoiding mismatched years in mixed queues.
    val displayAlbumYear = displayAlbum.year?.takeIf {
        currentTrack.albumRatingKey == null || currentTrack.albumRatingKey == displayAlbum.ratingKey
    }
    // Click defense: playlist placeholders without a real album key degrade to plain text.
    val canOpenAlbum = currentTrack.albumRatingKey != null ||
        displayAlbum.ratingKey.isNotBlank()
    // Immersive theming: the whole page (backdrop, accents, glow) follows the current cover.
    val palette = rememberAnimatedArtworkPalette(displayAlbum.thumbUrl)

    val trackDurationMillis = currentState.durationMillis?.takeIf { it > 0L } ?: currentTrack.durationMillis
    var isSeeking by remember(currentTrack.sourceId, currentTrack.ratingKey) { mutableStateOf(false) }
    var sliderPositionMillis by remember(currentTrack.sourceId, currentTrack.ratingKey) {
        mutableStateOf(currentState.currentPositionMillis.toFloat())
    }
    var isPlaylistSheetVisible by remember { mutableStateOf(false) }
    var isMoreSheetVisible by remember { mutableStateOf(false) }
    var selectedPlayerPage by rememberSaveable { mutableIntStateOf(0) }
    var isModeDialogVisible by remember { mutableStateOf(false) }
    var isRoomDialogVisible by remember { mutableStateOf(false) }
    var isSleepTimerDialogVisible by remember { mutableStateOf(false) }
    var sleepTimerHours by remember { mutableIntStateOf(0) }
    var sleepTimerMinutes by remember { mutableIntStateOf(30) }
    var sleepTimerInputError by remember { mutableStateOf<String?>(null) }
    var optimisticFavorite by remember(currentTrack.sourceId, currentTrack.ratingKey) { mutableStateOf(currentTrack.isFavorite) }
    var lyrics by remember(currentTrack.sourceId, currentTrack.ratingKey) { mutableStateOf(PlexLyrics(emptyList(), isSynced = false)) }
    var isLyricsLoading by remember(currentTrack.sourceId, currentTrack.ratingKey) { mutableStateOf(false) }

    LaunchedEffect(currentTrack.sourceId, currentTrack.ratingKey, currentTrack.lyricUrl) {
        isLyricsLoading = true
        lyrics = runCatching {
            resolvePlexLyricUrl(currentTrack)?.let { fetchPlexLyrics(it) }
                ?: PlexLyrics(emptyList(), isSynced = false)
        }.onFailure { Log.d("PlexLyrics", "Lyrics unavailable for ${currentTrack.ratingKey}", it) }
            .getOrDefault(PlexLyrics(emptyList(), isSynced = false))
        isLyricsLoading = false
    }
    LaunchedEffect(currentTrack.isFavorite, isFavoriteLoading) {
        if (!isFavoriteLoading) optimisticFavorite = currentTrack.isFavorite
    }
    LaunchedEffect(currentState.currentPositionMillis, currentTrack.sourceId, currentTrack.ratingKey, isSeeking) {
        if (!isSeeking) {
            sliderPositionMillis = currentState.currentPositionMillis.toFloat()
        }
    }

    val layoutInfo = LocalAppLayoutInfo.current
    val isSonosTarget = currentState.target is PlaybackTarget.Sonos
    val showsVolume = isSonosTarget && onVolumeChange != null
    // Design D top bar: dismiss chevron · output room key · more. The room lives here once;
    // the volume row below belongs to it.
    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BareIcon(
                icon = Icons.Filled.KeyboardArrowDown,
                contentDescription = Strings.back,
                onClick = onBack,
                tint = AppColors.TextPrimary,
                iconSize = 28.dp,
                modifier = Modifier.offset(x = (-12).dp),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                RoomKey(
                    targetName = currentState.target.displayName,
                    isSonos = isSonosTarget,
                    onClick = { isRoomDialogVisible = true },
                )
            }
            BareIcon(
                icon = Icons.Filled.MoreVert,
                contentDescription = Strings.more,
                onClick = { isMoreSheetVisible = true },
                modifier = Modifier.offset(x = 12.dp),
            )
        }
    }
    val artworkShape = RoundedCornerShape(14.dp)
    val artworkContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { maximumSize ->
        AsyncAlbumArtwork(
            imageUrl = displayAlbum.thumbUrl,
            title = displayAlbum.title,
            modifier = Modifier
                .playerSharedElement(PlayerSharedKeys.artwork(currentTrack))
                .widthIn(max = maximumSize)
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(16.dp, artworkShape, clip = false)
                .clip(artworkShape),
            contentScale = ContentScale.Crop,
            thumbnailSize = maximumSize,
            // The morph's first frame comes from the mini player's 52dp cache entry.
            placeholderThumbnailSize = 52.dp,
        )
    }

    // ── Group 1: track meta (title + favourite key, artist · album, signal path) ──
    val playerMeta: @Composable (Boolean) -> Unit = { compact ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredPlayerEnter(0),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
                ) {
                    Text(
                        currentTrack.title,
                        modifier = Modifier.basicMarquee(),
                        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                    )
                    // "Artist · Album (year)": artist in the accent opens the artist page, the
                    // album part opens the album page when it is a real album.
                    val hasRealAlbum = !currentTrack.albumTitle.isNullOrBlank() ||
                        (displayAlbum.title.isNotBlank() && !displayAlbum.title.equals(currentTrack.title, ignoreCase = true))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = displayAlbum.artistName ?: Strings.unknownArtist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = palette.accent,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { onArtistClick(currentTrack.artistName ?: displayAlbum.artistName, currentTrack.artists) },
                        )
                        if (hasRealAlbum) {
                            val albumLabel = buildString {
                                append(" · ")
                                append(displayAlbum.title)
                                displayAlbumYear?.let { append(" (").append(it).append(")") }
                            }
                            Text(
                                text = albumLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .clip(RoundedCornerShape(6.dp))
                                    .then(
                                        if (canOpenAlbum) Modifier.clickable { onAlbumClick(displayAlbum) } else Modifier,
                                    ),
                            )
                        }
                    }
                }
                Key(
                    onClick = {
                        optimisticFavorite = !optimisticFavorite
                        onToggleTrackFavorite(currentTrack)
                    },
                    modifier = Modifier.size(48.dp),
                    style = if (optimisticFavorite) KeyStyle.Hi else KeyStyle.Tonal,
                    enabled = !isFavoriteLoading,
                    contentDescription = if (optimisticFavorite) Strings.favorited else Strings.notFavorited,
                ) {
                    FavoriteIconGraphic(
                        isFavorite = optimisticFavorite,
                        tint = if (optimisticFavorite) keyHiInk() else AppColors.TextPrimary,
                        baseSize = 22.dp,
                        selectedSize = 22.dp,
                    )
                }
            }
            // Signal path: where the audio comes from and which room it goes to.
            val qualityBadges = remember(currentTrack, currentState.target, localPlaybackPolicy, cachedOriginalTrackKeys) {
                currentTrack.outputQualityBadges(currentState.target, localPlaybackPolicy, cachedOriginalTrackKeys)
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(palette.accent),
                )
                if (qualityBadges.isNotEmpty()) {
                    Text(
                        text = qualityBadges.joinToString(" · "),
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                    Icon(
                        Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = AppColors.TextTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = currentState.target.displayName,
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    // ── Group 2: seek + transport ──
    val playerTransport: @Composable (Boolean) -> Unit = { compact ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredPlayerEnter(1),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            trackDurationMillis?.let { durationMillis ->
                val clampedDuration = durationMillis.coerceAtLeast(1L)
                val clampedSliderValue = sliderPositionMillis.coerceIn(0f, clampedDuration.toFloat())
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Slider(
                        value = clampedSliderValue,
                        onValueChange = {
                            isSeeking = true
                            sliderPositionMillis = it
                        },
                        onValueChangeFinished = {
                            isSeeking = false
                            onSeek(currentState, sliderPositionMillis.roundToLong())
                        },
                        valueRange = 0f..clampedDuration.toFloat(),
                        modifier = Modifier.height(28.dp),
                        thumb = {
                            // Thumb only appears while scrubbing; at rest the bar is the control.
                            val thumbSize by androidx.compose.animation.core.animateDpAsState(
                                if (isSeeking) 16.dp else 0.dp,
                                label = "seek_thumb",
                            )
                            Box(
                                modifier = Modifier
                                    .size(thumbSize)
                                    .background(Color.White, CircleShape),
                            )
                        },
                        track = { sliderState ->
                            val range = sliderState.valueRange.endInclusive - sliderState.valueRange.start
                            val fraction = if (range == 0f) 0f else (sliderState.value - sliderState.valueRange.start) / range
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.Black.copy(alpha = 0.28f)),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(fraction)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(palette.accent),
                                )
                            }
                        },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = formatDuration(clampedSliderValue.roundToLong()),
                            color = AppColors.TextTertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = formatDuration(clampedDuration),
                            color = AppColors.TextTertiary,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
            // Mode toggles flank the transport: shuffle on the left, repeat (all → one → off)
            // on the right. Both invert to the hi fill while on.
            val isShuffle = playbackMode == PlaybackMode.Shuffle
            val isRepeat = playbackMode == PlaybackMode.RepeatAll || playbackMode == PlaybackMode.RepeatOne
            val skipSize = if (compact) 52.dp else 60.dp
            val playSize = if (compact) 68.dp else 84.dp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleKey(
                    checked = isShuffle,
                    icon = Icons.Filled.Shuffle,
                    contentDescription = PlaybackMode.Shuffle.label,
                    onClick = {
                        onSelectPlaybackMode(if (isShuffle) PlaybackMode.Sequential else PlaybackMode.Shuffle)
                    },
                    bareWhenOff = true,
                )
                Key(
                    onClick = onPrevious,
                    // Local playback's previous also means "restart this track", so it stays
                    // tappable even on the first/only track.
                    enabled = currentState.target == PlaybackTarget.LocalDevice || currentState.currentIndex > 0,
                    modifier = Modifier.size(skipSize),
                    shape = RoundedCornerShape(skipSize / 3),
                    contentDescription = Strings.previous,
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Previous,
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(if (compact) 26.dp else 30.dp),
                    )
                }
                Key(
                    // Always tappable, like the mini player's button — gating play/pause on an
                    // in-flight network command made the button silently dead for seconds.
                    onClick = { onTogglePause(currentState) },
                    style = KeyStyle.Hi,
                    modifier = Modifier.size(playSize),
                    shape = RoundedCornerShape(playSize / 3),
                    contentDescription = if (currentState.isPaused) Strings.play else Strings.pause,
                ) {
                    if (currentState.isBuffering) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(if (compact) 28.dp else 34.dp),
                            color = LocalContentColor.current,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        PlayerIconGraphic(
                            icon = if (currentState.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                            tint = LocalContentColor.current,
                            modifier = Modifier.size(if (compact) 34.dp else 42.dp),
                        )
                    }
                }
                Key(
                    onClick = onNext,
                    enabled = currentState.currentIndex < currentState.tracks.lastIndex ||
                        (currentState.tracks.isNotEmpty() && playbackMode in setOf(PlaybackMode.RepeatAll, PlaybackMode.Shuffle)),
                    modifier = Modifier.size(skipSize),
                    shape = RoundedCornerShape(skipSize / 3),
                    contentDescription = Strings.next,
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Next,
                        tint = LocalContentColor.current,
                        modifier = Modifier.size(if (compact) 26.dp else 30.dp),
                    )
                }
                ToggleKey(
                    checked = isRepeat,
                    icon = Icons.Filled.Repeat,
                    checkedIcon = if (playbackMode == PlaybackMode.RepeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    contentDescription = playbackMode.label,
                    onClick = {
                        onSelectPlaybackMode(
                            when (playbackMode) {
                                PlaybackMode.RepeatAll -> PlaybackMode.RepeatOne
                                PlaybackMode.RepeatOne -> PlaybackMode.Sequential
                                else -> PlaybackMode.RepeatAll
                            }
                        )
                    },
                    bareWhenOff = true,
                )
            }
        }
    }

    // ── Group 3: output (room volume) + tools ──
    val playerVolume: @Composable () -> Unit = {
        if (showsVolume) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredPlayerEnter(2),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.VolumeUp,
                    contentDescription = Strings.volume,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(20.dp),
                )
                Slider(
                    value = sonosVolume.coerceIn(0f, 100f),
                    onValueChange = { onVolumeChange?.invoke(it) },
                    valueRange = 0f..100f,
                    enabled = hasLoadedSonosVolume,
                    modifier = Modifier
                        .weight(1f)
                        .height(28.dp),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(if (hasLoadedSonosVolume) Color.White else AppColors.TextTertiary, CircleShape),
                        )
                    },
                    track = { sliderState ->
                        val fraction = sliderState.value / 100f
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.Black.copy(alpha = 0.28f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .background(Color.White.copy(alpha = 0.92f)),
                            )
                        }
                    },
                )
                Text(
                    text = if (hasLoadedSonosVolume) "${sonosVolume.toInt()}" else "--",
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(28.dp),
                )
            }
        }
    }
    val playerTools: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredPlayerEnter(3),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            PlayerToolButton(
                icon = Icons.Filled.QueueMusic,
                label = Strings.queueShort,
                onClick = { isPlaylistSheetVisible = true },
            )
            PlayerToolButton(
                icon = Icons.Filled.Subject,
                label = Strings.lyrics,
                selected = selectedPlayerPage == 1,
                onClick = { selectedPlayerPage = if (selectedPlayerPage == 1) 0 else 1 },
            )
            PlayerToolButton(
                icon = Icons.Filled.Bedtime,
                label = if (sleepTimerState.isActive) {
                    formatSleepTimerCountdown(sleepTimerState.remainingMillis)
                } else {
                    Strings.timerShort
                },
                selected = sleepTimerState.isActive,
                onClick = {
                    val totalMinutes = ((sleepTimerState.remainingMillis + 59_999L) / 60_000L).toInt()
                    sleepTimerHours = if (sleepTimerState.isActive) totalMinutes / 60 else 0
                    sleepTimerMinutes = if (sleepTimerState.isActive) totalMinutes % 60 else 30
                    sleepTimerInputError = null
                    isSleepTimerDialogVisible = true
                },
            )
        }
    }
    // Landscape / fixed-rhythm variant of the details column.
    val playerDetails: @Composable ColumnScope.(Boolean, Boolean) -> Unit = { compact, _ ->
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
        ) {
            playerMeta(compact)
            playerTransport(compact)
            playerVolume()
            playerTools()
        }
    }

    // Swipe-to-dismiss, two channels:
    // - nested scroll: drags that start inside a scrollable (lyrics list, landscape details)
    //   take over once that content is at its top;
    // - draggable: drags on non-scrollable areas (portrait artwork, meta, gaps). This is
    //   the stock slop-based detector — it never consumes the down event and only claims
    //   the pointer after vertical touch slop, so taps on transport buttons are untouched
    //   (the earlier hand-rolled pointerInput with a Final-pass watcher was what competed
    //   with child clicks). Movement already consumed by sliders/lists cancels it.
    val dismissScope = rememberCoroutineScope()
    // Plain state written synchronously from the scroll callback: launching a coroutine per
    // drag event to snapTo() an Animatable could land a frame late, so the sheet lagged
    // and jittered behind the finger. The spring-back runs as a cancellable job.
    var dismissOffset by remember { mutableFloatStateOf(0f) }
    var dismissSpringJob by remember { mutableStateOf<Job?>(null) }
    var dismissHeightPx by remember { mutableFloatStateOf(1f) }
    val landscapeDetailsScroll = rememberScrollState()
    val lyricsListState = rememberLazyListState()

    val dismissFlingThresholdPx = with(LocalDensity.current) { 1000.dp.toPx() }

    fun settleDismiss(velocityY: Float = 0f) {
        if (dismissOffset <= 0f) return
        val progress = dismissOffset / dismissHeightPx
            // Distance OR a quick downward flick dismisses (mainstream players honour flicks).
            if (progress > 0.22f || (velocityY > dismissFlingThresholdPx && progress > 0.04f)) {
                // Hand off immediately: the section exit continues the drop from the
                // current offset with a fast-start curve, so there is no stop-then-go
                // between the finger lifting and the sheet leaving.
                onBack()
            } else {
                dismissSpringJob = dismissScope.launch {
                    animate(
                        initialValue = dismissOffset,
                        targetValue = 0f,
                        initialVelocity = velocityY,
                        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
                    ) { value, _ -> dismissOffset = value.coerceAtLeast(0f) }
                }
            }
    }

    val dismissConnection = remember(selectedPlayerPage) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                val dy = available.y
                if (dy > 0f) {
                    val lyricsAtTop = lyricsListState.firstVisibleItemIndex == 0 && lyricsListState.firstVisibleItemScrollOffset == 0
                    val detailsAtTop = landscapeDetailsScroll.value == 0
                    val activeContentAtTop = if (selectedPlayerPage == 1) lyricsAtTop else detailsAtTop
                    if (activeContentAtTop || dismissOffset > 0f) {
                        dismissSpringJob?.cancel()
                        dismissOffset += dy
                        return Offset(0f, dy)
                    }
                } else if (dy < 0f && dismissOffset > 0f) {
                    dismissSpringJob?.cancel()
                    dismissOffset = (dismissOffset + dy).coerceAtLeast(0f)
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissOffset <= 0f) return Velocity.Zero
                settleDismiss(available.y)
                return available
            }
        }
    }

    val dismissDragState = rememberDraggableState { delta ->
        if (delta > 0f || dismissOffset > 0f) {
            dismissSpringJob?.cancel()
            dismissOffset = (dismissOffset + delta).coerceAtLeast(0f)
        }
    }

    // Order matters: the drag detector sits before graphicsLayer so it measures in the
    // un-translated parent space — otherwise the sheet moving under the finger would feed
    // back into the drag delta.
    val dismissModifier = Modifier
        .nestedScroll(dismissConnection)
        .draggable(
            state = dismissDragState,
            orientation = Orientation.Vertical,
            onDragStopped = { velocity -> settleDismiss(velocity) },
        )
        .graphicsLayer {
            // Drag reads as a physical sheet: it follows the finger 1:1 and rounds its top
            // corners. Pure translation only — no alpha (washed-out, ghosting) and no scale
            // (resampling makes text shimmer while moving).
            val p = (dismissOffset / dismissHeightPx).coerceIn(0f, 1f)
            translationY = dismissOffset
            val cornerPx = 28.dp.toPx() * (p * 8f).coerceAtMost(1f)
            shape = RoundedCornerShape(topStart = cornerPx, topEnd = cornerPx)
            clip = cornerPx > 0f
            shadowElevation = 12.dp.toPx() * (p * 8f).coerceAtMost(1f)
        }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { dismissHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .then(dismissModifier),
    ) {
        val topBarHeight = 56.dp
        val availableWidth = maxWidth
        val bodyHeight = (maxHeight - topBarHeight).coerceAtLeast(0.dp)
        val portraitViewportHeight = (
            maxHeight - topBarHeight - topSystemBarInset - bottomSystemBarInset - 20.dp
        ).coerceAtLeast(0.dp)
        val compactLandscape = layoutInfo.isLandscape && maxHeight < 500.dp
        val compactPortrait = !layoutInfo.isLandscape && maxHeight < 700.dp
        val playerHorizontalPadding = if (layoutInfo.isTablet) 32.dp else 24.dp

        // Full-bleed background: the same plain palette gradient the home page uses, so the
        // player and every other page share one consistent colouring. The section is laid out
        // edge-to-edge, so this naturally paints behind the system bars.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .playerSharedBounds(PlayerSharedKeys.Container)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(palette.backgroundTop, palette.backgroundBottom),
                    )
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = playerHorizontalPadding,
                    top = 4.dp + topSystemBarInset,
                    end = playerHorizontalPadding,
                    bottom = 16.dp + bottomSystemBarInset,
                ),
        ) {
            topBar()

            if (selectedPlayerPage == 1) {
                LyricsView(
                    lines = lyrics.lines,
                    isSynced = lyrics.isSynced,
                    positionMillis = currentState.currentPositionMillis,
                    listState = lyricsListState,
                    isLoading = isLyricsLoading,
                    palette = palette,
                    onSeekLine = { line -> onSeek(currentState, line.timeMillis) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                Spacer(Modifier.height(12.dp))
                playerTools()
            } else if (layoutInfo.isLandscape) {
                val verticalPadding = if (compactLandscape) 4.dp else 16.dp
                val columnSpacing = if (compactLandscape) 16.dp else 40.dp
                val artworkSize = minOf(
                    bodyHeight - (verticalPadding * 2),
                    availableWidth * if (compactLandscape) 0.34f else 0.38f,
                    if (layoutInfo.isTablet) 420.dp else 360.dp,
                ).coerceAtLeast(160.dp)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = verticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(columnSpacing),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(0.9f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(modifier = Modifier.width(artworkSize)) {
                            artworkContent(artworkSize)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Column(
                            modifier = Modifier
                                .widthIn(max = 560.dp)
                                .fillMaxWidth()
                                .heightIn(max = bodyHeight - (verticalPadding * 2))
                                .verticalScroll(landscapeDetailsScroll)
                                .padding(vertical = if (compactLandscape) 2.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            playerDetails(compactLandscape, false)
                        }
                    }
                }
            } else {
                // Height reserved for everything that is not the cover; the cover takes the
                // rest (up to the content width) so tall phones get a big cover rather than
                // empty bands.
                val nonArtworkHeight = (if (showsVolume) 392.dp else 352.dp) - (if (compactPortrait) 64.dp else 0.dp)
                val artworkSize = minOf(
                    availableWidth - playerHorizontalPadding * 2,
                    portraitViewportHeight - nonArtworkHeight,
                    440.dp,
                ).coerceAtLeast(140.dp)

                // Groups: cover → meta → seek/transport → volume + tools. Leftover height is
                // shared evenly by the weighted spacers between groups (never pooled in one
                // place), while the tools row stays attached to the volume row.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(1f).heightIn(min = 4.dp))
                    Box(modifier = Modifier.width(artworkSize)) { artworkContent(artworkSize) }
                    Spacer(Modifier.weight(1f).heightIn(min = 14.dp))
                    playerMeta(compactPortrait)
                    Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
                    playerTransport(compactPortrait)
                    Spacer(Modifier.weight(1f).heightIn(min = 12.dp))
                    playerVolume()
                    if (showsVolume) Spacer(Modifier.height(18.dp))
                    playerTools()
                }
            }
        }
    }

    if (isMoreSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isMoreSheetVisible = false },
            containerColor = palette.backgroundBottom,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Strings.more, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
                MoreSheetAction(Icons.Filled.Bedtime, Strings.sleepTimer) {
                    isMoreSheetVisible = false
                    val totalMinutes = ((sleepTimerState.remainingMillis + 59_999L) / 60_000L).toInt()
                    sleepTimerHours = if (sleepTimerState.isActive) totalMinutes / 60 else 0
                    sleepTimerMinutes = if (sleepTimerState.isActive) totalMinutes % 60 else 30
                    sleepTimerInputError = null
                    isSleepTimerDialogVisible = true
                }
                MoreSheetAction(Icons.Filled.Album, Strings.viewAlbum) {
                    isMoreSheetVisible = false
                    onAlbumClick(displayAlbum)
                }
                MoreSheetAction(Icons.Filled.Person, Strings.viewArtist) {
                    isMoreSheetVisible = false
                    onArtistClick(currentTrack.artistName ?: displayAlbum.artistName, currentTrack.artists)
                }
                Text(Strings.trackDetails, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                Text(currentTrack.trackDetailText(currentState.target, localPlaybackPolicy, cachedOriginalTrackKeys), color = AppColors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (isPlaylistSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isPlaylistSheetVisible = false },
            containerColor = LocalArtworkPalette.current.backgroundBottom,
        ) {
            // Lazy so a long queue does not compose hundreds of rows at once; start at the
            // currently playing track instead of the top.
            val queueListState = rememberLazyListState(
                initialFirstVisibleItemIndex = currentState.currentIndex.coerceIn(0, currentState.tracks.lastIndex.coerceAtLeast(0)),
            )
            LazyColumn(
                state = queueListState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "queue-title", contentType = "queue-title") {
                    Text(
                        text = Strings.playlist,
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                        modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
                    )
                }
                itemsIndexed(
                    items = currentState.tracks,
                    key = { index, track -> "$index-${track.ratingKey}" },
                    contentType = { _, _ -> "queue-track" },
                ) { index, track ->
                    val isCurrent = index == currentState.currentIndex
                    ChoiceRow(
                        selected = isCurrent,
                        showCheck = false,
                        onClick = {
                            isPlaylistSheetVisible = false
                            onSelectTrack(index)
                        },
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            .fillMaxWidth(),
                    ) {
                        Box(modifier = Modifier.width(24.dp), contentAlignment = Alignment.Center) {
                            if (isCurrent) {
                                LiveDot()
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    color = AppColors.TextTertiary,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = track.title,
                                color = if (isCurrent) LocalArtworkPalette.current.accent else AppColors.TextPrimary,
                                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.albumTitle ?: currentState.album.title,
                                color = AppColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        track.durationMillis?.let {
                            Text(
                                text = formatDuration(it),
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (isModeDialogVisible) {
        AlertDialog(
            onDismissRequest = { isModeDialogVisible = false },
            confirmButton = {},
            title = {
                Text(
                    text = Strings.playbackMode,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PlaybackMode.entries.forEach { mode ->
                        ChoiceRow(
                            selected = playbackMode == mode,
                            onClick = {
                                isModeDialogVisible = false
                                onSelectPlaybackMode(mode)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(mode.label, color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
        )
    }

    if (isRoomDialogVisible) {
        OutputTargetDialog(
            currentTarget = currentState.target,
            rooms = rooms,
            isRoomScanning = isRoomScanning,
            onDismiss = { isRoomDialogVisible = false },
            onSelectTarget = onSelectTarget,
            onRefreshRooms = onRefreshRooms,
        )
    }

    if (isSleepTimerDialogVisible) {
        AlertDialog(
            onDismissRequest = { isSleepTimerDialogVisible = false },
            title = {
                Text(
                    text = Strings.sleepTimerDialogTitle,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = Strings.sleepTimerDesc,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        WheelPickerField(
                            label = Strings.sleepTimerHours,
                            value = sleepTimerHours,
                            range = 0..23,
                            onValueChange = {
                                sleepTimerHours = it
                                sleepTimerInputError = null
                            },
                        )
                        WheelPickerField(
                            label = Strings.sleepTimerMinutes,
                            value = sleepTimerMinutes,
                            range = 0..59,
                            formatter = { "%02d".format(it) },
                            onValueChange = {
                                sleepTimerMinutes = it
                                sleepTimerInputError = null
                            },
                        )
                    }
                    sleepTimerInputError?.let { message ->
                        Text(
                            text = message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (sleepTimerHours <= 0 && sleepTimerMinutes <= 0) {
                            sleepTimerInputError = Strings.sleepTimerInvalidDuration
                        } else {
                            onStartSleepTimer(sleepTimerHours, sleepTimerMinutes)
                            isSleepTimerDialogVisible = false
                        }
                    },
                ) {
                    Text(Strings.sleepTimerConfirm)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (sleepTimerState.isActive) {
                        TextButton(
                            onClick = {
                                onCancelSleepTimer()
                                isSleepTimerDialogVisible = false
                            },
                        ) {
                            Text(Strings.cancelSleepTimer)
                        }
                    }
                    TextButton(onClick = { isSleepTimerDialogVisible = false }) {
                        Text(Strings.back)
                    }
                }
            },
        )
    }
}

/**
 * Shared output-device picker: this device plus every discovered Sonos room. Used by the
 * player page and the home output card so both entries behave identically.
 */
@Composable
internal fun OutputTargetDialog(
    currentTarget: PlaybackTarget,
    rooms: List<SonosRoom>,
    isRoomScanning: Boolean,
    onDismiss: () -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefreshRooms: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Strings.selectOutputDevice,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(
                    onClick = onRefreshRooms,
                    enabled = !isRoomScanning,
                ) {
                    Text(
                        text = if (isRoomScanning) Strings.searchingDevices else Strings.refreshDevices,
                        color = if (isRoomScanning) AppColors.TextTertiary else LocalArtworkPalette.current.accent,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                OutputTargetOption(
                    title = Strings.localPlayback,
                    subtitle = null,
                    isSonos = false,
                    isCurrent = currentTarget == PlaybackTarget.LocalDevice,
                    onClick = {
                        onDismiss()
                        onSelectTarget(PlaybackTarget.LocalDevice)
                    },
                )
                if (rooms.isEmpty()) {
                    Text(
                        text = if (isRoomScanning) Strings.searchingDevices else Strings.noAvailableRooms,
                        color = AppColors.TextSecondary,
                    )
                } else {
                    rooms.forEach { room ->
                        OutputTargetOption(
                            title = room.roomName,
                            subtitle = if (room.memberCount > 1) Strings.members(room.memberCount) else Strings.singleRoom,
                            isCurrent = (currentTarget as? PlaybackTarget.Sonos)?.room?.coordinatorUuid == room.coordinatorUuid,
                            onClick = {
                                onDismiss()
                                onSelectTarget(PlaybackTarget.Sonos(room))
                            },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun OutputTargetOption(
    title: String,
    subtitle: String?,
    isCurrent: Boolean,
    onClick: () -> Unit,
    isSonos: Boolean = true,
) {
    ChoiceRow(
        selected = isCurrent,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = if (isSonos) Icons.Filled.Speaker else Icons.Filled.PhoneAndroid,
            contentDescription = null,
            tint = if (isCurrent) LocalArtworkPalette.current.accent else AppColors.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
            subtitle?.let {
                Text(
                    text = it,
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LyricsView(
    lines: List<PlexLyricLine>,
    isSynced: Boolean,
    positionMillis: Long,
    listState: LazyListState,
    isLoading: Boolean,
    palette: ArtworkPalette,
    onSeekLine: (PlexLyricLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollowSuspended by remember { mutableStateOf(false) }
    var suspensionGeneration by remember { mutableIntStateOf(0) }
    val activeIndex = if (isSynced) lines.indexOfLast { it.timeMillis <= positionMillis }.coerceAtLeast(0) else -1

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            autoFollowSuspended = true
            suspensionGeneration++
        } else if (autoFollowSuspended) {
            val generation = ++suspensionGeneration
            snapshotFlow { listState.isScrollInProgress }
                .filter { inProgress -> !inProgress }
                .first()
            delay(3_000)
            if (generation == suspensionGeneration) autoFollowSuspended = false
        }
    }
    LaunchedEffect(activeIndex, isSynced, autoFollowSuspended) {
        if (isSynced && activeIndex >= 0 && !autoFollowSuspended) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -120)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 160.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (lines.isEmpty()) {
            item(key = "empty-lyrics") {
                Box(modifier = Modifier.fillParentMaxHeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Crossfade(targetState = isLoading, label = "lyrics_loading") { loading ->
                        if (loading) {
                            CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        } else {
                            Text(Strings.noLyrics, color = AppColors.TextSecondary, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        itemsIndexed(lines) { index, line ->
            val isActive = isSynced && index == activeIndex
            Text(
                text = line.text,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = isSynced) {
                        autoFollowSuspended = false
                        suspensionGeneration++
                        onSeekLine(line)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                color = if (isActive) palette.accent else AppColors.TextSecondary.copy(alpha = 0.72f),
                style = if (isActive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

internal fun originalAudioQualityBadges(codec: String?, bitDepth: Int?, sampleRate: Int?): List<String> = buildList {
    codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
    bitDepth?.takeIf { it > 0 }?.let { add("${it}bit") }
    sampleRate?.takeIf { it > 0 }?.let { rate ->
        add(if (rate % 1000 == 0) "${rate / 1000}kHz" else String.format(Locale.ROOT, "%.1fkHz", rate / 1000f))
    }
}

private fun PlexTrackStream.outputQualityBadges(
    target: PlaybackTarget,
    policy: LocalPlaybackPolicy,
    cachedOriginalTrackKeys: Set<String>,
): List<String> {
    if (sourceId.isBlank()) return emptyList()
    val cachedOriginal = originalCacheKey(sourceId, ratingKey, partKey) in cachedOriginalTrackKeys
    val localTranscode = target == PlaybackTarget.LocalDevice && !cachedOriginal && willTranscodeLocally(policy)
    val sonosTranscode = target is PlaybackTarget.Sonos && !playbackMimeType.isNullOrBlank()
    return when {
        localTranscode -> listOf("TRANSCODE", "OPUS", "${localTranscodeOpusKbps(policy)}k")
        sonosTranscode && playbackMimeType.equals("audio/mpeg", true) -> listOf("TRANSCODE", "MP3", "320k")
        sonosTranscode && playbackMimeType.equals("audio/flac", true) -> listOf("TRANSCODE", "FLAC")
        else -> originalAudioQualityBadges(sourceAudioCodec, bitDepth, sourceAudioSampleRate)
    }
}

/** 本机转码实际使用的 Opus 码率：032 按服务端白名单映射，Plex 直接用设置值。 */
private fun PlexTrackStream.localTranscodeOpusKbps(policy: LocalPlaybackPolicy): Int =
    if (Music032LocalTranscode.isMusic032Track(this)) {
        Music032LocalTranscode.opusBitrate(policy.opusBitrate.kbps, Music032LocalTranscode.capabilitiesFor(this))
    } else {
        policy.opusBitrate.kbps
    }

private fun PlexTrackStream.trackDetailText(
    target: PlaybackTarget,
    policy: LocalPlaybackPolicy,
    cachedOriginalTrackKeys: Set<String>,
): String = outputQualityBadges(target, policy, cachedOriginalTrackKeys)
    .joinToString(" · ")
    .ifBlank { sourceAudioCodec?.uppercase(Locale.ROOT) ?: "—" }

@Composable
private fun MoreSheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = AppColors.TextSecondary)
        Text(label, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
    }
}

/** Player tools row item: icon over a small label (queue / lyrics / timer). */
@Composable
private fun PlayerToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val tint by animateColorAsState(
        targetValue = if (selected) LocalArtworkPalette.current.accent else AppColors.TextSecondary,
        label = "player_tool_tint",
    )
    Column(
        modifier = Modifier
            .widthIn(min = 72.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumDetailSection(
    trackResult: PlexAlbumTracksResult?,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    trackListState: LazyListState = rememberLazyListState(),
    onReturnHome: () -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleAlbumFavorite: (PlexAlbum) -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onQueueAlbum: (PlexAlbum, QueuePlacement) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (PlexTrackStream) -> Unit = {},
    onDeleteDownloadedTrack: (PlexTrackStream) -> Unit = {},
    onDownloadAlbum: (PlexAlbum, List<PlexTrackStream>) -> Unit = { _, _ -> },
    onDeleteDownloadedAlbum: (PlexAlbum) -> Unit = {},
    onPlayAlbum: (PlexAlbumTracksResult) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream) -> Unit,
) {
    val currentTrackResult = trackResult
    if (currentTrackResult == null) {
        EmptyStateCard(
            title = Strings.noAlbumOpened,
            body = Strings.noAlbumOpenedDesc,
            actionLabel = Strings.backToHome,
            onAction = onReturnHome,
        )
        return
    }

    val layoutInfo = LocalAppLayoutInfo.current
    var showAlbumQueueMenu by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = layoutInfo.horizontalPadding,
                    top = 12.dp,
                    end = layoutInfo.horizontalPadding,
                    bottom = 4.dp,
                ),
        ) {
            BackCircleButton(onClick = onReturnHome, contentDescription = Strings.backToHome)
        }
    if (layoutInfo.isWide) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layoutInfo.horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier
                    .weight(0.82f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AlbumDetailHeroWide(
                    trackResult = currentTrackResult,
                    outputTargetName = outputTargetName,
                    isPlaybackLoading = isPlaybackLoading,
                    isFavoriteLoading = isFavoriteLoading,
                    showAlbumQueueMenu = showAlbumQueueMenu,
                    onShowAlbumQueueMenuChange = { showAlbumQueueMenu = it },
                    onArtistClick = onArtistClick,
                    onToggleAlbumFavorite = onToggleAlbumFavorite,
                    onQueueAlbum = onQueueAlbum,
                    onPlayAlbum = onPlayAlbum,
                    downloadedTrackKeys = downloadedTrackKeys,
                    downloadProgress = downloadProgress,
                    onDownloadAlbum = onDownloadAlbum,
                    onDeleteDownloadedAlbum = onDeleteDownloadedAlbum,
                )
            }
            LazyColumn(
                state = trackListState,
                modifier = Modifier
                    .weight(1.18f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
            ) {
                itemsIndexed(
                    items = currentTrackResult.tracks,
                    key = { index, track -> track.ratingKey ?: index },
                    contentType = { _, _ -> "album-track" },
                ) { index, track ->
                    Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        AlbumTrackRow(
                            index = index,
                            album = currentTrackResult.album,
                            track = track,
                            isPlaybackLoading = isPlaybackLoading,
                            isFavoriteLoading = isFavoriteLoading,
                            downloadedTrackKeys = downloadedTrackKeys,
                            downloadProgress = downloadProgress,
                            onDownloadTrack = onDownloadTrack,
                            onDeleteDownloadedTrack = onDeleteDownloadedTrack,
                            onToggleTrackFavorite = onToggleTrackFavorite,
                            onQueueTrack = onQueueTrack,
                            onPlayTrack = onPlayTrack,
                        )
                    }
                }

            }
        }
    } else {
        LazyColumn(
            state = trackListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = layoutInfo.horizontalPadding,
                end = layoutInfo.horizontalPadding,
                bottom = bottomContentPadding,
            ),
        ) {
            item(key = "album-summary", contentType = "album-summary") {
                AlbumDetailHeroCompact(
                    trackResult = currentTrackResult,
                    outputTargetName = outputTargetName,
                    isPlaybackLoading = isPlaybackLoading,
                    isFavoriteLoading = isFavoriteLoading,
                    showAlbumQueueMenu = showAlbumQueueMenu,
                    onShowAlbumQueueMenuChange = { showAlbumQueueMenu = it },
                    onArtistClick = onArtistClick,
                    onToggleAlbumFavorite = onToggleAlbumFavorite,
                    onQueueAlbum = onQueueAlbum,
                    onPlayAlbum = onPlayAlbum,
                    downloadedTrackKeys = downloadedTrackKeys,
                    downloadProgress = downloadProgress,
                    onDownloadAlbum = onDownloadAlbum,
                    onDeleteDownloadedAlbum = onDeleteDownloadedAlbum,
                )
            }
            itemsIndexed(
                items = currentTrackResult.tracks,
                key = { index, track -> track.ratingKey ?: index },
                contentType = { _, _ -> "album-track" },
            ) { index, track ->
                Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                    AlbumTrackRow(
                        index = index,
                        album = currentTrackResult.album,
                        track = track,
                        isPlaybackLoading = isPlaybackLoading,
                        isFavoriteLoading = isFavoriteLoading,
                        downloadedTrackKeys = downloadedTrackKeys,
                        downloadProgress = downloadProgress,
                        onDownloadTrack = onDownloadTrack,
                        onDeleteDownloadedTrack = onDeleteDownloadedTrack,
                        onToggleTrackFavorite = onToggleTrackFavorite,
                        onQueueTrack = onQueueTrack,
                        onPlayTrack = onPlayTrack,
                    )
                }
            }

        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumDetailHeroWide(
    trackResult: PlexAlbumTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    showAlbumQueueMenu: Boolean,
    onShowAlbumQueueMenuChange: (Boolean) -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleAlbumFavorite: (PlexAlbum) -> Unit,
    onQueueAlbum: (PlexAlbum, QueuePlacement) -> Unit,
    onPlayAlbum: (PlexAlbumTracksResult) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadAlbum: (PlexAlbum, List<PlexTrackStream>) -> Unit = { _, _ -> },
    onDeleteDownloadedAlbum: (PlexAlbum) -> Unit = {},
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val palette = LocalArtworkPalette.current
    val album = trackResult.album
    val totalTracks = trackResult.tracks.size
    val downloadedCount = trackResult.tracks.count { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val isAnyDownloading = trackResult.tracks.any { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadProgress
    }
    val isAllDownloaded = totalTracks > 0 && downloadedCount == totalTracks
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncAlbumArtwork(
            imageUrl = album.thumbUrl,
            title = album.title,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .shadow(14.dp, RoundedCornerShape(14.dp), clip = false)
                .clip(RoundedCornerShape(14.dp))
                .semantics { contentDescription = "${album.title}. ${Strings.longPressQueueHint}" }
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onShowAlbumQueueMenuChange(true) },
                ),
            contentScale = ContentScale.Crop,
            thumbnailSize = 300.dp,
            placeholderThumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
        )
        QueueActionMenu(
            expanded = showAlbumQueueMenu,
            onDismiss = { onShowAlbumQueueMenuChange(false) },
            onAction = { placement ->
                onShowAlbumQueueMenuChange(false)
                onQueueAlbum(album, placement)
            },
            onPlay = {
                onShowAlbumQueueMenuChange(false)
                onPlayAlbum(trackResult)
            },
            isDownloaded = isAllDownloaded,
            downloadLabel = if (isAllDownloaded) Strings.deleteAlbumDownload else Strings.downloadAlbumOriginal,
            onDownloadAction = {
                if (isAllDownloaded) {
                    showDeleteAlbumDialog = true
                } else {
                    onDownloadAlbum(album, trackResult.tracks)
                }
            },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val artistName = album.artistName ?: Strings.unknownArtist
        Text(
            text = album.title,
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.TextPrimary,
        )
        Text(
            text = artistName,
            color = palette.accent,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onArtistClick(album.artistName, album.artists) },
        )
        Text(
            text = buildString {
                album.year?.let {
                    append(it)
                    append(" · ")
                }
                append(Strings.tracks(trackResult.tracks.size))
            },
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        AlbumActionRow(
            trackResult = trackResult,
            outputTargetName = outputTargetName,
            isPlaybackLoading = isPlaybackLoading,
            isFavoriteLoading = isFavoriteLoading,
            isAllDownloaded = isAllDownloaded,
            isAnyDownloading = isAnyDownloading,
            onPlayAlbum = onPlayAlbum,
            onToggleAlbumFavorite = onToggleAlbumFavorite,
            onDownloadAlbum = onDownloadAlbum,
            onRequestDeleteDownload = { showDeleteAlbumDialog = true },
            onQueueAlbum = onQueueAlbum,
            onBeforeQueueMenu = { onShowAlbumQueueMenuChange(false) },
        )
    }

    if (showDeleteAlbumDialog) {
        DeleteAlbumDownloadDialog(
            onConfirm = {
                showDeleteAlbumDialog = false
                onDeleteDownloadedAlbum(album)
            },
            onDismiss = { showDeleteAlbumDialog = false },
        )
    }
}

/**
 * Album action row (design D): one light primary key naming the room, then tonal keys for
 * favourite / download / add-to-queue.
 */
@Composable
private fun AlbumActionRow(
    trackResult: PlexAlbumTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    isAllDownloaded: Boolean,
    isAnyDownloading: Boolean,
    onPlayAlbum: (PlexAlbumTracksResult) -> Unit,
    onToggleAlbumFavorite: (PlexAlbum) -> Unit,
    onDownloadAlbum: (PlexAlbum, List<PlexTrackStream>) -> Unit,
    onRequestDeleteDownload: () -> Unit,
    onQueueAlbum: (PlexAlbum, QueuePlacement) -> Unit,
    onBeforeQueueMenu: () -> Unit = {},
) {
    val palette = LocalArtworkPalette.current
    val album = trackResult.album
    var showActionQueueMenu by remember(album.sourceId, album.ratingKey) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitPlayKey(
            label = Strings.playOn(outputTargetName),
            onPlay = { onPlayAlbum(trackResult) },
            onShuffle = null,
            enabled = !isPlaybackLoading && trackResult.tracks.isNotEmpty(),
            isLoading = isPlaybackLoading,
            modifier = Modifier.weight(1f),
        )
        FavoriteIconButton(
            isFavorite = album.isFavorite,
            isLoading = isFavoriteLoading,
            onClick = { onToggleAlbumFavorite(album) },
        )
        when {
            isAllDownloaded -> Key(
                onClick = onRequestDeleteDownload,
                modifier = Modifier.size(48.dp),
                contentDescription = Strings.deleteAlbumDownload,
            ) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            isAnyDownloading -> Key(
                onClick = {},
                enabled = false,
                modifier = Modifier.size(48.dp),
                contentDescription = Strings.downloadAlbumOriginal,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
            }
            else -> IconKey(
                icon = Icons.Filled.Download,
                contentDescription = Strings.downloadAlbumOriginal,
                onClick = { onDownloadAlbum(album, trackResult.tracks) },
            )
        }
        Box {
            IconKey(
                icon = Icons.Filled.PlaylistAdd,
                contentDescription = Strings.addToPlaybackQueue,
                onClick = {
                    onBeforeQueueMenu()
                    showActionQueueMenu = true
                },
            )
            QueueActionMenu(
                expanded = showActionQueueMenu,
                onDismiss = { showActionQueueMenu = false },
                onAction = { placement ->
                    showActionQueueMenu = false
                    onQueueAlbum(album, placement)
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumDetailHeroCompact(
    trackResult: PlexAlbumTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    showAlbumQueueMenu: Boolean,
    onShowAlbumQueueMenuChange: (Boolean) -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleAlbumFavorite: (PlexAlbum) -> Unit,
    onQueueAlbum: (PlexAlbum, QueuePlacement) -> Unit,
    onPlayAlbum: (PlexAlbumTracksResult) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadAlbum: (PlexAlbum, List<PlexTrackStream>) -> Unit = { _, _ -> },
    onDeleteDownloadedAlbum: (PlexAlbum) -> Unit = {},
) {
    val palette = LocalArtworkPalette.current
    val album = trackResult.album
    val codec = trackResult.tracks.firstOrNull()
        ?.sourceAudioCodec
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
    val totalTracks = trackResult.tracks.size
    val downloadedCount = trackResult.tracks.count { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val isAnyDownloading = trackResult.tracks.any { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadProgress
    }
    val isAllDownloaded = totalTracks > 0 && downloadedCount == totalTracks
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }
    val totalDurationMillis = trackResult.tracks.sumOf { it.durationMillis ?: 0L }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(0.72f)
                .widthIn(max = 320.dp),
        ) {
            val coverShape = RoundedCornerShape(14.dp)
            AsyncAlbumArtwork(
                imageUrl = album.thumbUrl,
                title = album.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(14.dp, coverShape, clip = false)
                    .clip(coverShape)
                    .semantics { contentDescription = "${album.title}. ${Strings.longPressQueueHint}" }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onShowAlbumQueueMenuChange(true) },
                    ),
                contentScale = ContentScale.Crop,
                thumbnailSize = 320.dp,
                placeholderThumbnailSize = null,
            )
            QueueActionMenu(
                expanded = showAlbumQueueMenu,
                onDismiss = { onShowAlbumQueueMenuChange(false) },
                onAction = { placement ->
                    onShowAlbumQueueMenuChange(false)
                    onQueueAlbum(album, placement)
                },
                onPlay = {
                    onShowAlbumQueueMenuChange(false)
                    onPlayAlbum(trackResult)
                },
                isDownloaded = isAllDownloaded,
                downloadLabel = if (isAllDownloaded) Strings.deleteAlbumDownload else Strings.downloadAlbumOriginal,
                onDownloadAction = {
                    if (isAllDownloaded) {
                        showDeleteAlbumDialog = true
                    } else {
                        onDownloadAlbum(album, trackResult.tracks)
                    }
                },
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.artistName ?: Strings.unknownArtist,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onArtistClick(album.artistName, album.artists) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                album.year?.let { append("$it · ") }
                append(Strings.tracks(totalTracks))
                if (totalDurationMillis > 0L) append(" · ").append(formatDuration(totalDurationMillis))
                codec?.let { append(" · $it") }
            },
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(18.dp))
        AlbumActionRow(
            trackResult = trackResult,
            outputTargetName = outputTargetName,
            isPlaybackLoading = isPlaybackLoading,
            isFavoriteLoading = isFavoriteLoading,
            isAllDownloaded = isAllDownloaded,
            isAnyDownloading = isAnyDownloading,
            onPlayAlbum = onPlayAlbum,
            onToggleAlbumFavorite = onToggleAlbumFavorite,
            onDownloadAlbum = onDownloadAlbum,
            onRequestDeleteDownload = { showDeleteAlbumDialog = true },
            onQueueAlbum = onQueueAlbum,
            onBeforeQueueMenu = { onShowAlbumQueueMenuChange(false) },
        )
        Spacer(Modifier.height(8.dp))
    }

    if (showDeleteAlbumDialog) {
        DeleteAlbumDownloadDialog(
            onConfirm = {
                showDeleteAlbumDialog = false
                onDeleteDownloadedAlbum(album)
            },
            onDismiss = { showDeleteAlbumDialog = false },
        )
    }
}

@Composable
private fun DeleteAlbumDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.deleteAlbumDownload) },
        text = { Text(Strings.confirmDeleteAlbumDownload) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(Strings.confirmDelete, color = AppColors.ErrorText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel, color = AppColors.TextSecondary)
            }
        },
        containerColor = AppColors.CardSurface,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    index: Int,
    album: PlexAlbum,
    track: PlexTrackStream,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (PlexTrackStream) -> Unit = {},
    onDeleteDownloadedTrack: (PlexTrackStream) -> Unit = {},
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream) -> Unit,
) {
    var showQueueMenu by remember { mutableStateOf(false) }
    val isDownloaded = remember(track.sourceId, track.ratingKey, downloadedTrackKeys) {
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val progress = downloadProgress["${track.sourceId}:${track.ratingKey}"]
    val palette = LocalArtworkPalette.current

    // Editorial hairline row: number · title/duration · favourite · more. Tap plays, long-press
    // or ⋯ opens the queue menu.
    Box {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        enabled = !isPlaybackLoading,
                        onClick = { onPlayTrack(album, track) },
                        onLongClick = { showQueueMenu = true },
                    )
                    .padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(24.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = track.title,
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isDownloaded) {
                            Icon(
                                imageVector = Icons.Filled.DownloadDone,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(14.dp),
                            )
                        } else if (progress != null) {
                            CircularProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = palette.accent,
                            )
                        }
                        track.durationMillis?.let { duration ->
                            Text(
                                formatDuration(duration),
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
                Key(
                    onClick = { onToggleTrackFavorite(track) },
                    style = KeyStyle.Bare,
                    enabled = !isFavoriteLoading,
                    modifier = Modifier.size(44.dp),
                    contentDescription = if (track.isFavorite) Strings.favorited else Strings.notFavorited,
                ) {
                    FavoriteIconGraphic(
                        isFavorite = track.isFavorite,
                        tint = if (track.isFavorite) palette.accent else AppColors.TextTertiary,
                        baseSize = 20.dp,
                        selectedSize = 20.dp,
                    )
                }
                BareIcon(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = Strings.more,
                    onClick = { showQueueMenu = true },
                    tint = AppColors.TextTertiary,
                    size = 44.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.offset(x = 6.dp),
                )
            }
            Hairline(modifier = Modifier.fillMaxWidth().padding(start = 40.dp))
        }
        QueueActionMenu(
            expanded = showQueueMenu,
            onDismiss = { showQueueMenu = false },
            onAction = { placement ->
                showQueueMenu = false
                onQueueTrack(track, placement)
            },
            isDownloaded = isDownloaded,
            onDownloadAction = {
                if (isDownloaded) onDeleteDownloadedTrack(track) else onDownloadTrack(track)
            },
        )
    }
}

@Composable
private fun WheelPickerField(
    label: String,
    value: Int,
    range: IntRange,
    modifier: Modifier = Modifier,
    formatter: (Int) -> String = { it.toString() },
    onValueChange: (Int) -> Unit,
) {
    val values = remember(range) { range.toList() }
    val itemHeight = 44.dp
    val visibleItemCount = 5
    val contentPadding = itemHeight * ((visibleItemCount - 1) / 2)
    val density = LocalDensity.current
    val itemHeightPx = remember(density) { with(density) { itemHeight.roundToPx() } }
    val initialIndex = (value.coerceIn(range.first, range.last) - range.first)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(value, range) {
        val targetIndex = value.coerceIn(range.first, range.last) - range.first
        val currentCenteredIndex = centeredWheelIndex(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            itemHeightPx = itemHeightPx,
            maxIndex = values.lastIndex,
        )
        if (currentCenteredIndex != targetIndex) {
            listState.scrollToItem(targetIndex)
        }
    }

    LaunchedEffect(listState, itemHeightPx, values) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { isScrolling -> !isScrolling }
            .map {
                centeredWheelIndex(
                    firstVisibleItemIndex = listState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                    itemHeightPx = itemHeightPx,
                    maxIndex = values.lastIndex,
                )
            }
            .distinctUntilChanged()
            .collect { centeredIndex ->
                values.getOrNull(centeredIndex)?.let(onValueChange)
            }
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Box(
            modifier = Modifier
                .width(88.dp)
                .height(itemHeight * visibleItemCount)
                .clip(RoundedCornerShape(18.dp))
                .background(AppColors.CardSurface),
            contentAlignment = Alignment.Center,
        ) {
            LazyColumn(
                state = listState,
                flingBehavior = flingBehavior,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                contentPadding = PaddingValues(vertical = contentPadding),
            ) {
                items(values) { item ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = formatter(item),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .padding(horizontal = 8.dp)
                    .background(KeyColors.Tonal, RoundedCornerShape(12.dp))
            )
        }
    }
}

private fun centeredWheelIndex(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    itemHeightPx: Int,
    maxIndex: Int,
): Int {
    if (itemHeightPx <= 0) return firstVisibleItemIndex.coerceIn(0, maxIndex)
    val shouldAdvance = firstVisibleItemScrollOffset >= itemHeightPx / 2
    val rawIndex = firstVisibleItemIndex + if (shouldAdvance) 1 else 0
    return rawIndex.coerceIn(0, maxIndex)
}

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
            Box(
                modifier = Modifier
                    .miniSharedElement(PlayerSharedKeys.artwork(currentTrack), visible = !isPlayerOpen)
                    .clip(RoundedCornerShape(8.dp)),
            ) {
                AsyncAlbumArtwork(
                    imageUrl = displayAlbum.thumbUrl,
                    title = displayAlbum.title,
                    modifier = Modifier.size(44.dp),
                    thumbnailSize = 52.dp,
                )
            }
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
    trackResult: PlexAlbumTracksResult,
    startIndex: Int = 0,
    initialPositionMillis: Long = 0L,
    prepareTrackUrl: suspend (PlexTrackStream) -> String = { it.streamUrl },
    getTracks: () -> List<PlexTrackStream> = { trackResult.tracks },
    getNextIndex: (Int) -> Int?,
    onTrackChanged: (Int, PlexTrackStream, Long) -> Unit,
    preparePlayback: (suspend (PlexTrackStream) -> SonosPlayback)? = null,
    onTrackPushed: (PlexTrackStream, SonosPlayback) -> Unit = { _, _ -> },
) {
    val initialTracks = getTracks()
    require(initialTracks.isNotEmpty()) { "专辑里没有可播放的单曲。" }

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
        // Plex universal transcodes are live responses and do not support reliable Sonos
        // REL_TIME seeking. Their source offset is already encoded in playbackUrl.
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
        // Sonos commonly reports 00:00:00 while a Plex transcode is starting. A zero
        // duration is unknown, not a completed zero-length track; prefer Plex metadata
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

internal fun resolvePlaybackDurationSeconds(
    sonosDurationSeconds: Int?,
    expectedDurationSeconds: Int?,
): Int? = sonosDurationSeconds
    ?.takeIf { it > 0 }
    ?: expectedDurationSeconds?.takeIf { it > 0 }

private const val PLAYBACK_LOG_TAG = "MBPlayback"

private fun formatSleepTimerCountdown(remainingMillis: Long): String {
    val totalSeconds = (remainingMillis / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

private fun PlexTrackStream.toDisplayAlbum(fallbackAlbum: PlexAlbum): PlexAlbum =
    PlexAlbum(
        ratingKey = albumRatingKey ?: fallbackAlbum.ratingKey,
        title = albumTitle ?: fallbackAlbum.title,
        artistName = artistName ?: fallbackAlbum.artistName,
        year = fallbackAlbum.year,
        thumbUrl = thumbUrl ?: fallbackAlbum.thumbUrl,
        userRating = fallbackAlbum.userRating,
        addedAtEpochSeconds = fallbackAlbum.addedAtEpochSeconds,
        lastViewedAtEpochSeconds = fallbackAlbum.lastViewedAtEpochSeconds,
        section = fallbackAlbum.section,
        sourceId = sourceId.ifBlank { fallbackAlbum.sourceId },
    )
