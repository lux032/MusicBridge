package com.lux032.musicbridge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
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
                    IconCircleButton(
                        onClick = { isSortMenuExpanded = true },
                        containerColor = AppColors.CardSurface,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Sort,
                            contentDescription = Strings.sortFieldTitle,
                            tint = AppColors.TextPrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = isSortMenuExpanded,
                        onDismissRequest = { isSortMenuExpanded = false },
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

                // Shuffle All Button
                val shuffleEnabled = albums.isNotEmpty()
                IconCircleButton(
                    onClick = onShuffleAll,
                    enabled = shuffleEnabled,
                    containerColor = if (shuffleEnabled) palette.accent else palette.accent.copy(alpha = 0.35f),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = Strings.shuffleAllOffline,
                        tint = palette.onAccent.copy(alpha = if (shuffleEnabled) 1f else 0.5f),
                        modifier = Modifier.size(20.dp),
                    )
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
                fontWeight = FontWeight.Bold,
            )
            if (albums.isNotEmpty()) {
                val statsText = "${albums.size} 张专辑 · $totalTracks 首单曲 · 共 ${formatFileSize(totalBytes)}"
                Text(
                    text = statsText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
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
                verticalArrangement = Arrangement.spacedBy(14.dp),
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
) {
    var isExpanded by rememberSaveable(group.albumRatingKey) { mutableStateOf(false) }
    var isMenuExpanded by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    val palette = LocalArtworkPalette.current

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = AppColors.CardSurface,
        border = BorderStroke(1.dp, AppColors.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Album summary row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Cover
                AsyncAlbumArtwork(
                    imageUrl = group.thumbPath?.let { "file://$it" },
                    title = group.albumTitle,
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop,
                    thumbnailSize = 72.dp,
                    placeholderThumbnailSize = null,
                )

                // Info
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = group.albumTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
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
                    // Play Album
                    IconCircleButton(
                        onClick = onPlayAlbum,
                        modifier = Modifier.size(36.dp),
                        containerColor = palette.accent,
                    ) {
                        PlayerIconGraphic(
                            icon = PlayerIcon.Play,
                            tint = palette.onAccent,
                            modifier = Modifier.size(16.dp),
                        )
                    }

                    // Expand / Collapse Arrow
                    IconButton(
                        onClick = { isExpanded = !isExpanded },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "折叠" else "展开",
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    // Overflow Menu
                    Box {
                        IconButton(
                            onClick = { isMenuExpanded = true },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "更多",
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        DropdownMenu(
                            expanded = isMenuExpanded,
                            onDismissRequest = { isMenuExpanded = false },
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
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                ) {
                    HorizontalDivider(color = AppColors.Border.copy(alpha = 0.6f))
                    Spacer(modifier = Modifier.height(6.dp))

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
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
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
) {
    var showTrackDeleteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = displayIndex,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextTertiary,
                modifier = Modifier.widthIn(min = 22.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
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

        IconButton(
            onClick = { showTrackDeleteDialog = true },
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = Strings.deleteDownloaded,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
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
