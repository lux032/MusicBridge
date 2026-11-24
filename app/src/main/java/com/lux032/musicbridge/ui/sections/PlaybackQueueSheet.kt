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
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
    similarRadioState: SimilarRadioState = SimilarRadioState(),
    isSimilarRadioSupported: Boolean = false,
    sleepStopAfterCurrent: Boolean = false,
    onToggleAutoPlaySimilar: (Boolean) -> Unit = {},
    onRefreshAutoSimilarBatch: () -> Unit = {},
    onLoadMoreAutoSimilarTracks: () -> Unit = {},
    onSwitchToSequential: () -> Unit = {},
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
    var displayManualBoundary by remember {
        mutableIntStateOf(similarRadioState.manualQueueCount.coerceIn(0, state.tracks.size))
    }

    var draggedSlotId by remember { mutableStateOf<Long?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(-1) }
    var dragCurrentIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var autoScrollVelocityPx by remember { mutableFloatStateOf(0f) }

    // Reconcile external queue updates whenever no drag gesture is in progress.
    LaunchedEffect(state.tracks, state.currentIndex, similarRadioState.manualQueueCount, draggedSlotId) {
        if (draggedSlotId != null) return@LaunchedEffect
        displaySlots = reconcileQueueSlots(displaySlots, state.tracks) { nextSlotId++ }
        displayCurrentIndex = state.currentIndex.coerceIn(0, (state.tracks.size - 1).coerceAtLeast(0))
        displayManualBoundary = similarRadioState.manualQueueCount.coerceIn(0, state.tracks.size)
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
            info.key is Long &&
                info.key != activeId &&
                draggedCenterY >= info.offset &&
                draggedCenterY <= info.offset + info.size
        } ?: return
        val targetSlotId = targetInfo.key as? Long ?: return
        val targetIdx = latestDisplaySlots.indexOfFirst { it.id == targetSlotId }
        if (targetIdx < 0 || targetIdx == currentIdx || targetIdx !in latestDisplaySlots.indices) return

        val reordered = latestDisplaySlots.toMutableList().apply {
            val item = removeAt(currentIdx)
            add(targetIdx, item)
        }
        displaySlots = reordered
        displayCurrentIndex = remapIndexAfterMove(displayCurrentIndex, currentIdx, targetIdx)
        displayManualBoundary = remapManualQueueCountAfterMove(displayManualBoundary, currentIdx, targetIdx, reordered.size)
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
            val activeSlotId = displaySlots.getOrNull(displayCurrentIndex)?.id ?: return@derivedStateOf true
            queueListState.layoutInfo.visibleItemsInfo.any { it.key == activeSlotId }
        }
    }
    val showLocatePill = displaySlots.size > 5 && !isCurrentItemVisible && draggedSlotId == null
    val blockedByLoop = playbackMode == PlaybackMode.RepeatOne || playbackMode == PlaybackMode.RepeatAll
    val manualBoundary = displayManualBoundary.coerceIn(0, displaySlots.size)
    val hasAutoDivider = shouldShowAutoSimilarDivider(
        isSimilarRadioSupported = isSimilarRadioSupported,
        radioState = similarRadioState,
        manualBoundary = manualBoundary,
        totalCount = displaySlots.size,
    )

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
                        if (isSimilarRadioSupported && similarRadioState.radioSeed != null) {
                            QueueMetaBadge(
                                icon = Icons.Filled.WifiTethering,
                                label = Strings.queueRadioSeedBadge(similarRadioState.radioSeed.title),
                                accent = palette.accent,
                            )
                        } else if (isSimilarRadioSupported && similarRadioState.autoPlayEnabled && !blockedByLoop) {
                            QueueMetaBadge(
                                icon = Icons.Filled.AllInclusive,
                                label = Strings.queueAutoPlayBadge,
                                accent = palette.accent,
                            )
                        }
                        if (playbackMode == PlaybackMode.Shuffle) {
                            QueueMetaBadge(
                                icon = Icons.Filled.Shuffle,
                                label = Strings.queueShuffleBadge,
                                accent = palette.accent,
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (isSimilarRadioSupported) {
                        val isAutoOn = similarRadioState.autoPlayEnabled
                        val autoBg = when {
                            isAutoOn && !blockedByLoop -> KeyColors.Hi
                            isAutoOn -> KeyColors.Tonal
                            else -> Color.Transparent
                        }
                        val autoTint = when {
                            isAutoOn && !blockedByLoop -> keyHiInk()
                            isAutoOn -> AppColors.TextTertiary
                            else -> AppColors.TextSecondary
                        }
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(13.dp))
                                .background(autoBg)
                                .clickable(
                                    role = Role.Switch,
                                    onClickLabel = Strings.autoPlaySimilarToggleDesc,
                                ) {
                                    onToggleAutoPlaySimilar(!isAutoOn)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AllInclusive,
                                contentDescription = Strings.autoPlaySimilarToggleDesc,
                                tint = autoTint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
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
                    val manualSlots = if (hasAutoDivider) displaySlots.subList(0, manualBoundary) else displaySlots
                    val autoSlots = if (hasAutoDivider) displaySlots.subList(manualBoundary, displaySlots.size) else emptyList()

                    fun renderSlotRow(
                        scope: androidx.compose.foundation.lazy.LazyItemScope,
                        index: Int,
                        slot: QueueSlot,
                        isAutoSlot: Boolean,
                    ): @Composable () -> Unit = {
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
                        val reasonTag = if (isAutoSlot) similarRadioState.primaryReasonLabelFor(track) else null

                        Row(
                            modifier = with(scope) {
                                Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDragged) 10f else 0f)
                                    .then(
                                        if (isDragged) Modifier
                                        else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null),
                                    )
                            }
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
                                if (subtitle.isNotBlank() || reasonTag != null) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        if (subtitle.isNotBlank()) {
                                            Text(
                                                text = subtitle,
                                                color = AppColors.TextSecondary,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                        }
                                        if (reasonTag != null) {
                                            Text(
                                                text = reasonTag,
                                                color = palette.accent,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color.White.copy(alpha = 0.08f))
                                                    .padding(horizontal = 6.dp, vertical = 1.dp),
                                            )
                                        }
                                    }
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

                    itemsIndexed(
                        items = manualSlots,
                        key = { _, slot -> slot.id },
                        contentType = { _, _ -> "queue-track" },
                    ) { index, slot ->
                        renderSlotRow(this, index, slot, isAutoSlot = false)()
                    }

                    if (hasAutoDivider) {
                        item(key = "auto-similar-divider", contentType = "auto-divider") {
                            val seedTitle = similarRadioState.radioSeed?.title
                                ?: similarRadioState.lastAutoSeedTitle
                                ?: displaySlots.getOrNull((manualBoundary - 1).coerceAtLeast(0))?.track?.title
                                ?: ""
                            QueueAutoSimilarDivider(
                                seedTitle = seedTitle,
                                isHybrid = similarRadioState.isHybridMethod,
                                isLoading = similarRadioState.isLoading,
                                accent = palette.accent,
                                onRefresh = onRefreshAutoSimilarBatch,
                            )
                        }
                        itemsIndexed(
                            items = autoSlots,
                            key = { _, slot -> slot.id },
                            contentType = { _, _ -> "queue-track" },
                        ) { offset, slot ->
                            renderSlotRow(this, manualBoundary + offset, slot, isAutoSlot = true)()
                        }
                    }

                    if (isSimilarRadioSupported) {
                        item(key = "auto-similar-footer", contentType = "auto-footer") {
                            QueueAutoSimilarFooter(
                                radioState = similarRadioState,
                                playbackMode = playbackMode,
                                sleepStopAfterCurrent = sleepStopAfterCurrent,
                                accent = palette.accent,
                                onEnableAutoPlay = { onToggleAutoPlaySimilar(true) },
                                onSwitchToSequential = onSwitchToSequential,
                                onLoadMore = onLoadMoreAutoSimilarTracks,
                            )
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
                                val dividerOffset = if (hasAutoDivider && displayCurrentIndex >= manualBoundary) 1 else 0
                                val target = (displayCurrentIndex + dividerOffset - 1)
                                    .coerceIn(0, (displaySlots.lastIndex + dividerOffset).coerceAtLeast(0))
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
private fun QueueMetaBadge(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    accent: Color,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(KeyColors.Tonal)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QueueAutoSimilarDivider(
    seedTitle: String,
    isHybrid: Boolean?,
    isLoading: Boolean,
    accent: Color,
    onRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Hairline(Modifier.fillMaxWidth())
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.AllInclusive,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text(
                        text = Strings.queueAutoSectionTitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (seedTitle.isNotBlank()) {
                    Text(
                        text = Strings.queueAutoSectionSub(seedTitle, isHybrid),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(KeyColors.Tonal)
                    .clickable(enabled = !isLoading, onClick = onRefresh)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 1.8.dp,
                        color = accent,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = null,
                        tint = AppColors.TextPrimary,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = Strings.queueAutoRefreshBatch,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun QueueAutoSimilarFooter(
    radioState: SimilarRadioState,
    playbackMode: PlaybackMode,
    sleepStopAfterCurrent: Boolean,
    accent: Color,
    onEnableAutoPlay: () -> Unit,
    onSwitchToSequential: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val blockedByLoop = playbackMode == PlaybackMode.RepeatOne || playbackMode == PlaybackMode.RepeatAll
    val title: String
    val subtitle: String
    val actionLabel: String?
    val isPrimaryAction: Boolean
    val onAction: (() -> Unit)?

    when {
        !radioState.autoPlayEnabled -> {
            title = Strings.queueAutoStoppedTitle
            subtitle = Strings.queueAutoStoppedSub
            actionLabel = Strings.queueAutoEnableAction
            isPrimaryAction = true
            onAction = onEnableAutoPlay
        }
        blockedByLoop -> {
            title = Strings.queueAutoPausedLoopTitle(playbackMode)
            subtitle = Strings.queueAutoPausedLoopSub
            actionLabel = Strings.queueAutoSwitchSequential
            isPrimaryAction = false
            onAction = onSwitchToSequential
        }
        sleepStopAfterCurrent -> {
            title = Strings.queueAutoPausedSleepTitle
            subtitle = Strings.queueAutoPausedSleepSub
            actionLabel = null
            isPrimaryAction = false
            onAction = null
        }
        radioState.isLoading -> {
            title = Strings.queueAutoLoading
            subtitle = Strings.queueAutoActiveSub
            actionLabel = null
            isPrimaryAction = false
            onAction = null
        }
        radioState.errorKind == SimilarRadioErrorKind.Busy -> {
            title = Strings.queueAutoBusyTitle
            subtitle = Strings.queueAutoBusySub
            actionLabel = Strings.retry
            isPrimaryAction = false
            onAction = onLoadMore
        }
        radioState.errorKind == SimilarRadioErrorKind.OfflineOrFailed -> {
            title = Strings.queueAutoOfflineTitle
            subtitle = Strings.queueAutoOfflineSub
            actionLabel = Strings.retry
            isPrimaryAction = false
            onAction = onLoadMore
        }
        radioState.errorKind == SimilarRadioErrorKind.Empty -> {
            title = Strings.queueAutoEmptyTitle
            subtitle = Strings.queueAutoEmptySub
            actionLabel = Strings.retry
            isPrimaryAction = false
            onAction = onLoadMore
        }
        else -> {
            title = Strings.queueAutoActiveTitle
            subtitle = Strings.queueAutoActiveSub
            actionLabel = Strings.queueAutoLoadMore
            isPrimaryAction = false
            onAction = onLoadMore
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.055f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
        if (radioState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = accent,
            )
        } else if (actionLabel != null && onAction != null) {
            val hiInk = keyHiInk()
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isPrimaryAction) KeyColors.Hi else KeyColors.Tonal)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isPrimaryAction) hiInk else AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
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
