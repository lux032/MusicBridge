package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
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
    onOpenDownloadedTracks: () -> Unit,
    isAiGenerating: Boolean,
    onGenerateAiPlaylist: (String) -> Unit,
    onCancelAiGeneration: () -> Unit,
    isSyncing: Boolean = false,
    syncProgress: Pair<Int, Int> = 0 to 0,
    onSyncAllPlaylists: () -> Unit = {},
) {
    var showAiDialog by remember { mutableStateOf(false) }
    if (showAiDialog) AiPlaylistDialog(isAiGenerating, onCancelAiGeneration, { prompt -> onGenerateAiPlaylist(prompt) }) { showAiDialog = false }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Strings.playlists,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                IconButton(onClick = onSyncAllPlaylists, enabled = playlists.isNotEmpty() && !isSyncing) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Sync, contentDescription = "同步所有歌单")
                    }
                }
            }
            if (isSyncing) {
                val (current, total) = syncProgress
                Text("正在同步歌单 $current/$total", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
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
            DownloadedTracksCard(onClick = onOpenDownloadedTracks)
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
private fun DownloadedTracksCard(
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
                    text = Strings.downloadedTracksTitle,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = Strings.downloadedTracksDesc,
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = LocalArtworkPalette.current.accent,
                modifier = Modifier.size(24.dp),
            )
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
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (PlexTrackStream) -> Unit = {},
    onDeleteDownloadedTrack: (PlexTrackStream) -> Unit = {},
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
    val trackListState = androidx.compose.foundation.lazy.rememberLazyListState()

    Column(modifier = Modifier.fillMaxSize()) {
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
            BackCircleButton(onClick = onBack, contentDescription = Strings.backToPlaylists)
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
                    PlaylistDetailHeroWide(
                        playlistResult = currentResult,
                        outputTargetName = outputTargetName,
                        isPlaybackLoading = isPlaybackLoading,
                        aiDescription = aiDescription,
                        onSaveToPlex = onSaveToPlex,
                        onPlayPlaylist = onPlayPlaylist,
                        onShufflePlaylist = onShufflePlaylist,
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
                        items = currentResult.tracks,
                        key = { index, track -> track.ratingKey ?: index },
                        contentType = { _, _ -> "track" },
                    ) { index, track ->
                        Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                            PlaylistDetailTrackRow(
                                index = index,
                                playlist = currentResult.playlist,
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
                                onSonicRadio = onSonicRadio,
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
                item(key = "playlist-summary", contentType = "playlist-summary") {
                    PlaylistDetailHeroCompact(
                        playlistResult = currentResult,
                        isPlaybackLoading = isPlaybackLoading,
                        aiDescription = aiDescription,
                        onSaveToPlex = onSaveToPlex,
                        onPlayPlaylist = onPlayPlaylist,
                        onShufflePlaylist = onShufflePlaylist,
                    )
                }
                itemsIndexed(
                    items = currentResult.tracks,
                    key = { index, track -> track.ratingKey ?: index },
                    contentType = { _, _ -> "track" },
                ) { index, track ->
                    Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        PlaylistDetailTrackRow(
                            index = index,
                            playlist = currentResult.playlist,
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
                            onSonicRadio = onSonicRadio,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetailHeroWide(
    playlistResult: PlexPlaylistTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    aiDescription: String?,
    onSaveToPlex: () -> Unit,
    onPlayPlaylist: (PlexPlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlexPlaylistTracksResult) -> Unit,
) {
    // Shares the root now-playing tint so this page stays consistent with the mini player
    // and bottom bar.
    val palette = LocalArtworkPalette.current
    val playlist = playlistResult.playlist
    val playEnabled = !isPlaybackLoading && playlistResult.tracks.isNotEmpty()

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        AsyncAlbumArtwork(
            imageUrl = playlist.thumbUrl,
            title = playlist.title,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            thumbnailSize = 300.dp,
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            playlist.title,
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.TextPrimary,
            fontWeight = FontWeight.Bold,
        )
        if (playlist.ratingKey.startsWith("ai:")) {
            aiDescription?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.TextSecondary) }
            OutlinedButton(onClick = onSaveToPlex) { Text(Strings.saveToPlex) }
        }
        Text(
            text = Strings.tracks(playlistResult.tracks.size),
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
                onClick = { onPlayPlaylist(playlistResult) },
                enabled = playEnabled,
                containerColor = if (playEnabled) palette.accent else palette.accent.copy(alpha = 0.35f),
            ) {
                PlayerIconGraphic(
                    icon = PlayerIcon.Play,
                    tint = palette.onAccent.copy(alpha = if (playEnabled) 1f else 0.6f),
                    modifier = Modifier.size(22.dp),
                )
            }
            IconCircleButton(
                onClick = { onShufflePlaylist(playlistResult) },
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

@Composable
private fun PlaylistDetailHeroCompact(
    playlistResult: PlexPlaylistTracksResult,
    isPlaybackLoading: Boolean,
    aiDescription: String?,
    onSaveToPlex: () -> Unit,
    onPlayPlaylist: (PlexPlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlexPlaylistTracksResult) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val palette = LocalArtworkPalette.current
    val playlist = playlistResult.playlist
    val playEnabled = !isPlaybackLoading && playlistResult.tracks.isNotEmpty()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = playlist.thumbUrl,
                title = playlist.title,
                modifier = Modifier
                    .size(layoutInfo.albumDetailCompactArtworkSize)
                    .clip(RoundedCornerShape(14.dp)),
                thumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    text = playlist.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Strings.tracks(playlistResult.tracks.size),
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
                        onClick = { onPlayPlaylist(playlistResult) },
                        enabled = playEnabled,
                        containerColor = if (playEnabled) palette.accent else palette.accent.copy(alpha = 0.35f),
                    ) {
                        PlayerIconGraphic(
                            icon = PlayerIcon.Play,
                            tint = palette.onAccent.copy(alpha = if (playEnabled) 1f else 0.6f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconCircleButton(
                        onClick = { onShufflePlaylist(playlistResult) },
                        enabled = !isPlaybackLoading,
                        highlighted = false,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Shuffle,
                            contentDescription = Strings.shuffle,
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
        if (playlist.ratingKey.startsWith("ai:")) {
            aiDescription?.takeIf { it.isNotBlank() }?.let { Text(it, color = AppColors.TextSecondary) }
            OutlinedButton(onClick = onSaveToPlex) { Text(Strings.saveToPlex) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetailTrackRow(
    index: Int,
    playlist: PlexPlaylist,
    track: PlexTrackStream,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    downloadedTrackKeys: Set<String>,
    downloadProgress: Map<String, Float>,
    onDownloadTrack: (PlexTrackStream) -> Unit,
    onDeleteDownloadedTrack: (PlexTrackStream) -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    onPlayTrack: (PlexPlaylist, PlexTrackStream, Int) -> Unit,
    onSonicRadio: (PlexTrackStream) -> Unit,
) {
    var showQueueMenu by remember { mutableStateOf(false) }
    val isDownloaded = remember(track.sourceId, track.ratingKey, downloadedTrackKeys) {
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val progress = downloadProgress["${track.sourceId}:${track.ratingKey}"]

    Box {
        Surface(
            modifier = Modifier
                .combinedClickable(
                    enabled = !isPlaybackLoading,
                    onClick = {
                        onPlayTrack(playlist, track, index)
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("${index + 1}. ${track.title}", color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
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
                            onClick = { onPlayTrack(playlist, track, index) },
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
            isDownloaded = isDownloaded,
            onDownloadAction = {
                if (isDownloaded) onDeleteDownloadedTrack(track) else onDownloadTrack(track)
            },
        )
    }
}
