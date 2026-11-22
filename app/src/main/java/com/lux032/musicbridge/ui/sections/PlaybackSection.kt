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
    onAlbumClick: (MusicAlbum) -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSelectPlaybackMode: (PlaybackMode) -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefreshRooms: () -> Unit,
    onStartSleepTimer: (Int, Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    lyricsRepository: TrackLyricsRepository,
    lyricsTranslationSettings: LyricsTranslationSettings,
    onLyricsTranslationSettingsChange: (LyricsTranslationSettings) -> Unit,
    loadTrackWorks: suspend (MusicTrack) -> List<com.lux032.musicbridge.music.Music032TrackWork> = { emptyList() },
    onOpenWork: (String, Long) -> Unit = { _, _ -> },
    loadWorkInfo: suspend (String, Long) -> com.lux032.musicbridge.music.Music032Work? = { _, _ -> null },
    loadTrackCredits: suspend (MusicTrack) -> List<com.lux032.musicbridge.music.TrackCredit> = { emptyList() },
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
    val displayAlbumYear = displayAlbum.year
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
    // 032 servers resolve lyrics (and translate them); older servers fall back to the in-app lookup.
    val lyricsState = rememberTrackLyricsState(currentTrack, lyricsRepository, lyricsTranslationSettings)
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
    // Tap the cover to swap it for the spectrum and back. Only local playback has PCM to
    // analyse; on Sonos the cover stays and a tap explains why instead of faking bars.
    val isLocalTarget = currentState.target is PlaybackTarget.LocalDevice
    val showVisualizer = PlayerArtworkMode.showVisualizer && isLocalTarget
    val visualizerProgress by animateFloatAsState(
        targetValue = if (showVisualizer) 1f else 0f,
        animationSpec = tween(durationMillis = 260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "visualizer_crossfade",
    )
    // Elevation shadows assume opaque content: under a fading cover Android shows the shadow's
    // edge band with an undrawn centre, which read as an empty frame before the bars. So the
    // shadow only exists while the cover is fully opaque — dropped on the first frame of the
    // switch to the visualizer, faded back in only after the cover has fully returned.
    val coverIsSettled = !showVisualizer && visualizerProgress == 0f
    val artworkShadowFactor by animateFloatAsState(
        targetValue = if (coverIsSettled) 1f else 0f,
        animationSpec = if (coverIsSettled) tween(durationMillis = 180) else snap(),
        label = "artwork_shadow",
    )
    val spectrumState = remember { SpectrumState() }
    var localOnlyHintNonce by remember { mutableIntStateOf(0) }
    var isLocalOnlyHintVisible by remember { mutableStateOf(false) }
    LaunchedEffect(localOnlyHintNonce) {
        if (localOnlyHintNonce == 0) return@LaunchedEffect
        isLocalOnlyHintVisible = true
        delay(2_000)
        isLocalOnlyHintVisible = false
    }
    val artworkContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { maximumSize ->
        // The slot stays the full square it was before non-square covers. The cover is
        // contained and centered inside it, and the shared element stays on that fitted
        // box so the mini-player morph still scales one aspect. The spectrum is a sibling
        // of the slot, so a tall or wide cover does not shrink the bars.
        Box(
            modifier = Modifier
                // Before any clip: the light spills past the square, at the slot's size.
                .spectrumGlow(spectrumState, { visualizerProgress }, palette.accent)
                .widthIn(max = maximumSize)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clickable(
                    // The whole reserved square stays the hit target, including the
                    // letterbox around a non-square cover. The crossfade itself is
                    // the feedback; a ripple over the cover is noise.
                    interactionSource = null,
                    indication = null,
                    role = Role.Button,
                    onClickLabel = if (showVisualizer) Strings.showArtwork else Strings.showVisualizer,
                ) {
                    if (isLocalTarget) {
                        PlayerArtworkMode.showVisualizer = !PlayerArtworkMode.showVisualizer
                    } else {
                        localOnlyHintNonce++
                    }
                },
        ) {
            AsyncAlbumArtwork(
                imageUrl = displayAlbum.thumbUrl,
                title = displayAlbum.title,
                modifier = Modifier.fillMaxSize(),
                thumbnailSize = maximumSize,
                // The morph's first frame comes from the mini player's 52dp cache entry.
                placeholderThumbnailSize = 52.dp,
                fit = ArtworkFit.Contain,
                alignment = Alignment.Center,
                shape = artworkShape,
                imageModifier = Modifier
                    .playerSharedElement(PlayerSharedKeys.artwork(currentTrack))
                    .graphicsLayer {
                        // Shadow follows the cover. Dropped while the bars are up so a
                        // fading card does not leave an empty frame.
                        shadowElevation = 16.dp.toPx() * artworkShadowFactor
                        shape = artworkShape
                        clip = true
                    },
                artworkModifier = Modifier.graphicsLayer {
                    alpha = 1f - visualizerProgress
                    val scale = 1f - 0.03f * visualizerProgress
                    scaleX = scale
                    scaleY = scale
                },
            )
            if (visualizerProgress > 0f) {
                SpectrumVisualizer(
                    state = spectrumState,
                    isPlaying = !currentState.isPaused && !currentState.isBuffering,
                    accent = palette.accent,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = visualizerProgress
                            shape = artworkShape
                            clip = true
                        },
                )
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = isLocalOnlyHintVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(220)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            ) {
                Text(
                    text = Strings.visualizerLocalOnly,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
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
                    // "Artist · Album · Year": artist in the accent opens the artist page, the
                    // album and year open the album page when it is a real album. The year is
                    // never truncated; artist/album share the rest (see ArtistAlbumYearLine).
                    val hasRealAlbum = !currentTrack.albumTitle.isNullOrBlank() ||
                        (displayAlbum.title.isNotBlank() && !displayAlbum.title.equals(currentTrack.title, ignoreCase = true))
                    val albumClickModifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .then(if (canOpenAlbum) Modifier.clickable { onAlbumClick(displayAlbum) } else Modifier)
                    ArtistAlbumYearLine(
                        modifier = Modifier.fillMaxWidth(),
                        artist = {
                            Text(
                                text = displayAlbum.artistName ?: Strings.unknownArtist,
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.accent,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onArtistClick(currentTrack.artistName ?: displayAlbum.artistName, currentTrack.artists) },
                            )
                        },
                        album = if (hasRealAlbum) {
                            {
                                Text(
                                    text = " · ${displayAlbum.title}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = albumClickModifier,
                                )
                            }
                        } else {
                            null
                        },
                        year = displayAlbumYear?.takeIf { hasRealAlbum }?.let { year ->
                            {
                                Text(
                                    text = " · $year",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextSecondary,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = albumClickModifier,
                                )
                            }
                        },
                    )
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
                    // Previous also restarts the first/only track on every output.
                    enabled = currentState.tracks.getOrNull(currentState.currentIndex) != null,
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
                LyricsPanel(
                    track = currentTrack,
                    state = lyricsState,
                    settings = lyricsTranslationSettings,
                    onSettingsChange = onLyricsTranslationSettingsChange,
                    repository = lyricsRepository,
                    positionMillis = currentState.currentPositionMillis,
                    listState = lyricsListState,
                    palette = palette,
                    loadCredits = loadTrackCredits,
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
        PlaybackMoreSheet(
            track = currentTrack,
            artworkUrl = displayAlbum.thumbUrl,
            artistAlbum = listOfNotNull(currentTrack.artistName ?: displayAlbum.artistName, currentTrack.albumTitle ?: displayAlbum.title)
                .joinToString(" · "),
            technicalDetails = currentTrack.trackDetailText(currentState.target, localPlaybackPolicy, cachedOriginalTrackKeys),
            palette = palette,
            loadWorks = loadTrackWorks,
            loadWorkInfo = loadWorkInfo,
            loadCredits = loadTrackCredits,
            onOpenWork = onOpenWork,
            onOpenArtist = { ref -> onArtistClick(ref.name, listOf(ref)) },
            onAddToPlaylist = addTrackToPlaylistAction(currentTrack),
            onDismiss = { isMoreSheetVisible = false },
        )
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
            val nowPlayingMarker = LocalNowPlaying.current
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
                        // Queue position (not track identity) decides "current": the same track
                        // can sit in the queue more than once.
                        TrackNumberSlot(
                            text = "${index + 1}",
                            nowPlaying = if (isCurrent) nowPlayingMarker else null,
                            modifier = Modifier.width(24.dp),
                        )
                        AsyncAlbumArtwork(
                            imageUrl = track.thumbUrl,
                            title = track.title,
                            modifier = Modifier.size(40.dp),
                            thumbnailSize = 48.dp,
                            fit = ArtworkFit.Contain,
                            alignment = Alignment.Center,
                            shape = RoundedCornerShape(4.dp),
                        )
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

internal fun originalAudioQualityBadges(codec: String?, bitDepth: Int?, sampleRate: Int?): List<String> = buildList {
    codec?.takeIf { it.isNotBlank() }?.let { add(it.uppercase(Locale.ROOT)) }
    bitDepth?.takeIf { it > 0 }?.let { add("${it}bit") }
    sampleRate?.takeIf { it > 0 }?.let { rate ->
        add(if (rate % 1000 == 0) "${rate / 1000}kHz" else String.format(Locale.ROOT, "%.1fkHz", rate / 1000f))
    }
}

private fun MusicTrack.outputQualityBadges(
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

/** 本机转码实际使用的 Opus 码率：032 按服务端白名单映射，其他来源直接用设置值。 */
private fun MusicTrack.localTranscodeOpusKbps(policy: LocalPlaybackPolicy): Int =
    if (Music032LocalTranscode.isMusic032Track(this)) {
        Music032LocalTranscode.opusBitrate(policy.opusBitrate.kbps, Music032LocalTranscode.capabilitiesFor(this))
    } else {
        policy.opusBitrate.kbps
    }

private fun MusicTrack.trackDetailText(
    target: PlaybackTarget,
    policy: LocalPlaybackPolicy,
    cachedOriginalTrackKeys: Set<String>,
): String = outputQualityBadges(target, policy, cachedOriginalTrackKeys)
    .joinToString(" · ")
    .ifBlank { sourceAudioCodec?.uppercase(Locale.ROOT) ?: "—" }

internal fun sourceAudioDetailText(track: MusicTrack): String = buildList {
    addAll(originalAudioQualityBadges(track.sourceAudioCodec, track.bitDepth, track.sourceAudioSampleRate))
    track.sourceAudioBitrateKbps?.takeIf { it > 0 }?.let { add("$it kbps") }
}.joinToString(" · ").ifBlank { "—" }

@Composable
internal fun MoreSheetAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
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

internal fun resolvePlaybackDurationSeconds(
    sonosDurationSeconds: Int?,
    expectedDurationSeconds: Int?,
): Int? = sonosDurationSeconds
    ?.takeIf { it > 0 }
    ?: expectedDurationSeconds?.takeIf { it > 0 }

internal const val PLAYBACK_LOG_TAG = "MBPlayback"

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

internal fun MusicTrack.toDisplayAlbum(fallbackAlbum: MusicAlbum): MusicAlbum =
    MusicAlbum(
        ratingKey = albumRatingKey ?: fallbackAlbum.ratingKey,
        title = albumTitle ?: fallbackAlbum.title,
        artistName = artistName ?: fallbackAlbum.artistName,
        year = albumYearFrom(fallbackAlbum),
        thumbUrl = thumbUrl ?: fallbackAlbum.thumbUrl,
        userRating = fallbackAlbum.userRating,
        addedAtEpochSeconds = fallbackAlbum.addedAtEpochSeconds,
        lastViewedAtEpochSeconds = fallbackAlbum.lastViewedAtEpochSeconds,
        section = fallbackAlbum.section,
        sourceId = sourceId.ifBlank { fallbackAlbum.sourceId },
    )
