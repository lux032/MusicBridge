package com.lux032.musicbridge

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lux032.musicbridge.model.ArtistAlbumCategory
import com.lux032.musicbridge.model.ArtistAlbumClassification
import com.lux032.musicbridge.model.ReleaseKind
import com.lux032.musicbridge.model.classifyArtistAlbum
import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
internal fun ArtistsSection(
    artists: List<ArtistSummary>,
    presentation: ArtistPresentation,
    searchQuery: String,
    favoritesOnly: Boolean,
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    onPresentationChange: (ArtistPresentation) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
    onGoHome: () -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val compactPhone = !layoutInfo.isTablet && !layoutInfo.isLandscape
    if (artists.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmptyStateCard(
                title = Strings.noArtists,
                body = Strings.noArtistsDesc,
                actionLabel = Strings.goHome,
                onAction = onGoHome,
            )
        }
        return
    }

    val visibleArtists = remember(artists, searchQuery, favoritesOnly) {
        filterArtists(artists, searchQuery, favoritesOnly)
    }
    val artistGroups = remember(visibleArtists) {
        buildIndexedGroups(visibleArtists) { it.name }
    }
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }

    // A changed query/filter reshapes the list, so return to the top (where the search field
    // lives). The initial composition keeps the restored scroll position.
    var appliedFilter by remember { mutableStateOf(searchQuery to favoritesOnly) }
    LaunchedEffect(searchQuery, favoritesOnly) {
        val current = searchQuery to favoritesOnly
        if (current != appliedFilter) {
            appliedFilter = current
            when (presentation) {
                ArtistPresentation.Covers -> gridState.scrollToItem(0)
                ArtistPresentation.List -> listState.scrollToItem(0)
            }
        }
    }

    val header: @Composable () -> Unit = {
        ArtistsHeader(
            visibleCount = visibleArtists.size,
            presentation = presentation,
            searchQuery = searchQuery,
            favoritesOnly = favoritesOnly,
            onPresentationChange = onPresentationChange,
            onSearchQueryChange = onSearchQueryChange,
            onFavoritesOnlyChange = onFavoritesOnlyChange,
        )
    }
    val emptyFilterCard: @Composable () -> Unit = {
        EmptyStateCard(
            title = Strings.noFilteredArtists,
            body = Strings.noFilteredArtistsDesc,
            actionLabel = Strings.clearFilters,
            onAction = {
                onSearchQueryChange("")
                onFavoritesOnlyChange(false)
            },
        )
    }

    when (presentation) {
        ArtistPresentation.Covers -> {
            val jumpTargets = remember(artistGroups) {
                buildGridJumpTargets(
                    leadingItemCount = 1,
                    groups = artistGroups,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = if (compactPhone) GridCells.Fixed(2) else GridCells.Adaptive(layoutInfo.artistGridMinimumWidth),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = if (compactPhone) 34.dp else 42.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (compactPhone) 10.dp else 14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (compactPhone) 10.dp else 14.dp),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, contentType = "section-title") {
                        header()
                    }
                    if (visibleArtists.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                            emptyFilterCard()
                        }
                    }
                    artistGroups.forEach { group ->
                        item(
                            key = "artist-grid-header-${group.label}",
                            span = { GridItemSpan(maxLineSpan) },
                            contentType = "header",
                        ) {
                            IndexSectionHeader(label = group.label)
                        }
                        items(
                            items = group.items,
                            key = { artist -> "artist-${artist.name}" },
                            contentType = { "artist" },
                        ) { artist ->
                            Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                                ArtistCoverCard(
                                    artist = artist,
                                    compact = compactPhone,
                                    onClick = { onArtistClick(artist) },
                                )
                            }
                        }
                    }
                }

                if (jumpTargets.size > 1) {
                    VerticalIndexBar(
                        labels = jumpTargets.map { it.first },
                        compact = compactPhone,
                        modifier = Modifier.padding(top = if (compactPhone) 8.dp else 56.dp, bottom = 12.dp),
                        onSelect = { label ->
                            jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                                jumpJob?.cancel()
                                jumpJob = scope.launch { gridState.scrollToItem(index) }
                            }
                        },
                    )
                }
            }
        }
        ArtistPresentation.List -> {
            val jumpTargets = remember(artistGroups) {
                buildListJumpTargets(
                    leadingItemCount = 1,
                    groups = artistGroups,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = if (compactPhone) 34.dp else 42.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(contentType = "section-title") {
                        header()
                    }
                    if (visibleArtists.isEmpty()) {
                        item(contentType = "empty") {
                            emptyFilterCard()
                        }
                    }
                    artistGroups.forEach { group ->
                        item(key = "artist-list-header-${group.label}", contentType = "header") {
                            IndexSectionHeader(label = group.label)
                        }
                        items(
                            items = group.items,
                            key = { artist -> "artist-list-${artist.name}" },
                            contentType = { "artist" },
                        ) { artist ->
                            Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                                ArtistListRow(
                                    artist = artist,
                                    onClick = { onArtistClick(artist) },
                                )
                            }
                        }
                    }
                }

                if (jumpTargets.size > 1) {
                    VerticalIndexBar(
                        labels = jumpTargets.map { it.first },
                        compact = compactPhone,
                        modifier = Modifier.padding(top = if (compactPhone) 8.dp else 56.dp, bottom = 12.dp),
                        onSelect = { label ->
                            jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                                jumpJob?.cancel()
                                jumpJob = scope.launch { listState.scrollToItem(index) }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Title row (with the view toggle), search field and filter chip — one lazy item so the
 *  index-bar jump offsets keep a single leading item. */
@Composable
private fun ArtistsHeader(
    visibleCount: Int,
    presentation: ArtistPresentation,
    searchQuery: String,
    favoritesOnly: Boolean,
    onPresentationChange: (ArtistPresentation) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onFavoritesOnlyChange: (Boolean) -> Unit,
) {
    val filtered = favoritesOnly || searchQuery.isNotBlank()
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = Strings.artists,
                    style = MaterialTheme.typography.headlineMedium,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = if (filtered) {
                        listOfNotNull(
                            Strings.artistsCount(visibleCount),
                            Strings.filterFavoriteOnly.takeIf { favoritesOnly },
                        ).joinToString(" · ")
                    } else {
                        Strings.artistsDesc
                    },
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ArtistPresentationToggle(
                presentation = presentation,
                onPresentationChange = onPresentationChange,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TonalSearchField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = Strings.searchArtistsPlaceholder,
                modifier = Modifier.weight(1f),
            )
            AlbumControlChip(
                icon = Icons.Filled.Favorite,
                label = Strings.filterFavoriteOnly,
                selected = favoritesOnly,
                onClick = { onFavoritesOnlyChange(!favoritesOnly) },
            )
        }
    }
}

/** Case/width-insensitive substring match on the artist name, plus the favourites filter. */
internal fun filterArtists(
    artists: List<ArtistSummary>,
    query: String,
    favoritesOnly: Boolean,
): List<ArtistSummary> {
    val needle = normalizeArtistSearch(query)
    if (needle.isEmpty() && !favoritesOnly) return artists
    return artists.filter { artist ->
        (!favoritesOnly || artist.isFavorite) &&
            (needle.isEmpty() || normalizeArtistSearch(artist.name).contains(needle))
    }
}

private fun normalizeArtistSearch(value: String): String =
    java.text.Normalizer.normalize(value.trim(), java.text.Normalizer.Form.NFKC).lowercase()

@Composable
internal fun ArtistCoverCard(
    artist: ArtistSummary,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    // Design D: no card container — the photo carries the tile, the caption sits on the field.
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp),
    ) {
        if (artist.coverUrl != null) {
            AsyncAlbumArtwork(
                imageUrl = artist.coverUrl,
                title = artist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
                thumbnailSize = LocalAppLayoutInfo.current.artistGridMinimumWidth * 1.1f,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(KeyColors.Tonal),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = AppColors.TextTertiary,
                    modifier = Modifier.size(if (compact) 44.dp else 60.dp),
                )
            }
        }
        Column(
            modifier = Modifier.padding(horizontal = 2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = artist.name,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Strings.albumsCount(artist.albumCount),
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
internal fun ArtistListRow(
    artist: ArtistSummary,
    onClick: () -> Unit,
) {
    // Editorial list row: round portrait, name, count, hairline below. No card.
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (artist.coverUrl != null) {
                AsyncAlbumArtwork(
                    imageUrl = artist.coverUrl,
                    title = artist.name,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                    thumbnailSize = 56.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(KeyColors.Tonal),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AppColors.TextTertiary,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = artist.name,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Strings.albumsCount(artist.albumCount),
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(20.dp),
            )
        }
        Hairline(Modifier.fillMaxWidth())
    }
}

@Composable
internal fun IndexSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        color = AppColors.TextSecondary,
        style = MaterialTheme.typography.titleLarge,
    )
}

@Composable
internal fun BoxScope.VerticalIndexBar(
    labels: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    if (labels.isEmpty()) return

    var barHeightPx by remember { mutableIntStateOf(0) }
    var activeLabel by remember { mutableStateOf<String?>(null) }

    fun selectLabelAt(y: Float) {
        if (barHeightPx <= 0 || labels.isEmpty()) return
        val slotHeight = barHeightPx.toFloat() / labels.size
        val index = (y / slotHeight).toInt().coerceIn(0, labels.lastIndex)
        val label = labels[index]
        if (activeLabel == label) return
        activeLabel = label
        onSelect(label)
    }

    Surface(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .onSizeChanged { barHeightPx = it.height }
            .pointerInput(labels) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        selectLabelAt(offset.y)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        selectLabelAt(change.position.y)
                    },
                    onDragEnd = { activeLabel = null },
                    onDragCancel = { activeLabel = null },
                )
            },
        shape = RoundedCornerShape(if (compact) 14.dp else 18.dp),
        color = dockColor().copy(alpha = 0.92f),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(
                horizontal = if (compact) 3.dp else 6.dp,
                vertical = if (compact) 4.dp else 8.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .width(if (compact) 22.dp else 26.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(label) }
                        .padding(vertical = if (compact) 0.dp else 2.dp),
                    color = if (label == activeLabel) LocalArtworkPalette.current.accent else AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    style = if (compact) {
                        MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, lineHeight = 11.sp)
                    } else {
                        MaterialTheme.typography.labelSmall
                    },
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun ArtistAlbumsSection(
    artist: ArtistSummary?,
    selectedAlbum: PlexAlbum?,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    artistTracks: List<PlexTrackStream>,
    isArtistTracksLoading: Boolean,
    topTracks: List<PlexTrackStream>,
    topTracksArePopular: Boolean,
    tracksTotal: Int,
    tracksError: String?,
    hasMoreTracks: Boolean,
    trackSort: com.lux032.musicbridge.music.ArtistTrackSort,
    trackOrder: String,
    showTrackSorting: Boolean,
    onTrackSortChange: (com.lux032.musicbridge.music.ArtistTrackSort, String) -> Unit,
    onLoadMoreTracks: () -> Unit,
    onRetryTracks: () -> Unit,
    onPlayTopTrack: (ArtistSummary, PlexTrackStream, Int) -> Unit,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    listState: LazyListState = rememberLazyListState(),
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
    onPlayAlbum: (PlexAlbum) -> Unit = {},
    onShuffleArtist: (ArtistSummary) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (PlexTrackStream) -> Unit = {},
    onDeleteDownloadedTrack: (PlexTrackStream) -> Unit = {},
    onPlayTrack: (ArtistSummary, PlexTrackStream, Int) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    isResolving: Boolean = false,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val currentArtist = artist
    if (currentArtist == null && isResolving) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = layoutInfo.horizontalPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            BackCircleButton(onClick = onBack, contentDescription = Strings.backToArtists)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(
                    color = LocalArtworkPalette.current.accent,
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 3.dp,
                )
                Text(Strings.loadingArtist, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            }
        }
        return
    }
    if (currentArtist == null) {
        EmptyStateCard(
            title = Strings.artistNotFound,
            body = Strings.artistNotFoundDesc,
            actionLabel = Strings.backToArtists,
            onAction = onBack,
        )
        return
    }

    // Shares the root now-playing tint so this page stays consistent with the mini player
    // and bottom bar.
    val palette = LocalArtworkPalette.current
    val albumSections = remember(currentArtist.albums, currentArtist.appearanceAlbumKeys, layoutInfo.albumGridColumns) {
        // Newest release date first; groupBy keeps that order inside each category.
        currentArtist.albums
            .filterNot { it.ratingKey in currentArtist.appearanceAlbumKeys }
            .sortedByRelease(newestFirst = true)
            .map { ClassifiedArtistAlbum(it, classifyArtistAlbum(it)) }
            .groupBy { it.classification.category }
            .mapValues { (_, albums) -> albums.chunked(layoutInfo.albumGridColumns) }
    }
    // Web "参与作品": releases where the artist only sings some tracks (032 only).
    val appearanceRows = remember(currentArtist.albums, currentArtist.appearanceAlbumKeys, layoutInfo.albumGridColumns) {
        currentArtist.albums
            .filter { it.ratingKey in currentArtist.appearanceAlbumKeys }
            .sortedByRelease(newestFirst = true)
            .map { ClassifiedArtistAlbum(it, classifyArtistAlbum(it)) }
            .chunked(layoutInfo.albumGridColumns)
    }
    var showingAllTracks by rememberSaveable(currentArtist.sourceId, currentArtist.ratingKey, currentArtist.name) {
        mutableStateOf(false)
    }
    val allTracksListState = rememberLazyListState()
    val activeListState = if (showingAllTracks) allTracksListState else listState
    BackHandler(enabled = showingAllTracks) { showingAllTracks = false }
    LaunchedEffect(trackSort, trackOrder) { allTracksListState.scrollToItem(0) }
    LaunchedEffect(showingAllTracks, artistTracks.size, hasMoreTracks, isArtistTracksLoading, tracksError) {
        if (!showingAllTracks) return@LaunchedEffect
        snapshotFlow {
            val info = allTracksListState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3
        }.collect { nearEnd ->
            if (nearEnd && hasMoreTracks && !isArtistTracksLoading && tracksError == null) onLoadMoreTracks()
        }
    }

    LazyColumn(
        state = activeListState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = layoutInfo.horizontalPadding,
            top = 12.dp,
            end = layoutInfo.horizontalPadding,
            bottom = bottomContentPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item(contentType = "back") {
            BackCircleButton(
                onClick = { if (showingAllTracks) showingAllTracks = false else onBack() },
                contentDescription = if (showingAllTracks) Strings.back else Strings.backToArtists,
            )
        }

        if (!showingAllTracks) {
        item(contentType = "hero") {
            // Design D header: avatar + serif name, then one action row — the split primary key
            // names the output room, favourite is a toggle key beside it.
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (currentArtist.coverUrl != null) {
                        AsyncAlbumArtwork(
                            imageUrl = currentArtist.coverUrl,
                            title = currentArtist.name,
                            modifier = Modifier
                                .size(104.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            thumbnailSize = 104.dp,
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(104.dp)
                                .clip(CircleShape)
                                .background(AppColors.SurfaceMuted),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = AppColors.TextSecondary,
                                modifier = Modifier.size(44.dp),
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = currentArtist.name,
                            style = MaterialTheme.typography.headlineMedium,
                            color = AppColors.TextPrimary,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = Strings.albumsCount(currentArtist.albumCount),
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val playAllEnabled = artistTracks.isNotEmpty() && !isPlaybackLoading
                    val shuffleEnabled = currentArtist.albums.isNotEmpty() && !isPlaybackLoading
                    SplitPlayKey(
                        label = Strings.playOn(outputTargetName),
                        onPlay = {
                            artistTracks.firstOrNull()?.let { onPlayTrack(currentArtist, it, 0) }
                        },
                        onShuffle = { onShuffleArtist(currentArtist) },
                        enabled = playAllEnabled,
                        shuffleEnabled = shuffleEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    FavoriteIconButton(
                        isFavorite = currentArtist.isFavorite,
                        isLoading = isFavoriteLoading,
                        onClick = { onToggleArtistFavorite(currentArtist) },
                    )
                }
                if (hasMoreTracks && artistTracks.isNotEmpty()) {
                    Text(Strings.artistPartialQueue(artistTracks.size, tracksTotal),
                        color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        val categories = listOf(
            ArtistAlbumCategory.Album.name to Strings.studioAlbums,
            ArtistAlbumCategory.SingleEp.name to Strings.singlesAndEps,
            ArtistAlbumCategory.CompilationLive.name to Strings.compilationsAndLive,
            APPEARANCES_SECTION to Strings.artistAppearances,
        )
        categories.forEach { (category, title) ->
            val rows = if (category == APPEARANCES_SECTION) {
                appearanceRows
            } else {
                albumSections[ArtistAlbumCategory.valueOf(category)].orEmpty()
            }
            if (rows.isNotEmpty()) {
                item(key = "artist-album-header-$category", contentType = "albums-header") {
                    SectionHeader(title = title, modifier = Modifier.padding(top = 12.dp))
                }
                itemsIndexed(
                    rows,
                    key = { _, row -> "artist-album-row-$category-${row.first().album.ratingKey}" },
                    contentType = { _, _ -> "album-row" },
                ) { _, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth().animateItem(fadeInSpec = null, fadeOutSpec = null),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { classified ->
                            val album = classified.album
                            Box(modifier = Modifier.weight(1f)) {
                                AlbumCoverCard(
                                    album = album,
                                    selected = selectedAlbum?.ratingKey == album.ratingKey,
                                    onClick = { onAlbumClick(album) },
                                    onQueueAction = { onAlbumQueueAction(album, it) },
                                    onPlay = { onPlayAlbum(album) },
                                    compact = true,
                                    subtitleOverride = artistAlbumSubtitle(classified),
                                )
                            }
                        }
                        repeat(layoutInfo.albumGridColumns - row.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        if (topTracks.isNotEmpty()) {
            item(key = "top-tracks-header", contentType = "tracks-header") {
                SectionHeader(
                    title = if (topTracksArePopular) Strings.topTracks else Strings.artistSongs,
                    modifier = Modifier.padding(top = 12.dp),
                    actionLabel = Strings.artistAllTracks(tracksTotal),
                    onAction = { showingAllTracks = true },
                )
            }
            itemsIndexed(topTracks, key = { index, track -> "top-${track.ratingKey}-$index" }) { index, track ->
                ArtistTrackRow(
                    artist = currentArtist, track = track, index = index,
                    isPlaybackLoading = isPlaybackLoading,
                    downloadedTrackKeys = downloadedTrackKeys, downloadProgress = downloadProgress,
                    onDownloadTrack = onDownloadTrack, onDeleteDownloadedTrack = onDeleteDownloadedTrack,
                    onPlayTrack = onPlayTopTrack, onQueueTrack = onQueueTrack, showPlayCount = topTracksArePopular,
                )
            }
        }

        if (topTracks.isEmpty()) {
            item(key = "all-tracks-entry") {
                SectionHeader(
                    title = "",
                    actionLabel = Strings.artistAllTracks(tracksTotal),
                    onAction = { showingAllTracks = true },
                )
            }
        }
        }

        if (showingAllTracks) {
        item(key = "all-tracks-header", contentType = "tracks-header") {
            Column {
                SectionHeader(
                    title = Strings.artistAllTracks(tracksTotal),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text(currentArtist.name, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                if (showTrackSorting) {
                    ArtistTrackSortControls(trackSort, trackOrder, onTrackSortChange)
                }
                SplitPlayKey(
                    label = Strings.playOn(outputTargetName),
                    onPlay = { artistTracks.firstOrNull()?.let { onPlayTrack(currentArtist, it, 0) } },
                    onShuffle = { onShuffleArtist(currentArtist) },
                    enabled = artistTracks.isNotEmpty() && !isPlaybackLoading,
                    shuffleEnabled = artistTracks.isNotEmpty() && !isPlaybackLoading,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasMoreTracks && artistTracks.isNotEmpty()) {
                    Text(Strings.artistPartialQueue(artistTracks.size, tracksTotal),
                        color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        itemsIndexed(
            artistTracks,
            key = { index, track -> "all-${track.ratingKey}-$index" },
            contentType = { _, _ -> "track" },
        ) { index, track ->
            ArtistTrackRow(
                artist = currentArtist, track = track, index = index,
                isPlaybackLoading = isPlaybackLoading,
                downloadedTrackKeys = downloadedTrackKeys, downloadProgress = downloadProgress,
                onDownloadTrack = onDownloadTrack, onDeleteDownloadedTrack = onDeleteDownloadedTrack,
                onPlayTrack = onPlayTrack, onQueueTrack = onQueueTrack,
            )
        }
        }
        if (isArtistTracksLoading) {
            item(contentType = "tracks-loading") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = palette.accent,
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                    )
                }
            }
        }
        if (tracksError != null) {
            item(key = "tracks-error") {
                Column {
                    Text(tracksError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onRetryTracks) {
                        Text(Strings.retry)
                    }
                }
            }
        } else if (showingAllTracks && !isArtistTracksLoading && artistTracks.isEmpty()) {
            item(key = "tracks-empty") {
                Text(Strings.artistTracksEmpty, color = AppColors.TextSecondary)
            }
        }

    }
}

@Composable
private fun ArtistTrackSortControls(
    sort: com.lux032.musicbridge.music.ArtistTrackSort,
    order: String,
    onChange: (com.lux032.musicbridge.music.ArtistTrackSort, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = Strings.artistTrackSortLabels
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            TextButton(onClick = { expanded = true }) {
                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(labels[sort.ordinal])
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                com.lux032.musicbridge.music.ArtistTrackSort.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labels[option.ordinal]) },
                        onClick = { expanded = false; onChange(option, option.defaultOrder) },
                        trailingIcon = {
                            if (option == sort) Icon(Icons.Filled.Check, contentDescription = null)
                        },
                    )
                }
            }
        }
        TextButton(onClick = { onChange(sort, if (order == "asc") "desc" else "asc") }) {
            Icon(if (order == "asc") Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(if (order == "asc") Strings.sortAscending else Strings.sortDescending)
        }
    }
}

private const val APPEARANCES_SECTION = "Appearances"

/** Picker for multi-artist credits ("A, B"): each name opens its own artist page. */
@Composable
internal fun ArtistChoiceDialog(
    choices: List<ArtistRef>,
    onDismiss: () -> Unit,
    onChoose: (ArtistRef) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(Strings.back) }
        },
        title = {
            Text(Strings.chooseArtist, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                choices.forEach { ref ->
                    ChoiceRow(
                        selected = false,
                        onClick = { onChoose(ref) },
                        modifier = Modifier.fillMaxWidth(),
                        showCheck = false,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            ref.name,
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        },
        containerColor = AppColors.CardSurface,
    )
}

private data class ClassifiedArtistAlbum(
    val album: PlexAlbum,
    val classification: ArtistAlbumClassification,
)

private fun artistAlbumSubtitle(classified: ClassifiedArtistAlbum): String =
    listOfNotNull(
        classified.album.year?.toString(),
        when (classified.classification.kind) {
            ReleaseKind.Album -> classified.album.leafCount?.let(Strings.tracks)
            ReleaseKind.Ep -> listOfNotNull(Strings.epLabel, classified.album.leafCount?.let(Strings.tracks)).joinToString(" · ")
            ReleaseKind.Single -> Strings.singleLabel
            ReleaseKind.Compilation -> Strings.compilationLabel
            ReleaseKind.Live -> Strings.liveLabel
        },
    ).joinToString(" · ").ifBlank { classified.album.artistName.orEmpty() }

@Composable
private fun ArtistTrackRow(
    artist: ArtistSummary,
    track: PlexTrackStream,
    index: Int,
    isPlaybackLoading: Boolean,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (PlexTrackStream) -> Unit = {},
    onDeleteDownloadedTrack: (PlexTrackStream) -> Unit = {},
    onPlayTrack: (ArtistSummary, PlexTrackStream, Int) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
    showPlayCount: Boolean = false,
) {
    var showQueueMenu by remember { mutableStateOf(false) }
    val isDownloaded = remember(track.sourceId, track.ratingKey, downloadedTrackKeys) {
        "${track.sourceId}:${track.ratingKey}" in downloadedTrackKeys || track.ratingKey in downloadedTrackKeys
    }
    val progress = downloadProgress["${track.sourceId}:${track.ratingKey}"]

    Box {
        Column {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clickable(enabled = !isPlaybackLoading) { onPlayTrack(artist, track, index) }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = track.thumbUrl,
                title = track.title,
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
                thumbnailSize = 48.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(track.title, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isDownloaded) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = AppColors.Success,
                            modifier = Modifier.size(14.dp),
                        )
                    } else if (progress != null) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 2.dp,
                            color = LocalArtworkPalette.current.accent,
                        )
                    }
                }
                Text(
                    if (showPlayCount) {
                        Strings.trackPlayCount(track.viewCount ?: 0)
                    } else track.albumTitle ?: track.artistName.orEmpty(),
                    color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            track.durationMillis?.let {
                Text(formatDuration(it), color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
            }
            BareIcon(
                icon = Icons.Filled.MoreVert,
                contentDescription = Strings.more,
                onClick = { showQueueMenu = true },
                tint = AppColors.TextTertiary,
                iconSize = 20.dp,
                modifier = Modifier.offset(x = 10.dp),
            )
        }
        Hairline(Modifier.fillMaxWidth())
        }
        QueueActionMenu(
            expanded = showQueueMenu,
            onDismiss = { showQueueMenu = false },
            onAction = {
                showQueueMenu = false
                onQueueTrack(track, it)
            },
            isDownloaded = isDownloaded,
            onDownloadAction = {
                if (isDownloaded) onDeleteDownloadedTrack(track) else onDownloadTrack(track)
            },
        )
    }
}


@Composable
internal fun ArtistPresentationToggle(
    presentation: ArtistPresentation,
    onPresentationChange: (ArtistPresentation) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ToggleKey(
            checked = presentation == ArtistPresentation.Covers,
            icon = Icons.Filled.GridView,
            contentDescription = "Covers",
            onClick = { onPresentationChange(ArtistPresentation.Covers) },
            bareWhenOff = true,
            iconSize = 20.dp,
        )
        ToggleKey(
            checked = presentation == ArtistPresentation.List,
            icon = Icons.Filled.List,
            contentDescription = "List",
            onClick = { onPresentationChange(ArtistPresentation.List) },
            bareWhenOff = true,
            iconSize = 20.dp,
        )
    }
}
