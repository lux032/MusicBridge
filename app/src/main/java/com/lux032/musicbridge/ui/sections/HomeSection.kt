package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
internal fun HomeSection(
    connectionPreferences: MusicServerProfile,
    allAlbums: List<PlexAlbum>,
    recentPlayedAlbums: List<PlexAlbum>,
    favoriteAlbums: List<PlexAlbum>,
    recentAddedAlbums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    playbackTarget: PlaybackTarget,
    sonosRooms: List<SonosRoom>,
    isRoomScanning: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    actionMessage: String?,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    listState: LazyListState = rememberLazyListState(),
    onSelectTarget: (PlaybackTarget) -> Unit,
    onRefreshRooms: () -> Unit,
    onRefreshFavorites: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
    onOpenAllAlbums: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecentAdded: () -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val needsConnection = !connectionPreferences.hasCredentials() && allAlbums.isEmpty()
    var isOutputDialogVisible by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned top-right output picker: a small icon button instead of a full-width strip.
        // The icon mirrors the current target (phone vs speaker) so the state is readable
        // at a glance without spending a row of feed space on it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = layoutInfo.horizontalPadding, top = 8.dp, end = layoutInfo.horizontalPadding),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconCircleButton(
                onClick = { isOutputDialogVisible = true },
                containerColor = Color.White.copy(alpha = 0.10f),
            ) {
                Icon(
                    imageVector = if (playbackTarget is PlaybackTarget.Sonos) {
                        Icons.Filled.Speaker
                    } else {
                        Icons.Filled.PhoneAndroid
                    },
                    contentDescription = Strings.selectOutputDevice,
                    tint = AppColors.TextPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                start = layoutInfo.horizontalPadding,
                top = 8.dp,
                end = layoutInfo.horizontalPadding,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(layoutInfo.contentSpacing),
        ) {
        errorMessage?.let { message ->
            item(key = "home-error", contentType = "message") {
                MessageCard(label = Strings.error, message = message, tone = MessageTone.Error)
            }
        }
        if (isLoading) {
            item(key = "home-loading", contentType = "message") {
                LoadingCard(message = Strings.loading)
            }
        }
        if (needsConnection) {
            item(key = "home-connection", contentType = "empty") {
                EmptyStateCard(
                    title = Strings.settingsPromptTitle,
                    body = Strings.settingsPromptBody,
                    actionLabel = Strings.refreshHome,
                    onAction = onRefreshFavorites,
                )
            }
        } else {
            if (!isLoading && favoriteAlbums.isEmpty() && errorMessage == null) {
                item(key = "home-no-favorites", contentType = "empty") {
                    EmptyStateCard(
                        title = Strings.noFavoritesTitle,
                        body = Strings.noFavoritesBody,
                        actionLabel = Strings.retry,
                        onAction = onRefreshFavorites,
                    )
                }
            }
            if (allAlbums.isNotEmpty()) {
                item(key = "home-all-albums", contentType = "entry") {
                    EntryActionCard(
                        title = Strings.allAlbums,
                        body = Strings.albumsSynced(allAlbums.size),
                        actionLabel = Strings.view,
                        onAction = onOpenAllAlbums,
                    )
                }
            }
            if (recentPlayedAlbums.isNotEmpty()) {
                item(key = "home-recent-played", contentType = "album-shelf") {
                    RecentPlayedShelfSection(
                        albums = recentPlayedAlbums.take(20),
                        selectedAlbum = selectedAlbum,
                        onAlbumClick = onAlbumClick,
                        onAlbumQueueAction = onAlbumQueueAction,
                    )
                }
            }
            if (favoriteAlbums.isNotEmpty()) {
                albumPreviewGridItems(
                    keyPrefix = "home-favorites",
                    title = Strings.favoriteAlbums,
                    albums = favoriteAlbums.take(layoutInfo.homePreviewItemCount),
                    columns = layoutInfo.homePreviewColumns,
                    selectedAlbum = selectedAlbum,
                    actionLabel = Strings.viewAll,
                    onAction = onOpenFavorites,
                    onAlbumClick = onAlbumClick,
                    onAlbumQueueAction = onAlbumQueueAction,
                )
            }
            if (recentAddedAlbums.isNotEmpty()) {
                albumPreviewGridItems(
                    keyPrefix = "home-recent-added",
                    title = Strings.recentAdded,
                    albums = recentAddedAlbums.take(layoutInfo.homePreviewItemCount),
                    columns = layoutInfo.homePreviewColumns,
                    selectedAlbum = selectedAlbum,
                    actionLabel = Strings.viewTop100,
                    onAction = onOpenRecentAdded,
                    onAlbumClick = onAlbumClick,
                    onAlbumQueueAction = onAlbumQueueAction,
                )
            }
        }
    }
    }

    if (isOutputDialogVisible) {
        OutputTargetDialog(
            currentTarget = playbackTarget,
            rooms = sonosRooms,
            isRoomScanning = isRoomScanning,
            onDismiss = { isOutputDialogVisible = false },
            onSelectTarget = onSelectTarget,
            onRefreshRooms = onRefreshRooms,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentPlayedShelfSection(
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = Strings.recentPlayed,
            style = MaterialTheme.typography.titleLarge,
            color = AppColors.TextPrimary,
            fontWeight = FontWeight.SemiBold,
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = layoutInfo.horizontalPadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            lazyItems(
                items = albums,
                key = { it.ratingKey },
                contentType = { "recent-album" },
            ) { album ->
                var showQueueMenu by remember { mutableStateOf(false) }
                Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                Column(
                    modifier = Modifier
                        .width(layoutInfo.recentAlbumCardWidth)
                        .combinedClickable(
                            onClick = { onAlbumClick(album) },
                            onLongClick = { showQueueMenu = true },
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                        shape = RoundedCornerShape(20.dp),
                        color = AppColors.CardSurface,
                        border = BorderStroke(
                            width = if (selectedAlbum?.ratingKey == album.ratingKey) 1.5.dp else 1.dp,
                            color = if (selectedAlbum?.ratingKey == album.ratingKey) LocalArtworkPalette.current.accent else AppColors.Border,
                        ),
                    ) {
                        AsyncAlbumArtwork(
                            imageUrl = album.thumbUrl,
                            title = album.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            thumbnailSize = layoutInfo.recentAlbumCardWidth,
                        )
                    }
                    Text(
                        text = album.title,
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = album.artistName ?: Strings.unknownArtist,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                    QueueActionMenu(
                        expanded = showQueueMenu,
                        onDismiss = { showQueueMenu = false },
                        onAction = { placement ->
                            showQueueMenu = false
                            onAlbumQueueAction(album, placement)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun EntryActionCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    // Plain section-header style (no card chrome) so it blends into the tinted background,
    // matching the other home section headers.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAction),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = body,
                color = AppColors.TextSecondary,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "$actionLabel ›",
            color = LocalArtworkPalette.current.accent,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Emits a preview grid as one item per row instead of a single giant item, so scrolling past
 * it composes a handful of covers per frame rather than the whole grid at once.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.albumPreviewGridItems(
    keyPrefix: String,
    title: String,
    albums: List<PlexAlbum>,
    columns: Int,
    selectedAlbum: PlexAlbum?,
    actionLabel: String,
    onAction: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
) {
    item(key = "$keyPrefix-header", contentType = "grid-header") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = onAction) {
                Text(
                    text = "$actionLabel ›",
                    color = LocalArtworkPalette.current.accent,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    albums.chunked(columns).forEachIndexed { rowIndex, rowAlbums ->
        item(key = "$keyPrefix-row-$rowIndex", contentType = "album-row") {
            val layoutInfo = LocalAppLayoutInfo.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateItem(fadeInSpec = null, fadeOutSpec = null),
                horizontalArrangement = Arrangement.spacedBy(layoutInfo.contentSpacing),
            ) {
                rowAlbums.forEach { album ->
                    Box(modifier = Modifier.weight(1f)) {
                        AlbumCoverCard(
                            album = album,
                            selected = selectedAlbum?.ratingKey == album.ratingKey,
                            onClick = { onAlbumClick(album) },
                            onQueueAction = { onAlbumQueueAction(album, it) },
                            compact = true,
                        )
                    }
                }
                repeat(columns - rowAlbums.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun AlbumCollectionSection(
    title: String,
    subtitle: String,
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    gridState: LazyGridState = rememberLazyGridState(),
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val heroAlbum = albums.firstOrNull()
    if (heroAlbum == null) {
        EmptyStateCard(
            title = title,
            body = Strings.noAlbumsToShow,
            actionLabel = Strings.backToHome,
            onAction = onBack,
        )
        return
    }

    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(layoutInfo.albumGridColumns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layoutInfo.horizontalPadding,
            top = 12.dp,
            end = layoutInfo.horizontalPadding,
            bottom = bottomContentPadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "back") {
            BackCircleButton(onClick = onBack, contentDescription = Strings.backToHome)
        }
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "hero") {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AsyncAlbumArtwork(
                    imageUrl = heroAlbum.thumbUrl,
                    title = heroAlbum.title,
                    modifier = Modifier
                        .widthIn(max = layoutInfo.collectionHeroMaximumWidth)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                    thumbnailSize = layoutInfo.collectionHeroMaximumWidth,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$subtitle ${Strings.totalAlbums(albums.size)}",
                    color = AppColors.TextSecondary,
                )
                selectedAlbum?.let {
                    Text(
                        text = Strings.currentHighlight(it.title),
                        color = AppColors.TextTertiary,
                    )
                }
            }
        }
        items(
            items = albums,
            key = { it.ratingKey },
            contentType = { "album" },
        ) { album ->
            Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                AlbumCoverCard(
                    album = album,
                    selected = selectedAlbum?.ratingKey == album.ratingKey,
                    onClick = { onAlbumClick(album) },
                    onQueueAction = { onAlbumQueueAction(album, it) },
                    compact = false,
                )
            }
        }
    }
}

@Composable
internal fun AllAlbumsSection(
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    searchQuery: String,
    isSearchLoading: Boolean,
    sortOption: AlbumSortOption,
    favoritesOnly: Boolean,
    decadeFilter: AlbumDecadeFilter,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState = rememberLazyGridState(),
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
    onSortOptionChange: (AlbumSortOption) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onDecadeFilterChange: (AlbumDecadeFilter) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val palette = LocalArtworkPalette.current
    var isSortDialogVisible by remember { mutableStateOf(false) }
    var isDecadeDialogVisible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }

    val visibleAlbums = remember(albums, favoritesOnly, decadeFilter, sortOption) {
        val filtered = albums
            .filter { !favoritesOnly || it.isFavorite }
            .filter { decadeFilter.matches(it.year) }
        when (sortOption) {
            // Title/Artist ordering happens inside buildIndexedGroups (pinyin/letter aware).
            AlbumSortOption.Title,
            AlbumSortOption.Artist -> filtered
            AlbumSortOption.YearDesc -> filtered.sortedByDescending { it.year ?: Int.MIN_VALUE }
            AlbumSortOption.YearAsc -> filtered.sortedBy { it.year ?: Int.MAX_VALUE }
            AlbumSortOption.RecentlyAdded ->
                filtered.sortedByDescending { it.addedAtEpochSeconds ?: 0L }
            AlbumSortOption.RecentlyPlayed ->
                filtered.sortedByDescending { it.lastViewedAtEpochSeconds ?: 0L }
        }
    }
    val showIndexBar = sortOption == AlbumSortOption.Title || sortOption == AlbumSortOption.Artist
    val albumGroups = remember(visibleAlbums, showIndexBar, sortOption) {
        if (!showIndexBar) {
            emptyList()
        } else {
            buildIndexedGroups(visibleAlbums) {
                if (sortOption == AlbumSortOption.Artist) it.artistName else it.title
            }
        }
    }
    val jumpTargets = remember(albumGroups) {
        buildGridJumpTargets(leadingItemCount = 4, groups = albumGroups)
    }
    val heroSubtitle = buildString {
        append(Strings.albumsCount(visibleAlbums.size))
        append(" · ")
        append(sortOption.label)
        if (favoritesOnly) {
            append(" · ")
            append(Strings.filterFavoriteOnly)
        }
        if (decadeFilter != AlbumDecadeFilter.All) {
            append(" · ")
            append(decadeFilter.label)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(layoutInfo.albumGridColumns),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = layoutInfo.horizontalPadding),
            contentPadding = PaddingValues(
                top = 16.dp,
                // The 42dp strip is reserved for the alphabet index bar; without it (year /
                // recency sorts) the grid reclaims the space instead of leaving a dead gap.
                end = if (showIndexBar) 42.dp else 0.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                BackCircleButton(onClick = onBack, contentDescription = Strings.backToHome)
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = Strings.allAlbums,
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = heroSubtitle,
                        color = AppColors.TextSecondary,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(Strings.searchPlaceholder) },
                    singleLine = true,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AlbumControlChip(
                        icon = Icons.Filled.Sort,
                        label = sortOption.label,
                        selected = false,
                        onClick = { isSortDialogVisible = true },
                    )
                    AlbumControlChip(
                        icon = Icons.Filled.Favorite,
                        label = Strings.filterFavoriteOnly,
                        selected = favoritesOnly,
                        onClick = { onFavoritesOnlyChange(!favoritesOnly) },
                    )
                    AlbumControlChip(
                        icon = null,
                        label = "${Strings.filterDecadeTitle} · ${decadeFilter.label}",
                        selected = decadeFilter != AlbumDecadeFilter.All,
                        onClick = { isDecadeDialogVisible = true },
                    )
                }
            }
            if (isSearchLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadingCard(message = Strings.searchingLocal)
                }
            } else if (visibleAlbums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        albums.isNotEmpty() -> EmptyStateCard(
                            title = Strings.noFilteredAlbums,
                            body = Strings.noFilteredAlbumsDesc,
                            actionLabel = Strings.backToHome,
                            onAction = onBack,
                        )
                        searchQuery.isBlank() -> EmptyStateCard(
                            title = Strings.noLocalAlbums,
                            body = Strings.noLocalAlbumsDesc,
                            actionLabel = Strings.backToHome,
                            onAction = onBack,
                        )
                        else -> EmptyStateCard(
                            title = Strings.noSearchResults,
                            body = Strings.noSearchResultsDesc,
                            actionLabel = Strings.backToHome,
                            onAction = onBack,
                        )
                    }
                }
            } else if (showIndexBar) {
                albumGroups.forEach { group ->
                    item(
                        key = "album-header-${group.label}",
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = "header",
                    ) {
                        IndexSectionHeader(label = group.label)
                    }
                    items(
                        items = group.items,
                        key = { album -> album.ratingKey },
                        contentType = { "album" },
                    ) { album ->
                        Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                            AlbumCoverCard(
                                album = album,
                                selected = selectedAlbum?.ratingKey == album.ratingKey,
                                onClick = { onAlbumClick(album) },
                                onQueueAction = { onAlbumQueueAction(album, it) },
                                compact = false,
                            )
                        }
                    }
                }
            } else {
                items(
                    items = visibleAlbums,
                    key = { album -> album.ratingKey },
                    contentType = { "album" },
                ) { album ->
                    Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                        AlbumCoverCard(
                            album = album,
                            selected = selectedAlbum?.ratingKey == album.ratingKey,
                            onClick = { onAlbumClick(album) },
                            onQueueAction = { onAlbumQueueAction(album, it) },
                            compact = false,
                        )
                    }
                }
            }
        }

        if (showIndexBar && !isSearchLoading && jumpTargets.size > 1) {
            VerticalIndexBar(
                labels = jumpTargets.map { it.first },
                modifier = Modifier.padding(top = 100.dp, end = 4.dp, bottom = 12.dp),
                onSelect = { label ->
                    jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                        jumpJob?.cancel()
                        jumpJob = scope.launch { gridState.scrollToItem(index) }
                    }
                },
            )
        }
    }

    if (isSortDialogVisible) {
        AlbumOptionPickerDialog(
            title = Strings.sortDialogTitle,
            options = AlbumSortOption.entries,
            selected = sortOption,
            labelOf = { it.label },
            onSelect = onSortOptionChange,
            onDismiss = { isSortDialogVisible = false },
        )
    }
    if (isDecadeDialogVisible) {
        AlbumOptionPickerDialog(
            title = Strings.filterDecadeTitle,
            options = AlbumDecadeFilter.entries,
            selected = decadeFilter,
            labelOf = { it.label },
            onSelect = onDecadeFilterChange,
            onDismiss = { isDecadeDialogVisible = false },
        )
    }
}

@Composable
private fun AlbumControlChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = LocalArtworkPalette.current.accent
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) AppColors.CardSurfaceStrong else AppColors.CardSurface,
        border = BorderStroke(1.dp, if (selected) accent else AppColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = if (selected) accent else AppColors.TextSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
            Text(
                text = label,
                color = if (selected) accent else AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** Single-choice option list dialog in the same style as the output-device picker. */
@Composable
private fun <T> AlbumOptionPickerDialog(
    title: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Text(
                text = title,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                onSelect(option)
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = if (isSelected) AppColors.CardSurfaceStrong else AppColors.CardSurface,
                        border = BorderStroke(1.dp, if (isSelected) LocalArtworkPalette.current.accent else AppColors.Border),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(labelOf(option), color = AppColors.TextPrimary)
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = LocalArtworkPalette.current.accent,
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
internal fun SonosSettingsCard(
    rooms: List<SonosRoom>,
    selectedRoom: SonosRoom?,
    isLoading: Boolean,
    discoveryAttempted: Boolean,
    onDiscover: () -> Unit,
    onSelectRoom: (SonosRoom) -> Unit,
) {
    val palette = LocalArtworkPalette.current
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AppColors.CardSurface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(Strings.sonosRooms, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
            Text(Strings.sonosRoomsDesc, color = AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDiscover,
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.accent,
                        contentColor = palette.onAccent,
                    ),
                ) {
                    Text(Strings.discoverDevices)
                }
            }

            if (discoveryAttempted && rooms.isEmpty() && !isLoading) {
                Text(Strings.noRoomsFound, color = AppColors.ErrorText)
            }

            rooms.forEach { room ->
                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val scale by animateFloatAsState(
                    targetValue = if (isPressed) 0.97f else 1f,
                    label = "room_scale",
                )
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    shape = RoundedCornerShape(18.dp),
                    color = if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) AppColors.CardSurfaceStrong else AppColors.CardSurface,
                    border = BorderStroke(1.dp, if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) AppColors.BorderStrong else AppColors.Border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = interactionSource,
                                indication = ripple(bounded = true),
                                onClick = { onSelectRoom(room) },
                            )
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(room.roomName, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                            Text(
                                if (room.memberCount > 1) Strings.members(room.memberCount) else Strings.singleRoom,
                                color = AppColors.TextSecondary,
                            )
                        }
                        Text(
                            if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) Strings.currentRoom else Strings.select,
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}
