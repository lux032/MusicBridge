package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncAlbumArtwork(
            imageUrl = displayAlbum.thumbUrl,
            title = displayAlbum.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                currentTrack.title,
                modifier = Modifier.basicMarquee(),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = displayAlbum.artistName ?: Strings.unknownArtist,
                color = AppColors.Accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(displayAlbum.artistName) },
            )
            Text(
                text = displayAlbum.title,
                color = AppColors.TextSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAlbumClick(displayAlbum) },
            )
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
                verticalArrangement = Arrangement.spacedBy(6.dp),
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
            )
            Spacer(modifier = Modifier.width(32.dp))
            PlayerControlButton(
                icon = if (currentState.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                onClick = { onTogglePause(currentState) },
                enabled = !isLoading,
                highlighted = true,
            )
            Spacer(modifier = Modifier.width(32.dp))
            PlayerControlButton(
                icon = PlayerIcon.Next,
                onClick = onNext,
                enabled = currentState.currentIndex < currentState.tracks.lastIndex && !isLoading,
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        PlaybackActionBar(
            currentTrack = currentTrack,
            playbackMode = playbackMode,
            sleepTimerState = sleepTimerState,
            isFavoriteLoading = isFavoriteLoading,
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

    if (isPlaylistSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isPlaylistSheetVisible = false },
            containerColor = AppColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = Strings.playlist,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                currentState.tracks.forEachIndexed { index, track ->
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
    modifier: Modifier = Modifier,
    onShowPlaylist: () -> Unit,
    onShowModePicker: () -> Unit,
    onToggleTrackFavorite: () -> Unit,
    onShowSleepTimer: () -> Unit,
    onShowRoomPicker: () -> Unit,
) {
    val actionIconSize = 36.dp
    val actionSlotHeight = 56.dp
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
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FavoriteIconGraphic(
                    isFavorite = currentTrack.isFavorite,
                    tint = if (currentTrack.isFavorite) AppColors.Accent else AppColors.TextSecondary,
                    baseSize = actionIconSize,
                    selectedSize = 38.dp,
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

@Composable
internal fun AlbumDetailSection(
    trackResult: PlexAlbumTracksResult?,
    selectedRoom: SonosRoom?,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    onReturnHome: () -> Unit,
    onArtistClick: (String?) -> Unit,
    onToggleAlbumFavorite: (PlexAlbum) -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncAlbumArtwork(
            imageUrl = currentTrackResult.album.thumbUrl,
            title = currentTrackResult.album.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(16.dp)),
        )

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

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            currentTrackResult.tracks.forEachIndexed { index, track ->
                Surface(
                    modifier = Modifier
                        .clickable(
                            enabled = selectedRoom != null && !isPlaybackLoading,
                        ) {
                            onPlayTrack(currentTrackResult.album, track, selectedRoom!!)
                        },
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
                            Text("${index + 1}. ${track.title}", color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                            track.durationMillis?.let {
                                Text(formatDuration(it), color = AppColors.TextTertiary)
                            }
                        }
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
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
                                    onClick = { selectedRoom?.let { onPlayTrack(currentTrackResult.album, track, it) } },
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
                }
            }
        }
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
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = AppColors.SurfaceStrong,
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(bounded = true),
                    onClick = onArtworkClick,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                )
            }
            Text(
                text = currentTrack.title,
                modifier = Modifier.weight(1f),
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PlayerControlButton(
                icon = if (state.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                onClick = onTogglePause,
                enabled = true,
                highlighted = true,
                sizeOverride = 54.dp,
                iconSizeOverride = 24.dp,
            )
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
internal fun TrackSection(
    trackResult: PlexAlbumTracksResult?,
    selectedRoom: SonosRoom?,
    isLoading: Boolean,
    onPlayAlbum: (PlexAlbumTracksResult, SonosRoom) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream, SonosRoom) -> Unit,
) {
    val currentTrackResult = trackResult ?: return

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(currentTrackResult.album.title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
            Text(
                text = "${currentTrackResult.album.artistName ?: "未知艺人"} · ${currentTrackResult.tracks.size} 首",
                color = AppColors.TextSecondary,
            )
            Text(
                text = selectedRoom?.let { "当前将推送到 ${it.roomName}" } ?: "尚未选择 Sonos 房间，先到上方控制台选择房间。",
                color = AppColors.TextTertiary,
            )
            if (selectedRoom != null) {
                Button(
                    onClick = { onPlayAlbum(currentTrackResult, selectedRoom) },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = AppColors.SurfaceStrong,
                    ),
                ) {
                    Text("连续播放整张专辑")
                }
            }
            currentTrackResult.tracks.forEachIndexed { index, track ->
                Surface(
                    modifier = Modifier
                        .clickable(
                            enabled = selectedRoom != null && !isLoading,
                        ) {
                            onPlayTrack(currentTrackResult.album, track, selectedRoom!!)
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = AppColors.SurfaceAlt,
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
                            Text("${index + 1}. ${track.title}", color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                            track.durationMillis?.let {
                                Text(formatDuration(it), color = AppColors.TextTertiary)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (selectedRoom != null) {
                            Button(
                                onClick = { onPlayTrack(currentTrackResult.album, track, selectedRoom) },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.AccentMuted,
                                    contentColor = AppColors.TextPrimary,
                                ),
                            ) {
                                Text(Strings.push)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SettingsSection(
    rooms: List<SonosRoom>,
    selectedRoom: SonosRoom?,
    isSonosLoading: Boolean,
    discoveryAttempted: Boolean,
    token: String,
    baseUrl: String,
    onDiscoverSonos: () -> Unit,
    onSelectRoom: (SonosRoom) -> Unit,
    onTokenChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAndRefresh: () -> Unit,
    onRefreshHome: () -> Unit,
) {
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
        Text(Strings.plexSettings, style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
        Text(
            Strings.plexSettingsDesc,
            color = AppColors.TextSecondary,
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(Strings.plexToken) },
            singleLine = true,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(Strings.plexBaseUrl) },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.SurfaceStrong,
                ),
            ) {
                Text(Strings.save)
            }
            OutlinedButton(
                onClick = onSaveAndRefresh,
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
    getNextIndex: (Int) -> Int?,
    onTrackChanged: (Int, PlexTrackStream, Long) -> Unit,
) {
    val tracks = trackResult.tracks
    require(tracks.isNotEmpty()) { "专辑里没有可播放的单曲。" }

    var index = startIndex.coerceIn(0, tracks.lastIndex)

    while (true) {
        val track = tracks[index]
        val startPositionMillis = if (index == startIndex) initialPositionMillis.coerceAtLeast(0L) else 0L
        sonosController.playTrack(
            room = room,
            trackUrl = track.streamUrl,
            title = track.title,
            albumTitle = track.albumTitle ?: trackResult.album.title,
        )
        if (startPositionMillis > 0L) {
            sonosController.seek(room, (startPositionMillis / 1_000L).toInt())
        }
        onTrackChanged(index, track, startPositionMillis)

        delay(2_000)

        waitForTrackToFinish(
            sonosController = sonosController,
            room = room,
            expectedTrackUrl = track.streamUrl,
            expectedDurationMillis = track.durationMillis,
        )

        val nextIndex = getNextIndex(index)
        if (nextIndex == null) {
            return
        }

        index = nextIndex.coerceIn(0, tracks.lastIndex)
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
        val normalizedExpected = expectedTrackUrl.substringBefore('?')
        val normalizedCurrent = currentUri.substringBefore('?')

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
    )
