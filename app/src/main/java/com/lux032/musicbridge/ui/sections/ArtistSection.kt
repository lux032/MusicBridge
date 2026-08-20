package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyItemScope
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
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

@Composable
internal fun ArtistsSection(
    artists: List<ArtistSummary>,
    presentation: ArtistPresentation,
    gridState: LazyGridState = rememberLazyGridState(),
    listState: LazyListState = rememberLazyListState(),
    onPresentationChange: (ArtistPresentation) -> Unit,
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

    val artistGroups = remember(artists) {
        buildIndexedGroups(artists) { it.name }
    }
    val scope = rememberCoroutineScope()
    var jumpJob by remember { mutableStateOf<Job?>(null) }

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
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
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
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = Strings.artistsDesc,
                                    color = AppColors.TextSecondary,
                                )
                            }
                            ArtistPresentationToggle(
                                presentation = presentation,
                                onPresentationChange = onPresentationChange,
                            )
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
                            ArtistCoverCard(
                                artist = artist,
                                compact = compactPhone,
                                onClick = { onArtistClick(artist) },
                            )
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
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = Strings.artistsDesc,
                                    color = AppColors.TextSecondary,
                                )
                            }
                            ArtistPresentationToggle(
                                presentation = presentation,
                                onPresentationChange = onPresentationChange,
                            )
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
                            ArtistListRow(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                            )
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

@Composable
internal fun ArtistCoverCard(
    artist: ArtistSummary,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val cornerRadius = if (compact) 18.dp else 24.dp
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(cornerRadius),
        color = AppColors.CardSurface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 0.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 10.dp)) {
            if (artist.coverUrl != null) {
                AsyncAlbumArtwork(
                    imageUrl = artist.coverUrl,
                    title = artist.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f)
                        .clip(RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)),
                    thumbnailSize = LocalAppLayoutInfo.current.artistGridMinimumWidth * 1.1f,
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f)
                        .background(AppColors.SurfaceMuted, RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(if (compact) 44.dp else 64.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(
                    start = if (compact) 10.dp else 14.dp,
                    end = if (compact) 10.dp else 14.dp,
                    bottom = if (compact) 10.dp else 14.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = artist.name,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Strings.albumsCount(artist.albumCount),
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
internal fun ArtistListRow(
    artist: ArtistSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.CardSurface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = artist.coverUrl,
                title = artist.name,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(18.dp)),
                thumbnailSize = 68.dp,
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = artist.name,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = Strings.albumsCount(artist.albumCount),
                    color = AppColors.TextSecondary,
                )
            }
            NavIconGraphic(
                icon = NavIcon.Artists,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
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
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
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
        shape = RoundedCornerShape(if (compact) 16.dp else 20.dp),
        color = LocalArtworkPalette.current.backgroundBottom.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 6.dp,
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
    selectedRoom: SonosRoom?,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    artistTracks: List<PlexTrackStream>,
    isArtistTracksLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    listState: LazyListState = rememberLazyListState(),
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onAlbumQueueAction: (PlexAlbum, QueuePlacement) -> Unit,
    onShuffleArtist: (ArtistSummary, SonosRoom) -> Unit,
    onToggleArtistFavorite: (ArtistSummary) -> Unit,
    onPlayTrack: (ArtistSummary, PlexTrackStream, SonosRoom, Int) -> Unit,
    onQueueTrack: (PlexTrackStream, QueuePlacement) -> Unit,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val currentArtist = artist
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
    // Reference layout shows newest releases first.
    val sortedAlbums = remember(currentArtist.albums) {
        currentArtist.albums.sortedByDescending { it.year ?: Int.MIN_VALUE }
    }
    val topTracks = remember(artistTracks) { artistTracks.take(10) }

    LazyColumn(
        state = listState,
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
            BackCircleButton(onClick = onBack, contentDescription = Strings.backToArtists)
        }

        item(contentType = "hero") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = currentArtist.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = Strings.albumsCount(currentArtist.albumCount),
                        color = AppColors.TextSecondary,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FavoriteIconButton(
                            isFavorite = currentArtist.isFavorite,
                            isLoading = isFavoriteLoading,
                            onClick = { onToggleArtistFavorite(currentArtist) },
                        )
                        val playAllEnabled =
                            selectedRoom != null && artistTracks.isNotEmpty() && !isPlaybackLoading
                        IconCircleButton(
                            onClick = {
                                selectedRoom?.let { room ->
                                    artistTracks.firstOrNull()?.let { onPlayTrack(currentArtist, it, room, 0) }
                                }
                            },
                            enabled = playAllEnabled,
                            containerColor = if (playAllEnabled) palette.accent else palette.accent.copy(alpha = 0.35f),
                            modifier = Modifier.semantics { contentDescription = Strings.playAll },
                        ) {
                            PlayerIconGraphic(
                                icon = PlayerIcon.Play,
                                tint = palette.onAccent.copy(alpha = if (playAllEnabled) 1f else 0.6f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        val shuffleEnabled =
                            selectedRoom != null && currentArtist.albums.isNotEmpty() && !isPlaybackLoading
                        IconCircleButton(
                            onClick = { selectedRoom?.let { onShuffleArtist(currentArtist, it) } },
                            enabled = shuffleEnabled,
                            containerColor = AppColors.CardSurfaceStrong,
                            modifier = Modifier.semantics { contentDescription = Strings.shuffleShort },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shuffle,
                                contentDescription = null,
                                tint = if (shuffleEnabled) AppColors.TextPrimary else AppColors.TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Text(
                        text = selectedRoom?.let { Strings.willPushTo(it.roomName) } ?: Strings.noRoomSelectedDesc,
                        color = AppColors.TextTertiary,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (currentArtist.coverUrl != null) {
                    AsyncAlbumArtwork(
                        imageUrl = currentArtist.coverUrl,
                        title = currentArtist.name,
                        modifier = Modifier
                            .size(104.dp)
                            .border(BorderStroke(1.dp, AppColors.BorderStrong), CircleShape)
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop,
                        thumbnailSize = 104.dp,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(104.dp)
                            .border(BorderStroke(1.dp, AppColors.BorderStrong), CircleShape)
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
            }
        }

        if (sortedAlbums.isNotEmpty()) {
            item(contentType = "albums-header") {
                Text(
                    text = Strings.albums,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            itemsIndexed(
                sortedAlbums.chunked(3),
                key = { _, rowAlbums -> "artist-album-row-${rowAlbums.first().ratingKey}" },
                contentType = { _, _ -> "album-row" },
            ) { _, rowAlbums ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    rowAlbums.forEach { album ->
                        Box(modifier = Modifier.weight(1f)) {
                            AlbumCoverCard(
                                album = album,
                                selected = selectedAlbum?.ratingKey == album.ratingKey,
                                onClick = { onAlbumClick(album) },
                                onQueueAction = { onAlbumQueueAction(album, it) },
                                compact = true,
                                // The artist name is redundant on their own page; show year · track count.
                                subtitleOverride = listOfNotNull(
                                    album.year?.toString(),
                                    album.leafCount?.let(Strings.tracks),
                                ).joinToString(" · ").ifBlank { album.artistName.orEmpty() },
                            )
                        }
                    }
                    repeat(3 - rowAlbums.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
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
        } else if (topTracks.isNotEmpty()) {
            item(contentType = "tracks-header") {
                Text(
                    text = Strings.topTracks,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            itemsIndexed(
                topTracks,
                key = { index, track -> track.ratingKey ?: "artist-track-$index" },
                contentType = { _, _ -> "track" },
            ) { index, track ->
                var showQueueMenu by remember { mutableStateOf(false) }
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !isPlaybackLoading) {
                                selectedRoom?.let { onPlayTrack(currentArtist, track, it, index) }
                            }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncAlbumArtwork(
                            imageUrl = track.thumbUrl,
                            title = track.title,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            thumbnailSize = 48.dp,
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text(
                                text = track.title,
                                color = AppColors.TextPrimary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = track.artistName ?: track.albumTitle.orEmpty(),
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        track.durationMillis?.let {
                            Text(
                                text = formatDuration(it),
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconCircleButton(onClick = { showQueueMenu = true }) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = null,
                                tint = AppColors.TextTertiary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    QueueActionMenu(
                        expanded = showQueueMenu,
                        onDismiss = { showQueueMenu = false },
                        onAction = { placement ->
                            showQueueMenu = false
                            onQueueTrack(track, placement)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun ArtistPresentationToggle(
    presentation: ArtistPresentation,
    onPresentationChange: (ArtistPresentation) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconCircleButton(
            onClick = { onPresentationChange(ArtistPresentation.Covers) },
            enabled = presentation != ArtistPresentation.Covers,
            highlighted = presentation == ArtistPresentation.Covers,
        ) {
            NavIconGraphic(
                icon = NavIcon.Covers,
                tint = if (presentation == ArtistPresentation.Covers) AppColors.Accent else AppColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconCircleButton(
            onClick = { onPresentationChange(ArtistPresentation.List) },
            enabled = presentation != ArtistPresentation.List,
            highlighted = presentation == ArtistPresentation.List,
        ) {
            NavIconGraphic(
                icon = NavIcon.List,
                tint = if (presentation == ArtistPresentation.List) AppColors.Accent else AppColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
