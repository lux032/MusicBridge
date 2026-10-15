package com.lux032.musicbridge

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
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    canCreatePlaylist: Boolean = false,
    onCreatePlaylist: suspend (name: String, description: String) -> Result<PlexPlaylist>? = { _, _ -> null },
    onOpenCreatedPlaylist: (PlexPlaylist) -> Unit = {},
) {
    var showAiDialog by remember { mutableStateOf(false) }
    var showCreateSheet by rememberSaveable { mutableStateOf(false) }
    if (showAiDialog) AiPlaylistDialog(isAiGenerating, onCancelAiGeneration, { prompt -> onGenerateAiPlaylist(prompt) }) { showAiDialog = false }
    if (showCreateSheet) {
        var created by remember { mutableStateOf<PlexPlaylist?>(null) }
        PlaylistEditSheet(
            title = Strings.newPlaylist,
            confirmLabel = Strings.createPlaylist,
            initialName = "",
            initialDescription = "",
            onSubmit = { name, description ->
                (onCreatePlaylist(name, description) ?: Result.failure(IllegalStateException(Strings.playlistEditUnsupported)))
                    .onSuccess { created = it }
            },
            // Open the new playlist once the sheet has finished sliding away.
            onDismiss = {
                showCreateSheet = false
                created?.let(onOpenCreatedPlaylist)
            },
        )
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 8.dp),
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
                    modifier = Modifier.weight(1f),
                )
                if (isSyncing) {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = LocalArtworkPalette.current.accent,
                        )
                    }
                } else {
                    BareIcon(
                        icon = Icons.Filled.Sync,
                        contentDescription = "同步所有歌单",
                        onClick = onSyncAllPlaylists,
                        enabled = playlists.isNotEmpty(),
                        modifier = if (canCreatePlaylist) Modifier else Modifier.offset(x = 12.dp),
                    )
                }
                if (canCreatePlaylist) {
                    // Secondary tier: creating is frequent enough to sit in the header, but the
                    // page's content (the playlists) stays the focus.
                    LabelKey(
                        text = Strings.newPlaylist,
                        icon = Icons.Filled.Add,
                        onClick = { showCreateSheet = true },
                        height = 40.dp,
                        shape = RoundedCornerShape(14.dp),
                    )
                }
            }
            if (isSyncing) {
                val (current, total) = syncProgress
                Text("正在同步歌单 $current/$total", color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(16.dp))
        }
        // Quick entries as three tonal keys, matching the home page's quick keys.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlaylistQuickKey(
                    icon = Icons.Filled.AutoAwesome,
                    label = Strings.aiPlaylist,
                    accent = true,
                    onClick = { showAiDialog = true },
                    modifier = Modifier.weight(1f),
                )
                PlaylistQuickKey(
                    icon = Icons.Filled.Favorite,
                    label = Strings.myFavoriteTracks,
                    onClick = onOpenFavorites,
                    modifier = Modifier.weight(1f),
                )
                PlaylistQuickKey(
                    icon = Icons.Filled.DownloadDone,
                    label = Strings.downloadedTracksTitle,
                    onClick = onOpenDownloadedTracks,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        if (playlists.isEmpty()) {
            item {
                if (canCreatePlaylist) {
                    EmptyStateCard(
                        title = Strings.noPlaylistsYet,
                        body = Strings.noPlaylistsYetDesc,
                        actionLabel = Strings.newPlaylist,
                        onAction = { showCreateSheet = true },
                    )
                } else {
                    EmptyStateCard(
                        title = Strings.noPlaylists,
                        body = Strings.noPlaylistsDesc,
                        actionLabel = Strings.goHome,
                        onAction = onGoHome,
                    )
                }
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
private fun PlaylistQuickKey(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    Key(
        onClick = onClick,
        modifier = modifier.height(84.dp),
        style = KeyStyle.Tonal,
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (accent) LocalArtworkPalette.current.accent else AppColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = label,
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PlaylistCard(
    playlist: PlexPlaylist,
    onClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = playlist.thumbUrl,
                title = playlist.title,
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(10.dp)),
                thumbnailSize = 60.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = playlist.title,
                    color = AppColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Strings.tracks(playlist.leafCount),
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextTertiary,
            )
        }
        Hairline(modifier = Modifier.fillMaxWidth().padding(start = 74.dp))
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
    canEditPlaylist: Boolean = false,
    onRemoveTrack: (PlexTrackStream) -> Unit = {},
    onUpdatePlaylist: suspend (PlexPlaylist, String, String) -> Result<*> = { _, _, _ -> Result.success(Unit) },
    onDeletePlaylist: suspend (PlexPlaylist) -> Result<*> = { Result.success(Unit) },
    nowPlaying: NowPlayingMarker? = LocalNowPlaying.current,
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
    var showPlaylistMenu by remember { mutableStateOf(false) }
    var showEditSheet by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    val editable = canEditPlaylist
    val removeTrack: ((PlexTrackStream) -> Unit)? = if (editable) onRemoveTrack else null

    if (editable && showEditSheet) {
        PlaylistEditSheet(
            title = Strings.editPlaylist,
            confirmLabel = Strings.save,
            initialName = currentResult.playlist.title,
            initialDescription = currentResult.playlist.description,
            onSubmit = { name, description -> onUpdatePlaylist(currentResult.playlist, name, description) },
            onDismiss = { showEditSheet = false },
        )
    }
    if (editable && showDeleteDialog) {
        DeletePlaylistDialog(
            playlist = currentResult.playlist,
            onConfirm = { onDeletePlaylist(currentResult.playlist) },
            onDismiss = { showDeleteDialog = false },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = layoutInfo.horizontalPadding,
                    top = 12.dp,
                    end = layoutInfo.horizontalPadding,
                    bottom = 4.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackCircleButton(
                onClick = onBack,
                contentDescription = Strings.backToPlaylists,
                modifier = Modifier.weight(1f),
            )
            if (editable) {
                Box {
                    BareIcon(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = Strings.playlistActions,
                        onClick = { showPlaylistMenu = true },
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.offset(x = 12.dp),
                    )
                    DropdownMenu(
                        expanded = showPlaylistMenu,
                        onDismissRequest = { showPlaylistMenu = false },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text(Strings.editPlaylist) },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = {
                                showPlaylistMenu = false
                                showEditSheet = true
                            },
                        )
                        HorizontalDivider(color = KeyColors.Line, modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(Strings.deletePlaylist, color = AppColors.ErrorText) },
                            leadingIcon = { Icon(Icons.Filled.DeleteOutline, contentDescription = null, tint = AppColors.ErrorText) },
                            onClick = {
                                showPlaylistMenu = false
                                showDeleteDialog = true
                            },
                        )
                    }
                }
            }
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
                    if (editable && currentResult.tracks.isEmpty()) EmptyPlaylistHint()
                }
                LazyColumn(
                    state = trackListState,
                    modifier = Modifier
                        .weight(1.18f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding),
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
                                onRemoveFromPlaylist = removeTrack,
                                nowPlaying = nowPlaying?.takeIf { it.matches(track) },
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
                item(key = "playlist-summary", contentType = "playlist-summary") {
                    PlaylistDetailHeroCompact(
                        playlistResult = currentResult,
                        outputTargetName = outputTargetName,
                        isPlaybackLoading = isPlaybackLoading,
                        aiDescription = aiDescription,
                        onSaveToPlex = onSaveToPlex,
                        onPlayPlaylist = onPlayPlaylist,
                        onShufflePlaylist = onShufflePlaylist,
                    )
                }
                if (editable && currentResult.tracks.isEmpty()) {
                    item(key = "playlist-empty", contentType = "playlist-empty") { EmptyPlaylistHint() }
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
                            onRemoveFromPlaylist = removeTrack,
                            nowPlaying = nowPlaying?.takeIf { it.matches(track) },
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
    val playlist = playlistResult.playlist
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
                .clip(RoundedCornerShape(14.dp)),
            thumbnailSize = 300.dp,
        )
    }
    PlaylistHeroText(playlistResult, aiDescription, onSaveToPlex, centered = false)
    PlaylistActionRow(playlistResult, outputTargetName, isPlaybackLoading, onPlayPlaylist, onShufflePlaylist)
}

@Composable
private fun PlaylistDetailHeroCompact(
    playlistResult: PlexPlaylistTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    aiDescription: String?,
    onSaveToPlex: () -> Unit,
    onPlayPlaylist: (PlexPlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlexPlaylistTracksResult) -> Unit,
) {
    val playlist = playlistResult.playlist
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncAlbumArtwork(
            imageUrl = playlist.thumbUrl,
            title = playlist.title,
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(0.6f)
                .widthIn(max = 280.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
            thumbnailSize = 280.dp,
        )
        Spacer(Modifier.height(18.dp))
        PlaylistHeroText(playlistResult, aiDescription, onSaveToPlex, centered = true)
        Spacer(Modifier.height(18.dp))
        PlaylistActionRow(playlistResult, outputTargetName, isPlaybackLoading, onPlayPlaylist, onShufflePlaylist)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PlaylistHeroText(
    playlistResult: PlexPlaylistTracksResult,
    aiDescription: String?,
    onSaveToPlex: () -> Unit,
    centered: Boolean,
) {
    val playlist = playlistResult.playlist
    val align = if (centered) TextAlign.Center else TextAlign.Start
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            playlist.title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.TextPrimary,
            textAlign = align,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = buildString {
                append(Strings.tracks(playlistResult.tracks.size))
                val totalMillis = playlistResult.tracks.sumOf { it.durationMillis ?: 0L }
                if (totalMillis > 0L) append(" · ").append(formatDuration(totalMillis))
            },
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
        )
        if (playlist.description.isNotBlank() && !playlist.ratingKey.startsWith("ai:")) {
            Text(
                playlist.description,
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = align,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playlist.ratingKey.startsWith("ai:")) {
            aiDescription?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = align)
            }
            LabelKey(
                text = Strings.saveToPlex,
                icon = Icons.Filled.CloudUpload,
                onClick = onSaveToPlex,
                height = 40.dp,
            )
        }
    }
}

@Composable
private fun PlaylistActionRow(
    playlistResult: PlexPlaylistTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    onPlayPlaylist: (PlexPlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlexPlaylistTracksResult) -> Unit,
) {
    SplitPlayKey(
        label = Strings.playOn(outputTargetName),
        onPlay = { onPlayPlaylist(playlistResult) },
        onShuffle = { onShufflePlaylist(playlistResult) },
        enabled = !isPlaybackLoading && playlistResult.tracks.isNotEmpty(),
        isLoading = isPlaybackLoading,
        modifier = Modifier.fillMaxWidth(),
    )
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
    onRemoveFromPlaylist: ((PlexTrackStream) -> Unit)? = null,
    nowPlaying: NowPlayingMarker? = null,
) {
    var showQueueMenu by remember { mutableStateOf(false) }
    val isCurrent = nowPlaying != null
    val isDownloaded = remember(track.sourceId, track.ratingKey, downloadedTrackKeys) {
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val progress = downloadProgress["${track.sourceId}:${track.ratingKey}"]
    val palette = LocalArtworkPalette.current
    val subtitle = listOfNotNull(
        track.artistName?.takeIf { it.isNotBlank() },
        track.durationMillis?.let { formatDuration(it) },
    ).joinToString(" · ")

    // Editorial hairline row: number · title / artist · duration · favourite · more.
    Box {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        enabled = !isPlaybackLoading,
                        onClick = { onPlayTrack(playlist, track, index) },
                        onLongClick = { showQueueMenu = true },
                    )
                    .padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackNumberSlot(
                    text = "${index + 1}",
                    nowPlaying = nowPlaying,
                    modifier = Modifier.width(24.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = track.title,
                        color = nowPlayingTitleColor(isCurrent),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
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
                        if (subtitle.isNotEmpty()) {
                            Text(
                                subtitle,
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
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
            onSonicRadio = if (track.sourceId.isNotBlank()) {
                { onSonicRadio(track) }
            } else {
                null
            },
            onAddToPlaylist = addTrackToPlaylistAction(track),
            onRemoveFromPlaylist = onRemoveFromPlaylist?.let { remove -> { remove(track) } },
        )
    }
}

/** Empty server playlist: says what to do next instead of showing a blank list. */
@Composable
private fun EmptyPlaylistHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Hairline(Modifier.fillMaxWidth())
        Spacer(Modifier.height(14.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.LibraryAdd,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(22.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Strings.emptyPlaylistTitle, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleMedium)
                Text(Strings.emptyPlaylistBody, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
