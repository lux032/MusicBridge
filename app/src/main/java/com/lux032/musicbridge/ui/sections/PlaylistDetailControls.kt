package com.lux032.musicbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** Builds an [AnnotatedString] with case-insensitive multi-token accent highlights. */
internal fun highlightedPlaylistText(
    text: String,
    tokens: List<String>,
    accent: Color,
): AnnotatedString {
    val ranges = findHighlightRanges(text, tokens)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        val style = SpanStyle(
            color = accent,
            background = Color.White.copy(alpha = 0.14f),
            fontWeight = FontWeight.SemiBold,
        )
        ranges.forEach { range ->
            addStyle(style, start = range.first, end = (range.last + 1).coerceAtMost(text.length))
        }
    }
}

/**
 * 48dp squircle tonal download key sitting beside [SplitPlayKey] in the playlist hero.
 * Mirrors [AlbumDetailSection]'s download affordance and adds partial-offline indicator +
 * tappable circular progress control.
 */
@Composable
internal fun PlaylistDownloadKey(
    summary: PlaylistDownloadSummary,
    enabled: Boolean,
    onStartDownload: () -> Unit,
    onManageDownload: () -> Unit,
    onRequestDeleteOffline: () -> Unit,
) {
    val palette = LocalArtworkPalette.current
    val canTap = enabled && summary.total > 0
    when (summary.phase) {
        PlaylistDownloadPhase.AllDone -> Key(
            onClick = onRequestDeleteOffline,
            enabled = canTap,
            modifier = Modifier.size(48.dp),
            contentDescription = Strings.removePlaylistOfflineAction,
        ) {
            Icon(
                imageVector = Icons.Filled.DownloadDone,
                contentDescription = null,
                tint = palette.accent,
                modifier = Modifier.size(22.dp),
            )
        }
        PlaylistDownloadPhase.Downloading,
        PlaylistDownloadPhase.Paused -> Key(
            onClick = onManageDownload,
            enabled = canTap,
            modifier = Modifier.size(48.dp),
            contentDescription = Strings.managePlaylistDownload,
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { summary.fraction.coerceIn(0.06f, 1f) },
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.6.dp,
                    color = palette.accent,
                    trackColor = Color.White.copy(alpha = 0.16f),
                    strokeCap = StrokeCap.Round,
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.accent),
                )
            }
        }
        PlaylistDownloadPhase.Partial,
        PlaylistDownloadPhase.None -> Key(
            onClick = onStartDownload,
            enabled = canTap,
            modifier = Modifier.size(48.dp),
            contentDescription = if (summary.phase == PlaylistDownloadPhase.Partial) {
                Strings.downloadRemainingTracks(summary.remainingCount)
            } else {
                Strings.downloadWholePlaylist
            },
        ) {
            Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(22.dp),
                )
                if (summary.phase == PlaylistDownloadPhase.Partial) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 8.dp, end = 8.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(palette.accent),
                    )
                }
            }
        }
    }
}

/** Bottom sheet shown when tapping an active/paused playlist download progress key. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaylistDownloadControlSheet(
    summary: PlaylistDownloadSummary,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancelRemaining: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalArtworkPalette.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPausedOnly = summary.phase == PlaylistDownloadPhase.Paused
    val percent = (summary.fraction * 100f).roundToInt().coerceIn(0, 100)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.backgroundBottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = if (isPausedOnly) Strings.playlistDownloadPausedTitle else Strings.playlistDownloadSheetTitle,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
            )
            Text(
                text = Strings.playlistDownloadSheetSubtitle(
                    done = summary.downloadedCount,
                    total = summary.total,
                    percent = percent,
                    remaining = summary.remainingCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
            LinearProgressIndicator(
                progress = { summary.fraction.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(CircleShape),
                color = palette.accent,
                trackColor = Color.White.copy(alpha = 0.14f),
                strokeCap = StrokeCap.Round,
            )
            Spacer(Modifier.height(2.dp))
            PlaylistSheetActionRow(
                icon = if (isPausedOnly) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                label = if (isPausedOnly) Strings.resumePlaylistDownload else Strings.pausePlaylistDownload,
                onClick = {
                    if (isPausedOnly) onResume() else onPause()
                },
            )
            PlaylistSheetActionRow(
                icon = Icons.Filled.Cancel,
                label = Strings.cancelPlaylistDownload(summary.allPendingTaskKeys.size.coerceAtLeast(summary.remainingCount)),
                isDanger = true,
                onClick = {
                    onCancelRemaining()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun PlaylistSheetActionRow(
    icon: ImageVector,
    label: String,
    isDanger: Boolean = false,
    onClick: () -> Unit,
) {
    val contentColor = if (isDanger) AppColors.ErrorText else AppColors.TextPrimary
    val iconColor = if (isDanger) AppColors.ErrorText else AppColors.TextSecondary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KeyColors.Tonal)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Confirmation dialog for deleting local offline files associated with a playlist. */
@Composable
internal fun DeletePlaylistOfflineDialog(
    downloadedCount: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.removePlaylistOfflineTitle) },
        text = { Text(Strings.removePlaylistOfflineBody(downloadedCount)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = Strings.removePlaylistOfflineAction,
                    color = AppColors.ErrorText,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel, color = AppColors.TextSecondary)
            }
        },
        containerColor = AppColors.SurfaceAlt,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}

/** Compact tonal search input (44dp) used inline below the hero and as sticky top-bar search. */
@Composable
internal fun PlaylistCompactSearchBox(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    autoFocus: Boolean = false,
) {
    val palette = LocalArtworkPalette.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (autoFocus && focusRequester != null) {
        LaunchedEffect(Unit) {
            runCatching { focusRequester.requestFocus() }
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(KeyColors.Tonal)
            .padding(start = 13.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = AppColors.TextSecondary,
            modifier = Modifier.size(19.dp),
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (query.isEmpty()) {
                Text(
                    text = Strings.searchPlaylistPlaceholder,
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = AppColors.TextPrimary),
                cursorBrush = SolidColor(palette.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { onQueryChange("") },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = Strings.clear,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(17.dp),
                )
            }
        }
    }
}

/** Inline search field + Downloaded/Favorited filter chips + Reorder chip + active result strip. */
@Composable
internal fun PlaylistFilterBar(
    filter: PlaylistFilterState,
    totalCount: Int,
    matchedCount: Int,
    downloadedCount: Int,
    favoritedCount: Int,
    canReorder: Boolean,
    isPlaybackLoading: Boolean,
    searchFocusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onToggleDownloaded: () -> Unit,
    onToggleFavorited: () -> Unit,
    onClearFilter: () -> Unit,
    onPlayFiltered: () -> Unit,
    onEnterReorder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        PlaylistCompactSearchBox(
            query = filter.query,
            onQueryChange = onQueryChange,
            focusRequester = searchFocusRequester,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            PlaylistFilterChip(
                icon = Icons.Filled.DownloadDone,
                label = Strings.filterDownloaded,
                count = downloadedCount,
                selected = filter.downloadedOnly,
                onClick = onToggleDownloaded,
            )
            PlaylistFilterChip(
                icon = Icons.Filled.Favorite,
                label = Strings.filterFavorited,
                count = favoritedCount,
                selected = filter.favoritedOnly,
                onClick = onToggleFavorited,
            )
            Spacer(Modifier.weight(1f))
            if (canReorder) {
                PlaylistFilterChip(
                    icon = Icons.Filled.SwapVert,
                    label = Strings.reorderShort,
                    count = null,
                    selected = false,
                    onClick = onEnterReorder,
                    modifier = Modifier.alpha(if (filter.isActive) 0.62f else 1f),
                )
            }
        }

        if (filter.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.065f))
                    .padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = Strings.playlistFilterSummary(matchedCount, totalCount),
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = Strings.clearFilter,
                        color = palette.accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onClearFilter)
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
                if (matchedCount > 0) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(KeyColors.TonalPressed)
                            .clickable(enabled = !isPlaybackLoading, role = Role.Button, onClick = onPlayFiltered)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = palette.accent,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = Strings.playFilteredTracks(matchedCount),
                            color = AppColors.TextPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistFilterChip(
    icon: ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) KeyColors.Hi else KeyColors.Tonal
    val content = if (selected) keyHiInk() else AppColors.TextPrimary
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(container)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        if (count != null) {
            Text(
                text = count.toString(),
                color = content.copy(alpha = 0.72f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
        }
    }
}

/** Elevated sticky top bar active during drag-and-drop reorder mode. */
@Composable
internal fun PlaylistReorderTopBar(
    moveCount: Int,
    isSaving: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    val barColor = remember(palette.backgroundBottom) {
        lerp(palette.backgroundBottom, Color.White, 0.11f)
    }
    Column(modifier = modifier.fillMaxWidth().background(barColor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(
                onClick = onCancel,
                enabled = !isSaving,
            ) {
                Text(
                    text = Strings.cancel,
                    color = AppColors.TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = Strings.reorderModeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                )
                Text(
                    text = if (moveCount > 0) Strings.reorderModeChangedHint(moveCount) else Strings.reorderModeHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Key(
                onClick = onSave,
                enabled = !isSaving,
                style = KeyStyle.Hi,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                    }
                    Text(
                        text = when {
                            isSaving -> Strings.saving
                            moveCount > 0 -> Strings.save
                            else -> Strings.reorderDone
                        },
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
            }
        }
        Hairline(Modifier.fillMaxWidth())
    }
}

/** Subtle hint banner rendered at the top of the track list while in reorder mode. */
@Composable
internal fun PlaylistReorderHintBanner(modifier: Modifier = Modifier) {
    val palette = LocalArtworkPalette.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.075f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.DragHandle,
            contentDescription = null,
            tint = palette.accent,
            modifier = Modifier.size(17.dp),
        )
        Text(
            text = Strings.reorderModeListHint,
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Empty state shown when in-playlist search or filter yields 0 tracks. */
@Composable
internal fun PlaylistFilterEmptyState(
    query: String,
    onClearFilter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(KeyColors.Tonal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = Strings.playlistNoFilterMatchTitle,
            color = AppColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = if (query.isNotBlank()) Strings.playlistNoFilterMatchQuery(query.trim()) else Strings.playlistNoFilterMatchDesc,
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        LabelKey(
            text = Strings.clearFilterButton,
            onClick = onClearFilter,
            height = 38.dp,
            shape = RoundedCornerShape(13.dp),
        )
    }
}
