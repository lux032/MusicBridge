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
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.plex.playbackUrlIdentity
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PlaybackDetailSection(
    state: MiniPlayerState?,
    rooms: List<SonosRoom>,
    playbackMode: PlaybackMode,
    sleepTimerState: SleepTimerState,
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
    onSelectRoom: (SonosRoom) -> Unit,
    onStartSleepTimer: (Int, Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
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
    val showAlbumTitle = !displayAlbum.title.equals(currentTrack.title, ignoreCase = true)

    val trackDurationMillis = currentState.durationMillis?.takeIf { it > 0L } ?: currentTrack.durationMillis
    var isSeeking by remember(currentTrack.ratingKey) { mutableStateOf(false) }
    var sliderPositionMillis by remember(currentTrack.ratingKey) {
        mutableStateOf(currentState.currentPositionMillis.toFloat())
    }
    var isPlaylistSheetVisible by remember { mutableStateOf(false) }
    var isModeDialogVisible by remember { mutableStateOf(false) }
    var isRoomDialogVisible by remember { mutableStateOf(false) }
    var isSleepTimerDialogVisible by remember { mutableStateOf(false) }
    var sleepTimerHours by remember { mutableIntStateOf(0) }
    var sleepTimerMinutes by remember { mutableIntStateOf(30) }
    var sleepTimerInputError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentState.currentPositionMillis, currentTrack.ratingKey, isSeeking) {
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
            IconCircleButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Filled.ArrowBack,
                    contentDescription = Strings.back,
                    tint = AppColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(
                text = Strings.nowPlaying,
                modifier = Modifier.weight(1f),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            // Keep the title optically centered against the back button.
            Spacer(modifier = Modifier.size(42.dp))
        }
    }
    val artworkContent: @Composable (androidx.compose.ui.unit.Dp) -> Unit = { maximumSize ->
        AsyncAlbumArtwork(
            imageUrl = displayAlbum.thumbUrl,
            title = displayAlbum.title,
            modifier = Modifier
                .widthIn(max = maximumSize)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
            thumbnailSize = maximumSize,
        )
    }
    val playerDetails: @Composable ColumnScope.(Boolean) -> Unit = { compact ->
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 18.dp),
        ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
        ) {
            Text(
                currentTrack.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAlbumClick(displayAlbum) }
                    .basicMarquee(),
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayAlbum.artistName ?: Strings.unknownArtist,
                    color = AppColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = !showAlbumTitle)
                        .clickable { onArtistClick(displayAlbum.artistName) },
                )
                if (showAlbumTitle) {
                    Text("·", color = AppColors.TextTertiary)
                    Text(
                        text = displayAlbum.title,
                        color = AppColors.TextSecondary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onAlbumClick(displayAlbum) },
                    )
                }
            }
            Text(
                text = "${currentState.room.roomName} · ${currentState.currentIndex + 1}/${currentState.tracks.size}",
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { isRoomDialogVisible = true },
            )
        }
        trackDurationMillis?.let { durationMillis ->
            val clampedDuration = durationMillis.coerceAtLeast(1L)
            val clampedSliderValue = sliderPositionMillis.coerceIn(0f, clampedDuration.toFloat())
            Column(
                modifier = Modifier.fillMaxWidth(),
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
                    enabled = !isLoading,
                    colors = SliderDefaults.colors(
                        thumbColor = AppColors.Accent,
                        activeTrackColor = AppColors.Accent,
                        inactiveTrackColor = AppColors.SurfaceMuted,
                        disabledThumbColor = AppColors.TextTertiary,
                        disabledActiveTrackColor = AppColors.TextTertiary,
                        disabledInactiveTrackColor = AppColors.SurfaceMuted,
                    ),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .background(AppColors.Accent, CircleShape),
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
                                .background(AppColors.SurfaceMuted),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(AppColors.Accent),
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
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlButton(
                icon = PlayerIcon.Previous,
                onClick = onPrevious,
                enabled = currentState.currentIndex > 0 && !isLoading,
                sizeOverride = if (compact) 46.dp else 56.dp,
                iconSizeOverride = if (compact) 20.dp else 23.dp,
            )
            Spacer(modifier = Modifier.width(if (compact) 20.dp else 32.dp))
            PlayerControlButton(
                icon = if (currentState.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                onClick = { onTogglePause(currentState) },
                enabled = !isLoading,
                highlighted = true,
                sizeOverride = if (compact) 54.dp else 72.dp,
                iconSizeOverride = if (compact) 24.dp else 30.dp,
            )
            Spacer(modifier = Modifier.width(if (compact) 20.dp else 32.dp))
            PlayerControlButton(
                icon = PlayerIcon.Next,
                onClick = onNext,
                enabled = currentState.currentIndex < currentState.tracks.lastIndex && !isLoading,
                sizeOverride = if (compact) 46.dp else 56.dp,
                iconSizeOverride = if (compact) 20.dp else 23.dp,
            )
        }
        PlaybackActionBar(
            currentTrack = currentTrack,
            playbackMode = playbackMode,
            sleepTimerState = sleepTimerState,
            isFavoriteLoading = isFavoriteLoading,
            compact = compact,
            modifier = Modifier.fillMaxWidth(),
            onShowPlaylist = { isPlaylistSheetVisible = true },
            onShowModePicker = { isModeDialogVisible = true },
            onToggleTrackFavorite = { onToggleTrackFavorite(currentTrack) },
            onShowSleepTimer = {
                val remainingMillis = sleepTimerState.remainingMillis
                val totalMinutes = ((remainingMillis + 59_999L) / 60_000L).toInt()
                if (sleepTimerState.isActive && totalMinutes > 0) {
                    sleepTimerHours = totalMinutes / 60
                    sleepTimerMinutes = totalMinutes % 60
                } else {
                    sleepTimerHours = 0
                    sleepTimerMinutes = 30
                }
                sleepTimerInputError = null
                isSleepTimerDialogVisible = true
            },
            onShowRoomPicker = { isRoomDialogVisible = true },
        )
        if (sleepTimerState.isActive) {
            Text(
                text = Strings.sleepTimerRemaining(formatSleepTimerCountdown(sleepTimerState.remainingMillis)),
                color = AppColors.Accent,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val topBarHeight = 48.dp
        val availableWidth = maxWidth
        val bodyHeight = (maxHeight - topBarHeight).coerceAtLeast(0.dp)
        val compactLandscape = layoutInfo.isLandscape && maxHeight < 500.dp

        Column(modifier = Modifier.fillMaxSize()) {
            topBar()

            if (layoutInfo.isLandscape) {
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
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = if (compactLandscape) 2.dp else 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            playerDetails(compactLandscape)
                        }
                    }
                }
            } else {
                val artworkSize = minOf(
                    availableWidth,
                    bodyHeight * if (layoutInfo.isTablet) 0.42f else 0.46f,
                    if (layoutInfo.isTablet) 420.dp else 360.dp,
                ).coerceAtLeast(220.dp)

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (layoutInfo.isTablet) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceEvenly,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(modifier = Modifier.width(artworkSize)) {
                                artworkContent(artworkSize)
                            }
                            playerDetails(false)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = bodyHeight)
                                .verticalScroll(rememberScrollState())
                                .padding(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(modifier = Modifier.width(artworkSize)) {
                                artworkContent(artworkSize)
                            }
                            playerDetails(false)
                        }
                    }
                }
            }
        }
    }

    if (isPlaylistSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isPlaylistSheetVisible = false },
            containerColor = AppColors.Surface,
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
                            .fillMaxWidth()
                            .clickable {
                                isPlaylistSheetVisible = false
                                onSelectTrack(index)
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isCurrent) AppColors.SurfaceStrong else AppColors.SurfaceAlt,
                        border = BorderStroke(1.dp, if (isCurrent) AppColors.Accent else AppColors.Border),
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
                                    color = if (isCurrent) AppColors.Accent else AppColors.TextSecondary,
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
                            color = if (playbackMode == mode) AppColors.SurfaceStrong else AppColors.SurfaceAlt,
                            border = BorderStroke(1.dp, if (playbackMode == mode) AppColors.Accent else AppColors.Border),
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
                                        tint = AppColors.Accent,
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
        AlertDialog(
            onDismissRequest = { isRoomDialogVisible = false },
            confirmButton = {},
            title = {
                Text(
                    text = Strings.selectSonosRoom,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                if (rooms.isEmpty()) {
                    Text(Strings.noAvailableRooms, color = AppColors.TextSecondary)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rooms.forEach { room ->
                            val isCurrentRoom = room.coordinatorUuid == currentState.room.coordinatorUuid
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        isRoomDialogVisible = false
                                        onSelectRoom(room)
                                    },
                                shape = RoundedCornerShape(14.dp),
                                color = if (isCurrentRoom) AppColors.SurfaceStrong else AppColors.SurfaceAlt,
                                border = BorderStroke(1.dp, if (isCurrentRoom) AppColors.Accent else AppColors.Border),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(room.roomName, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                                        Text(
                                            text = if (room.memberCount > 1) Strings.members(room.memberCount) else Strings.singleRoom,
                                            color = AppColors.TextSecondary,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    if (isCurrentRoom) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = AppColors.Accent,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
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

@Composable
private fun PlaybackActionBar(
    currentTrack: PlexTrackStream,
    playbackMode: PlaybackMode,
    sleepTimerState: SleepTimerState,
    isFavoriteLoading: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
    onShowPlaylist: () -> Unit,
    onShowModePicker: () -> Unit,
    onToggleTrackFavorite: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowRoomPicker: () -> Unit,
) {
    val actionIconSize = if (compact) 24.dp else 28.dp
    val actionSlotHeight = if (compact) 44.dp else 56.dp
    Row(
        modifier = modifier.fillMaxWidth(),
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
            contentDescription = playbackMode.label,
            modifier = Modifier.weight(1f),
            isSelected = playbackMode != PlaybackMode.Sequential,
            iconSize = actionIconSize,
            slotHeight = actionSlotHeight,
            onClick = onShowModePicker,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(actionSlotHeight),
            contentAlignment = Alignment.Center,
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.85f else 1f,
                label = "favorite_scale",
            )
            Column(
                modifier = Modifier
                    .clickable(
                        enabled = !isFavoriteLoading,
                        interactionSource = interactionSource,
                        indication = ripple(bounded = true),
                        onClick = onToggleTrackFavorite,
                    )
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .fillMaxWidth()
                    .height(actionSlotHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                FavoriteIconGraphic(
                    isFavorite = currentTrack.isFavorite,
                    tint = if (currentTrack.isFavorite) AppColors.Accent else AppColors.TextSecondary,
                    baseSize = actionIconSize,
                    selectedSize = if (compact) 27.dp else 31.dp,
                )
            }
        }
        PlaybackActionButton(
            icon = Icons.Filled.QueueMusic,
            contentDescription = Strings.playlistLabel,
            modifier = Modifier.weight(1f),
            isSelected = false,
            iconSize = actionIconSize,
            slotHeight = actionSlotHeight,
            onClick = onShowPlaylist,
        )
        PlaybackActionButton(
            icon = Icons.Filled.Bedtime,
            contentDescription = Strings.sleepTimer,
            modifier = Modifier.weight(1f),
            isSelected = sleepTimerState.isActive,
            iconSize = actionIconSize,
            slotHeight = actionSlotHeight,
            onClick = onShowSleepTimer,
        )
        PlaybackActionButton(
            icon = Icons.Filled.Speaker,
            contentDescription = Strings.castRoom,
            modifier = Modifier.weight(1f),
            isSelected = false,
            iconSize = actionIconSize,
            slotHeight = actionSlotHeight,
            onClick = onShowRoomPicker,
        )
    }
}

@Composable
private fun PlaybackActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    isSelected: Boolean,
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
        targetValue = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
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
            .height(slotHeight),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = animatedTint,
            modifier = Modifier.size(iconSize),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumDetailSection(
    trackResult: PlexAlbumTracksResult?,
    selectedRoom: SonosRoom?,
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
    onPlayAlbum: (PlexAlbumTracksResult, SonosRoom) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream, SonosRoom) -> Unit,
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
    val artworkMaximumWidth = if (layoutInfo.isWide) 300.dp else layoutInfo.detailArtworkMaximumWidth
    var showAlbumQueueMenu by remember { mutableStateOf(false) }
    val albumSummary: @Composable ColumnScope.() -> Unit = {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncAlbumArtwork(
                imageUrl = currentTrackResult.album.thumbUrl,
                title = currentTrackResult.album.title,
                modifier = Modifier
                    .widthIn(max = artworkMaximumWidth)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showAlbumQueueMenu = true },
                    ),
                contentScale = ContentScale.Crop,
                thumbnailSize = artworkMaximumWidth,
            )
            QueueActionMenu(
                expanded = showAlbumQueueMenu,
                onDismiss = { showAlbumQueueMenu = false },
                onAction = { placement ->
                    showAlbumQueueMenu = false
                    onQueueAlbum(currentTrackResult.album, placement)
                },
            )
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val artistName = currentTrackResult.album.artistName ?: Strings.unknownArtist
            Text(
                currentTrackResult.album.title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = artistName,
                    color = AppColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onArtistClick(currentTrackResult.album.artistName) },
                )
            }
            Text(
                text = buildString {
                    currentTrackResult.album.year?.let {
                        append(it)
                        append(" · ")
                    }
                    append(Strings.tracks(currentTrackResult.tracks.size))
                },
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = selectedRoom?.let { Strings.willPushTo(it.roomName) } ?: Strings.noRoomSelectedDesc,
                color = AppColors.TextTertiary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoriteIconButton(
                    isFavorite = currentTrackResult.album.isFavorite,
                    isLoading = isFavoriteLoading,
                    onClick = { onToggleAlbumFavorite(currentTrackResult.album) },
                )
                IconCircleButton(
                    onClick = { selectedRoom?.let { onPlayAlbum(currentTrackResult, it) } },
                    enabled = selectedRoom != null && !isPlaybackLoading,
                    highlighted = true,
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Play,
                        tint = if (selectedRoom != null) AppColors.TextPrimary else AppColors.TextTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }

    if (layoutInfo.isWide) {
        Row(
            modifier = modifier.fillMaxSize(),
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
                content = albumSummary,
            )
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
                    AlbumTrackRow(
                        index = index,
                        album = currentTrackResult.album,
                        track = track,
                        selectedRoom = selectedRoom,
                        isPlaybackLoading = isPlaybackLoading,
                        isFavoriteLoading = isFavoriteLoading,
                        onToggleTrackFavorite = onToggleTrackFavorite,
                        onQueueTrack = onQueueTrack,
                        onPlayTrack = onPlayTrack,
                    )
                }
            }
        }
    } else {
        LazyColumn(
            state = trackListState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "album-summary", contentType = "album-summary") {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    content = albumSummary,
                )
            }
            itemsIndexed(
                items = currentTrackResult.tracks,
                key = { index, track -> track.ratingKey ?: index },
                contentType = { _, _ -> "album-track" },
            ) { index, track ->
                AlbumTrackRow(
                    index = index,
                    album = currentTrackResult.album,
                    track = track,
                    selectedRoom = selectedRoom,
                    isPlaybackLoading = isPlaybackLoading,
                    isFavoriteLoading = isFavoriteLoading,
                    onToggleTrackFavorite = onToggleTrackFavorite,
                    onQueueTrack = onQueueTrack,
                    onPlayTrack = onPlayTrack,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    index: Int,
    album: PlexAlbum,
    track: PlexTrackStream,
    selectedRoom: SonosRoom?,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream, SonosRoom) -> Unit,
) {
    var showQueueMenu by remember { mutableStateOf(false) }
    Box {
    Surface(
        modifier = Modifier.combinedClickable(
            enabled = !isPlaybackLoading,
            onClick = { selectedRoom?.let { onPlayTrack(album, track, it) } },
            onLongClick = { showQueueMenu = true },
        ),
        shape = RoundedCornerShape(12.dp),
        color = AppColors.Surface,
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
                Text(
                    text = "${index + 1}. ${track.title}",
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
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
                    onClick = { selectedRoom?.let { onPlayTrack(album, track, it) } },
                    enabled = selectedRoom != null && !isPlaybackLoading,
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Play,
                        tint = if (selectedRoom != null) AppColors.TextPrimary else AppColors.TextTertiary,
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
                .background(AppColors.SurfaceAlt),
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
    onArtworkClick: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val currentTrack = state.tracks.getOrNull(state.currentIndex) ?: return
    val displayAlbum = currentTrack.toDisplayAlbum(state.album)
    val artistName = currentTrack.artistName ?: displayAlbum.artistName ?: Strings.unknownArtist
    val durationMillis = state.durationMillis ?: currentTrack.durationMillis
    val progress = if (durationMillis != null && durationMillis > 0L) {
        (state.currentPositionMillis.toFloat() / durationMillis).coerceIn(0f, 1f)
    } else {
        0f
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = AppColors.SurfaceStrong,
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onArtworkClick,
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
                    onClick = onTogglePause,
                    enabled = true,
                    highlighted = true,
                    sizeOverride = 54.dp,
                    iconSizeOverride = 24.dp,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppColors.SurfaceMuted),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(AppColors.Accent),
                )
            }
        }
    }
}

@Composable
internal fun PlayerControlButton(
    icon: PlayerIcon,
    onClick: () -> Unit,
    enabled: Boolean,
    highlighted: Boolean = false,
    sizeOverride: androidx.compose.ui.unit.Dp? = null,
    iconSizeOverride: androidx.compose.ui.unit.Dp? = null,
) {
    val containerColor = if (highlighted) AppColors.Accent else AppColors.SurfaceMuted
    val contentColor = if (highlighted) AppColors.SurfaceStrong else AppColors.TextPrimary
    val borderColor = if (highlighted) Color.Transparent else AppColors.Border
    val buttonSize = sizeOverride ?: if (highlighted) 64.dp else 52.dp
    val iconSize = iconSizeOverride ?: if (highlighted) 26.dp else 22.dp
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        label = "button_scale",
    )

    Surface(
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                enabled = enabled,
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
            PlayerIconGraphic(
                icon = icon,
                tint = contentColor.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
internal fun SettingsSection(
    rooms: List<SonosRoom>,
    selectedRoom: SonosRoom?,
    isSonosLoading: Boolean,
    discoveryAttempted: Boolean,
    serverProfiles: List<MusicServerProfile>,
    activeServerId: String,
    onDiscoverSonos: () -> Unit,
    onSelectRoom: (SonosRoom) -> Unit,
    onSaveServer: (MusicServerProfile, Boolean) -> Unit,
    onSwitchServer: (String) -> Unit,
    onDeleteServer: (String) -> Unit,
    onRefreshHome: () -> Unit,
) {
    val activeProfile = serverProfiles.firstOrNull { it.id == activeServerId }
    var draft by remember { mutableStateOf(activeProfile ?: MusicServerProfile(type = MusicServerType.Music032)) }
    var pendingDelete by remember { mutableStateOf<MusicServerProfile?>(null) }

    LaunchedEffect(activeServerId) {
        serverProfiles.firstOrNull { it.id == activeServerId }?.let { draft = it }
    }

    pendingDelete?.let { profile ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除服务器？") },
            text = { Text("将从本机删除“${profile.displayName}”的连接配置，服务器上的音乐和数据不会被删除。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteServer(profile.id)
                        pendingDelete = null
                    },
                ) { Text("移除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SonosSettingsCard(
            rooms = rooms,
            selectedRoom = selectedRoom,
            isLoading = isSonosLoading,
            discoveryAttempted = discoveryAttempted,
            onDiscover = onDiscoverSonos,
            onSelectRoom = onSelectRoom,
        )
        HorizontalDivider(color = AppColors.Border)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("音乐服务器", style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
                Text("可以保存多个 Plex 或 032 Music Server，并随时切换。", color = AppColors.TextSecondary)
            }
            FilledTonalButton(
                onClick = { draft = MusicServerProfile(type = MusicServerType.Music032) },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("添加")
            }
        }

        serverProfiles.forEach { profile ->
            val isActive = profile.id == activeServerId
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.elevatedCardColors(
                    containerColor = if (isActive) AppColors.Accent.copy(alpha = 0.14f) else AppColors.SurfaceMuted,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSwitchServer(profile.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (profile.type == MusicServerType.Plex) Icons.Filled.Cloud else Icons.Filled.Storage,
                        contentDescription = null,
                        tint = if (isActive) AppColors.Accent else AppColors.TextSecondary,
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.displayName, color = AppColors.TextPrimary, fontWeight = FontWeight.SemiBold)
                            if (isActive) AssistChip(onClick = {}, label = { Text("当前") })
                        }
                        Text(
                            "${profile.type.label} · ${profile.baseUrl.ifBlank { "自动发现" }}",
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { draft = profile }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑服务器")
                    }
                    IconButton(onClick = { pendingDelete = profile }) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "移除服务器")
                    }
                }
            }
        }

        Text(
            if (draft.id.isBlank()) "添加服务器" else "编辑服务器",
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MusicServerType.values().forEach { type ->
                FilterChip(
                    selected = draft.type == type,
                    enabled = draft.id.isBlank(),
                    onClick = {
                        if (draft.type != type) draft = draft.freshDraftForType(type)
                    },
                    label = { Text(type.label) },
                    leadingIcon = if (draft.type == type) {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else null,
                )
            }
        }
        if (draft.id.isNotBlank()) {
            Text(
                "服务器类型在保存后不可更改。如需使用另一种服务，请点击“添加”创建独立配置。",
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        OutlinedTextField(
            value = draft.name,
            onValueChange = { draft = draft.copy(name = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("显示名称") },
            placeholder = { Text(if (draft.type == MusicServerType.Plex) "家里的 Plex" else "032 Music Server") },
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.baseUrl,
            onValueChange = { draft = draft.copy(baseUrl = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器地址") },
            placeholder = { Text(if (draft.type == MusicServerType.Plex) "http://192.168.1.10:32400" else "http://192.168.1.10:4533") },
            singleLine = true,
        )
        OutlinedTextField(
            value = draft.token,
            onValueChange = { draft = draft.copy(token = it) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (draft.type == MusicServerType.Plex) "Plex Token" else "API Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        if (draft.type == MusicServerType.Music032) {
            OutlinedTextField(
                value = draft.mediaToken,
                onValueChange = { draft = draft.copy(mediaToken = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("媒体 Token") },
                supportingText = { Text("留空时使用 API Token；建议填写服务端独立的媒体 Token。") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        } else {
            OutlinedTextField(
                value = draft.server,
                onValueChange = { draft = draft.copy(server = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Plex 服务器名称（可选）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.username,
                onValueChange = { draft = draft.copy(username = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Plex 用户名（无 Token 时使用）") },
                singleLine = true,
            )
            OutlinedTextField(
                value = draft.password,
                onValueChange = { draft = draft.copy(password = it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Plex 密码") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSaveServer(draft, false) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.SurfaceStrong,
                ),
            ) {
                Text(Strings.save)
            }
            OutlinedButton(
                onClick = { onSaveServer(draft, true) },
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AppColors.BorderStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
            ) {
                Text(Strings.saveAndRefresh)
            }
        }
        OutlinedButton(
            onClick = onRefreshHome,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
        ) {
            Text(Strings.refreshHome)
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
        val playbackUrl = prepareTrackUrl(track)
        sonosController.playTrack(
            room = room,
            trackUrl = playbackUrl,
            title = track.title,
            albumTitle = track.albumTitle ?: trackResult.album.title,
            mimeType = track.playbackMimeType,
        )
        if (startPositionMillis > 0L) {
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

    while (true) {
        delay(1_000)
        val status = sonosController.getPlaybackStatus(room)
        val currentUri = status.currentTrackUri.orEmpty()
        val normalizedExpected = playbackUrlIdentity(expectedTrackUrl)
        val normalizedCurrent = playbackUrlIdentity(currentUri)

        if (normalizedCurrent.isNotBlank() && normalizedCurrent != normalizedExpected) {
            return
        }

        val relTimeSeconds = status.relTimeSeconds
        val trackDurationSeconds = status.trackDurationSeconds ?: expectedDurationSeconds

        if (relTimeSeconds != null && relTimeSeconds > 0) {
            stableProgressSeen = true
            stoppedNoProgressCount = 0
        }

        if (status.transportState.equals("STOPPED", ignoreCase = true) && stableProgressSeen) {
            return
        }

        if (status.transportState.equals("STOPPED", ignoreCase = true) && !stableProgressSeen) {
            stoppedNoProgressCount += 1
            if (stoppedNoProgressCount >= 5) {
                return
            }
        }

        if (relTimeSeconds != null && trackDurationSeconds != null && relTimeSeconds >= trackDurationSeconds - 1) {
            return
        }
    }
}

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
