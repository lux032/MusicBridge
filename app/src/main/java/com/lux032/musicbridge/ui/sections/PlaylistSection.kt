package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.sonos.SonosRoom

@Composable
internal fun PlaylistsSection(
    playlists: List<PlexPlaylist>,
    onGoHome: () -> Unit,
    onPlaylistClick: (PlexPlaylist) -> Unit,
    onOpenFavorites: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                text = Strings.playlists,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            FavoriteTracksCard(onClick = onOpenFavorites)
        }
        if (playlists.isEmpty()) {
            item {
                EmptyStateCard(
                    title = Strings.noPlaylists,
                    body = Strings.noPlaylistsDesc,
                    actionLabel = Strings.goHome,
                    onAction = onGoHome,
                )
            }
        } else {
            items(playlists, key = { it.ratingKey }) { playlist ->
                PlaylistCard(playlist = playlist, onClick = { onPlaylistClick(playlist) })
            }
        }
    }
}

@Composable
private fun FavoriteTracksCard(
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "favorite_tracks_scale",
    )
    Surface(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(20.dp),
        color = AppColors.SurfaceStrong,
        border = BorderStroke(1.dp, AppColors.BorderStrong),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = Strings.myFavoriteTracks,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = Strings.myFavoriteTracksDesc,
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FavoriteIconGraphic(
                isFavorite = true,
                tint = AppColors.Accent,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlexPlaylist,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "playlist_scale",
    )
    Surface(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = RoundedCornerShape(20.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = playlist.thumbUrl,
                title = playlist.title,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = playlist.title,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Strings.tracks(playlist.leafCount),
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
internal fun PlaylistDetailSection(
    playlistResult: PlexPlaylistTracksResult?,
    selectedRoom: SonosRoom?,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    onBack: () -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onPlayPlaylist: (PlexPlaylistTracksResult, SonosRoom) -> Unit,
    onShufflePlaylist: (PlexPlaylistTracksResult, SonosRoom) -> Unit,
    onPlayTrack: (PlexPlaylist, PlexTrackStream, SonosRoom, Int) -> Unit,
) {
    val currentResult = playlistResult
    if (currentResult == null) {
        EmptyStateCard(
            title = Strings.playlistDetail,
            body = Strings.noPlaylistsDesc,
            actionLabel = Strings.backToPlaylists,
            onAction = onBack,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            OutlinedButton(onClick = onBack) {
                Text(Strings.backToPlaylists)
            }
        }

        item {
            AsyncAlbumArtwork(
                imageUrl = currentResult.playlist.thumbUrl,
                title = currentResult.playlist.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.3f)
                    .clip(RoundedCornerShape(16.dp)),
            )
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    currentResult.playlist.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = Strings.tracks(currentResult.tracks.size),
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
                    IconCircleButton(
                        onClick = { selectedRoom?.let { onPlayPlaylist(currentResult, it) } },
                        enabled = selectedRoom != null && !isPlaybackLoading,
                        highlighted = true,
                    ) {
                        PlayerIconGraphic(
                            icon = PlayerIcon.Play,
                            tint = if (selectedRoom != null) AppColors.TextPrimary else AppColors.TextTertiary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconCircleButton(
                        onClick = { selectedRoom?.let { onShufflePlaylist(currentResult, it) } },
                        enabled = selectedRoom != null && !isPlaybackLoading,
                        highlighted = false,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = Strings.shuffle,
                            tint = if (selectedRoom != null) AppColors.TextPrimary else AppColors.TextTertiary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        itemsIndexed(currentResult.tracks, key = { index, track -> track.ratingKey ?: index }) { index, track ->
            Surface(
                modifier = Modifier
                    .clickable(
                        enabled = selectedRoom != null && !isPlaybackLoading,
                    ) {
                        onPlayTrack(currentResult.playlist, track, selectedRoom!!, index)
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
                                onClick = { selectedRoom?.let { onPlayTrack(currentResult.playlist, track, it, index) } },
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
