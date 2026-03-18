package com.lux032.plextosonosplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbum
import kotlinx.coroutines.launch

@Composable
internal fun ArtistsSection(
    artists: List<ArtistSummary>,
    presentation: ArtistPresentation,
    onPresentationChange: (ArtistPresentation) -> Unit,
    onGoHome: () -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
) {
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

    when (presentation) {
        ArtistPresentation.Covers -> {
            val gridState = rememberLazyGridState()
            val jumpTargets = remember(artistGroups) {
                buildGridJumpTargets(
                    leadingItemCount = 1,
                    groups = artistGroups,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = 42.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(span = { GridItemSpan(2) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        ) {
                            IndexSectionHeader(label = group.label)
                        }
                        items(
                            items = group.items,
                            key = { artist -> "artist-${artist.name}" },
                        ) { artist ->
                            ArtistCoverCard(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                            )
                        }
                    }
                }

                if (jumpTargets.size > 1) {
                    VerticalIndexBar(
                        labels = jumpTargets.map { it.first },
                        modifier = Modifier.padding(top = 56.dp, bottom = 12.dp),
                        onSelect = { label ->
                            jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                                scope.launch { gridState.animateScrollToItem(index) }
                            }
                        },
                    )
                }
            }
        }
        ArtistPresentation.List -> {
            val listState = rememberLazyListState()
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
                    contentPadding = PaddingValues(end = 42.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        item(key = "artist-list-header-${group.label}") {
                            IndexSectionHeader(label = group.label)
                        }
                        items(
                            items = group.items,
                            key = { artist -> "artist-list-${artist.name}" },
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
                        modifier = Modifier.padding(top = 56.dp, bottom = 12.dp),
                        onSelect = { label ->
                            jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                                scope.launch { listState.animateScrollToItem(index) }
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
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 4.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (artist.coverUrl != null) {
                AsyncAlbumArtwork(
                    imageUrl = artist.coverUrl,
                    title = artist.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f)
                        .background(AppColors.SurfaceMuted, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
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
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.Surface,
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
        shape = RoundedCornerShape(20.dp),
        color = AppColors.SurfaceStrong.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .width(26.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(label) }
                        .padding(vertical = 2.dp),
                    color = if (label == activeLabel) AppColors.SurfaceStrong else AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
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
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
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

    val heroAlbum = currentArtist.albums.firstOrNull()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text(Strings.backToArtists)
        }
        heroAlbum?.let { album ->
            AsyncAlbumArtwork(
                imageUrl = currentArtist.coverUrl ?: album.thumbUrl,
                title = currentArtist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(RoundedCornerShape(24.dp)),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = currentArtist.name,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = Strings.artistAlbumsDesc(currentArtist.albumCount),
                color = AppColors.TextSecondary,
            )
        }
        AlbumGrid(
            albums = currentArtist.albums,
            columns = 2,
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
            compact = false,
        )
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
                tint = if (presentation == ArtistPresentation.Covers) AppColors.SurfaceStrong else AppColors.TextPrimary,
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
                tint = if (presentation == ArtistPresentation.List) AppColors.SurfaceStrong else AppColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
