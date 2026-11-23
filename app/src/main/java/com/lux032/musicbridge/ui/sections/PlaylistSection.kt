package com.lux032.musicbridge

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lux032.musicbridge.music.MusicPlaylist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.PlaylistTracksResult
import com.lux032.musicbridge.music.isFavorite
import com.lux032.musicbridge.storage.DownloadTaskItem
import com.lux032.musicbridge.storage.containsDownloadedTrack
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun PlaylistsSection(
    playlists: List<MusicPlaylist>,
    onGoHome: () -> Unit,
    onPlaylistClick: (MusicPlaylist) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenDownloadedTracks: () -> Unit,
    isAiGenerating: Boolean,
    onGenerateAiPlaylist: (String) -> Unit,
    onCancelAiGeneration: () -> Unit,
    isSyncing: Boolean = false,
    syncProgress: Pair<Int, Int> = 0 to 0,
    onSyncAllPlaylists: () -> Unit = {},
    canCreatePlaylist: Boolean = false,
    onCreatePlaylist: suspend (name: String, description: String) -> Result<MusicPlaylist>? = { _, _ -> null },
    onOpenCreatedPlaylist: (MusicPlaylist) -> Unit = {},
) {
    var showAiDialog by remember { mutableStateOf(false) }
    var showCreateSheet by rememberSaveable { mutableStateOf(false) }
    if (showAiDialog) AiPlaylistDialog(isAiGenerating, onCancelAiGeneration, { prompt -> onGenerateAiPlaylist(prompt) }) { showAiDialog = false }
    if (showCreateSheet) {
        var created by remember { mutableStateOf<MusicPlaylist?>(null) }
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
    playlist: MusicPlaylist,
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
                modifier = Modifier.size(60.dp),
                thumbnailSize = 60.dp,
                fit = ArtworkFit.Contain,
                alignment = Alignment.Center,
                shape = RoundedCornerShape(10.dp),
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

private data class PlaylistReorderSlot(
    val id: Long,
    val track: MusicTrack,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlaylistDetailSection(
    playlistResult: PlaylistTracksResult?,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onBack: () -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
    onQueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    downloadTasks: List<DownloadTaskItem> = emptyList(),
    onDownloadTrack: (MusicTrack) -> Unit = {},
    onDeleteDownloadedTrack: (MusicTrack) -> Unit = {},
    onDownloadPlaylist: (MusicPlaylist, List<MusicTrack>) -> Unit = { _, _ -> },
    onDeleteDownloadedPlaylist: (MusicPlaylist, List<MusicTrack>) -> Unit = { _, _ -> },
    onPausePlaylistDownloads: (List<String>) -> Unit = {},
    onResumePlaylistDownloads: (List<String>) -> Unit = {},
    onCancelPlaylistDownloads: (List<String>) -> Unit = {},
    onPlayPlaylist: (PlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlaylistTracksResult) -> Unit,
    onPlayTrack: (MusicPlaylist, MusicTrack, Int) -> Unit,
    aiDescription: String? = null,
    onSaveAiPlaylist: () -> Unit = {},
    canEditPlaylist: Boolean = false,
    onRemoveTrack: (MusicTrack) -> Unit = {},
    onReorderTracks: suspend (MusicPlaylist, List<MusicTrack>) -> Result<Unit> = { _, _ -> Result.success(Unit) },
    onUpdatePlaylist: suspend (MusicPlaylist, String, String) -> Result<*> = { _, _, _ -> Result.success(Unit) },
    onDeletePlaylist: suspend (MusicPlaylist) -> Result<*> = { Result.success(Unit) },
    onNotify: (String) -> Unit = {},
    dailyMix: DailyMixDetailState? = null,
    onRateDailyMix: (Int, List<String>) -> Unit = { _, _ -> },
    onReplaceDailyMix: () -> Unit = {},
    onSaveDailyMix: () -> Unit = {},
    onToggleRecommendationBlock: (MusicTrack) -> Unit = {},
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

    val playlistIdentity = "${currentResult.playlist.sourceId}:${currentResult.playlist.ratingKey}"
    val layoutInfo = LocalAppLayoutInfo.current
    val palette = LocalArtworkPalette.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val trackListState = rememberLazyListState()
    val inlineSearchFocusRequester = remember { FocusRequester() }
    val stickySearchFocusRequester = remember { FocusRequester() }

    var showPlaylistMenu by remember(playlistIdentity) { mutableStateOf(false) }
    var showEditSheet by rememberSaveable(playlistIdentity) { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable(playlistIdentity) { mutableStateOf(false) }
    var showDownloadControlSheet by remember(playlistIdentity) { mutableStateOf(false) }
    var showDeleteOfflineDialog by remember(playlistIdentity) { mutableStateOf(false) }

    var filterState by remember(playlistIdentity) { mutableStateOf(PlaylistFilterState()) }
    var stickySearchOpen by remember(playlistIdentity) { mutableStateOf(false) }

    var isReordering by remember(playlistIdentity) { mutableStateOf(false) }
    var nextReorderSlotId by remember(playlistIdentity) { mutableLongStateOf(1L) }
    var reorderSlots by remember(playlistIdentity) { mutableStateOf<List<PlaylistReorderSlot>>(emptyList()) }
    var reorderMoveCount by remember(playlistIdentity) { mutableIntStateOf(0) }
    var isSavingOrder by remember(playlistIdentity) { mutableStateOf(false) }

    var draggedSlotId by remember(playlistIdentity) { mutableStateOf<Long?>(null) }
    var dragOffsetY by remember(playlistIdentity) { mutableFloatStateOf(0f) }
    var autoScrollVelocityPx by remember(playlistIdentity) { mutableFloatStateOf(0f) }

    val editable = canEditPlaylist
    val canReorder = editable && currentResult.tracks.size >= 2
    val removeTrack: ((MusicTrack) -> Unit)? = if (editable) onRemoveTrack else null

    val downloadSummary = remember(currentResult.tracks, downloadedTrackKeys, downloadTasks, downloadProgress) {
        summarizePlaylistDownload(
            tracks = currentResult.tracks,
            downloadedTrackKeys = downloadedTrackKeys,
            downloadTasks = downloadTasks,
            downloadProgress = downloadProgress,
        )
    }

    val favoritedCount = remember(currentResult.tracks) {
        currentResult.tracks.count { it.isFavorite }
    }
    val highlightTokens = remember(filterState.query) {
        tokenizePlaylistQuery(filterState.query)
    }
    val filteredEntries = remember(currentResult.tracks, filterState, downloadedTrackKeys) {
        filterPlaylistTracks(currentResult.tracks, filterState, downloadedTrackKeys)
    }

    val isScrolledPastHero by remember(layoutInfo.isWide) {
        derivedStateOf {
            !layoutInfo.isWide && (trackListState.firstVisibleItemIndex > 0 || trackListState.firstVisibleItemScrollOffset > 280)
        }
    }

    LaunchedEffect(isScrolledPastHero) {
        if (!isScrolledPastHero && stickySearchOpen) {
            stickySearchOpen = false
        }
    }

    LaunchedEffect(downloadSummary.phase) {
        if (showDownloadControlSheet &&
            downloadSummary.phase != PlaylistDownloadPhase.Downloading &&
            downloadSummary.phase != PlaylistDownloadPhase.Paused
        ) {
            showDownloadControlSheet = false
        }
    }

    val latestReorderSlots by rememberUpdatedState(reorderSlots)

    fun evaluateReorderSwap() {
        val activeId = draggedSlotId ?: return
        val visible = trackListState.layoutInfo.visibleItemsInfo
        val draggedInfo = visible.firstOrNull { it.key == activeId } ?: return
        val currentIdx = latestReorderSlots.indexOfFirst { it.id == activeId }
        if (currentIdx !in latestReorderSlots.indices) return

        val draggedCenterY = draggedInfo.offset + draggedInfo.size / 2f + dragOffsetY
        val targetInfo = visible.firstOrNull { info ->
            info.key != activeId &&
                info.key is Long &&
                draggedCenterY >= info.offset &&
                draggedCenterY <= info.offset + info.size
        } ?: return

        val targetIdx = latestReorderSlots.indexOfFirst { it.id == targetInfo.key }
        if (targetIdx !in latestReorderSlots.indices || targetIdx == currentIdx) return

        val updated = latestReorderSlots.toMutableList().apply {
            val moved = removeAt(currentIdx)
            add(targetIdx, moved)
        }
        reorderSlots = updated
        reorderMoveCount += 1
        dragOffsetY -= (targetInfo.offset - draggedInfo.offset).toFloat()
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        if (currentIdx == 0 || targetIdx == 0) {
            trackListState.requestScrollToItem(
                trackListState.firstVisibleItemIndex,
                trackListState.firstVisibleItemScrollOffset,
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
                val consumed = trackListState.scrollBy(step)
                if (consumed != 0f) {
                    evaluateReorderSwap()
                }
            }
            delay(16L)
        }
    }

    fun enterReorderMode() {
        if (!canReorder) return
        val hadFilter = filterState.isActive
        if (hadFilter) {
            filterState = PlaylistFilterState()
            stickySearchOpen = false
        }
        reorderSlots = currentResult.tracks.map { track ->
            PlaylistReorderSlot(id = nextReorderSlotId++, track = track)
        }
        reorderMoveCount = 0
        isSavingOrder = false
        draggedSlotId = null
        dragOffsetY = 0f
        autoScrollVelocityPx = 0f
        isReordering = true
        if (hadFilter) {
            onNotify(Strings.reorderClearedFilterNotice)
        }
        if (!layoutInfo.isWide) {
            scope.launch {
                trackListState.animateScrollToItem(1)
            }
        }
    }

    fun cancelReorderMode() {
        if (isSavingOrder) return
        val hadChanges = reorderMoveCount > 0 && hasPlaylistOrderChanged(currentResult.tracks, reorderSlots.map { it.track })
        isReordering = false
        reorderSlots = emptyList()
        reorderMoveCount = 0
        draggedSlotId = null
        dragOffsetY = 0f
        autoScrollVelocityPx = 0f
        if (hadChanges) {
            onNotify(Strings.playlistOrderDiscarded)
        }
    }

    fun saveReorderMode() {
        if (isSavingOrder) return
        val candidateTracks = reorderSlots.map { it.track }
        if (!hasPlaylistOrderChanged(currentResult.tracks, candidateTracks)) {
            isReordering = false
            reorderSlots = emptyList()
            reorderMoveCount = 0
            return
        }
        isSavingOrder = true
        scope.launch {
            val result = onReorderTracks(currentResult.playlist, candidateTracks)
            isSavingOrder = false
            isReordering = false
            reorderSlots = emptyList()
            reorderMoveCount = 0
            draggedSlotId = null
            dragOffsetY = 0f
            autoScrollVelocityPx = 0f
            result.exceptionOrNull()
        }
    }

    BackHandler(enabled = isReordering) {
        cancelReorderMode()
    }

    val elevatedDragRowColor = remember(palette.backgroundBottom) {
        lerp(palette.backgroundBottom, Color.White, 0.12f)
    }

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
    if (showDownloadControlSheet) {
        PlaylistDownloadControlSheet(
            summary = downloadSummary,
            onPause = { onPausePlaylistDownloads(downloadSummary.activeTaskKeys) },
            onResume = {
                val resumable = downloadSummary.pausedTaskKeys + downloadSummary.failedTaskKeys
                if (resumable.isNotEmpty()) {
                    onResumePlaylistDownloads(resumable)
                } else {
                    onDownloadPlaylist(currentResult.playlist, currentResult.tracks)
                }
            },
            onCancelRemaining = { onCancelPlaylistDownloads(downloadSummary.allPendingTaskKeys) },
            onDismiss = { showDownloadControlSheet = false },
        )
    }
    if (showDeleteOfflineDialog) {
        DeletePlaylistOfflineDialog(
            downloadedCount = downloadSummary.downloadedCount,
            onConfirm = {
                showDeleteOfflineDialog = false
                onDeleteDownloadedPlaylist(currentResult.playlist, currentResult.tracks)
            },
            onDismiss = { showDeleteOfflineDialog = false },
        )
    }

    val handleTopSearchClick: () -> Unit = {
        if (isScrolledPastHero) {
            stickySearchOpen = !stickySearchOpen
        } else {
            scope.launch {
                val targetIndex = if (layoutInfo.isWide) 0 else 1
                trackListState.animateScrollToItem(targetIndex)
                runCatching { inlineSearchFocusRequester.requestFocus() }
            }
        }
    }

    val handleDownloadAction: () -> Unit = {
        when (downloadSummary.phase) {
            PlaylistDownloadPhase.AllDone -> showDeleteOfflineDialog = true
            PlaylistDownloadPhase.Downloading,
            PlaylistDownloadPhase.Paused -> showDownloadControlSheet = true
            PlaylistDownloadPhase.Partial,
            PlaylistDownloadPhase.None -> onDownloadPlaylist(currentResult.playlist, currentResult.tracks)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isReordering) {
            PlaylistReorderTopBar(
                moveCount = reorderMoveCount,
                isSaving = isSavingOrder,
                onCancel = ::cancelReorderMode,
                onSave = ::saveReorderMode,
            )
        } else {
            val topBarTitleAlpha by animateFloatAsState(
                targetValue = if (isScrolledPastHero) 1f else 0f,
                label = "playlist_topbar_title_alpha",
            )
            Column(modifier = Modifier.fillMaxWidth()) {
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BackCircleButton(
                        onClick = onBack,
                        contentDescription = Strings.backToPlaylists,
                    )
                    Text(
                        text = currentResult.playlist.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 6.dp)
                            .alpha(topBarTitleAlpha),
                    )
                    if (currentResult.tracks.isNotEmpty()) {
                        BareIcon(
                            icon = Icons.Filled.Search,
                            contentDescription = Strings.searchPlaylistAction,
                            onClick = handleTopSearchClick,
                            tint = AppColors.TextPrimary,
                            size = 42.dp,
                            iconSize = 22.dp,
                        )
                    }
                    if (canReorder) {
                        BareIcon(
                            icon = Icons.Filled.SwapVert,
                            contentDescription = Strings.reorderPlaylistAction,
                            onClick = ::enterReorderMode,
                            tint = AppColors.TextPrimary,
                            size = 42.dp,
                            iconSize = 22.dp,
                        )
                    }
                    if (editable || currentResult.tracks.isNotEmpty()) {
                        Box {
                            BareIcon(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = Strings.playlistActions,
                                onClick = { showPlaylistMenu = true },
                                tint = AppColors.TextPrimary,
                                size = 42.dp,
                                iconSize = 22.dp,
                                modifier = Modifier.offset(x = 8.dp),
                            )
                            DropdownMenu(
                                expanded = showPlaylistMenu,
                                onDismissRequest = { showPlaylistMenu = false },
                                shape = RoundedCornerShape(16.dp),
                            ) {
                                if (canReorder) {
                                    DropdownMenuItem(
                                        text = { Text(Strings.reorderPlaylistAction) },
                                        leadingIcon = { Icon(Icons.Filled.SwapVert, contentDescription = null) },
                                        onClick = {
                                            showPlaylistMenu = false
                                            enterReorderMode()
                                        },
                                    )
                                }
                                if (currentResult.tracks.isNotEmpty()) {
                                    val (dlLabel, dlIcon) = when (downloadSummary.phase) {
                                        PlaylistDownloadPhase.AllDone -> Strings.removePlaylistOfflineAction to Icons.Filled.DeleteOutline
                                        PlaylistDownloadPhase.Downloading,
                                        PlaylistDownloadPhase.Paused -> Strings.managePlaylistDownload to Icons.Filled.Download
                                        PlaylistDownloadPhase.Partial -> Strings.downloadRemainingTracks(downloadSummary.remainingCount) to Icons.Filled.Download
                                        PlaylistDownloadPhase.None -> Strings.downloadWholePlaylist to Icons.Filled.Download
                                    }
                                    DropdownMenuItem(
                                        text = { Text(dlLabel) },
                                        leadingIcon = { Icon(dlIcon, contentDescription = null) },
                                        onClick = {
                                            showPlaylistMenu = false
                                            handleDownloadAction()
                                        },
                                    )
                                }
                                if (editable) {
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
                }
                AnimatedVisibility(
                    visible = stickySearchOpen && !isReordering && currentResult.tracks.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = layoutInfo.horizontalPadding,
                                end = layoutInfo.horizontalPadding,
                                top = 4.dp,
                                bottom = 8.dp,
                            ),
                    ) {
                        PlaylistCompactSearchBox(
                            query = filterState.query,
                            onQueryChange = { filterState = filterState.copy(query = it) },
                            focusRequester = stickySearchFocusRequester,
                            autoFocus = true,
                        )
                    }
                }
            }
        }

        val renderTrackItems: LazyListScope.() -> Unit = {
            if (isReordering) {
                item(key = "playlist-reorder-hint", contentType = "playlist-reorder-hint") {
                    PlaylistReorderHintBanner()
                }
                itemsIndexed(
                    items = reorderSlots,
                    key = { _, slot -> slot.id },
                    contentType = { _, _ -> "reorder-track" },
                ) { index, slot ->
                    val isDragged = slot.id == draggedSlotId
                    val dragScale by animateFloatAsState(
                        targetValue = if (isDragged) 1.015f else 1f,
                        label = "playlist_drag_scale",
                    )
                    PlaylistReorderTrackRow(
                        index = index,
                        track = slot.track,
                        nowPlaying = nowPlaying?.takeIf { it.matches(slot.track) },
                        isDragged = isDragged,
                        elevatedColor = elevatedDragRowColor,
                        modifier = Modifier
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
                                shape = RoundedCornerShape(12.dp)
                                clip = isDragged
                            },
                        handleModifier = Modifier
                            .semantics { contentDescription = Strings.queueReorderHandle }
                            .pointerInput(slot.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        if (isSavingOrder) return@detectDragGestures
                                        draggedSlotId = slot.id
                                        dragOffsetY = 0f
                                        autoScrollVelocityPx = 0f
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragEnd = {
                                        draggedSlotId = null
                                        dragOffsetY = 0f
                                        autoScrollVelocityPx = 0f
                                    },
                                    onDragCancel = {
                                        draggedSlotId = null
                                        dragOffsetY = 0f
                                        autoScrollVelocityPx = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        if (draggedSlotId != slot.id) return@detectDragGestures
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        evaluateReorderSwap()

                                        val layout = trackListState.layoutInfo
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
                    )
                }
            } else {
                if (currentResult.tracks.isNotEmpty()) {
                    item(key = "playlist-filter-bar", contentType = "playlist-filter-bar") {
                        PlaylistFilterBar(
                            filter = filterState,
                            totalCount = currentResult.tracks.size,
                            matchedCount = filteredEntries.size,
                            downloadedCount = downloadSummary.downloadedCount,
                            favoritedCount = favoritedCount,
                            canReorder = canReorder,
                            isPlaybackLoading = isPlaybackLoading,
                            searchFocusRequester = inlineSearchFocusRequester,
                            onQueryChange = { filterState = filterState.copy(query = it) },
                            onToggleDownloaded = {
                                filterState = filterState.copy(downloadedOnly = !filterState.downloadedOnly)
                            },
                            onToggleFavorited = {
                                filterState = filterState.copy(favoritedOnly = !filterState.favoritedOnly)
                            },
                            onClearFilter = {
                                filterState = PlaylistFilterState()
                                stickySearchOpen = false
                            },
                            onPlayFiltered = {
                                val subset = filteredEntries.map { it.track }
                                if (subset.isNotEmpty()) {
                                    onPlayPlaylist(currentResult.copy(tracks = subset))
                                }
                            },
                            onEnterReorder = ::enterReorderMode,
                        )
                    }
                }
                if (currentResult.tracks.isNotEmpty() && filteredEntries.isEmpty()) {
                    item(key = "playlist-filter-empty", contentType = "playlist-filter-empty") {
                        PlaylistFilterEmptyState(
                            query = filterState.query,
                            onClearFilter = {
                                filterState = PlaylistFilterState()
                                stickySearchOpen = false
                            },
                        )
                    }
                } else {
                    items(
                        items = filteredEntries,
                        key = { entry -> "${entry.originalIndex}:${entry.track.ratingKey}" },
                        contentType = { "track" },
                    ) { entry ->
                        val track = entry.track
                        Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                            PlaylistDetailTrackRow(
                                index = entry.originalIndex,
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
                                onRemoveFromPlaylist = removeTrack,
                                nowPlaying = nowPlaying?.takeIf { it.matches(track) },
                                highlightTokens = highlightTokens,
                                isExplore = dailyMix != null && track.ratingKey in dailyMix.exploreTrackIds,
                                isRecommendationBlocked = dailyMix != null && track.ratingKey in dailyMix.blockedTrackIds,
                                onToggleRecommendationBlock = if (dailyMix != null) {
                                    { onToggleRecommendationBlock(track) }
                                } else {
                                    null
                                },
                            )
                        }
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
                        .padding(bottom = bottomContentPadding)
                        .alpha(if (isReordering) 0.35f else 1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PlaylistDetailHeroWide(
                        dailyMix = dailyMix,
                        onRateDailyMix = onRateDailyMix,
                        onReplaceDailyMix = onReplaceDailyMix,
                        onSaveDailyMix = onSaveDailyMix,
                        playlistResult = currentResult,
                        downloadSummary = downloadSummary,
                        outputTargetName = outputTargetName,
                        isPlaybackLoading = isPlaybackLoading || isReordering,
                        aiDescription = aiDescription,
                        onSaveAiPlaylist = onSaveAiPlaylist,
                        onPlayPlaylist = onPlayPlaylist,
                        onShufflePlaylist = onShufflePlaylist,
                        onDownloadAction = handleDownloadAction,
                    )
                    if (editable && currentResult.tracks.isEmpty()) EmptyPlaylistHint()
                }
                LazyColumn(
                    state = trackListState,
                    modifier = Modifier
                        .weight(1.18f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(bottom = bottomContentPadding),
                    content = renderTrackItems,
                )
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
                    Box(modifier = Modifier.alpha(if (isReordering) 0.35f else 1f)) {
                        PlaylistDetailHeroCompact(
                            dailyMix = dailyMix,
                            onRateDailyMix = onRateDailyMix,
                            onReplaceDailyMix = onReplaceDailyMix,
                            onSaveDailyMix = onSaveDailyMix,
                            playlistResult = currentResult,
                            downloadSummary = downloadSummary,
                            outputTargetName = outputTargetName,
                            isPlaybackLoading = isPlaybackLoading || isReordering,
                            aiDescription = aiDescription,
                            onSaveAiPlaylist = onSaveAiPlaylist,
                            onPlayPlaylist = onPlayPlaylist,
                            onShufflePlaylist = onShufflePlaylist,
                            onDownloadAction = handleDownloadAction,
                        )
                    }
                }
                if (editable && currentResult.tracks.isEmpty()) {
                    item(key = "playlist-empty", contentType = "playlist-empty") { EmptyPlaylistHint() }
                }
                renderTrackItems()
            }
        }
    }
}

@Composable
private fun PlaylistDetailHeroWide(
    dailyMix: DailyMixDetailState?,
    onRateDailyMix: (Int, List<String>) -> Unit,
    onReplaceDailyMix: () -> Unit,
    onSaveDailyMix: () -> Unit,
    playlistResult: PlaylistTracksResult,
    downloadSummary: PlaylistDownloadSummary,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    aiDescription: String?,
    onSaveAiPlaylist: () -> Unit,
    onPlayPlaylist: (PlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlaylistTracksResult) -> Unit,
    onDownloadAction: () -> Unit,
) {
    val playlist = playlistResult.playlist
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val coverModifier = Modifier
            .widthIn(max = 300.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
        val heroShape = RoundedCornerShape(14.dp)
        if (dailyMix != null) {
            Box(coverModifier) {
                DailyMixMosaic(dailyMix.mix.coverUrls, playlist.title, 300.dp, shape = heroShape)
            }
        } else {
            AsyncAlbumArtwork(
                imageUrl = playlist.thumbUrl,
                title = playlist.title,
                modifier = coverModifier,
                thumbnailSize = 300.dp,
                fit = ArtworkFit.Contain,
                alignment = Alignment.Center,
                shape = heroShape,
            )
        }
    }
    PlaylistHeroText(
        playlistResult = playlistResult,
        downloadSummary = downloadSummary,
        aiDescription = aiDescription,
        onSaveAiPlaylist = onSaveAiPlaylist,
        centered = false,
        themeLine = dailyMix?.mix?.themeLine(),
    )
    PlaylistActionRow(
        playlistResult = playlistResult,
        downloadSummary = downloadSummary,
        outputTargetName = outputTargetName,
        isPlaybackLoading = isPlaybackLoading,
        onPlayPlaylist = onPlayPlaylist,
        onShufflePlaylist = onShufflePlaylist,
        onDownloadAction = onDownloadAction,
    )
    if (dailyMix != null) {
        DailyMixFeedbackPanel(dailyMix, onRateDailyMix, onReplaceDailyMix, onSaveDailyMix)
    }
}

@Composable
private fun PlaylistDetailHeroCompact(
    dailyMix: DailyMixDetailState?,
    onRateDailyMix: (Int, List<String>) -> Unit,
    onReplaceDailyMix: () -> Unit,
    onSaveDailyMix: () -> Unit,
    playlistResult: PlaylistTracksResult,
    downloadSummary: PlaylistDownloadSummary,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    aiDescription: String?,
    onSaveAiPlaylist: () -> Unit,
    onPlayPlaylist: (PlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlaylistTracksResult) -> Unit,
    onDownloadAction: () -> Unit,
) {
    val playlist = playlistResult.playlist
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val coverModifier = Modifier
            .padding(top = 4.dp)
            .fillMaxWidth(0.6f)
            .widthIn(max = 280.dp)
            .aspectRatio(1f)
        val heroShape = RoundedCornerShape(14.dp)
        if (dailyMix != null) {
            Box(coverModifier) {
                DailyMixMosaic(dailyMix.mix.coverUrls, playlist.title, 280.dp, shape = heroShape)
            }
        } else {
            AsyncAlbumArtwork(
                imageUrl = playlist.thumbUrl,
                title = playlist.title,
                modifier = coverModifier,
                thumbnailSize = 280.dp,
                fit = ArtworkFit.Contain,
                alignment = Alignment.Center,
                shape = heroShape,
            )
        }
        Spacer(Modifier.height(18.dp))
        PlaylistHeroText(
            playlistResult = playlistResult,
            downloadSummary = downloadSummary,
            aiDescription = aiDescription,
            onSaveAiPlaylist = onSaveAiPlaylist,
            centered = true,
            themeLine = dailyMix?.mix?.themeLine(),
        )
        Spacer(Modifier.height(18.dp))
        PlaylistActionRow(
            playlistResult = playlistResult,
            downloadSummary = downloadSummary,
            outputTargetName = outputTargetName,
            isPlaybackLoading = isPlaybackLoading,
            onPlayPlaylist = onPlayPlaylist,
            onShufflePlaylist = onShufflePlaylist,
            onDownloadAction = onDownloadAction,
        )
        if (dailyMix != null) {
            Spacer(Modifier.height(12.dp))
            DailyMixFeedbackPanel(dailyMix, onRateDailyMix, onReplaceDailyMix, onSaveDailyMix)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun PlaylistHeroText(
    playlistResult: PlaylistTracksResult,
    downloadSummary: PlaylistDownloadSummary,
    aiDescription: String?,
    onSaveAiPlaylist: () -> Unit,
    centered: Boolean,
    themeLine: String? = null,
) {
    val playlist = playlistResult.playlist
    val palette = LocalArtworkPalette.current
    val align = if (centered) TextAlign.Center else TextAlign.Start
    val downloadStatusText = remember(downloadSummary) {
        val percent = (downloadSummary.fraction * 100f).roundToInt().coerceIn(0, 100)
        when (downloadSummary.phase) {
            PlaylistDownloadPhase.AllDone -> Strings.playlistAllDownloadedBadge
            PlaylistDownloadPhase.Downloading -> Strings.playlistDownloadingBadge(downloadSummary.downloadedCount, downloadSummary.total, percent)
            PlaylistDownloadPhase.Paused -> Strings.playlistDownloadPausedBadge(downloadSummary.downloadedCount, downloadSummary.total, percent)
            PlaylistDownloadPhase.Partial -> Strings.playlistPartialDownloadedBadge(downloadSummary.downloadedCount, downloadSummary.total)
            PlaylistDownloadPhase.None -> null
        }
    }
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append(Strings.tracks(playlistResult.tracks.size))
                    val totalMillis = playlistResult.tracks.sumOf { it.durationMillis ?: 0L }
                    if (totalMillis > 0L) append(" · ").append(formatDuration(totalMillis))
                },
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (downloadStatusText != null) {
                Text("·", color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
                if (downloadSummary.phase == PlaylistDownloadPhase.AllDone) {
                    Icon(
                        imageVector = Icons.Filled.DownloadDone,
                        contentDescription = null,
                        tint = palette.accent,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = downloadStatusText,
                    color = palette.accent,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!themeLine.isNullOrBlank()) {
            Text(
                themeLine,
                color = palette.accent,
                style = MaterialTheme.typography.labelLarge,
                textAlign = align,
            )
        }
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
            if (playlistResult.tracks.isNotEmpty()) {
                LabelKey(
                    text = Strings.dailyMixSave,
                    icon = Icons.Filled.LibraryAdd,
                    onClick = onSaveAiPlaylist,
                    height = 40.dp,
                )
            }
        }
    }
}

@Composable
private fun PlaylistActionRow(
    playlistResult: PlaylistTracksResult,
    downloadSummary: PlaylistDownloadSummary,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    onPlayPlaylist: (PlaylistTracksResult) -> Unit,
    onShufflePlaylist: (PlaylistTracksResult) -> Unit,
    onDownloadAction: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitPlayKey(
            label = Strings.playOn(outputTargetName),
            onPlay = { onPlayPlaylist(playlistResult) },
            onShuffle = { onShufflePlaylist(playlistResult) },
            enabled = !isPlaybackLoading && playlistResult.tracks.isNotEmpty(),
            isLoading = isPlaybackLoading,
            modifier = Modifier.weight(1f),
        )
        PlaylistDownloadKey(
            summary = downloadSummary,
            enabled = !isPlaybackLoading,
            onStartDownload = onDownloadAction,
            onManageDownload = onDownloadAction,
            onRequestDeleteOffline = onDownloadAction,
        )
    }
}

@Composable
private fun PlaylistReorderTrackRow(
    index: Int,
    track: MusicTrack,
    nowPlaying: NowPlayingMarker?,
    isDragged: Boolean,
    elevatedColor: Color,
    modifier: Modifier = Modifier,
    handleModifier: Modifier = Modifier,
) {
    val palette = LocalArtworkPalette.current
    val isCurrent = nowPlaying != null
    val subtitle = listOfNotNull(
        track.artistName?.takeIf { it.isNotBlank() },
        track.durationMillis?.let { formatDuration(it) },
    ).joinToString(" · ")

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDragged) elevatedColor else Color.Transparent),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .padding(start = if (isDragged) 8.dp else 4.dp, end = 2.dp),
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
                if (subtitle.isNotEmpty()) {
                    Text(
                        text = subtitle,
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .then(handleModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = if (isDragged) palette.accent else AppColors.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        if (!isDragged) {
            Hairline(modifier = Modifier.fillMaxWidth().padding(start = 40.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistDetailTrackRow(
    index: Int,
    playlist: MusicPlaylist,
    track: MusicTrack,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    downloadedTrackKeys: Set<String>,
    downloadProgress: Map<String, Float>,
    onDownloadTrack: (MusicTrack) -> Unit,
    onDeleteDownloadedTrack: (MusicTrack) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
    onQueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onPlayTrack: (MusicPlaylist, MusicTrack, Int) -> Unit,
    onRemoveFromPlaylist: ((MusicTrack) -> Unit)? = null,
    nowPlaying: NowPlayingMarker? = null,
    highlightTokens: List<String> = emptyList(),
    isExplore: Boolean = false,
    isRecommendationBlocked: Boolean = false,
    onToggleRecommendationBlock: (() -> Unit)? = null,
) {
    val menuState = rememberContextualMenuState()
    val isCurrent = nowPlaying != null
    val isDownloaded = remember(track.sourceId, track.ratingKey, downloadedTrackKeys) {
        downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)
    }
    val progress = downloadProgress["${track.sourceId}:${track.ratingKey}"]
    val palette = LocalArtworkPalette.current
    val subtitle = listOfNotNull(
        track.artistName?.takeIf { it.isNotBlank() },
        if (highlightTokens.isNotEmpty()) track.albumTitle?.takeIf { it.isNotBlank() } else null,
        track.durationMillis?.let { formatDuration(it) },
    ).joinToString(" · ")
    val titleAnnotated = remember(track.title, highlightTokens, palette.accent) {
        highlightedPlaylistText(track.title, highlightTokens, palette.accent)
    }
    val subtitleAnnotated = remember(subtitle, highlightTokens, palette.accent) {
        highlightedPlaylistText(subtitle, highlightTokens, palette.accent)
    }
    val renderMenu: @Composable () -> Unit = {
        QueueActionMenu(
            expanded = menuState.expanded,
            onDismiss = { menuState.dismiss() },
            onAction = { placement ->
                menuState.dismiss()
                onQueueTrack(track, placement)
            },
            isDownloaded = isDownloaded,
            onDownloadAction = {
                if (isDownloaded) onDeleteDownloadedTrack(track) else onDownloadTrack(track)
            },
            onAddToPlaylist = addTrackToPlaylistAction(track),
            onRemoveFromPlaylist = onRemoveFromPlaylist?.let { remove -> { remove(track) } },
            recommendationBlocked = isRecommendationBlocked,
            onToggleRecommendationBlock = onToggleRecommendationBlock,
        )
    }

    // Editorial hairline row: number · title / artist · duration · favourite · more.
    Box {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .trackMenuPressPosition(menuState)
                    .combinedClickable(
                        enabled = !isPlaybackLoading,
                        onClick = { onPlayTrack(playlist, track, index) },
                        onLongClick = { menuState.openAtLastPress() },
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
                        text = titleAnnotated,
                        color = if (isRecommendationBlocked && !isCurrent) AppColors.TextTertiary else nowPlayingTitleColor(isCurrent),
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
                        if (isExplore) {
                            Text(
                                Strings.exploreTrack,
                                color = palette.accent,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        if (isRecommendationBlocked) {
                            Text(
                                Strings.blockRecommendation,
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                            )
                        }
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitleAnnotated,
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
                Box(modifier = Modifier.offset(x = 6.dp)) {
                    BareIcon(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = Strings.more,
                        onClick = { menuState.openAtAnchor() },
                        tint = AppColors.TextTertiary,
                        size = 44.dp,
                        iconSize = 20.dp,
                    )
                    if (menuState.pressOffset == null) {
                        renderMenu()
                    }
                }
            }
            Hairline(modifier = Modifier.fillMaxWidth().padding(start = 40.dp))
        }
        ContextualMenuAnchor(menuState, renderMenu)
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
