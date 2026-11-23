package com.lux032.musicbridge

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.SearchBestMatch
import com.lux032.musicbridge.music.SearchCategory
import com.lux032.musicbridge.music.UnifiedSearchResults
import com.lux032.musicbridge.music.isFavorite

private const val OVERVIEW_PREVIEW_TRACKS = 5

@Composable
internal fun SearchSection(
    queryInput: String,
    activeQuery: String,
    selectedTab: SearchTab,
    history: List<String>,
    isOverviewLoading: Boolean,
    overviewError: String?,
    overview: UnifiedSearchResults?,
    downloadedOnly: Boolean = false,
    localSource: SearchLocalSource? = null,
    categoryStates: Map<SearchCategory, SearchCategoryFeedState>,
    bottomContentPadding: Dp,
    activeSourceId: String,
    onQueryInputChange: (String) -> Unit,
    onSubmitSearch: () -> Unit,
    onToggleDownloadedOnly: () -> Unit = {},
    onSelectHistoryQuery: (String) -> Unit,
    onRemoveHistoryQuery: (String) -> Unit,
    onClearHistory: () -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    onRetryOverview: () -> Unit,
    onRetryCategory: (SearchCategory) -> Unit,
    onLoadMoreCategory: (SearchCategory) -> Unit,
    onNavigateBackFromResults: () -> Unit,
    onClearToRecent: () -> Unit,
    onOpenBestMatch: (SearchBestMatch) -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onPlayArtist: (ArtistSummary, Boolean) -> Unit,
    onEnqueueArtist: (ArtistSummary, QueuePlacement) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
    onPlayAlbum: (MusicAlbum) -> Unit,
    onEnqueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onEnqueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val focusManager = LocalFocusManager.current
    val inResultsMode = activeQuery.isNotBlank() || isOverviewLoading || overviewError != null || selectedTab != SearchTab.All

    BackHandler(enabled = inResultsMode) {
        focusManager.clearFocus()
        onNavigateBackFromResults()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = layoutInfo.horizontalPadding),
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (inResultsMode) {
                BackCircleButton(
                    onClick = {
                        focusManager.clearFocus()
                        onNavigateBackFromResults()
                    },
                    contentDescription = if (selectedTab == SearchTab.All) {
                        Strings.searchBackToRecent
                    } else {
                        Strings.searchBackToOverview
                    },
                )
            }
            TonalSearchField(
                value = queryInput,
                onValueChange = onQueryInputChange,
                placeholder = Strings.searchUnifiedPlaceholder,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        focusManager.clearFocus()
                        onSubmitSearch()
                    },
                ),
                onClear = {
                    focusManager.clearFocus()
                    onClearToRecent()
                },
            )
        }

        if (!inResultsMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AlbumControlChip(
                    icon = Icons.Filled.DownloadDone,
                    label = Strings.searchDownloadedOnlyChip,
                    selected = downloadedOnly,
                    onClick = onToggleDownloadedOnly,
                )
            }
            SearchHistoryList(
                history = history,
                bottomContentPadding = bottomContentPadding,
                onSelect = { query ->
                    focusManager.clearFocus()
                    onSelectHistoryQuery(query)
                },
                onRemove = onRemoveHistoryQuery,
                onClearAll = onClearHistory,
            )
            return@Column
        }

        SearchCategoryChipRow(
            selectedTab = selectedTab,
            overview = overview,
            downloadedOnly = downloadedOnly,
            onToggleDownloadedOnly = onToggleDownloadedOnly,
            onSelectTab = onSelectTab,
        )

        if (localSource == SearchLocalSource.OfflineFallback) {
            SearchOfflineFallbackBanner(onRetryOnline = onRetryOverview)
        }

        val category = selectedTab.toCategory()
        if (category == null) {
            SearchOverviewBody(
                isLoading = isOverviewLoading,
                error = overviewError,
                overview = overview,
                downloadedOnly = downloadedOnly,
                activeSourceId = activeSourceId,
                bottomContentPadding = bottomContentPadding,
                onRetry = onRetryOverview,
                onTurnOffDownloadedOnly = onToggleDownloadedOnly,
                onSelectTab = onSelectTab,
                onOpenBestMatch = onOpenBestMatch,
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onPlayTrack = onPlayTrack,
                onPlayArtist = onPlayArtist,
                onEnqueueArtist = onEnqueueArtist,
                onToggleArtistFavorite = onToggleArtistFavorite,
                onPlayAlbum = onPlayAlbum,
                onEnqueueAlbum = onEnqueueAlbum,
                onToggleAlbumFavorite = onToggleAlbumFavorite,
                onEnqueueTrack = onEnqueueTrack,
                onToggleTrackFavorite = onToggleTrackFavorite,
            )
        } else {
            val catState = categoryStates[category] ?: SearchCategoryFeedState(isLoading = true)
            SearchCategoryBody(
                category = category,
                feedState = catState,
                bottomContentPadding = bottomContentPadding,
                onRetry = { onRetryCategory(category) },
                onLoadMore = { onLoadMoreCategory(category) },
                onOpenArtist = onOpenArtist,
                onOpenAlbum = onOpenAlbum,
                onPlayTrack = onPlayTrack,
                onPlayArtist = onPlayArtist,
                onEnqueueArtist = onEnqueueArtist,
                onToggleArtistFavorite = onToggleArtistFavorite,
                onPlayAlbum = onPlayAlbum,
                onEnqueueAlbum = onEnqueueAlbum,
                onToggleAlbumFavorite = onToggleAlbumFavorite,
                onEnqueueTrack = onEnqueueTrack,
                onToggleTrackFavorite = onToggleTrackFavorite,
            )
        }
    }
}

@Composable
private fun SearchHistoryList(
    history: List<String>,
    bottomContentPadding: Dp,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 18.dp, bottom = bottomContentPadding),
    ) {
        item(key = "recent-header", contentType = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Strings.searchRecentTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                )
                if (history.isNotEmpty()) {
                    Text(
                        text = Strings.searchClearHistory,
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onClearAll)
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (history.isEmpty()) {
            item(key = "recent-empty", contentType = "empty") {
                EmptyStateCard(
                    title = Strings.searchNoHistory,
                    body = Strings.searchNoHistoryDesc,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            lazyItems(items = history, key = { it }, contentType = { "history-row" }) { query ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(query) }
                        .padding(vertical = 8.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(KeyColors.Tonal),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = AppColors.TextSecondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        text = query,
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    BareIcon(
                        icon = Icons.Filled.Close,
                        contentDescription = Strings.searchDeleteHistoryItem,
                        onClick = { onRemove(query) },
                        tint = AppColors.TextTertiary,
                        iconSize = 18.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchCategoryChipRow(
    selectedTab: SearchTab,
    overview: UnifiedSearchResults?,
    downloadedOnly: Boolean,
    onToggleDownloadedOnly: () -> Unit,
    onSelectTab: (SearchTab) -> Unit,
) {
    val items = listOf(
        SearchTab.All to Strings.searchCategoryAll,
        SearchTab.Artists to tabLabel(Strings.artists, overview?.artists?.total ?: 0),
        SearchTab.Albums to tabLabel(Strings.albums, overview?.albums?.total ?: 0),
        SearchTab.Tracks to tabLabel(Strings.searchCategoryTracks, overview?.tracks?.total ?: 0),
        SearchTab.Composers to tabLabel(Strings.searchCategoryComposers, overview?.composers?.total ?: 0),
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "downloaded-only-filter") {
            AlbumControlChip(
                icon = Icons.Filled.DownloadDone,
                label = Strings.searchDownloadedOnlyChip,
                selected = downloadedOnly,
                onClick = onToggleDownloadedOnly,
            )
        }
        lazyItems(items, key = { it.first.name }) { (tab, label) ->
            AlbumControlChip(
                icon = null,
                label = label,
                selected = selectedTab == tab,
                onClick = { onSelectTab(tab) },
            )
        }
    }
}

@Composable
private fun SearchOfflineFallbackBanner(
    onRetryOnline: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 4.dp),
        shape = RoundedCornerShape(14.dp),
        color = AppColors.SurfaceMuted,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = Strings.searchOfflineBannerTitle,
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = Strings.searchRetryOnline,
                color = AppColors.Accent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onRetryOnline)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

private fun tabLabel(base: String, count: Int): String = if (count > 0) "$base $count" else base

@Composable
private fun SearchOverviewBody(
    isLoading: Boolean,
    error: String?,
    overview: UnifiedSearchResults?,
    downloadedOnly: Boolean,
    activeSourceId: String,
    bottomContentPadding: Dp,
    onRetry: () -> Unit,
    onTurnOffDownloadedOnly: () -> Unit,
    onSelectTab: (SearchTab) -> Unit,
    onOpenBestMatch: (SearchBestMatch) -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onPlayArtist: (ArtistSummary, Boolean) -> Unit,
    onEnqueueArtist: (ArtistSummary, QueuePlacement) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
    onPlayAlbum: (MusicAlbum) -> Unit,
    onEnqueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onEnqueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
) {
    if (isLoading && overview == null) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            LoadingCard(message = Strings.searchInProgress)
        }
        return
    }
    if (error != null && overview == null) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            EmptyStateCard(
                title = Strings.searchFailedTitle,
                body = error,
                actionLabel = Strings.retry,
                onAction = onRetry,
            )
        }
        return
    }
    val data = overview ?: return
    if (data.isEmpty) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            if (downloadedOnly) {
                EmptyStateCard(
                    title = Strings.searchDownloadedEmptyTitle,
                    body = Strings.searchDownloadedEmptyDesc,
                    actionLabel = Strings.searchTurnOffDownloadedOnly,
                    onAction = onTurnOffDownloadedOnly,
                )
            } else {
                EmptyStateCard(
                    title = Strings.searchEmptyTitle,
                    body = Strings.searchEmptyDesc,
                )
            }
        }
        return
    }

    val previewTracks = data.tracks.items.take(OVERVIEW_PREVIEW_TRACKS)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        data.bestMatch?.let { best ->
            item(key = "best-match", contentType = "best-match") {
                SearchBestMatchCard(
                    best = best,
                    overview = data,
                    activeSourceId = activeSourceId,
                    onClick = { onOpenBestMatch(best) },
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                    onPlayArtist = onPlayArtist,
                    onEnqueueArtist = onEnqueueArtist,
                    onToggleArtistFavorite = onToggleArtistFavorite,
                    onPlayAlbum = onPlayAlbum,
                    onEnqueueAlbum = onEnqueueAlbum,
                    onToggleAlbumFavorite = onToggleAlbumFavorite,
                    onPlayTrack = onPlayTrack,
                    onEnqueueTrack = onEnqueueTrack,
                    onToggleTrackFavorite = onToggleTrackFavorite,
                )
            }
        }

        if (data.artists.items.isNotEmpty()) {
            item(key = "sec-artists", contentType = "person-shelf") {
                SearchPersonShelf(
                    title = Strings.artists,
                    artists = data.artists.items,
                    showMore = data.artists.total > data.artists.items.size || data.artists.total > 0,
                    onMore = { onSelectTab(SearchTab.Artists) },
                    onOpenArtist = onOpenArtist,
                    onPlayArtist = onPlayArtist,
                    onEnqueueArtist = onEnqueueArtist,
                    onToggleArtistFavorite = onToggleArtistFavorite,
                )
            }
        }

        if (data.albums.items.isNotEmpty()) {
            item(key = "sec-albums", contentType = "album-shelf") {
                SearchAlbumShelf(
                    title = Strings.albums,
                    albums = data.albums.items,
                    showMore = data.albums.total > 0,
                    onMore = { onSelectTab(SearchTab.Albums) },
                    onOpenAlbum = onOpenAlbum,
                    onPlayAlbum = onPlayAlbum,
                    onEnqueueAlbum = onEnqueueAlbum,
                    onToggleAlbumFavorite = onToggleAlbumFavorite,
                )
            }
        }

        if (previewTracks.isNotEmpty()) {
            item(key = "sec-tracks-head", contentType = "section-head") {
                SectionHeader(
                    title = Strings.searchCategoryTracks,
                    actionLabel = Strings.more,
                    onAction = { onSelectTab(SearchTab.Tracks) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "sec-tracks-list", contentType = "tracks-preview") {
                Column(modifier = Modifier.fillMaxWidth()) {
                    previewTracks.forEachIndexed { index, track ->
                        if (index > 0) Hairline()
                        SearchTrackRow(
                            track = track,
                            onPlay = { onPlayTrack(previewTracks, index) },
                            onEnqueue = { placement -> onEnqueueTrack(track, placement) },
                            onToggleFavorite = { onToggleTrackFavorite(track) },
                            onOpenAlbum = track.toStubAlbum()?.let { stubAlbum ->
                                { onOpenAlbum(stubAlbum) }
                            },
                            onOpenArtist = track.toStubArtist()?.let { stubArtist ->
                                { onOpenArtist(stubArtist) }
                            },
                        )
                    }
                }
            }
        }

        if (data.composers.items.isNotEmpty()) {
            item(key = "sec-composers", contentType = "person-shelf") {
                SearchPersonShelf(
                    title = Strings.searchCategoryComposers,
                    artists = data.composers.items,
                    showMore = data.composers.total > 0,
                    onMore = { onSelectTab(SearchTab.Composers) },
                    onOpenArtist = onOpenArtist,
                    onPlayArtist = onPlayArtist,
                    onEnqueueArtist = onEnqueueArtist,
                    onToggleArtistFavorite = onToggleArtistFavorite,
                )
            }
        }
    }
}

@Composable
private fun SearchBestMatchCard(
    best: SearchBestMatch,
    overview: UnifiedSearchResults,
    activeSourceId: String,
    onClick: () -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onPlayArtist: (ArtistSummary, Boolean) -> Unit,
    onEnqueueArtist: (ArtistSummary, QueuePlacement) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
    onPlayAlbum: (MusicAlbum) -> Unit,
    onEnqueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onEnqueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
) {
    var menuExpanded by remember(best.category, best.id) { mutableStateOf(false) }
    val isPerson = best.category == SearchCategory.Artists || best.category == SearchCategory.Composers
    val kindLabel = when (best.category) {
        SearchCategory.Artists -> Strings.searchKindArtist
        SearchCategory.Albums -> Strings.searchKindAlbum
        SearchCategory.Tracks -> Strings.searchKindTrack
        SearchCategory.Composers -> Strings.searchKindComposer
    }
    val matchedArtist = when (best.category) {
        SearchCategory.Artists -> overview.artists.items.firstOrNull { it.ratingKey == best.id }
        SearchCategory.Composers -> overview.composers.items.firstOrNull { it.ratingKey == best.id }
        else -> null
    }
    val matchedAlbum = if (best.category == SearchCategory.Albums) {
        overview.albums.items.firstOrNull { it.ratingKey == best.id } ?: best.toAlbum(activeSourceId)
    } else null
    val matchedTrackIndex = if (best.category == SearchCategory.Tracks) {
        overview.tracks.items.indexOfFirst { it.ratingKey == best.id }
    } else -1
    val matchedTrack = overview.tracks.items.getOrNull(matchedTrackIndex)

    val displaySubtitle = best.subtitle.ifBlank {
        when (best.category) {
            SearchCategory.Artists -> matchedArtist?.albumCount?.takeIf { it > 0 }?.let { Strings.albumsCount(it) }.orEmpty()
            SearchCategory.Composers -> matchedArtist?.trackCount?.takeIf { it > 0 }?.let(Strings::artistCreditsCount).orEmpty()
            else -> ""
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AppColors.CardSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = Strings.searchBestMatch,
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SearchArtworkThumbnail(
                    imageUrl = best.imageUrl,
                    title = best.title,
                    isCircle = isPerson,
                    size = 62.dp,
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = kindLabel,
                        color = LocalArtworkPalette.current.accent,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = best.title,
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (displaySubtitle.isNotEmpty()) {
                        Text(
                            text = displaySubtitle,
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box {
                    BareIcon(
                        icon = Icons.Filled.MoreVert,
                        contentDescription = Strings.more,
                        onClick = { menuExpanded = true },
                        tint = AppColors.TextSecondary,
                    )
                    when (best.category) {
                        SearchCategory.Artists, SearchCategory.Composers -> {
                            val summary = matchedArtist?.toArtistSummary() ?: best.toArtistSummary(activeSourceId)
                            SearchArtistActionMenu(
                                expanded = menuExpanded,
                                isFavorite = summary.isFavorite,
                                onDismiss = { menuExpanded = false },
                                onOpen = onClick,
                                onPlay = { onPlayArtist(summary, false) },
                                onShuffle = { onPlayArtist(summary, true) },
                                onEnqueue = { placement -> onEnqueueArtist(summary, placement) },
                                onToggleFavorite = { onToggleArtistFavorite(summary) },
                            )
                        }
                        SearchCategory.Albums -> if (matchedAlbum != null) {
                            QueueActionMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                onAction = { placement -> onEnqueueAlbum(matchedAlbum, placement) },
                                onPlay = { onPlayAlbum(matchedAlbum) },
                                onAddToPlaylist = addAlbumToPlaylistAction(matchedAlbum),
                                isFavorite = matchedAlbum.isFavorite,
                                onToggleFavorite = { onToggleAlbumFavorite(matchedAlbum) },
                            )
                        }
                        SearchCategory.Tracks -> if (matchedTrack != null) {
                            SearchTrackActionMenu(
                                expanded = menuExpanded,
                                track = matchedTrack,
                                onDismiss = { menuExpanded = false },
                                onPlay = { onPlayTrack(overview.tracks.items, matchedTrackIndex) },
                                onEnqueue = { placement -> onEnqueueTrack(matchedTrack, placement) },
                                onToggleFavorite = { onToggleTrackFavorite(matchedTrack) },
                                onOpenAlbum = matchedTrack.toStubAlbum()?.let { stubAlbum ->
                                    { onOpenAlbum(stubAlbum) }
                                },
                                onOpenArtist = matchedTrack.toStubArtist()?.let { stubArtist ->
                                    { onOpenArtist(stubArtist) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPersonShelf(
    title: String,
    artists: List<MusicArtist>,
    showMore: Boolean,
    onMore: () -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onPlayArtist: (ArtistSummary, Boolean) -> Unit,
    onEnqueueArtist: (ArtistSummary, QueuePlacement) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(
            title = title,
            actionLabel = if (showMore) Strings.more else null,
            onAction = if (showMore) onMore else null,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            lazyItems(items = artists, key = { it.ratingKey }, contentType = { "search-person" }) { artist ->
                SearchPersonCircleCard(
                    artist = artist,
                    onClick = { onOpenArtist(artist) },
                    onPlay = { onPlayArtist(artist.toArtistSummary(), false) },
                    onShuffle = { onPlayArtist(artist.toArtistSummary(), true) },
                    onEnqueue = { placement -> onEnqueueArtist(artist.toArtistSummary(), placement) },
                    onToggleFavorite = { onToggleArtistFavorite(artist.toArtistSummary()) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchPersonCircleCard(
    artist: MusicArtist,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onEnqueue: (QueuePlacement) -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 96.dp,
) {
    var menuExpanded by remember(artist.ratingKey) { mutableStateOf(false) }
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .width(avatarSize)
                .clip(RoundedCornerShape(14.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                )
                .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchArtworkThumbnail(
                imageUrl = artist.thumbUrl,
                title = artist.title,
                isCircle = true,
                size = avatarSize,
            )
            Text(
                text = artist.title,
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = artist.albumCount?.takeIf { it > 0 }?.let { Strings.albumsCount(it) }
                ?: artist.trackCount?.takeIf { it > 0 }?.let(Strings::artistCreditsCount)
            if (sub != null) {
                Text(
                    text = sub,
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SearchArtistActionMenu(
            expanded = menuExpanded,
            isFavorite = artist.userRating != null && artist.userRating > 0f,
            onDismiss = { menuExpanded = false },
            onOpen = onClick,
            onPlay = onPlay,
            onShuffle = onShuffle,
            onEnqueue = onEnqueue,
            onToggleFavorite = onToggleFavorite,
        )
    }
}

@Composable
private fun SearchAlbumShelf(
    title: String,
    albums: List<MusicAlbum>,
    showMore: Boolean,
    onMore: () -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onPlayAlbum: (MusicAlbum) -> Unit,
    onEnqueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(
            title = title,
            actionLabel = if (showMore) Strings.more else null,
            onAction = if (showMore) onMore else null,
            modifier = Modifier.fillMaxWidth(),
        )
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lazyItems(items = albums, key = { it.ratingKey }, contentType = { "search-album" }) { album ->
                SearchAlbumShelfCard(
                    album = album,
                    onClick = { onOpenAlbum(album) },
                    onPlay = { onPlayAlbum(album) },
                    onEnqueue = { placement -> onEnqueueAlbum(album, placement) },
                    onToggleFavorite = { onToggleAlbumFavorite(album) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchAlbumShelfCard(
    album: MusicAlbum,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: (QueuePlacement) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val nowPlaying = LocalNowPlaying.current
    var menuExpanded by remember(album.ratingKey) { mutableStateOf(false) }
    var centerCaption by remember(album.thumbUrl) { mutableStateOf(false) }
    val cardWidth = 128.dp
    val shape = RoundedCornerShape(12.dp)

    Box {
        Column(
            modifier = Modifier
                .width(cardWidth)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true },
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AsyncAlbumArtwork(
                imageUrl = album.thumbUrl,
                title = album.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                thumbnailSize = cardWidth,
                fit = ArtworkFit.Contain,
                alignment = Alignment.BottomCenter,
                shape = shape,
                onCenterCaption = { centerCaption = it },
                overlay = { AlbumCoverOverlayBadges(nowPlaying?.takeIf { it.matchesAlbum(album) }, album.discCount) },
            )
            Text(
                text = album.title,
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centerCaption) TextAlign.Center else TextAlign.Start,
            )
            Text(
                text = album.artistName ?: Strings.unknownArtist,
                modifier = Modifier.fillMaxWidth(),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (centerCaption) TextAlign.Center else TextAlign.Start,
            )
        }
        QueueActionMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            onAction = onEnqueue,
            onPlay = onPlay,
            onAddToPlaylist = addAlbumToPlaylistAction(album),
            isFavorite = album.isFavorite,
            onToggleFavorite = onToggleFavorite,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchTrackRow(
    track: MusicTrack,
    onPlay: () -> Unit,
    onEnqueue: (QueuePlacement) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAlbum: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
) {
    val nowPlaying = LocalNowPlaying.current?.takeIf { it.matches(track) }
    val menuState = remember(track.ratingKey) { ContextualMenuState() }
    val subtitle = listOfNotNull(
        track.artistName?.takeIf { it.isNotBlank() },
        track.albumTitle?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val renderMenu: @Composable () -> Unit = {
        SearchTrackActionMenu(
            expanded = menuState.expanded,
            track = track,
            onDismiss = { menuState.dismiss() },
            onPlay = onPlay,
            onEnqueue = onEnqueue,
            onToggleFavorite = onToggleFavorite,
            onOpenAlbum = onOpenAlbum,
            onOpenArtist = onOpenArtist,
        )
    }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .trackMenuPressPosition(menuState)
                .combinedClickable(
                    onClick = onPlay,
                    onLongClick = { menuState.openAtLastPress() },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(KeyColors.Tonal),
            contentAlignment = Alignment.Center,
        ) {
            AsyncAlbumArtwork(
                imageUrl = track.thumbUrl,
                title = track.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                thumbnailSize = 44.dp,
                fit = ArtworkFit.Cover,
                shape = RoundedCornerShape(8.dp),
            )
            NowPlayingArtworkScrim(nowPlaying)
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = track.title,
                color = nowPlayingTitleColor(nowPlaying != null),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (nowPlaying != null) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        track.durationMillis?.let { duration ->
            Text(
                text = formatDuration(duration),
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
            Box {
                BareIcon(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = Strings.more,
                    onClick = { menuState.openAtAnchor() },
                    tint = AppColors.TextTertiary,
                    iconSize = 20.dp,
                )
                if (menuState.pressOffset == null) {
                    renderMenu()
                }
            }
        }
        ContextualMenuAnchor(menuState, renderMenu)
    }
}

@Composable
private fun SearchCategoryBody(
    category: SearchCategory,
    feedState: SearchCategoryFeedState,
    bottomContentPadding: Dp,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onOpenArtist: (MusicArtist) -> Unit,
    onOpenAlbum: (MusicAlbum) -> Unit,
    onPlayTrack: (List<MusicTrack>, Int) -> Unit,
    onPlayArtist: (ArtistSummary, Boolean) -> Unit,
    onEnqueueArtist: (ArtistSummary, QueuePlacement) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
    onPlayAlbum: (MusicAlbum) -> Unit,
    onEnqueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onEnqueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
) {
    val itemCount = feedState.itemCount(category)
    if (feedState.isLoading && itemCount == 0) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            LoadingCard(message = Strings.searchInProgress)
        }
        return
    }
    if (feedState.error != null && itemCount == 0) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            EmptyStateCard(
                title = Strings.searchFailedTitle,
                body = feedState.error,
                actionLabel = Strings.retry,
                onAction = onRetry,
            )
        }
        return
    }
    if (itemCount == 0) {
        Box(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
            EmptyStateCard(
                title = Strings.searchEmptyTitle,
                body = Strings.searchEmptyDesc,
            )
        }
        return
    }

    val layoutInfo = LocalAppLayoutInfo.current
    if (category == SearchCategory.Tracks) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 6.dp, bottom = bottomContentPadding),
        ) {
            itemsIndexed(
                items = feedState.tracks,
                key = { _, track -> track.ratingKey },
                contentType = { _, _ -> "search-track-row" },
            ) { index, track ->
                if (index > 0) Hairline()
                if (index >= feedState.tracks.lastIndex - 5 && feedState.hasMore(category) && !feedState.isLoadingMore) {
                    LaunchedEffect(feedState.tracks.size) { onLoadMore() }
                }
                SearchTrackRow(
                    track = track,
                    onPlay = { onPlayTrack(feedState.tracks, index) },
                    onEnqueue = { placement -> onEnqueueTrack(track, placement) },
                    onToggleFavorite = { onToggleTrackFavorite(track) },
                    onOpenAlbum = track.toStubAlbum()?.let { stubAlbum ->
                        { onOpenAlbum(stubAlbum) }
                    },
                    onOpenArtist = track.toStubArtist()?.let { stubArtist ->
                        { onOpenArtist(stubArtist) }
                    },
                )
            }
            item(key = "tracks-footer", contentType = "footer") {
                SearchPaginationFooter(
                    loaded = itemCount,
                    total = feedState.total,
                    hasMore = feedState.hasMore(category),
                    isLoadingMore = feedState.isLoadingMore,
                    error = feedState.error,
                    onLoadMore = onLoadMore,
                    onRetry = onRetry,
                )
            }
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = layoutInfo.albumGridMinimumWidth),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp, bottom = bottomContentPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (category == SearchCategory.Albums) {
            gridItems(
                items = feedState.albums,
                key = { it.ratingKey },
                contentType = { "search-album-grid" },
            ) { album ->
                if (album == feedState.albums.lastOrNull() && feedState.hasMore(category) && !feedState.isLoadingMore) {
                    LaunchedEffect(feedState.albums.size) { onLoadMore() }
                }
                AlbumCoverCard(
                    album = album,
                    selected = false,
                    onClick = { onOpenAlbum(album) },
                    onQueueAction = { placement -> onEnqueueAlbum(album, placement) },
                    onPlay = { onPlayAlbum(album) },
                    compact = true,
                )
            }
        } else {
            gridItems(
                items = feedState.artists,
                key = { it.ratingKey },
                contentType = { "search-person-grid" },
            ) { artist ->
                if (artist == feedState.artists.lastOrNull() && feedState.hasMore(category) && !feedState.isLoadingMore) {
                    LaunchedEffect(feedState.artists.size) { onLoadMore() }
                }
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    SearchPersonCircleCard(
                        artist = artist,
                        onClick = { onOpenArtist(artist) },
                        onPlay = { onPlayArtist(artist.toArtistSummary(), false) },
                        onShuffle = { onPlayArtist(artist.toArtistSummary(), true) },
                        onEnqueue = { placement -> onEnqueueArtist(artist.toArtistSummary(), placement) },
                        onToggleFavorite = { onToggleArtistFavorite(artist.toArtistSummary()) },
                        avatarSize = 112.dp,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }, key = "grid-footer", contentType = "footer") {
            SearchPaginationFooter(
                loaded = itemCount,
                total = feedState.total,
                hasMore = feedState.hasMore(category),
                isLoadingMore = feedState.isLoadingMore,
                error = feedState.error,
                onLoadMore = onLoadMore,
                onRetry = onRetry,
            )
        }
    }
}

@Composable
private fun SearchPaginationFooter(
    loaded: Int,
    total: Int,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    error: String?,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (error != null) {
            Text(
                text = error,
                color = AppColors.ErrorText,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            LabelKey(text = Strings.retry, onClick = onRetry, style = KeyStyle.Tonal)
        } else if (isLoadingMore) {
            LoadingCard(message = Strings.searchInProgress)
        } else if (hasMore) {
            LabelKey(text = Strings.loadMore, onClick = onLoadMore, style = KeyStyle.Tonal)
        }
        if (total > 0) {
            Text(
                text = Strings.searchLoadedProgress(loaded, total),
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun SearchArtworkThumbnail(
    imageUrl: String?,
    title: String,
    isCircle: Boolean,
    size: Dp,
) {
    val shape = if (isCircle) CircleShape else RoundedCornerShape(12.dp)
    if (imageUrl != null) {
        AsyncAlbumArtwork(
            imageUrl = imageUrl,
            title = title,
            modifier = Modifier
                .size(size)
                .clip(shape),
            contentScale = ContentScale.Crop,
            thumbnailSize = size,
            fit = ArtworkFit.Cover,
            shape = shape,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(shape)
                .background(KeyColors.Tonal),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isCircle) Icons.Filled.Person else Icons.Filled.Album,
                contentDescription = null,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}

@Composable
private fun SearchArtistActionMenu(
    expanded: Boolean,
    isFavorite: Boolean,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onEnqueue: (QueuePlacement) -> Unit,
    onToggleFavorite: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(16.dp)) {
        DropdownMenuItem(
            text = { Text(Strings.searchOpenArtist) },
            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
            onClick = { onDismiss(); onOpen() },
        )
        DropdownMenuItem(
            text = { Text(Strings.play) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { onDismiss(); onPlay() },
        )
        DropdownMenuItem(
            text = { Text(Strings.shuffle) },
            leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
            onClick = { onDismiss(); onShuffle() },
        )
        HorizontalDivider(color = KeyColors.Line, modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text(Strings.playNext) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
            onClick = { onDismiss(); onEnqueue(QueuePlacement.Next) },
        )
        DropdownMenuItem(
            text = { Text(Strings.addToQueueEnd) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
            onClick = { onDismiss(); onEnqueue(QueuePlacement.End) },
        )
        HorizontalDivider(color = KeyColors.Line, modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text(if (isFavorite) Strings.searchUnfavoriteAction else Strings.searchFavoriteAction) },
            leadingIcon = {
                Icon(
                    if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                )
            },
            onClick = { onDismiss(); onToggleFavorite() },
        )
    }
}

@Composable
private fun SearchTrackActionMenu(
    expanded: Boolean,
    track: MusicTrack,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onEnqueue: (QueuePlacement) -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenAlbum: (() -> Unit)?,
    onOpenArtist: (() -> Unit)?,
) {
    val addToPlaylist = addTrackToPlaylistAction(track)
    val startSimilarRadio = startSimilarRadioAction(track)
    val accent = LocalArtworkPalette.current.accent
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(16.dp)) {
        DropdownMenuItem(
            text = { Text(Strings.play) },
            leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
            onClick = { onDismiss(); onPlay() },
        )
        if (startSimilarRadio != null) {
            DropdownMenuItem(
                text = { Text(Strings.startSimilarRadio, color = accent, fontWeight = FontWeight.SemiBold) },
                leadingIcon = { Icon(Icons.Filled.WifiTethering, contentDescription = null, tint = accent) },
                onClick = { onDismiss(); startSimilarRadio() },
            )
        }
        if (addToPlaylist != null) {
            DropdownMenuItem(
                text = { Text(Strings.addToPlaylist) },
                leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
                onClick = { onDismiss(); addToPlaylist() },
            )
        }
        HorizontalDivider(color = KeyColors.Line, modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text(Strings.playNext) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null) },
            onClick = { onDismiss(); onEnqueue(QueuePlacement.Next) },
        )
        DropdownMenuItem(
            text = { Text(Strings.addToQueueEnd) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null) },
            onClick = { onDismiss(); onEnqueue(QueuePlacement.End) },
        )
        HorizontalDivider(color = KeyColors.Line, modifier = Modifier.padding(vertical = 4.dp))
        DropdownMenuItem(
            text = { Text(if (track.isFavorite) Strings.searchUnfavoriteAction else Strings.searchFavoriteAction) },
            leadingIcon = {
                Icon(
                    if (track.isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                )
            },
            onClick = { onDismiss(); onToggleFavorite() },
        )
        if (onOpenAlbum != null) {
            DropdownMenuItem(
                text = { Text(Strings.searchOpenAlbum) },
                leadingIcon = { Icon(Icons.Filled.Album, contentDescription = null) },
                onClick = { onDismiss(); onOpenAlbum() },
            )
        }
        if (onOpenArtist != null) {
            DropdownMenuItem(
                text = { Text(Strings.searchOpenArtist) },
                leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null) },
                onClick = { onDismiss(); onOpenArtist() },
            )
        }
    }
}
