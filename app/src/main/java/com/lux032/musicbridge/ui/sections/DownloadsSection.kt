package com.lux032.musicbridge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.storage.DownloadedAlbumGroup
import com.lux032.musicbridge.storage.DownloadedAudioTrack
import java.util.Locale

enum class DownloadSortOrder {
    Recent,
    Name,
    Size,
}

@Composable
internal fun DownloadsSection(
    albums: List<DownloadedAlbumGroup>,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlayAlbum: (DownloadedAlbumGroup) -> Unit,
    onPlayTrack: (DownloadedAlbumGroup, DownloadedAudioTrack, Int) -> Unit,
    onDeleteAlbum: (DownloadedAlbumGroup) -> Unit,
    onDeleteTrack: (DownloadedAudioTrack) -> Unit,
    onShuffleAll: () -> Unit,
    nowPlaying: NowPlayingMarker? = LocalNowPlaying.current,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val palette = LocalArtworkPalette.current
    var sortOrder by rememberSaveable { mutableStateOf(DownloadSortOrder.Recent) }
    var isSortMenuExpanded by remember { mutableStateOf(false) }

    val sortedAlbums = remember(albums, sortOrder) {
        when (sortOrder) {
            DownloadSortOrder.Recent -> albums.sortedByDescending { it.latestDownloadedAt }
            DownloadSortOrder.Name -> albums.sortedBy { it.albumTitle.lowercase(Locale.ROOT) }
            DownloadSortOrder.Size -> albums.sortedByDescending { it.totalSizeBytes }
        }
    }

    val totalTracks = remember(albums) { albums.sumOf { it.tracks.size } }
    val totalBytes = remember(albums) { albums.sumOf { it.totalSizeBytes } }

    Column(modifier = modifier.fillMaxSize()) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = layoutInfo.horizontalPadding,
                    top = 12.dp,
                    end = layoutInfo.horizontalPadding,
                    bottom = 4.dp,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackCircleButton(onClick = onBack, contentDescription = Strings.back)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Sort Menu Button
                Box {
                    BareIcon(
                        icon = Icons.Filled.Sort,
                        contentDescription = Strings.sortFieldTitle,
                        onClick = { isSortMenuExpanded = true },
                        modifier = Modifier.offset(x = 12.dp),
                    )

                    DropdownMenu(
                        expanded = isSortMenuExpanded,
                        onDismissRequest = { isSortMenuExpanded = false },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    Strings.sortRecentDownloaded,
                                    fontWeight = if (sortOrder == DownloadSortOrder.Recent) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                sortOrder = DownloadSortOrder.Recent
                                isSortMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    Strings.sortAlbumName,
                                    fontWeight = if (sortOrder == DownloadSortOrder.Name) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                sortOrder = DownloadSortOrder.Name
                                isSortMenuExpanded = false
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    Strings.sortStorageSize,
                                    fontWeight = if (sortOrder == DownloadSortOrder.Size) FontWeight.Bold else FontWeight.Normal,
                                )
                            },
                            onClick = {
                                sortOrder = DownloadSortOrder.Size
                                isSortMenuExpanded = false
                            },
                        )
                    }
                }

            }
        }

        // Header Title & Statistics
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = layoutInfo.horizontalPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = Strings.offlineDownloadsTitle,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
            )
            if (albums.isNotEmpty()) {
                val statsText = "${albums.size} 张专辑 · $totalTracks 首单曲 · 共 ${formatFileSize(totalBytes)}"
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextTertiary,
                )
                Spacer(Modifier.height(12.dp))
                LabelKey(
                    text = Strings.shuffleAllOffline,
                    icon = Icons.Filled.Shuffle,
                    onClick = onShuffleAll,
                    style = KeyStyle.Hi,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (albums.isEmpty()) {
            EmptyStateCard(
                title = Strings.noOfflineMusic,
                body = Strings.noOfflineMusicDesc,
                actionLabel = Strings.backToHome,
                onAction = onBack,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = layoutInfo.horizontalPadding,
                    end = layoutInfo.horizontalPadding,
                    top = 8.dp,
                    bottom = bottomContentPadding,
                ),
            ) {
                items(
                    items = sortedAlbums,
                    key = { "${it.sourceId}:${it.albumRatingKey.ifBlank { it.albumTitle }}" },
                ) { group ->
                    DownloadedAlbumCard(
                        group = group,
                        onPlayAlbum = { onPlayAlbum(group) },
                        onPlayTrack = { track, index -> onPlayTrack(group, track, index) },
                        onDeleteAlbum = { onDeleteAlbum(group) },
                        onDeleteTrack = onDeleteTrack,
                        nowPlaying = nowPlaying?.takeIf { marker ->
                            marker.matchesAlbum(group.sourceId, group.albumRatingKey) ||
                                group.tracks.any { marker.matches(it) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadedAlbumCard(
    group: DownloadedAlbumGroup,
    onPlayAlbum: () -> Unit,
    onPlayTrack: (DownloadedAudioTrack, Int) -> Unit,
    onDeleteAlbum: () -> Unit,
    onDeleteTrack: (DownloadedAudioTrack) -> Unit,
    nowPlaying: NowPlayingMarker? = null,
) {
    var isExpanded by rememberSaveable(group.albumRatingKey) { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val palette = LocalArtworkPalette.current

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Album summary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Cover
                Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(10.dp))) {
                    AsyncAlbumArtwork(
                        imageUrl = group.thumbPath?.let { "file://$it" },
                        title = group.albumTitle,
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Crop,
                        thumbnailSize = 72.dp,
                        placeholderThumbnailSize = null,
                    )
                    NowPlayingArtworkScrim(nowPlaying)
                }

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = group.albumTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = group.artistName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${group.tracks.size} 首歌曲 · ${formatFileSize(group.totalSizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                    )
                }

                // Trailing actions
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconKey(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = Strings.play,
                        onClick = onPlayAlbum,
                        size = 44.dp,
                    )
                    BareIcon(
                        icon = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (isExpanded) "折叠" else "展开",
                        onClick = { isExpanded = !isExpanded },
                        size = 44.dp,
                    )
                    Box {
                        BareIcon(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = Strings.more,
                            onClick = { isMenuExpanded = true },
                            size = 44.dp,
                            iconSize = 20.dp,
                            modifier = Modifier.offset(x = 8.dp),
                        )

                        DropdownMenu(
                            expanded = isMenuExpanded,
                            onDismissRequest = { isMenuExpanded = false },
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text(Strings.deleteAlbumDownload, color = AppColors.ErrorText) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = null,
                                        tint = AppColors.ErrorText,
                                    )
                                },
                                onClick = {
                                    isMenuExpanded = false
                                    showDeleteConfirmDialog = true
                                },
                            )
                        }
                    }
                }
            }

            // Expandable Track List
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 66.dp, bottom = 4.dp),
                ) {

                    val hasMultipleDiscs = group.tracks.mapNotNull { it.discNumber }.distinct().size > 1
                    group.tracks.forEachIndexed { index, track ->
                        val displayIndex = if (hasMultipleDiscs && track.discNumber != null) {
                            "${track.discNumber}-${track.trackNumber ?: (index + 1)}"
                        } else if (track.trackNumber != null) {
                            "${track.trackNumber}"
                        } else {
                            "${index + 1}"
                        }
                        DownloadedTrackItem(
                            displayIndex = displayIndex,
                            track = track,
                            onPlay = { onPlayTrack(track, index) },
                            onDelete = { onDeleteTrack(track) },
                            nowPlaying = nowPlaying?.takeIf { it.matches(track) },
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
        Hairline(modifier = Modifier.fillMaxWidth().padding(start = 74.dp))
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text(Strings.deleteAlbumDownload) },
            text = { Text(Strings.confirmDeleteAlbumDownload) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirmDialog = false
                    onDeleteAlbum()
                }) {
                    Text(Strings.confirmDelete, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSurface,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
}

@Composable
private fun DownloadedTrackItem(
    displayIndex: String,
    track: DownloadedAudioTrack,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    nowPlaying: NowPlayingMarker? = null,
) {
    var showTrackDeleteDialog by remember { mutableStateOf(false) }
    val isCurrent = nowPlaying != null

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay)
            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TrackNumberSlot(
                text = displayIndex,
                nowPlaying = nowPlaying,
                style = MaterialTheme.typography.bodySmall,
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier.widthIn(min = 22.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = nowPlayingTitleColor(isCurrent),
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    track.durationMillis?.let { duration ->
                        Text(
                            text = formatDuration(duration),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextTertiary,
                        )
                    }
                    val codecTag = buildString {
                        track.sourceAudioCodec?.takeIf { it.isNotBlank() }?.let { append(it.uppercase(Locale.ROOT)) }
                        if (track.bitDepth != null && track.sourceAudioSampleRate != null) {
                            append(" · ${track.bitDepth}bit/${track.sourceAudioSampleRate / 1000}kHz")
                        }
                    }
                    if (codecTag.isNotBlank()) {
                        Text(
                            text = codecTag,
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
            }
        }

        BareIcon(
            icon = Icons.Filled.Delete,
            contentDescription = Strings.deleteDownloaded,
            onClick = { showTrackDeleteDialog = true },
            tint = AppColors.TextTertiary,
            size = 44.dp,
            iconSize = 18.dp,
            modifier = Modifier.offset(x = 10.dp),
        )
    }

    if (showTrackDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showTrackDeleteDialog = false },
            title = { Text(Strings.deleteDownloaded) },
            text = { Text("确定要删除「${track.title}」的离线原档吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showTrackDeleteDialog = false
                    onDelete()
                }) {
                    Text(Strings.confirmDelete, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showTrackDeleteDialog = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
            containerColor = AppColors.CardSurface,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0L) return "0 MB"
    val megabytes = bytes / (1024.0 * 1024.0)
    return if (megabytes >= 1024.0) {
        String.format(Locale.US, "%.2f GB", megabytes / 1024.0)
    } else {
        String.format(Locale.US, "%.1f MB", megabytes)
    }
}
