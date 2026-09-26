package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
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
    onArtistClick: (String?) -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSelectPlaybackMode: (PlaybackMode) -> Unit,
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefreshRooms: () -> Unit,
    onStartSleepTimer: (Int, Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
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
    val topBar: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(
                onClick = onBack,
                containerColor = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = Strings.back,
                    tint = AppColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            TabRow(
                selectedTabIndex = selectedPlayerPage,
                modifier = Modifier.weight(1f),
                containerColor = Color.Transparent,
                contentColor = palette.accent,
                divider = {},
                indicator = { tabPositions ->
                    Box(
                        Modifier.tabIndicatorOffset(tabPositions[selectedPlayerPage])
                            .fillMaxWidth()
                            .wrapContentSize(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .width(28.dp)
                            .height(3.dp)
                            .background(palette.accent, CircleShape)
                    )
                },
            ) {
                listOf(Strings.nowPlaying, Strings.lyrics).forEachIndexed { index, label ->
                    Tab(
                        selected = selectedPlayerPage == index,
                        onClick = { selectedPlayerPage = index },
                        text = { Text(label, fontWeight = FontWeight.SemiBold) },
                    )
                }
            }
            IconCircleButton(
                onClick = { isMoreSheetVisible = true },
                containerColor = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.MoreHoriz, contentDescription = Strings.more, tint = AppColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
    val artworkShape = RoundedCornerShape(28.dp)
    val artworkContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { maximumSize ->
        AsyncAlbumArtwork(
            imageUrl = displayAlbum.thumbUrl,
            title = displayAlbum.title,
            modifier = Modifier
                .playerSharedElement(PlayerSharedKeys.artwork(currentTrack))
                .widthIn(max = maximumSize)
                .fillMaxWidth()
                .aspectRatio(1f)
                // Subtle black depth shadow only — the blurred backdrop already provides the
                // ambiance, and a strong colored glow reads as a dark band above the cover.
                .shadow(10.dp, artworkShape, clip = false)
                .clip(artworkShape),
            contentScale = ContentScale.Crop,
            thumbnailSize = maximumSize,
            // The morph's first frame comes from the mini player's 52dp cache entry.
            placeholderThumbnailSize = 52.dp,
        )
    }
    val playerDetails: @Composable ColumnScope.(Boolean, Boolean) -> Unit = { compact, useFullTransport ->
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 16.dp),
        ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredPlayerEnter(0),
            verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    currentTrack.title,
                    modifier = Modifier.weight(1f).basicMarquee(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(
                    enabled = !isFavoriteLoading,
                    onClick = {
                        optimisticFavorite = !optimisticFavorite
                        onToggleTrackFavorite(currentTrack)
                    },
                ) {
                    FavoriteIconGraphic(
                        isFavorite = optimisticFavorite,
                        tint = if (optimisticFavorite) palette.accent else AppColors.TextSecondary,
                        baseSize = 28.dp,
                        selectedSize = 32.dp,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onArtistClick(displayAlbum.artistName) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayAlbum.artistName ?: Strings.unknownArtist,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            // Album line: `专辑名 (年份) >`. Real-album guard: same-title EPs (e.g. Amore)
            // must still render their album row; playlist placeholders without album metadata don't.
            val hasRealAlbum = !currentTrack.albumTitle.isNullOrBlank() ||
                (displayAlbum.title.isNotBlank() && !displayAlbum.title.equals(currentTrack.title, ignoreCase = true))
            if (hasRealAlbum) {
                val albumLabel = buildString {
                    append(displayAlbum.title)
                    displayAlbumYear?.let { append(" (").append(it).append(")") }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (canOpenAlbum) Modifier.clickable { onAlbumClick(displayAlbum) } else Modifier,
                        )
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = albumLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (canOpenAlbum) {
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            val qualityBadges = remember(currentTrack, currentState.target, localPlaybackPolicy, cachedOriginalTrackKeys) {
                currentTrack.outputQualityBadges(currentState.target, localPlaybackPolicy, cachedOriginalTrackKeys)
            }
            if (qualityBadges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    qualityBadges.forEach { badge -> AudioQualityBadge(badge) }
                }
            }
        }
        trackDurationMillis?.let { durationMillis ->
            val clampedDuration = durationMillis.coerceAtLeast(1L)
            val clampedSliderValue = sliderPositionMillis.coerceIn(0f, clampedDuration.toFloat())
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .staggeredPlayerEnter(1),
                verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
            ) {
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
                    // Not gated on isLoading: transport controls must stay responsive while a
                    // playback command is in flight — the seek generation logic resolves races.
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
                        disabledThumbColor = AppColors.TextTertiary,
                        disabledActiveTrackColor = AppColors.TextTertiary,
                        disabledInactiveTrackColor = Color.White.copy(alpha = 0.14f),
                    ),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(Color.White, CircleShape),
                        )
                    },
                    track = { sliderState ->
                        val fraction = if (sliderState.valueRange.endInclusive - sliderState.valueRange.start == 0f) 0f
                            else (sliderState.value - sliderState.valueRange.start) / (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.14f)),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(palette.accent),
                            )
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = formatDuration(clampedSliderValue.roundToLong()),
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = formatDuration(clampedDuration),
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .staggeredPlayerEnter(2),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlButton(
                icon = PlayerIcon.Previous,
                onClick = onPrevious,
                // Local playback's previous also means "restart this track" (standard
                // behavior), so it must stay tappable even on the first/only track.
                enabled = currentState.target == PlaybackTarget.LocalDevice ||
                    currentState.currentIndex > 0,
                sizeOverride = if (useFullTransport) 56.dp else 46.dp,
                iconSizeOverride = if (useFullTransport) 24.dp else 20.dp,
                unhighlightedContainerColor = Color.White.copy(alpha = 0.08f),
                unhighlightedBorderColor = Color.White.copy(alpha = 0.12f),
            )
            Spacer(modifier = Modifier.width(if (useFullTransport) 28.dp else 20.dp))
            GlowPlayerControlButton(
                icon = if (currentState.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                isLoading = currentState.isBuffering,
                onClick = { onTogglePause(currentState) },
                // Always tappable, like the mini player's button — gating play/pause on an
                // in-flight network command made the button silently dead for seconds.
                enabled = true,
                accent = palette.accent,
                onAccent = palette.onAccent,
                buttonSize = if (useFullTransport) 72.dp else 54.dp,
                iconSize = if (useFullTransport) 32.dp else 24.dp,
            )
            Spacer(modifier = Modifier.width(if (useFullTransport) 28.dp else 20.dp))
            PlayerControlButton(
                icon = PlayerIcon.Next,
                onClick = onNext,
                enabled = currentState.currentIndex < currentState.tracks.lastIndex ||
                    (currentState.tracks.isNotEmpty() && playbackMode in setOf(PlaybackMode.RepeatAll, PlaybackMode.Shuffle)),
                sizeOverride = if (useFullTransport) 56.dp else 46.dp,
                iconSizeOverride = if (useFullTransport) 24.dp else 20.dp,
                unhighlightedContainerColor = Color.White.copy(alpha = 0.08f),
                unhighlightedBorderColor = Color.White.copy(alpha = 0.12f),
            )
        }
        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
        PlaybackActionBar(
            playbackMode = playbackMode,
            palette = palette,
            compact = compact,
            useFullTransport = useFullTransport,
            modifier = Modifier
                .fillMaxWidth()
                .staggeredPlayerEnter(3),
            onShowPlaylist = { isPlaylistSheetVisible = true },
            onShowModePicker = { isModeDialogVisible = true },
            onShowRoomPicker = { isRoomDialogVisible = true },
            onShowMore = { isMoreSheetVisible = true },
        )
        if (sleepTimerState.isActive) {
            Text(
                text = Strings.sleepTimerRemaining(formatSleepTimerCountdown(sleepTimerState.remainingMillis)),
                color = palette.accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        }
    }

    // Swipe-to-dismiss: downward drags from the top of the scrollable details area slide the
    // page down. Keep this entirely in nested scroll: a full-page pointerInput competes with
    // child click targets and can prevent transport buttons from receiving an otherwise
    // valid tap.
    val dismissScope = rememberCoroutineScope()
    val dismissOffset = remember { Animatable(0f) }
    var dismissHeightPx by remember { mutableFloatStateOf(1f) }
    val landscapeDetailsScroll = rememberScrollState()
    val lyricsListState = rememberLazyListState()

    fun settleDismiss() {
        if (dismissOffset.value <= 0f) return
        dismissScope.launch {
            val progress = dismissOffset.value / dismissHeightPx
            if (progress > 0.22f) {
                dismissOffset.animateTo(
                    dismissHeightPx * 0.6f,
                    tween(TransitionDurations.DismissSettleMillis),
                )
                onBack()
            } else {
                dismissOffset.animateTo(0f, spring(dampingRatio = 0.68f, stiffness = 380f))
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
                    if (activeContentAtTop || dismissOffset.value > 0f) {
                        dismissScope.launch { dismissOffset.snapTo(dismissOffset.value + dy) }
                        return Offset(0f, dy)
                    }
                } else if (dy < 0f && dismissOffset.value > 0f) {
                    dismissScope.launch {
                        dismissOffset.snapTo((dismissOffset.value + dy).coerceAtLeast(0f))
                    }
                    return Offset(0f, dy)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (dismissOffset.value <= 0f) return Velocity.Zero
                settleDismiss()
                return available
            }
        }
    }

    val dismissModifier = Modifier
        .nestedScroll(dismissConnection)
        .graphicsLayer {
            val p = (dismissOffset.value / dismissHeightPx).coerceIn(0f, 1f)
            translationY = dismissOffset.value
            val s = 1f - 0.07f * p
            scaleX = s
            scaleY = s
            alpha = 1f - 0.45f * p
        }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { dismissHeightPx = it.height.toFloat().coerceAtLeast(1f) }
            .then(dismissModifier),
    ) {
        val topBarHeight = 48.dp
        val availableWidth = maxWidth
        val bodyHeight = (maxHeight - topBarHeight).coerceAtLeast(0.dp)
        val portraitViewportHeight = (
            maxHeight - topBarHeight - topSystemBarInset - bottomSystemBarInset - 20.dp
        ).coerceAtLeast(0.dp)
        val compactLandscape = layoutInfo.isLandscape && maxHeight < 500.dp
        val compactPortrait = !layoutInfo.isLandscape && (!layoutInfo.isTablet || maxHeight < 780.dp)
        // Transport sizing is decoupled from compact: tall phones get the full-size buttons.
        val useFullTransport = !layoutInfo.isLandscape && maxHeight >= 680.dp

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
                    start = layoutInfo.horizontalPadding,
                    top = 6.dp + topSystemBarInset,
                    end = layoutInfo.horizontalPadding,
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
                val artworkSize = minOf(
                    availableWidth - 36.dp,
                    portraitViewportHeight * 0.38f,
                    340.dp,
                ).coerceAtLeast(200.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    PortraitPlayerContent(
                        artworkSize = artworkSize,
                        compact = compactPortrait,
                        useFullTransport = useFullTransport,
                        artworkContent = artworkContent,
                        playerDetails = playerDetails,
                    )
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
                    onArtistClick(displayAlbum.artistName)
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item(key = "queue-title", contentType = "queue-title") {
                    Text(
                        text = Strings.playlist,
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                itemsIndexed(
                    items = currentState.tracks,
                    key = { index, track -> "$index-${track.ratingKey}" },
                    contentType = { _, _ -> "queue-track" },
                ) { index, track ->
                    val isCurrent = index == currentState.currentIndex
                    Surface(
                        modifier = Modifier
                            .animateItem(fadeInSpec = null, fadeOutSpec = null)
                            .fillMaxWidth()
                            .clickable {
                                isPlaylistSheetVisible = false
                                onSelectTrack(index)
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) AppColors.CardSurfaceStrong else AppColors.CardSurface,
                        border = BorderStroke(1.dp, if (isCurrent) LocalArtworkPalette.current.accent else AppColors.Border),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = "${index + 1}. ${track.title}",
                                    color = AppColors.TextPrimary,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = if (isCurrent) Strings.nowPlaying else (track.albumTitle ?: currentState.album.title),
                                    color = if (isCurrent) LocalArtworkPalette.current.accent else AppColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaybackMode.entries.forEach { mode ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = playbackMode == mode,
                                    onClick = {
                                        isModeDialogVisible = false
                                        onSelectPlaybackMode(mode)
                                    },
                                ),
                            shape = RoundedCornerShape(14.dp),
                            color = if (playbackMode == mode) AppColors.CardSurfaceStrong else AppColors.CardSurface,
                            border = BorderStroke(1.dp, if (playbackMode == mode) LocalArtworkPalette.current.accent else AppColors.Border),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(mode.label, color = AppColors.TextPrimary)
                                if (playbackMode == mode) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = LocalArtworkPalette.current.accent,
                                    )
                                }
                            }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputTargetOption(
                    title = Strings.localPlayback,
                    subtitle = null,
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
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (isCurrent) AppColors.CardSurfaceStrong else AppColors.CardSurface,
        border = BorderStroke(1.dp, if (isCurrent) LocalArtworkPalette.current.accent else AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                subtitle?.let {
                    Text(
                        text = it,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (isCurrent) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = AppColors.Accent,
                )
            }
        }
    }
}

@Composable
private fun PortraitPlayerContent(
    artworkSize: androidx.compose.ui.unit.Dp,
    compact: Boolean,
    useFullTransport: Boolean,
    artworkContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit,
    playerDetails: @Composable ColumnScope.(Boolean, Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Elastic top gap: keeps the artwork at least 16.dp below the top bar and lets extra
        // height on tall screens breathe above the cover instead of below it.
        Spacer(Modifier.weight(1f).heightIn(min = 16.dp))

        Box(modifier = Modifier.width(artworkSize)) { artworkContent(artworkSize) }

        // Fixed rhythm between the cover and the metadata block (matches the reference).
        Spacer(Modifier.height(if (compact) 12.dp else 14.dp))

        Column(modifier = Modifier.fillMaxWidth()) { playerDetails(compact, useFullTransport) }

        // Elastic bottom gap: guarantees the Dock always floats >= 12.dp above the safe edge
        // while any surplus space is absorbed here, never between interior sections.
        Spacer(Modifier.weight(1f).heightIn(min = if (compact) 12.dp else 16.dp))
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

@Composable
private fun AudioQualityBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(7.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp), color = AppColors.TextSecondary, style = MaterialTheme.typography.labelMedium)
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
        localTranscode -> listOf("TRANSCODE", "OPUS", "${policy.opusBitrate.kbps}k")
        sonosTranscode && playbackMimeType.equals("audio/mpeg", true) -> listOf("TRANSCODE", "MP3", "320k")
        sonosTranscode && playbackMimeType.equals("audio/flac", true) -> listOf("TRANSCODE", "FLAC")
        else -> originalAudioQualityBadges(sourceAudioCodec, bitDepth, sourceAudioSampleRate)
    }
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

@Composable
private fun PlaybackActionBar(
    playbackMode: PlaybackMode,
    palette: ArtworkPalette,
    compact: Boolean = false,
    useFullTransport: Boolean = false,
    modifier: Modifier = Modifier,
    onShowPlaylist: () -> Unit,
    onShowModePicker: () -> Unit,
    onShowRoomPicker: () -> Unit,
    onShowMore: () -> Unit,
) {
    val actionIconSize = if (useFullTransport) 23.dp else if (compact) 20.dp else 22.dp
    val actionSlotHeight = if (useFullTransport) 62.dp else if (compact) 50.dp else 58.dp
    // Glassy pill container with icon + label items, mirroring the reference player UI.
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = if (useFullTransport) 5.dp else if (compact) 2.dp else 6.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlaybackActionButton(
                icon = when (playbackMode) {
                    PlaybackMode.Sequential -> Icons.Filled.ArrowForward
                    PlaybackMode.RepeatAll -> Icons.Filled.Repeat
                    PlaybackMode.RepeatOne -> Icons.Filled.RepeatOne
                    PlaybackMode.Shuffle -> Icons.Filled.Shuffle
                },
                label = Strings.playbackModeShort,
                contentDescription = playbackMode.label,
                modifier = Modifier.weight(1f),
                isSelected = playbackMode != PlaybackMode.Sequential,
                accent = palette.accent,
                iconSize = actionIconSize,
                slotHeight = actionSlotHeight,
                onClick = onShowModePicker,
            )
            PlaybackActionButton(
                icon = Icons.Filled.QueueMusic,
                label = Strings.playlistLabel,
                contentDescription = Strings.playlistLabel,
                modifier = Modifier.weight(1f),
                isSelected = false,
                accent = palette.accent,
                iconSize = actionIconSize,
                slotHeight = actionSlotHeight,
                onClick = onShowPlaylist,
            )
            PlaybackActionButton(
                icon = Icons.Filled.Speaker,
                label = Strings.deviceShort,
                contentDescription = Strings.castRoom,
                modifier = Modifier.weight(1f),
                isSelected = false,
                accent = palette.accent,
                iconSize = actionIconSize,
                slotHeight = actionSlotHeight,
                onClick = onShowRoomPicker,
            )
            PlaybackActionButton(
                icon = Icons.Filled.MoreHoriz,
                label = Strings.more,
                contentDescription = Strings.more,
                modifier = Modifier.weight(1f),
                isSelected = false,
                accent = palette.accent,
                iconSize = actionIconSize,
                slotHeight = actionSlotHeight,
                onClick = onShowMore,
            )
        }
    }
}

@Composable
private fun PlaybackActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean,
    accent: Color,
    iconSize: androidx.compose.ui.unit.Dp = 30.dp,
    slotHeight: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        label = "action_button_scale",
    )
    val animatedTint by animateColorAsState(
        targetValue = if (isSelected) accent else AppColors.TextSecondary,
        label = "action_button_tint",
    )
    Column(
        modifier = modifier
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .fillMaxWidth()
            .heightIn(min = slotHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = animatedTint,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = label,
            color = animatedTint,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Play/pause button with a soft accent-colored halo, matching the glowing transport button
 * in the reference design. The halo fades out when the action is unavailable.
 */
@Composable
private fun GlowPlayerControlButton(
    icon: PlayerIcon,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    enabled: Boolean,
    accent: Color,
    onAccent: Color,
    buttonSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        label = "glow_button_scale",
    )
    // The glow layers are scaled past the button's layout bounds via graphicsLayer, so they
    // never inflate the surrounding row — that previously pushed the page into scrolling.
    Box(
        modifier = modifier.size(buttonSize),
        contentAlignment = Alignment.Center,
    ) {
        // Soft ambient halo spilling onto the background — kept weak so the top arc reads
        // as the dominant glow element, like the reference design.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1.9f
                    scaleY = 1.9f
                }
                .background(
                    Brush.radialGradient(
                        colorStops = arrayOf(
                            0.0f to accent.copy(alpha = if (enabled) 0.06f else 0.02f),
                            0.52f to accent.copy(alpha = if (enabled) 0.14f else 0.04f),
                            1.0f to Color.Transparent,
                        ),
                    )
                ),
        )
        // Luminous arc hugging the top of the button edge, like the reference design — the
        // glow is brightest above the button and fades out towards the bottom.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer {
                    scaleX = 1.6f
                    scaleY = 1.6f
                }
                .background(ArcRingGlowBrush(color = accent, enabled = enabled)),
        )
        Box(
            modifier = Modifier
                .size(buttonSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                // White -> accent vertical gradient reads as a lit glass disc, closer to the
                // reference than a flat accent fill.
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (enabled) 1f else 0.35f),
                            accent.copy(alpha = if (enabled) 1f else 0.35f),
                        ),
                    )
                )
                .clickable(
                    enabled = enabled && !isLoading,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = onAccent,
                    strokeWidth = 2.5.dp,
                )
            } else {
                PlayerIconGraphic(
                    icon = icon,
                    tint = onAccent.copy(alpha = if (enabled) 1f else 0.35f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

/**
 * Top-arc ring glow for the play button. A radial band profile (ring shape peaking just
 * outside the button edge) multiplied by an angular falloff (brightest at 12 o'clock, fading
 * to nothing at the bottom) produces the reference design's half-ring halo.
 *
 * SweepGradient angles run clockwise from 3 o'clock, so fraction 0.75 is straight up.
 */
private class ArcRingGlowBrush(
    private val color: Color,
    private val enabled: Boolean,
) : androidx.compose.ui.graphics.ShaderBrush() {
    override fun createShader(size: androidx.compose.ui.geometry.Size): android.graphics.Shader {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension / 2f
        val strength = if (enabled) 1f else 0.25f

        fun withAlpha(argb: Int, alpha: Float): Int =
            androidx.core.graphics.ColorUtils.setAlphaComponent(
                argb,
                (alpha.coerceIn(0f, 1f) * 255).toInt(),
            )

        val ringColor = color.toArgb()
        val white = android.graphics.Color.WHITE
        val transparent = android.graphics.Color.TRANSPARENT

        // Ring band: the button covers the inner 1/1.6 ≈ 0.62 of this box's radius; a narrow,
        // bright peak just outside the edge reads as a crisp arc line rather than a fog ring.
        val radial = android.graphics.RadialGradient(
            cx, cy, radius,
            intArrayOf(
                transparent,
                transparent,
                withAlpha(ringColor, 0.90f * strength),
                withAlpha(ringColor, 0.28f * strength),
                transparent,
            ),
            floatArrayOf(0.00f, 0.59f, 0.645f, 0.72f, 0.82f),
            android.graphics.Shader.TileMode.CLAMP,
        )
        // Angular falloff: full strength at the top (0.75), fully gone around the bottom.
        val sweep = android.graphics.SweepGradient(
            cx, cy,
            intArrayOf(
                withAlpha(white, 0.10f),
                withAlpha(white, 0.00f),
                withAlpha(white, 0.00f),
                withAlpha(white, 0.10f),
                withAlpha(white, 0.55f),
                withAlpha(white, 1.00f),
                withAlpha(white, 0.55f),
                withAlpha(white, 0.10f),
            ),
            floatArrayOf(0.00f, 0.13f, 0.25f, 0.37f, 0.63f, 0.75f, 0.87f, 1.00f),
        )
        return android.graphics.ComposeShader(radial, sweep, android.graphics.PorterDuff.Mode.MULTIPLY)
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
    onArtistClick: (String?) -> Unit,
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
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "album-summary", contentType = "album-summary") {
                AlbumDetailHeroCompact(
                    trackResult = currentTrackResult,
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
    onArtistClick: (String?) -> Unit,
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
                .clip(RoundedCornerShape(16.dp))
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
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = artistName,
            color = AppColors.TextSecondary,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { onArtistClick(album.artistName) },
        )
        Text(
            text = buildString {
                album.year?.let {
                    append(it)
                    append(" · ")
                }
                append(Strings.tracks(trackResult.tracks.size))
            },
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = Strings.outputTargetDesc(outputTargetName),
            color = AppColors.TextTertiary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FavoriteIconButton(
                isFavorite = album.isFavorite,
                isLoading = isFavoriteLoading,
                onClick = { onToggleAlbumFavorite(album) },
            )
            val playEnabled = !isPlaybackLoading && trackResult.tracks.isNotEmpty()
            IconCircleButton(
                onClick = { onPlayAlbum(trackResult) },
                enabled = playEnabled,
                containerColor = if (playEnabled) palette.accent else palette.accent.copy(alpha = 0.35f),
                modifier = Modifier.semantics { contentDescription = Strings.playAll },
            ) {
                PlayerIconGraphic(
                    icon = PlayerIcon.Play,
                    tint = palette.onAccent.copy(alpha = if (playEnabled) 1f else 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
            if (isAllDownloaded) {
                IconCircleButton(
                    onClick = { showDeleteAlbumDialog = true },
                    modifier = Modifier.semantics { contentDescription = Strings.deleteAlbumDownload },
                ) {
                    Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = Strings.deleteAlbumDownload,
                        tint = palette.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            } else if (isAnyDownloading) {
                Box(
                    modifier = Modifier.size(44.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = palette.accent,
                    )
                }
            } else {
                IconCircleButton(
                    onClick = { onDownloadAlbum(album, trackResult.tracks) },
                    modifier = Modifier.semantics { contentDescription = Strings.downloadAlbumOriginal },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = Strings.downloadAlbumOriginal,
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

    if (showDeleteAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbumDialog = false },
            title = { Text(Strings.deleteAlbumDownload) },
            text = { Text(Strings.confirmDeleteAlbumDownload) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAlbumDialog = false
                    onDeleteDownloadedAlbum(album)
                }) {
                    Text(Strings.confirmDelete, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbumDialog = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSurface,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumDetailHeroCompact(
    trackResult: PlexAlbumTracksResult,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    showAlbumQueueMenu: Boolean,
    onShowAlbumQueueMenuChange: (Boolean) -> Unit,
    onArtistClick: (String?) -> Unit,
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
    val codec = trackResult.tracks.firstOrNull()
        ?.sourceAudioCodec
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
    val playEnabled = !isPlaybackLoading && trackResult.tracks.isNotEmpty()
    var showActionQueueMenu by remember(album.sourceId, album.ratingKey) { mutableStateOf(false) }
    val totalTracks = trackResult.tracks.size
    val downloadedCount = trackResult.tracks.count { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val isAnyDownloading = trackResult.tracks.any { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadProgress
    }
    val isAllDownloaded = totalTracks > 0 && downloadedCount == totalTracks
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            AsyncAlbumArtwork(
                imageUrl = album.thumbUrl,
                title = album.title,
                modifier = Modifier
                    .size(layoutInfo.albumDetailCompactArtworkSize)
                    .clip(RoundedCornerShape(14.dp))
                    .semantics { contentDescription = "${album.title}. ${Strings.longPressQueueHint}" }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = {
                            showActionQueueMenu = false
                            onShowAlbumQueueMenuChange(true)
                        },
                    ),
                contentScale = ContentScale.Crop,
                thumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
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

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.titleMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = album.artistName ?: Strings.unknownArtist,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { onArtistClick(album.artistName) },
            )
            Text(
                text = buildString {
                    album.year?.let { append("$it · ") }
                    append(Strings.tracks(trackResult.tracks.size))
                    codec?.let { append(" · $it") }
                },
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconCircleButton(
                    onClick = { onPlayAlbum(trackResult) },
                    enabled = playEnabled,
                    containerColor = if (playEnabled) palette.accent else palette.accent.copy(alpha = 0.35f),
                    modifier = Modifier.semantics { contentDescription = Strings.playAll },
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Play,
                        tint = palette.onAccent.copy(alpha = if (playEnabled) 1f else 0.6f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                FavoriteIconButton(
                    isFavorite = album.isFavorite,
                    isLoading = isFavoriteLoading,
                    onClick = { onToggleAlbumFavorite(album) },
                )
                if (isAllDownloaded) {
                    IconCircleButton(
                        onClick = { showDeleteAlbumDialog = true },
                        modifier = Modifier.semantics { contentDescription = Strings.deleteAlbumDownload },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = Strings.deleteAlbumDownload,
                            tint = palette.accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                } else if (isAnyDownloading) {
                    Box(
                        modifier = Modifier.size(40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = palette.accent,
                        )
                    }
                } else {
                    IconCircleButton(
                        onClick = { onDownloadAlbum(album, trackResult.tracks) },
                        modifier = Modifier.semantics { contentDescription = Strings.downloadAlbumOriginal },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = Strings.downloadAlbumOriginal,
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Box {
                    IconCircleButton(
                        onClick = {
                            onShowAlbumQueueMenuChange(false)
                            showActionQueueMenu = true
                        },
                        containerColor = AppColors.CardSurfaceStrong,
                        modifier = Modifier.semantics { contentDescription = Strings.addToPlaybackQueue },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlaylistAdd,
                            contentDescription = null,
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
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
    }

    if (showDeleteAlbumDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAlbumDialog = false },
            title = { Text(Strings.deleteAlbumDownload) },
            text = { Text(Strings.confirmDeleteAlbumDownload) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteAlbumDialog = false
                    onDeleteDownloadedAlbum(album)
                }) {
                    Text(Strings.confirmDelete, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAlbumDialog = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSurface,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
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

    Box {
    Surface(
        modifier = Modifier.combinedClickable(
            enabled = !isPlaybackLoading,
            onClick = { onPlayTrack(album, track) },
            onLongClick = { showQueueMenu = true },
        ),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.CardSurface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "${index + 1}. ${track.title}",
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = AppColors.Success,
                            modifier = Modifier.size(16.dp),
                        )
                    } else if (progress != null) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalArtworkPalette.current.accent,
                        )
                    }
                }
                track.durationMillis?.let { duration ->
                    Text(formatDuration(duration), color = AppColors.TextTertiary)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoriteIconButton(
                    isFavorite = track.isFavorite,
                    isLoading = isFavoriteLoading,
                    onClick = { onToggleTrackFavorite(track) },
                )
                IconCircleButton(
                    onClick = { onPlayTrack(album, track) },
                    enabled = !isPlaybackLoading,
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Play,
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
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
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.95f)), RoundedCornerShape(12.dp))
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
    onArtworkClick: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val currentTrack = state.tracks.getOrNull(state.currentIndex) ?: return
    val displayAlbum = currentTrack.toDisplayAlbum(state.album)
    // Subtle bridge to the immersive player: tint the bar with a dimmed cover color.
    val palette = rememberAnimatedArtworkPalette(displayAlbum.thumbUrl)
    val artistName = currentTrack.artistName ?: displayAlbum.artistName ?: Strings.unknownArtist
    val durationMillis = state.durationMillis ?: currentTrack.durationMillis
    val progress = if (durationMillis != null && durationMillis > 0L) {
        (state.currentPositionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            // Caller-managed: while the full player is open the pill hands itself to the
            // transition overlay (morphing into the player backdrop) instead of drawing.
            .miniSharedBounds(PlayerSharedKeys.Container, visible = !isPlayerOpen),
        shape = RoundedCornerShape(24.dp),
        color = palette.backgroundTop,
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // While the player is open the pill is invisible (its shared elements live
                // in the transition overlay); strip the click target so the hidden pill can
                // never swallow touches meant for the page beneath it.
                .then(
                    if (isPlayerOpen) Modifier
                    else Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true),
                        onClick = onArtworkClick,
                    )
                ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .miniSharedElement(PlayerSharedKeys.artwork(currentTrack), visible = !isPlayerOpen)
                        .clip(RoundedCornerShape(14.dp)),
                ) {
                    AsyncAlbumArtwork(
                        imageUrl = displayAlbum.thumbUrl,
                        title = displayAlbum.title,
                        modifier = Modifier.size(52.dp),
                        thumbnailSize = 52.dp,
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = currentTrack.title,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = artistName,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                PlayerControlButton(
                    icon = if (state.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                    isLoading = state.isBuffering,
                    onClick = onTogglePause,
                    enabled = !isPlayerOpen && !state.isBuffering,
                    highlighted = true,
                    sizeOverride = 54.dp,
                    iconSizeOverride = 24.dp,
                    highlightedContainerColor = palette.accent,
                    highlightedContentColor = palette.onAccent,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.14f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.accent),
                )
            }
        }
    }
}

@Composable
internal fun PlayerControlButton(
    icon: PlayerIcon,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    enabled: Boolean,
    highlighted: Boolean = false,
    sizeOverride: androidx.compose.ui.unit.Dp? = null,
    iconSizeOverride: androidx.compose.ui.unit.Dp? = null,
    highlightedContainerColor: Color = AppColors.Accent,
    highlightedContentColor: Color = AppColors.SurfaceStrong,
    unhighlightedContainerColor: Color = AppColors.SurfaceMuted,
    unhighlightedBorderColor: Color = AppColors.Border,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (highlighted) highlightedContainerColor else unhighlightedContainerColor
    val contentColor = if (highlighted) highlightedContentColor else AppColors.TextPrimary
    val borderColor = if (highlighted) Color.Transparent else unhighlightedBorderColor
    val buttonSize = sizeOverride ?: if (highlighted) 64.dp else 52.dp
    val iconSize = iconSizeOverride ?: if (highlighted) 26.dp else 22.dp
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        label = "button_scale",
    )

    Surface(
        modifier = modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                enabled = enabled && !isLoading,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor.copy(alpha = if (enabled) 1f else 0.35f)),
        shadowElevation = if (highlighted) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    color = contentColor,
                    strokeWidth = 2.5.dp,
                )
            } else {
                PlayerIconGraphic(
                    icon = icon,
                    tint = contentColor.copy(alpha = if (enabled) 1f else 0.35f),
                    modifier = Modifier.size(iconSize),
                )
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
