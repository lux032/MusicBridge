package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.sonos.SonosRoom
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

@Composable
internal fun HomeSection(
    connectionPreferences: PlexConnectionPreferences,
    allAlbums: List<PlexAlbum>,
    recentPlayedAlbums: List<PlexAlbum>,
    favoriteAlbums: List<PlexAlbum>,
    recentAddedAlbums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    selectedSonosRoom: SonosRoom?,
    lastAlbumSyncEpochMillis: Long?,
    isLoading: Boolean,
    errorMessage: String?,
    actionMessage: String?,
    onRefreshFavorites: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onOpenAllAlbums: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecentAdded: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }

    LaunchedEffect(recentPlayedAlbums, favoriteAlbums, recentAddedAlbums) {
        (recentPlayedAlbums.take(9) + favoriteAlbums.take(9) + recentAddedAlbums.take(9))
            .mapNotNull(PlexAlbum::thumbUrl)
            .distinct()
            .forEach { imageUrl ->
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .size(coil3.size.Size(512, 512))
                        .build()
                )
            }
    }

    HomeStatusCard(
        selectedRoom = selectedSonosRoom,
        lastAlbumSyncEpochMillis = lastAlbumSyncEpochMillis,
    )

    errorMessage?.let { MessageCard(label = Strings.error, message = it, tone = MessageTone.Error) }

    if (isLoading) {
        LoadingCard(message = Strings.loading)
    }

    if (!connectionPreferences.hasCredentials() && allAlbums.isEmpty()) {
        EmptyStateCard(
            title = Strings.settingsPromptTitle,
            body = Strings.settingsPromptBody,
            actionLabel = Strings.refreshHome,
            onAction = onRefreshFavorites,
        )
        return
    }

    if (!isLoading && favoriteAlbums.isEmpty() && errorMessage == null) {
        EmptyStateCard(
            title = Strings.noFavoritesTitle,
            body = Strings.noFavoritesBody,
            actionLabel = Strings.retry,
            onAction = onRefreshFavorites,
        )
    }

    if (allAlbums.isNotEmpty()) {
        EntryActionCard(
            title = Strings.allAlbums,
            body = Strings.albumsSynced(allAlbums.size),
            actionLabel = Strings.view,
            onAction = onOpenAllAlbums,
        )
    }
    if (recentPlayedAlbums.isNotEmpty()) {
        RecentPlayedCarouselSection(
            albums = recentPlayedAlbums.take(9),
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
        )
    }
    if (favoriteAlbums.isNotEmpty()) {
        AlbumPreviewGridSection(
            title = Strings.favoriteAlbums,
            subtitle = "",
            albums = favoriteAlbums.take(9),
            selectedAlbum = selectedAlbum,
            actionLabel = Strings.viewAll,
            onAction = onOpenFavorites,
            onAlbumClick = onAlbumClick,
        )
    }
    if (recentAddedAlbums.isNotEmpty()) {
        AlbumPreviewGridSection(
            title = Strings.recentAdded,
            subtitle = "",
            albums = recentAddedAlbums.take(9),
            selectedAlbum = selectedAlbum,
            actionLabel = Strings.viewTop100,
            onAction = onOpenRecentAdded,
            onAlbumClick = onAlbumClick,
        )
    }
}

@Composable
internal fun HomeStatusCard(
    selectedRoom: SonosRoom?,
    lastAlbumSyncEpochMillis: Long?,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = Strings.currentOutputRoom,
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = selectedRoom?.roomName ?: Strings.noRoomSelected,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = formatSyncStatus(lastAlbumSyncEpochMillis),
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RecentPlayedCarouselSection(
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { albums.size })

    LaunchedEffect(albums.size) {
        if (albums.size <= 1) return@LaunchedEffect
        while (true) {
            delay(3_500)
            pagerState.animateScrollToPage((pagerState.currentPage + 1) % albums.size)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = Strings.recentPlayed,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 28.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val album = albums[page]
            val offset = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction).absoluteValue
                .coerceIn(0f, 1f)
            val scale = 0.9f + ((1f - offset) * 0.1f)
            val alpha = 0.7f + ((1f - offset) * 0.3f)

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .clickable { onAlbumClick(album) },
                shape = RoundedCornerShape(28.dp),
                color = AppColors.Surface,
                border = BorderStroke(
                    width = if (selectedAlbum?.ratingKey == album.ratingKey) 1.5.dp else 1.dp,
                    color = if (selectedAlbum?.ratingKey == album.ratingKey) AppColors.Accent else AppColors.Border,
                ),
                shadowElevation = 6.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.18f)
                ) {
                    AsyncAlbumArtwork(
                        imageUrl = album.thumbUrl,
                        title = album.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Transparent,
                                        Color.Black.copy(alpha = 0.18f + offset * 0.18f),
                                        Color.Black.copy(alpha = 0.72f),
                                    )
                                )
                            )
                    )
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (selectedAlbum?.ratingKey == album.ratingKey) {
                            Text(
                                text = Strings.currentViewing,
                                color = AppColors.Accent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Text(
                            text = album.title,
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = album.artistName ?: Strings.unknownArtist,
                            color = Color.White.copy(alpha = 0.88f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            albums.forEachIndexed { index, _ ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(
                            width = if (index == pagerState.currentPage) 22.dp else 8.dp,
                            height = 8.dp,
                        )
                        .clip(RoundedCornerShape(999.dp))
                        .background(
                            if (index == pagerState.currentPage) AppColors.Accent
                            else AppColors.BorderStrong.copy(alpha = 0.7f)
                        )
                )
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
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    color = AppColors.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun AlbumPreviewGridSection(
    title: String,
    subtitle: String,
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    onAlbumClick: (PlexAlbum) -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, color = AppColors.TextSecondary)
                }
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
        AlbumGrid(
            albums = albums,
            columns = 3,
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
            compact = true,
        )
    }
}

@Composable
internal fun AlbumCollectionSection(
    title: String,
    subtitle: String,
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
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

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text(Strings.backToHome)
        }
        AsyncAlbumArtwork(
            imageUrl = heroAlbum.thumbUrl,
            title = heroAlbum.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(16.dp)),
        )
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
        AlbumGrid(
            albums = albums,
            columns = 2,
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
            compact = false,
        )
    }
}

@Composable
internal fun AllAlbumsSection(
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    searchQuery: String,
    isSearchLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
    val albumGroups = remember(albums) {
        buildIndexedGroups(albums) { it.title }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val jumpTargets = remember(albumGroups) {
        buildGridJumpTargets(
            leadingItemCount = 3,
            groups = albumGroups,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 16.dp,
                end = 42.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(onClick = onBack) {
                    Text(Strings.backToHome)
                }
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
                        text = if (searchQuery.isBlank()) {
                            Strings.allAlbumsDesc(albums.size)
                        } else {
                            Strings.searchResults(searchQuery, albums.size)
                        },
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
            if (isSearchLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadingCard(message = Strings.searchingLocal)
                }
            } else if (albums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        title = if (searchQuery.isBlank()) Strings.noLocalAlbums else Strings.noSearchResults,
                        body = if (searchQuery.isBlank()) {
                            Strings.noLocalAlbumsDesc
                        } else {
                            Strings.noSearchResultsDesc
                        },
                        actionLabel = Strings.backToHome,
                        onAction = onBack,
                    )
                }
            } else {
                albumGroups.forEach { group ->
                    item(
                        key = "album-header-${group.label}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        IndexSectionHeader(label = group.label)
                    }
                    items(
                        items = group.items,
                        key = { album -> album.ratingKey },
                    ) { album ->
                        Box(modifier = Modifier.animateItem()) {
                            AlbumCoverCard(
                                album = album,
                                selected = selectedAlbum?.ratingKey == album.ratingKey,
                                onClick = { onAlbumClick(album) },
                                compact = false,
                            )
                        }
                    }
                }
            }
        }

        if (!isSearchLoading && jumpTargets.size > 1) {
            VerticalIndexBar(
                labels = jumpTargets.map { it.first },
                modifier = Modifier.padding(top = 100.dp, end = 4.dp, bottom = 12.dp),
                onSelect = { label ->
                    jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                        scope.launch { gridState.animateScrollToItem(index) }
                    }
                },
            )
        }
    }
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
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Surface,
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
                        containerColor = AppColors.Accent,
                        contentColor = AppColors.SurfaceStrong,
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
                    color = if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) AppColors.SurfaceMuted else AppColors.SurfaceAlt,
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
