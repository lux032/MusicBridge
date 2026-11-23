package com.lux032.musicbridge

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lux032.musicbridge.music.MusicTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class QueueSlot(
    val id: Long,
    val track: MusicTrack,
)

private val QueueErrorOnLight = Color(0xFFB3261E)

/**
 * Interactive playing queue bottom sheet (Design D).
 *
 * - Sticky header: serif title, current position + remaining duration, Save as Playlist, Clear.
 * - Flat numbered list starting at the currently playing track (with PlayingBars).
 * - Always-on lightweight editing: trailing × remove (with in-sheet Undo) + ≡ drag handle
 *   (immediate drag without long-press delay, single atomic commit on drop).
 * - Floating "Jump to now playing" pill when scrolled away from the active item.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackQueueSheet(
    state: MiniPlayerState,
    playbackMode: PlaybackMode,
    queueNotice: QueueNotice?,
    onConsumeQueueNotice: (Long) -> Unit,
    onSelectTrack: (Int) -> Unit,
    onMoveTrack: (Int, Int) -> Unit,
    onRemoveTrack: (Int) -> Unit,
    onClearUpcomingKeepCurrent: () -> Unit,
    onClearQueueAndStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalArtworkPalette.current
    val nowPlayingMarker = LocalNowPlaying.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val saveAsPlaylistAction = addTracksToPlaylistAction(state.tracks)
    var isClearDialogVisible by remember { mutableStateOf(false) }

    // Maintain stable unique IDs per slot even when duplicate tracks appear in the queue.
    var nextSlotId by remember { mutableLongStateOf(1L) }
    var displaySlots by remember {
        mutableStateOf(
            state.tracks.map { track -> QueueSlot(nextSlotId++, track) },
        )
    }
    var displayCurrentIndex by remember {
        mutableIntStateOf(state.currentIndex.coerceIn(0, (state.tracks.size - 1).coerceAtLeast(0)))
    }

    var draggedSlotId by remember { mutableStateOf<Long?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var autoScrollVelocityPx by remember { mutableFloatStateOf(0f) }

    // Reconcile external queue updates whenever no drag gesture is in progress.
    LaunchedEffect(state.tracks, state.currentIndex, draggedSlotId) {
        if (draggedSlotId != null) return@LaunchedEffect
        displaySlots = reconcileQueueSlots(displaySlots, state.tracks) { nextSlotId++ }
        displayCurrentIndex = state.currentIndex.coerceIn(0, (state.tracks.size - 1).coerceAtLeast(0))
    }

    val queueListState = rememberLazyListState(
        initialFirstVisibleItemIndex = (state.currentIndex - 1).coerceIn(0, state.tracks.lastIndex.coerceAtLeast(0)),
    )

    val latestDisplaySlots by rememberUpdatedState(displaySlots)
    val latestOnMoveTrack by rememberUpdatedState(onMoveTrack)

    fun evaluateSwapIfNeeded() {
        val activeId = draggedSlotId ?: return
        val visible = queueListState.layoutInfo.visibleItemsInfo
        val draggedInfo = visible.firstOrNull { it.key == activeId } ?: return
        val currentIdx = dragCurrentIndex
        if (currentIdx !in latestDisplaySlots.indices) return
        val draggedCenterY = draggedInfo.offset + draggedInfo.size / 2f + dragOffsetY
        val targetInfo = visible.firstOrNull { info ->
            info.key != activeId &&
                draggedCenterY >= info.offset &&
                draggedCenterY <= info.offset + info.size
        } ?: return
        val targetIdx = targetInfo.index
        if (targetIdx == currentIdx || targetIdx !in latestDisplaySlots.indices) return

        val reordered = latestDisplaySlots.toMutableList().apply {
            val item = removeAt(currentIdx)
            add(targetIdx, item)
        }
        displaySlots = reordered
        displayCurrentIndex = remapIndexAfterMove(displayCurrentIndex, currentIdx, targetIdx)
        dragCurrentIndex = targetIdx
        dragOffsetY -= (targetInfo.offset - draggedInfo.offset).toFloat()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (currentIdx == 0 || targetIdx == 0) {
            queueListState.requestScrollToItem(
                queueListState.firstVisibleItemIndex,
                queueListState.firstVisibleItemScrollOffset,
            )
        }
    }

    val autoScrollZonePx = with(density) { 68.dp.toPx() }
    val maxAutoScrollStepPx = with(density) { 12.dp.toPx() }

    LaunchedEffect(draggedSlotId) {
        if (draggedSlotId == null) return@LaunchedEffect
        while (draggedSlotId != null) {
            val step = autoScrollVelocityPx
            if (step != 0f) {
                val consumed = queueListState.scrollBy(step)
                if (consumed != 0f) {
                    evaluateSwapIfNeeded()
                }
            }
            delay(16L)
        }
    }

    val isCurrentItemVisible by remember {
        derivedStateOf {
            queueListState.layoutInfo.visibleItemsInfo.any { it.index == displayCurrentIndex }
        }
    }
    val showLocatePill = displaySlots.size > 5 && !isCurrentItemVisible && draggedSlotId == null

    val remainingMillis = remember(displaySlots, displayCurrentIndex, state.currentPositionMillis) {
        remainingQueueDurationMillis(
            tracks = displaySlots.map(QueueSlot::track),
            currentIndex = displayCurrentIndex,
            currentPositionMillis = state.currentPositionMillis,
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }
    var isSnackVisible by remember { mutableStateOf(false) }
    LaunchedEffect(queueNotice?.id) {
        val current = queueNotice ?: return@LaunchedEffect
        isSnackVisible = true
        try {
            val result = snackbarHostState.showSnackbar(
                QueueSnackbarVisuals(
                    message = current.message,
                    actionLabel = current.actionLabel,
                    isError = current.isError,
                ),
            )
            if (result == SnackbarResult.ActionPerformed) {
                current.onAction?.invoke()
            }
        } finally {
            isSnackVisible = false
            onConsumeQueueNotice(current.id)
        }
    }

    val elevatedRowColor = remember(palette.backgroundBottom) {
        lerp(palette.backgroundBottom, Color.White, 0.12f)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = palette.backgroundBottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.88f)
                .navigationBarsPadding(),
        ) {
            // Sticky Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 10.dp, top = 2.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = Strings.playbackQueueTitle,
                        style = MaterialTheme.typography.titleLarge,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val positionText = Strings.queuePositionSummary(
                            currentOneBased = (displayCurrentIndex + 1).coerceAtMost(displaySlots.size.coerceAtLeast(1)),
                            total = displaySlots.size,
                        )
                        val remainingText = remainingMillis?.let { millis ->
                            Strings.queueRemainingDuration(formatQueueRemainingDuration(millis))
                        }
                        val metaLine = listOfNotNull(positionText, remainingText).joinToString(" · ")
                        Text(
                            text = metaLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (playbackMode == PlaybackMode.Shuffle) {
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(KeyColors.Tonal)
                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Shuffle,
                                    contentDescription = null,
                                    tint = palette.accent,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = Strings.queueShuffleBadge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = palette.accent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (saveAsPlaylistAction != null) {
                        BareIcon(
                            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                            contentDescription = Strings.queueSaveAsPlaylist,
                            tint = AppColors.TextSecondary,
                            onClick = {
                                onDismiss()
                                saveAsPlaylistAction()
                            },
                        )
                    }
                    BareIcon(
                        icon = Icons.Filled.DeleteSweep,
                        contentDescription = Strings.queueClearAction,
                        tint = AppColors.TextSecondary,
                        onClick = { isClearDialogVisible = true },
                    )
                }
            }

            Hairline(Modifier.fillMaxWidth())

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                LazyColumn(
                    state = queueListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 84.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    itemsIndexed(
                        items = displaySlots,
                        key = { _, slot -> slot.id },
                        contentType = { _, _ -> "queue-track" },
                    ) { index, slot ->
                        val track = slot.track
                        val isCurrent = index == displayCurrentIndex
                        val isPlayed = index < displayCurrentIndex
                        val isDragged = slot.id == draggedSlotId
                        val dragScale by animateFloatAsState(
                            targetValue = if (isDragged) 1.02f else 1f,
                            label = "queue_drag_scale",
                        )
                        val rowBackground = when {
                            isDragged -> elevatedRowColor
                            isCurrent -> KeyColors.Tonal
                            else -> Color.Transparent
                        }
                        val subtitle = listOfNotNull(
                            track.artistName?.takeIf { it.isNotBlank() },
                            (track.albumTitle ?: state.album.title).takeIf { it.isNotBlank() },
                        ).joinToString(" · ")

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isDragged) 10f else 0f)
                                .then(
                                    if (isDragged) Modifier
                                    else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                )
                                .graphicsLayer {
                                    translationY = if (isDragged) dragOffsetY else 0f
                                    scaleX = dragScale
                                    scaleY = dragScale
                                    shadowElevation = if (isDragged) 12.dp.toPx() else 0f
                                    shape = RoundedCornerShape(14.dp)
                                    clip = isDragged
                                    alpha = if (isPlayed && !isDragged) 0.72f else 1f
                                }
                                .clip(RoundedCornerShape(14.dp))
                                .background(rowBackground)
                                .clickable(enabled = draggedSlotId == null) {
                                    onDismiss()
                                    onSelectTrack(index)
                                }
                                .padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            TrackNumberSlot(
                                text = "${index + 1}",
                                nowPlaying = if (isCurrent) nowPlayingMarker else null,
                                modifier = Modifier.width(24.dp),
                            )
                            AsyncAlbumArtwork(
                                imageUrl = track.thumbUrl,
                                title = track.title,
                                modifier = Modifier.size(40.dp),
                                thumbnailSize = 48.dp,
                                fit = ArtworkFit.Contain,
                                alignment = Alignment.Center,
                                shape = RoundedCornerShape(6.dp),
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = track.title,
                                    color = if (isCurrent) palette.accent else AppColors.TextPrimary,
                                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (subtitle.isNotBlank()) {
                                    Text(
                                        text = subtitle,
                                        color = AppColors.TextSecondary,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 36.dp, height = 40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable(
                                            enabled = draggedSlotId == null,
                                            role = Role.Button,
                                            onClickLabel = Strings.queueRemoveTrack,
                                        ) {
                                            onRemoveTrack(index)
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = Strings.queueRemoveTrack,
                                        tint = AppColors.TextTertiary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(width = 36.dp, height = 40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .semantics { contentDescription = Strings.queueReorderHandle }
                                        .pointerInput(slot.id) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    val idx = latestDisplaySlots.indexOfFirst { it.id == slot.id }
                                                    if (idx < 0) return@detectDragGestures
                                                    draggedSlotId = slot.id
                                                    dragStartIndex = idx
                                                    dragCurrentIndex = idx
                                                    dragOffsetY = 0f
                                                    autoScrollVelocityPx = 0f
                                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDragEnd = {
                                                    val from = dragStartIndex
                                                    val to = dragCurrentIndex
                                                    draggedSlotId = null
                                                    dragStartIndex = -1
                                                    dragCurrentIndex = -1
                                                    dragOffsetY = 0f
                                                    autoScrollVelocityPx = 0f
                                                    if (from >= 0 && to >= 0 && from != to) {
                                                        latestOnMoveTrack(from, to)
                                                    }
                                                },
                                                onDragCancel = {
                                                    val from = dragStartIndex
                                                    val to = dragCurrentIndex
                                                    draggedSlotId = null
                                                    dragStartIndex = -1
                                                    dragCurrentIndex = -1
                                                    dragOffsetY = 0f
                                                    autoScrollVelocityPx = 0f
                                                    if (from >= 0 && to >= 0 && from != to) {
                                                        latestOnMoveTrack(from, to)
                                                    }
                                                },
                                                onDrag = { change, dragAmount ->
                                                    if (draggedSlotId != slot.id) return@detectDragGestures
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    evaluateSwapIfNeeded()

                                                    val layout = queueListState.layoutInfo
                                                    val info = layout.visibleItemsInfo.firstOrNull { it.key == slot.id }
                                                    if (info != null) {
                                                        val itemTop = info.offset + dragOffsetY
                                                        val itemBottom = itemTop + info.size
                                                        val topBoundary = layout.viewportStartOffset + autoScrollZonePx
                                                        val bottomBoundary = layout.viewportEndOffset - autoScrollZonePx
                                                        autoScrollVelocityPx = when {
                                                            itemTop < topBoundary -> {
                                                                val ratio = ((topBoundary - itemTop) / autoScrollZonePx).coerceIn(0f, 1f)
                                                                -maxAutoScrollStepPx * ratio
                                                            }
                                                            itemBottom > bottomBoundary -> {
                                                                val ratio = ((itemBottom - bottomBoundary) / autoScrollZonePx).coerceIn(0f, 1f)
                                                                maxAutoScrollStepPx * ratio
                                                            }
                                                            else -> 0f
                                                        }
                                                    } else {
                                                        autoScrollVelocityPx = 0f
                                                    }
                                                },
                                            )
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = null,
                                        tint = if (isDragged) palette.accent else AppColors.TextSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating "Jump to now playing" pill when scrolled away from the active track.
                val pillBottomPadding by animateDpAsState(
                    targetValue = if (isSnackVisible) 76.dp else 18.dp,
                    label = "locate_pill_bottom",
                )
                androidx.compose.animation.AnimatedVisibility(
                    visible = showLocatePill,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = pillBottomPadding),
                ) {
                    Surface(
                        onClick = {
                            scope.launch {
                                val target = (displayCurrentIndex - 1).coerceIn(0, displaySlots.lastIndex.coerceAtLeast(0))
                                queueListState.animateScrollToItem(target)
                            }
                        },
                        shape = CircleShape,
                        color = elevatedRowColor,
                        shadowElevation = 8.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MyLocation,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = Strings.queueJumpToCurrent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextPrimary,
                            )
                        }
                    }
                }

                // In-sheet feedback snackbar (Undo / Shuffle exit / Sonos rollback).
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) { data ->
                    val visuals = data.visuals as? QueueSnackbarVisuals
                    val ink = keyHiInk()
                    Snackbar(
                        containerColor = KeyColors.Hi,
                        contentColor = ink,
                        shape = KeyShape,
                        action = data.visuals.actionLabel?.let { label ->
                            {
                                TextButton(onClick = { data.performAction() }) {
                                    Text(label, color = ink, fontWeight = FontWeight.Bold)
                                }
                            }
                        },
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (visuals?.isError == true) {
                                Icon(
                                    imageVector = Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = QueueErrorOnLight,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                text = data.visuals.message,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    if (isClearDialogVisible) {
        ClearQueueConfirmDialog(
            trackCount = displaySlots.size,
            onClearUpcomingOnly = {
                isClearDialogVisible = false
                onClearUpcomingKeepCurrent()
            },
            onClearAllAndStop = {
                isClearDialogVisible = false
                onDismiss()
                onClearQueueAndStop()
            },
            onDismiss = { isClearDialogVisible = false },
        )
    }
}

@Composable
private fun ClearQueueConfirmDialog(
    trackCount: Int,
    onClearUpcomingOnly: () -> Unit,
    onClearAllAndStop: () -> Unit,
    onDismiss: () -> Unit,
) {
    val onlyOneTrack = trackCount <= 1
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.SurfaceAlt,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
        title = {
            Text(
                text = if (onlyOneTrack) Strings.queueClearSingleDialogTitle else Strings.queueClearDialogTitle,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = if (onlyOneTrack) Strings.queueClearSingleDialogBody else Strings.queueClearDialogBody,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(2.dp))
                if (!onlyOneTrack) {
                    ClearQueueOptionRow(
                        title = Strings.queueClearUpcomingOnly,
                        subtitle = Strings.queueClearUpcomingOnlyDesc(trackCount - 1),
                        titleColor = AppColors.TextPrimary,
                        onClick = onClearUpcomingOnly,
                    )
                }
                ClearQueueOptionRow(
                    title = Strings.queueClearAllAndStop,
                    subtitle = Strings.queueClearAllAndStopDesc,
                    titleColor = AppColors.ErrorText,
                    onClick = onClearAllAndStop,
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel, color = AppColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun ClearQueueOptionRow(
    title: String,
    subtitle: String,
    titleColor: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(KeyColors.Tonal)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            color = titleColor,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = subtitle,
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private data class QueueSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    val isError: Boolean,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration =
        if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/**
 * Preserves existing slot IDs when reconciling [incomingTracks] with [existingSlots]
 * so LazyColumn item animations stay smooth even when duplicate tracks appear in the queue.
 */
private fun reconcileQueueSlots(
    existingSlots: List<QueueSlot>,
    incomingTracks: List<MusicTrack>,
    allocateId: () -> Long,
): List<QueueSlot> {
    if (existingSlots.size == incomingTracks.size &&
        existingSlots.indices.all { i ->
            existingSlots[i].track.sourceId == incomingTracks[i].sourceId &&
                existingSlots[i].track.ratingKey == incomingTracks[i].ratingKey
        }
    ) {
        return existingSlots.mapIndexed { i, slot ->
            if (slot.track === incomingTracks[i]) slot else slot.copy(track = incomingTracks[i])
        }
    }
    val pools = HashMap<Pair<String, String>, ArrayDeque<Long>>(existingSlots.size)
    existingSlots.forEach { slot ->
        val key = slot.track.sourceId to slot.track.ratingKey
        pools.getOrPut(key) { ArrayDeque() }.addLast(slot.id)
    }
    return incomingTracks.map { track ->
        val key = track.sourceId to track.ratingKey
        val reusedId = pools[key]?.removeFirstOrNull()
        QueueSlot(id = reusedId ?: allocateId(), track = track)
    }
}

private fun formatQueueRemainingDuration(durationMillis: Long): String {
    val totalMinutes = ((durationMillis + 30_000L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        Strings.queueDurationHoursMinutes(hours, minutes)
    } else {
        Strings.queueDurationMinutes(minutes)
    }
}
