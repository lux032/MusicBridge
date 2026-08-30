package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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

@Composable
internal fun PlaylistsSection(
    playlists: List<PlexPlaylist>,
    onGoHome: () -> Unit,
    onPlaylistClick: (PlexPlaylist) -> Unit,
    onOpenFavorites: () -> Unit,
    isAiGenerating: Boolean,
    onGenerateAiPlaylist: (String) -> Unit,
    onCancelAiGeneration: () -> Unit,
) {
    var showAiDialog by remember { mutableStateOf(false) }
    if (showAiDialog) AiPlaylistDialog(isAiGenerating, onCancelAiGeneration, { prompt -> onGenerateAiPlaylist(prompt) }) { showAiDialog = false }
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
            Surface(modifier = Modifier.fillMaxWidth().clickable { showAiDialog = true }, shape = RoundedCornerShape(20.dp), color = LocalArtworkPalette.current.accent.copy(alpha = .15f), border = BorderStroke(1.dp, LocalArtworkPalette.current.accent)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Strings.aiPlaylist, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(Strings.aiPlaylistDesc, color = AppColors.TextSecondary)
                }
            }
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
            items(playlists, key = { it.ratingKey }, contentType = { "playlist" }) { playlist ->
                // Placement-only animation: inserted/removed playlists shift siblings smoothly
                // without an extra fade on top of the page transition.
                Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                    PlaylistCard(playlist = playlist, onClick = { onPlaylistClick(playlist) })
                }
            }
        }
    }
}

@Composable
private fun FavoriteTracksCard(
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.CardSurface,
        border = BorderStroke(1.dp, AppColors.Border),
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
                tint = LocalArtworkPalette.current.accent,
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
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.CardSurface,
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
                thumbnailSize = 68.dp,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaylistDetailSection(
    playlistResult: PlexPlaylistTracksResult?,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    onPlayPlaylist: (PlexPlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlexPlaylistTracksResult) -> Unit,
    onPlayTrack: (PlexPlaylist, PlexTrackStream, Int) -> Unit,
    aiDescription: String? = null,
    onSaveToPlex: () -> Unit = {},
    onSonicRadio: (PlexTrackStream) -> Unit = {},
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
    val layoutInfo = LocalAppLayoutInfo.current
    // Shares the root now-playing tint so this page stays consistent with the mini player
    // and bottom bar.
    val palette = LocalArtworkPalette.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layoutInfo.horizontalPadding,
            top = 12.dp,
            end = layoutInfo.horizontalPadding,
            bottom = bottomContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            BackCircleButton(onClick = onBack, contentDescription = Strings.backToPlaylists)
        }

        item {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncAlbumArtwork(
                    imageUrl = currentResult.playlist.thumbUrl,
                    title = currentResult.playlist.title,
                    modifier = Modifier
                        .widthIn(max = layoutInfo.detailArtworkMaximumWidth)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    thumbnailSize = layoutInfo.detailArtworkMaximumWidth,
                )
            }
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
                if (currentResult.playlist.ratingKey.startsWith("ai:")) {
                    aiDescription?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.TextSecondary) }
                    OutlinedButton(onClick = onSaveToPlex) { Text(Strings.saveToPlex) }
                }
                Text(
                    text = Strings.tracks(currentResult.tracks.size),
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
                    IconCircleButton(
                        onClick = { onPlayPlaylist(currentResult) },
                        enabled = !isPlaybackLoading,
                        containerColor = palette.accent,
                    ) {
                        PlayerIconGraphic(
                            icon = PlayerIcon.Play,
                            tint = palette.onAccent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    IconCircleButton(
                        onClick = { onShufflePlaylist(currentResult) },
                        enabled = !isPlaybackLoading,
                        highlighted = false,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = Strings.shuffle,
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        itemsIndexed(
            currentResult.tracks,
            key = { index, track -> track.ratingKey ?: index },
            contentType = { _, _ -> "track" },
        ) { index, track ->
            var showQueueMenu by remember { mutableStateOf(false) }
            Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
            Surface(
                modifier = Modifier
                    .combinedClickable(
                        enabled = !isPlaybackLoading,
                        onClick = {
                            onPlayTrack(currentResult.playlist, track, index)
                        },
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
                            if (track.sourceId.isNotBlank()) {
                                TextButton(onClick = { onSonicRadio(track) }) { Text(Strings.sonicRadio) }
                            }
                            FavoriteIconButton(
                                isFavorite = track.isFavorite,
                                isLoading = isFavoriteLoading,
                                onClick = { onToggleTrackFavorite(track) },
                            )
                            IconCircleButton(
                                onClick = { onPlayTrack(currentResult.playlist, track, index) },
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
    }
}
