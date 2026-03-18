package com.lux032.plextosonosplayer

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.lux032.plextosonosplayer.plex.PlexAlbum
import com.lux032.plextosonosplayer.plex.PlexAlbumTracksResult
import com.lux032.plextosonosplayer.plex.PlexTrackStream
import com.lux032.plextosonosplayer.plex.isFavorite
import com.lux032.plextosonosplayer.sonos.SonosController
import com.lux032.plextosonosplayer.sonos.SonosRoom
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun PlaybackDetailSection(
    state: MiniPlayerState?,
    rooms: List<SonosRoom>,
    playbackMode: PlaybackMode,
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
) {
    val currentState = state
    val currentTrack = currentState?.tracks?.getOrNull(currentState.currentIndex)
    if (currentState == null || currentTrack == null) {
        EmptyStateCard(
            title = "当前没有播放中的专辑",
            body = "先从专辑详情里播放内容，底部栏出现后再进入播放详情。",
            actionLabel = "返回首页",
            onAction = onBack,
        )
        return
    }

    val trackDurationMillis = currentState.durationMillis?.takeIf { it > 0L } ?: currentTrack.durationMillis
    var isSeeking by remember(currentTrack.ratingKey) { mutableStateOf(false) }
    var sliderPositionMillis by remember(currentTrack.ratingKey) {
        mutableStateOf(currentState.currentPositionMillis.toFloat())
    }
    var isPlaylistSheetVisible by remember { mutableStateOf(false) }
    var isModeDialogVisible by remember { mutableStateOf(false) }
    var isRoomDialogVisible by remember { mutableStateOf(false) }

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
            imageUrl = currentState.album.thumbUrl,
            title = currentState.album.title,
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
                text = currentState.album.artistName ?: "未知艺人",
                color = AppColors.Accent,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(currentState.album.artistName) },
            )
            Text(
                text = currentState.album.title,
                color = AppColors.TextSecondary,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAlbumClick(currentState.album) },
            )
            Text(
                text = "${currentState.room.roomName} · ${currentState.currentIndex + 1}/${currentState.tracks.size}",
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
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
            isFavoriteLoading = isFavoriteLoading,
            modifier = Modifier.fillMaxWidth(),
            onShowPlaylist = { isPlaylistSheetVisible = true },
            onShowModePicker = { isModeDialogVisible = true },
            onToggleTrackFavorite = { onToggleTrackFavorite(currentTrack) },
            onShowRoomPicker = { isRoomDialogVisible = true },
        )
    }

    if (isPlaylistSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { isPlaylistSheetVisible = false },
            containerColor = AppColors.Surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "播放列表",
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
                                    text = if (isCurrent) "正在播放" else currentState.album.title,
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
                    text = "播放模式",
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
                    text = "选择 Sonos 房间",
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                if (rooms.isEmpty()) {
                    Text("当前没有可用的 Sonos 房间。", color = AppColors.TextSecondary)
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
                                            text = if (room.memberCount > 1) "${room.memberCount} 个成员" else "单房间",
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
}

@Composable
private fun PlaybackActionBar(
    currentTrack: PlexTrackStream,
    playbackMode: PlaybackMode,
    isFavoriteLoading: Boolean,
    modifier: Modifier = Modifier,
    onShowPlaylist: () -> Unit,
    onShowModePicker: () -> Unit,
    onToggleTrackFavorite: () -> Unit,
    onShowRoomPicker: () -> Unit,
) {
    Row(
        modifier = modifier.padding(vertical = 0.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaybackActionButton(
            icon = Icons.Filled.QueueMusic,
            contentDescription = "播放列表",
            modifier = Modifier.weight(1f),
            isSelected = false,
            onClick = onShowPlaylist,
        )
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
            onClick = onShowModePicker,
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .clickable(enabled = !isFavoriteLoading, onClick = onToggleTrackFavorite)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FavoriteIconGraphic(
                    isFavorite = currentTrack.isFavorite,
                    tint = if (currentTrack.isFavorite) AppColors.Accent else AppColors.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        PlaybackActionButton(
            icon = Icons.Filled.Speaker,
            contentDescription = "投射房间",
            modifier = Modifier.weight(1f),
            isSelected = false,
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
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
            modifier = Modifier.size(24.dp),
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
            title = "还没有打开专辑",
            body = "先从首页选择一张收藏专辑，再进入专辑详情页。",
            actionLabel = "返回首页",
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
            val artistName = currentTrackResult.album.artistName ?: "未知艺人"
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
                    append("${currentTrackResult.tracks.size} 首")
                },
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = selectedRoom?.let { "当前将推送到 ${it.roomName}" } ?: "尚未选择 Sonos 房间，请先到设置页选择房间。",
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
                    modifier = Modifier.clickable(
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
internal fun BottomMiniPlayer(
    state: MiniPlayerState,
    modifier: Modifier = Modifier,
    onArtworkClick: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val currentTrack = state.tracks.getOrNull(state.currentIndex) ?: return
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onArtworkClick),
            ) {
                AsyncAlbumArtwork(
                    imageUrl = state.album.thumbUrl,
                    title = state.album.title,
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

    Surface(
        modifier = Modifier
            .size(buttonSize)
            .clickable(enabled = enabled, onClick = onClick),
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
                    modifier = Modifier.clickable(
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
                                Text("推送")
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
        Text("Plex 设置", style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
        Text(
            "Plex 连接信息放在 Sonos 设置下面。保存后会持久化到本地，下次启动会自动读取。",
            color = AppColors.TextSecondary,
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Token（可选，优先）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Base URL（可选）") },
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
                Text("保存")
            }
            OutlinedButton(
                onClick = onSaveAndRefresh,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AppColors.BorderStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
            ) {
                Text("保存并刷新首页")
            }
        }
        OutlinedButton(
            onClick = onRefreshHome,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, AppColors.BorderStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
        ) {
            Text("刷新首页")
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
            albumTitle = trackResult.album.title,
        )
        if (startPositionMillis > 0L) {
            sonosController.seek(room, (startPositionMillis / 1_000L).toInt())
        }
        onTrackChanged(index, track, startPositionMillis)

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
        }

        if (status.transportState.equals("STOPPED", ignoreCase = true) && stableProgressSeen) {
            return
        }

        if (relTimeSeconds != null && trackDurationSeconds != null && relTimeSeconds >= trackDurationSeconds - 1) {
            return
        }
    }
}
