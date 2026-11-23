package com.lux032.musicbridge

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animate
import kotlinx.coroutines.Job
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.semantics.Role
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.graphics.graphicsLayer
import com.lux032.musicbridge.storage.containsDownloadedTrack
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToLong
import com.lux032.musicbridge.music.AlbumDiscGroup
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.AlbumTracksResult
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.effectiveDiscCount
import com.lux032.musicbridge.music.groupByDisc
import com.lux032.musicbridge.music.isFavorite
import com.lux032.musicbridge.music.isMultiDiscAlbum
import com.lux032.musicbridge.music.playbackUrlIdentity
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.willTranscodeLocally
import com.lux032.musicbridge.music.Music032LocalTranscode
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import android.util.Log
import com.lux032.musicbridge.local.originalCacheKey
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


internal fun albumDetailMetaText(
    album: MusicAlbum,
    tracks: List<MusicTrack>,
    isMultiDisc: Boolean,
    discCount: Int,
): String {
    val totalDurationMillis = tracks.sumOf { it.durationMillis ?: 0L }
    val codec = tracks.firstOrNull()
        ?.sourceAudioCodec
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.ROOT)
    return buildList {
        album.formattedReleaseDate()?.let(::add)
        if (isMultiDisc) add(Strings.discCountFact(discCount))
        add(Strings.tracks(tracks.size))
        if (totalDurationMillis > 0L) add(formatDuration(totalDurationMillis))
        codec?.let(::add)
    }.joinToString(" · ")
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumDetailSection(
    trackResult: AlbumTracksResult?,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    trackListState: LazyListState = rememberLazyListState(),
    onReturnHome: () -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
    onQueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onQueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (MusicTrack) -> Unit = {},
    onDeleteDownloadedTrack: (MusicTrack) -> Unit = {},
    onDownloadAlbum: (MusicAlbum, List<MusicTrack>) -> Unit = { _, _ -> },
    onDeleteDownloadedAlbum: (MusicAlbum) -> Unit = {},
    onDeleteDownloadedDisc: (List<MusicTrack>, Int) -> Unit = { _, _ -> },
    onPlayAlbum: (AlbumTracksResult) -> Unit,
    onPlayDisc: (MusicAlbum, AlbumDiscGroup<MusicTrack>) -> Unit = { _, _ -> },
    onQueueDisc: (MusicAlbum, AlbumDiscGroup<MusicTrack>, QueuePlacement) -> Unit = { _, _, _ -> },
    onPlayTrack: (MusicAlbum, MusicTrack) -> Unit,
    loadTrackWorks: suspend (MusicTrack) -> List<com.lux032.musicbridge.music.Music032TrackWork> = { emptyList() },
    loadWorkInfo: suspend (String, Long) -> com.lux032.musicbridge.music.Music032Work? = { _, _ -> null },
    loadTrackCredits: suspend (MusicTrack) -> List<com.lux032.musicbridge.music.TrackCredit> = { emptyList() },
    onOpenWork: (String, Long) -> Unit = { _, _ -> },
    nowPlaying: NowPlayingMarker? = LocalNowPlaying.current,
) {
    val currentTrackResult = trackResult
    if (currentTrackResult == null) {
        EmptyStateCard(
            title = Strings.noAlbumOpened,
            body = Strings.noAlbumOpenedDesc,
            actionLabel = Strings.backToHome,
            onAction = onReturnHome,
        )
        return
    }

    val layoutInfo = LocalAppLayoutInfo.current
    var showAlbumQueueMenu by remember { mutableStateOf(false) }
    var selectedInfoTrack by remember(currentTrackResult.album.sourceId, currentTrackResult.album.ratingKey) {
        mutableStateOf<MusicTrack?>(null)
    }
    val isMultiDisc = remember(currentTrackResult) {
        isMultiDiscAlbum(currentTrackResult.album, currentTrackResult.tracks)
    }
    val discCount = remember(currentTrackResult) {
        effectiveDiscCount(currentTrackResult.album, currentTrackResult.tracks)
    }
    val discGroups = remember(currentTrackResult, isMultiDisc) {
        if (isMultiDisc) currentTrackResult.tracks.groupByDisc() else emptyList()
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = layoutInfo.horizontalPadding,
                    top = 12.dp,
                    end = layoutInfo.horizontalPadding,
                    bottom = 4.dp,
                ),
        ) {
            BackCircleButton(onClick = onReturnHome, contentDescription = Strings.backToHome)
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
                    .padding(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                AlbumDetailHeroWide(
                    trackResult = currentTrackResult,
                    isMultiDisc = isMultiDisc,
                    discCount = discCount,
                    outputTargetName = outputTargetName,
                    isPlaybackLoading = isPlaybackLoading,
                    isFavoriteLoading = isFavoriteLoading,
                    showAlbumQueueMenu = showAlbumQueueMenu,
                    onShowAlbumQueueMenuChange = { showAlbumQueueMenu = it },
                    onArtistClick = onArtistClick,
                    onToggleAlbumFavorite = onToggleAlbumFavorite,
                    onQueueAlbum = onQueueAlbum,
                    onPlayAlbum = onPlayAlbum,
                    downloadedTrackKeys = downloadedTrackKeys,
                    downloadProgress = downloadProgress,
                    onDownloadAlbum = onDownloadAlbum,
                    onDeleteDownloadedAlbum = onDeleteDownloadedAlbum,
                )
            }
            LazyColumn(
                state = trackListState,
                modifier = Modifier
                    .weight(1.18f)
                    .fillMaxHeight(),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
            ) {
                albumTrackItems(
                    album = currentTrackResult.album,
                    tracks = currentTrackResult.tracks,
                    isMultiDisc = isMultiDisc,
                    discGroups = discGroups,
                    isPlaybackLoading = isPlaybackLoading,
                    isFavoriteLoading = isFavoriteLoading,
                    downloadedTrackKeys = downloadedTrackKeys,
                    downloadProgress = downloadProgress,
                    onDownloadTrack = onDownloadTrack,
                    onDeleteDownloadedTrack = onDeleteDownloadedTrack,
                    onDownloadAlbum = onDownloadAlbum,
                    onDeleteDownloadedDisc = onDeleteDownloadedDisc,
                    onToggleTrackFavorite = onToggleTrackFavorite,
                    onQueueTrack = onQueueTrack,
                    onPlayDisc = onPlayDisc,
                    onQueueDisc = onQueueDisc,
                    onPlayTrack = onPlayTrack,
                    onShowTrackInfo = { selectedInfoTrack = it },
                    nowPlaying = nowPlaying,
                )
            }
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
            item(key = "album-summary", contentType = "album-summary") {
                AlbumDetailHeroCompact(
                    trackResult = currentTrackResult,
                    isMultiDisc = isMultiDisc,
                    discCount = discCount,
                    outputTargetName = outputTargetName,
                    isPlaybackLoading = isPlaybackLoading,
                    isFavoriteLoading = isFavoriteLoading,
                    showAlbumQueueMenu = showAlbumQueueMenu,
                    onShowAlbumQueueMenuChange = { showAlbumQueueMenu = it },
                    onArtistClick = onArtistClick,
                    onToggleAlbumFavorite = onToggleAlbumFavorite,
                    onQueueAlbum = onQueueAlbum,
                    onPlayAlbum = onPlayAlbum,
                    downloadedTrackKeys = downloadedTrackKeys,
                    downloadProgress = downloadProgress,
                    onDownloadAlbum = onDownloadAlbum,
                    onDeleteDownloadedAlbum = onDeleteDownloadedAlbum,
                )
            }
            albumTrackItems(
                album = currentTrackResult.album,
                tracks = currentTrackResult.tracks,
                isMultiDisc = isMultiDisc,
                discGroups = discGroups,
                isPlaybackLoading = isPlaybackLoading,
                isFavoriteLoading = isFavoriteLoading,
                downloadedTrackKeys = downloadedTrackKeys,
                downloadProgress = downloadProgress,
                onDownloadTrack = onDownloadTrack,
                onDeleteDownloadedTrack = onDeleteDownloadedTrack,
                onDownloadAlbum = onDownloadAlbum,
                onDeleteDownloadedDisc = onDeleteDownloadedDisc,
                onToggleTrackFavorite = onToggleTrackFavorite,
                onQueueTrack = onQueueTrack,
                onPlayDisc = onPlayDisc,
                onQueueDisc = onQueueDisc,
                onPlayTrack = onPlayTrack,
                onShowTrackInfo = { selectedInfoTrack = it },
                nowPlaying = nowPlaying,
            )
        }
    }
    }

    selectedInfoTrack?.let { infoTrack ->
        AlbumTrackInfoSheet(
            track = infoTrack,
            album = currentTrackResult.album,
            technicalDetails = sourceAudioDetailText(infoTrack),
            palette = LocalArtworkPalette.current,
            loadWorks = loadTrackWorks,
            loadWorkInfo = loadWorkInfo,
            loadCredits = loadTrackCredits,
            onOpenWork = onOpenWork,
            onOpenArtist = { ref -> onArtistClick(ref.name, listOf(ref)) },
            onDismiss = { selectedInfoTrack = null },
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.albumTrackItems(
    album: MusicAlbum,
    tracks: List<MusicTrack>,
    isMultiDisc: Boolean,
    discGroups: List<AlbumDiscGroup<MusicTrack>>,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    downloadedTrackKeys: Set<String>,
    downloadProgress: Map<String, Float>,
    onDownloadTrack: (MusicTrack) -> Unit,
    onDeleteDownloadedTrack: (MusicTrack) -> Unit,
    onDownloadAlbum: (MusicAlbum, List<MusicTrack>) -> Unit,
    onDeleteDownloadedDisc: (List<MusicTrack>, Int) -> Unit,
    onToggleTrackFavorite: (MusicTrack) -> Unit,
    onQueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onPlayDisc: (MusicAlbum, AlbumDiscGroup<MusicTrack>) -> Unit,
    onQueueDisc: (MusicAlbum, AlbumDiscGroup<MusicTrack>, QueuePlacement) -> Unit,
    onPlayTrack: (MusicAlbum, MusicTrack) -> Unit,
    onShowTrackInfo: (MusicTrack) -> Unit,
    nowPlaying: NowPlayingMarker?,
) {
    if (!isMultiDisc) {
        itemsIndexed(
            items = tracks,
            key = { index, track -> track.ratingKey ?: index },
            contentType = { _, _ -> "album-track" },
        ) { index, track ->
            val displayNumber = (track.trackNumber?.takeIf { it > 0 } ?: (index + 1)).toString()
            Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                AlbumTrackRow(
                    index = index,
                    displayNumber = displayNumber,
                    album = album,
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
                    onShowTrackInfo = onShowTrackInfo,
                    nowPlaying = nowPlaying?.takeIf { it.matches(track) },
                )
            }
        }
        return
    }

    discGroups.forEachIndexed { groupIndex, discGroup ->
        item(
            key = "disc-header-${discGroup.discNumber}",
            contentType = "album-disc-header",
        ) {
            AlbumDiscHeaderRow(
                album = album,
                discGroup = discGroup,
                isFirstDisc = groupIndex == 0,
                isPlaybackLoading = isPlaybackLoading,
                downloadedTrackKeys = downloadedTrackKeys,
                onPlayDisc = { onPlayDisc(album, discGroup) },
                onQueueDisc = { placement -> onQueueDisc(album, discGroup, placement) },
                onDownloadDisc = { onDownloadAlbum(album, discGroup.tracks) },
                onDeleteDownloadedDisc = { onDeleteDownloadedDisc(discGroup.tracks, discGroup.discNumber) },
            )
        }
        itemsIndexed(
            items = discGroup.tracks,
            key = { index, track -> "d${discGroup.discNumber}:${track.ratingKey ?: index}" },
            contentType = { _, _ -> "album-track" },
        ) { indexInDisc, track ->
            // Multi-disc albums restart track numbers from 1 per disc on compact mobile rows.
            val displayNumber = (indexInDisc + 1).toString()
            Box(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                AlbumTrackRow(
                    index = indexInDisc,
                    displayNumber = displayNumber,
                    album = album,
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
                    onShowTrackInfo = onShowTrackInfo,
                    nowPlaying = nowPlaying?.takeIf { it.matches(track) },
                )
            }
        }
    }
}

@Composable
private fun AlbumDiscHeaderRow(
    album: MusicAlbum,
    discGroup: AlbumDiscGroup<MusicTrack>,
    isFirstDisc: Boolean,
    isPlaybackLoading: Boolean,
    downloadedTrackKeys: Set<String>,
    onPlayDisc: () -> Unit,
    onQueueDisc: (QueuePlacement) -> Unit,
    onDownloadDisc: () -> Unit,
    onDeleteDownloadedDisc: () -> Unit,
) {
    val palette = LocalArtworkPalette.current
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val isDiscDownloaded = remember(discGroup.tracks, downloadedTrackKeys) {
        discGroup.tracks.isNotEmpty() && discGroup.tracks.all { track ->
            downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)
        }
    }
    val metaText = remember(discGroup.tracks.size, discGroup.totalDurationMillis) {
        buildString {
            append(Strings.tracks(discGroup.tracks.size))
            if (discGroup.totalDurationMillis > 0L) {
                append(" · ")
                append(formatDuration(discGroup.totalDurationMillis))
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (isFirstDisc) 8.dp else 18.dp, bottom = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(
                        if (!isPlaybackLoading) palette.accent else palette.accent.copy(alpha = 0.45f),
                    )
                    .clickable(
                        enabled = !isPlaybackLoading,
                        role = Role.Button,
                        onClickLabel = Strings.discPlayContentDescription(discGroup.discNumber),
                        onClick = onPlayDisc,
                    )
                    .semantics {
                        contentDescription = Strings.discPlayContentDescription(discGroup.discNumber)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = Strings.discLabel(discGroup.discNumber),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                )
                Text(
                    text = metaText,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                )
            }
            Box {
                BareIcon(
                    icon = Icons.Filled.MoreVert,
                    contentDescription = Strings.discMoreContentDescription(discGroup.discNumber),
                    onClick = { showMenu = true },
                    modifier = Modifier.offset(x = 8.dp),
                )
                QueueActionMenu(
                    expanded = showMenu,
                    onDismiss = { showMenu = false },
                    onAction = { placement ->
                        showMenu = false
                        onQueueDisc(placement)
                    },
                    onPlay = {
                        showMenu = false
                        onPlayDisc()
                    },
                    isDownloaded = isDiscDownloaded,
                    downloadLabel = if (isDiscDownloaded) Strings.deleteDiscDownload else Strings.downloadDiscOriginal,
                    onDownloadAction = {
                        if (isDiscDownloaded) {
                            showDeleteDialog = true
                        } else {
                            onDownloadDisc()
                        }
                    },
                    onAddToPlaylist = addTracksToPlaylistAction(discGroup.tracks),
                )
            }
        }
        Hairline()
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(Strings.deleteDiscDownload) },
            text = { Text("${album.title} · ${Strings.discLabel(discGroup.discNumber)}") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteDownloadedDisc()
                }) {
                    Text(Strings.confirmDelete, color = AppColors.ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(Strings.cancel, color = AppColors.TextSecondary)
                }
            },
            containerColor = palette.backgroundBottom,
            titleContentColor = AppColors.TextPrimary,
            textContentColor = AppColors.TextSecondary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumDetailHeroWide(
    trackResult: AlbumTracksResult,
    isMultiDisc: Boolean,
    discCount: Int,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    showAlbumQueueMenu: Boolean,
    onShowAlbumQueueMenuChange: (Boolean) -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onQueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onPlayAlbum: (AlbumTracksResult) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadAlbum: (MusicAlbum, List<MusicTrack>) -> Unit = { _, _ -> },
    onDeleteDownloadedAlbum: (MusicAlbum) -> Unit = {},
) {
    val layoutInfo = LocalAppLayoutInfo.current
    val palette = LocalArtworkPalette.current
    val album = trackResult.album
    val totalTracks = trackResult.tracks.size
    val downloadedCount = trackResult.tracks.count { track ->
        downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)
    }
    val isAnyDownloading = trackResult.tracks.any { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadProgress
    }
    val isAllDownloaded = totalTracks > 0 && downloadedCount == totalTracks
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val heroShape = RoundedCornerShape(14.dp)
        AsyncAlbumArtwork(
            imageUrl = album.thumbUrl,
            title = album.title,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .fillMaxWidth()
                .aspectRatio(1f),
            thumbnailSize = 300.dp,
            placeholderThumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
            fit = ArtworkFit.Contain,
            alignment = Alignment.Center,
            shape = heroShape,
            imageModifier = Modifier
                .shadow(14.dp, heroShape, clip = false)
                .clip(heroShape)
                .semantics { contentDescription = "${album.title}. ${Strings.longPressQueueHint}" }
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onShowAlbumQueueMenuChange(true) },
                ),
        )
        QueueActionMenu(
            expanded = showAlbumQueueMenu,
            onDismiss = { onShowAlbumQueueMenuChange(false) },
            onAction = { placement ->
                onShowAlbumQueueMenuChange(false)
                onQueueAlbum(album, placement)
            },
            onPlay = {
                onShowAlbumQueueMenuChange(false)
                onPlayAlbum(trackResult)
            },
            isDownloaded = isAllDownloaded,
            downloadLabel = if (isAllDownloaded) Strings.deleteAlbumDownload else Strings.downloadAlbumOriginal,
            onDownloadAction = {
                if (isAllDownloaded) {
                    showDeleteAlbumDialog = true
                } else {
                    onDownloadAlbum(album, trackResult.tracks)
                }
            },
            onAddToPlaylist = addAlbumToPlaylistAction(album, trackResult.tracks.size),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val artistName = album.artistName ?: Strings.unknownArtist
        Text(
            text = album.title,
            style = MaterialTheme.typography.headlineMedium,
            color = AppColors.TextPrimary,
        )
        Text(
            text = artistName,
            color = palette.accent,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onArtistClick(album.artistName, album.artists) },
        )
        val metaText = remember(album, trackResult.tracks, isMultiDisc, discCount) {
            albumDetailMetaText(album, trackResult.tracks, isMultiDisc, discCount)
        }
        Text(
            text = metaText,
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        AlbumActionRow(
            trackResult = trackResult,
            outputTargetName = outputTargetName,
            isPlaybackLoading = isPlaybackLoading,
            isFavoriteLoading = isFavoriteLoading,
            isAllDownloaded = isAllDownloaded,
            isAnyDownloading = isAnyDownloading,
            onPlayAlbum = onPlayAlbum,
            onToggleAlbumFavorite = onToggleAlbumFavorite,
            onDownloadAlbum = onDownloadAlbum,
            onRequestDeleteDownload = { showDeleteAlbumDialog = true },
            onQueueAlbum = onQueueAlbum,
            onBeforeQueueMenu = { onShowAlbumQueueMenuChange(false) },
        )
    }

    if (showDeleteAlbumDialog) {
        DeleteAlbumDownloadDialog(
            onConfirm = {
                showDeleteAlbumDialog = false
                onDeleteDownloadedAlbum(album)
            },
            onDismiss = { showDeleteAlbumDialog = false },
        )
    }
}

/**
 * Album action row (design D): one light primary key naming the room, then tonal keys for
 * favourite / download / add-to-queue.
 */
@Composable
private fun AlbumActionRow(
    trackResult: AlbumTracksResult,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    isAllDownloaded: Boolean,
    isAnyDownloading: Boolean,
    onPlayAlbum: (AlbumTracksResult) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onDownloadAlbum: (MusicAlbum, List<MusicTrack>) -> Unit,
    onRequestDeleteDownload: () -> Unit,
    onQueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onBeforeQueueMenu: () -> Unit = {},
) {
    val palette = LocalArtworkPalette.current
    val album = trackResult.album
    var showActionQueueMenu by remember(album.sourceId, album.ratingKey) { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitPlayKey(
            label = Strings.playOn(outputTargetName),
            onPlay = { onPlayAlbum(trackResult) },
            onShuffle = null,
            enabled = !isPlaybackLoading && trackResult.tracks.isNotEmpty(),
            isLoading = isPlaybackLoading,
            modifier = Modifier.weight(1f),
        )
        FavoriteIconButton(
            isFavorite = album.isFavorite,
            isLoading = isFavoriteLoading,
            onClick = { onToggleAlbumFavorite(album) },
        )
        when {
            isAllDownloaded -> Key(
                onClick = onRequestDeleteDownload,
                modifier = Modifier.size(48.dp),
                contentDescription = Strings.deleteAlbumDownload,
            ) {
                Icon(
                    Icons.Filled.DownloadDone,
                    contentDescription = null,
                    tint = palette.accent,
                    modifier = Modifier.size(22.dp),
                )
            }
            isAnyDownloading -> Key(
                onClick = {},
                enabled = false,
                modifier = Modifier.size(48.dp),
                contentDescription = Strings.downloadAlbumOriginal,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
            }
            else -> IconKey(
                icon = Icons.Filled.Download,
                contentDescription = Strings.downloadAlbumOriginal,
                onClick = { onDownloadAlbum(album, trackResult.tracks) },
            )
        }
        Box {
            IconKey(
                icon = Icons.Filled.PlaylistAdd,
                contentDescription = Strings.addToPlaybackQueue,
                onClick = {
                    onBeforeQueueMenu()
                    showActionQueueMenu = true
                },
            )
            QueueActionMenu(
                expanded = showActionQueueMenu,
                onDismiss = { showActionQueueMenu = false },
                onAction = { placement ->
                    showActionQueueMenu = false
                    onQueueAlbum(album, placement)
                },
                onAddToPlaylist = addAlbumToPlaylistAction(album, trackResult.tracks.size),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumDetailHeroCompact(
    trackResult: AlbumTracksResult,
    isMultiDisc: Boolean,
    discCount: Int,
    outputTargetName: String,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    showAlbumQueueMenu: Boolean,
    onShowAlbumQueueMenuChange: (Boolean) -> Unit,
    onArtistClick: (String?, List<ArtistRef>) -> Unit,
    onToggleAlbumFavorite: (MusicAlbum) -> Unit,
    onQueueAlbum: (MusicAlbum, QueuePlacement) -> Unit,
    onPlayAlbum: (AlbumTracksResult) -> Unit,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadAlbum: (MusicAlbum, List<MusicTrack>) -> Unit = { _, _ -> },
    onDeleteDownloadedAlbum: (MusicAlbum) -> Unit = {},
) {
    val palette = LocalArtworkPalette.current
    val album = trackResult.album
    val totalTracks = trackResult.tracks.size
    val downloadedCount = trackResult.tracks.count { track ->
        downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)
    }
    val isAnyDownloading = trackResult.tracks.any { track ->
        "${track.sourceId}:${track.ratingKey}" in downloadProgress
    }
    val isAllDownloaded = totalTracks > 0 && downloadedCount == totalTracks
    var showDeleteAlbumDialog by remember { mutableStateOf(false) }
    val metaText = remember(album, trackResult.tracks, isMultiDisc, discCount) {
        albumDetailMetaText(album, trackResult.tracks, isMultiDisc, discCount)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .fillMaxWidth(0.72f)
                .widthIn(max = 320.dp),
        ) {
            val coverShape = RoundedCornerShape(14.dp)
            AsyncAlbumArtwork(
                imageUrl = album.thumbUrl,
                title = album.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
                thumbnailSize = 320.dp,
                placeholderThumbnailSize = null,
                fit = ArtworkFit.Contain,
                alignment = Alignment.Center,
                shape = coverShape,
                imageModifier = Modifier
                    .shadow(14.dp, coverShape, clip = false)
                    .clip(coverShape)
                    .semantics { contentDescription = "${album.title}. ${Strings.longPressQueueHint}" }
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onShowAlbumQueueMenuChange(true) },
                    ),
            )
            QueueActionMenu(
                expanded = showAlbumQueueMenu,
                onDismiss = { onShowAlbumQueueMenuChange(false) },
                onAction = { placement ->
                    onShowAlbumQueueMenuChange(false)
                    onQueueAlbum(album, placement)
                },
                onPlay = {
                    onShowAlbumQueueMenuChange(false)
                    onPlayAlbum(trackResult)
                },
                isDownloaded = isAllDownloaded,
                downloadLabel = if (isAllDownloaded) Strings.deleteAlbumDownload else Strings.downloadAlbumOriginal,
                onDownloadAction = {
                    if (isAllDownloaded) {
                        showDeleteAlbumDialog = true
                    } else {
                        onDownloadAlbum(album, trackResult.tracks)
                    }
                },
                onAddToPlaylist = addAlbumToPlaylistAction(album, trackResult.tracks.size),
            )
        }

        Spacer(Modifier.height(20.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.headlineSmall,
            color = AppColors.TextPrimary,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = album.artistName ?: Strings.unknownArtist,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.accent,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onArtistClick(album.artistName, album.artists) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = metaText,
            color = AppColors.TextTertiary,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(18.dp))
        AlbumActionRow(
            trackResult = trackResult,
            outputTargetName = outputTargetName,
            isPlaybackLoading = isPlaybackLoading,
            isFavoriteLoading = isFavoriteLoading,
            isAllDownloaded = isAllDownloaded,
            isAnyDownloading = isAnyDownloading,
            onPlayAlbum = onPlayAlbum,
            onToggleAlbumFavorite = onToggleAlbumFavorite,
            onDownloadAlbum = onDownloadAlbum,
            onRequestDeleteDownload = { showDeleteAlbumDialog = true },
            onQueueAlbum = onQueueAlbum,
            onBeforeQueueMenu = { onShowAlbumQueueMenuChange(false) },
        )
        Spacer(Modifier.height(8.dp))
    }

    if (showDeleteAlbumDialog) {
        DeleteAlbumDownloadDialog(
            onConfirm = {
                showDeleteAlbumDialog = false
                onDeleteDownloadedAlbum(album)
            },
            onDismiss = { showDeleteAlbumDialog = false },
        )
    }
}

@Composable
private fun DeleteAlbumDownloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.deleteAlbumDownload) },
        text = { Text(Strings.confirmDeleteAlbumDownload) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(Strings.confirmDelete, color = AppColors.ErrorText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(Strings.cancel, color = AppColors.TextSecondary)
            }
        },
        containerColor = LocalArtworkPalette.current.backgroundBottom,
        titleContentColor = AppColors.TextPrimary,
        textContentColor = AppColors.TextSecondary,
    )
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumTrackRow(
    index: Int,
    displayNumber: String = "${index + 1}",
    album: MusicAlbum,
    track: MusicTrack,
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    downloadedTrackKeys: Set<String> = emptySet(),
    downloadProgress: Map<String, Float> = emptyMap(),
    onDownloadTrack: (MusicTrack) -> Unit = {},
    onDeleteDownloadedTrack: (MusicTrack) -> Unit = {},
    onToggleTrackFavorite: (MusicTrack) -> Unit,
    onQueueTrack: (MusicTrack, QueuePlacement) -> Unit,
    onPlayTrack: (MusicAlbum, MusicTrack) -> Unit,
    onShowTrackInfo: (MusicTrack) -> Unit = {},
    nowPlaying: NowPlayingMarker? = null,
) {
    val menuState = rememberContextualMenuState()
    // Only the matching row receives a non-null marker, so other rows skip recomposition
    // when play/pause toggles.
    val isCurrent = nowPlaying != null
    val isDownloaded = remember(track.sourceId, track.ratingKey, downloadedTrackKeys) {
        downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)
    }
    val progress = downloadProgress["${track.sourceId}:${track.ratingKey}"]
    val performerSummary = remember(album, track) { track.albumTrackRowPerformerSummary(album) }
    val compactPerformerSummary = remember(album, track) {
        track.albumTrackRowPerformerSummary(album, compact = true)?.takeUnless { it == performerSummary }
    }
    val palette = LocalArtworkPalette.current
    val renderMenu: @Composable () -> Unit = {
        QueueActionMenu(
            expanded = menuState.expanded,
            onDismiss = { menuState.dismiss() },
            onAction = { placement ->
                menuState.dismiss()
                onQueueTrack(track, placement)
            },
            onShowTrackInfo = { onShowTrackInfo(track) },
            isDownloaded = isDownloaded,
            onDownloadAction = {
                if (isDownloaded) onDeleteDownloadedTrack(track) else onDownloadTrack(track)
            },
            onAddToPlaylist = addTrackToPlaylistAction(track),
            onStartSimilarRadio = startSimilarRadioAction(track, album),
        )
    }

    // Editorial hairline row: number · title/duration · favourite · more. Tap plays, long-press
    // opens the menu at the press point, and ⋯ anchors the menu to the button.
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
                        onClick = { onPlayTrack(album, track) },
                        onLongClick = { menuState.openAtLastPress() },
                    )
                    .padding(start = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrackNumberSlot(
                    text = displayNumber,
                    nowPlaying = nowPlaying,
                    modifier = Modifier.widthIn(min = 24.dp),
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
                        if (performerSummary != null) {
                            BoxWithConstraints(modifier = Modifier.weight(1f, fill = false)) {
                                val style = MaterialTheme.typography.bodySmall
                                val caption = if (compactPerformerSummary != null && rememberTextMeasurer().measure(
                                        text = performerSummary,
                                        style = style,
                                        maxLines = 1,
                                        constraints = Constraints(maxWidth = constraints.maxWidth),
                                    ).hasVisualOverflow
                                ) compactPerformerSummary else performerSummary
                                Text(
                                    text = caption,
                                    color = AppColors.TextSecondary,
                                    style = style,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (track.durationMillis != null) {
                                Text(
                                    text = "·",
                                    color = AppColors.TextTertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        track.durationMillis?.let { duration ->
                            Text(
                                formatDuration(duration),
                                color = AppColors.TextTertiary,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
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

