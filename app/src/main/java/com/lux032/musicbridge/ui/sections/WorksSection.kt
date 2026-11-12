package com.lux032.musicbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.music.*
import kotlinx.coroutines.CancellationException

@Composable
internal fun WorksHomeShelf(
    rotation: Music032WorkRotation,
    loading: Boolean,
    error: String?,
    onRotate: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (Music032Work) -> Unit,
    onPlay: (Music032Work, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Strings.browseWorks, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                if (rotation.pages > 0) Text(
                    Strings.workBatch(rotation.batch, rotation.pages),
                    style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                )
            }
            if (loading) CircularProgressIndicator(Modifier.size(24.dp), color = LocalArtworkPalette.current.accent, strokeWidth = 2.dp)
            else if (error != null) BareIcon(Icons.Filled.Refresh, Strings.retry, onRetry)
            else if (rotation.pages > 1) BareIcon(Icons.Filled.Refresh, Strings.workNextBatch, onRotate)
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        key(rotation.generation, rotation.batch) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(rotation.items, key = { it.id }) { work ->
                    WorkPosterCard(work, onClick = { onOpen(work) }, onPlay = { onPlay(work, it) })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WorkPosterCard(
    work: Music032Work,
    onClick: () -> Unit,
    onPlay: ((Boolean) -> Unit)? = null,
    current: String? = null,
    small: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val width = if (small) 96.dp else 112.dp
    Column(
        // Only the artwork is rounded. Clipping the whole column cuts the leading E/O
        // off the last metadata line at its bottom corners (especially at large font sizes).
        Modifier.width(width)
            .combinedClickable(onClick = onClick, onLongClick = onPlay?.let { { menu = true } }),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AsyncAlbumArtwork(work.artworkUrl, work.title,
            Modifier.fillMaxWidth().aspectRatio(112f / 158f).clip(RoundedCornerShape(14.dp)),
            contentScale = ContentScale.Crop, thumbnailSize = 160.dp)
        Text(work.title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(current ?: work.kindLabel(), style = MaterialTheme.typography.bodySmall,
            color = if (current != null) LocalArtworkPalette.current.accent else AppColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (work.roleCounts.isNotEmpty()) Text(
            work.roleCounts.joinToString(" · ") { "${Strings.workRole(it.first)} ${it.second}" },
            style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(Strings.play) }, onClick = { menu = false; onPlay?.invoke(false) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
            DropdownMenuItem(text = { Text(Strings.shuffle) }, onClick = { menu = false; onPlay?.invoke(true) },
                leadingIcon = { Icon(Icons.Filled.Shuffle, null) })
        }
    }
}

internal fun Music032Work.kindLabel(): String =
    listOfNotNull(Strings.workType(type), year.takeIf { it > 0 }?.toString()).joinToString(" · ")

@Composable
internal fun WorkDetailSection(
    sourceId: String,
    workId: Long,
    bottomPadding: Dp,
    outputName: String,
    playbackBusy: Boolean,
    load: suspend (String, Long) -> Music032WorkDetail,
    loadSeries: suspend (String, Long) -> Music032WorkSeries?,
    onBack: () -> Unit,
    onOpen: (String, Long) -> Unit,
    onAlbum: (MusicAlbum) -> Unit,
    onPlay: (Music032Work, Boolean, Boolean) -> Unit,
    onQueue: (List<MusicTrack>, String) -> Unit,
    onTrack: (Music032Work, List<MusicTrack>, Int) -> Unit,
    safePadding: PaddingValues = PaddingValues(),
) {
    var detail by remember(sourceId, workId) { mutableStateOf<Music032WorkDetail?>(null) }
    var error by remember(sourceId, workId) { mutableStateOf<String?>(null) }
    var loading by remember(sourceId, workId) { mutableStateOf(true) }
    var attempt by remember { mutableIntStateOf(0) }
    var seriesAttempt by remember { mutableIntStateOf(0) }
    var series by remember(sourceId, workId) { mutableStateOf<Music032WorkSeries?>(null) }
    var seriesError by remember(sourceId, workId) { mutableStateOf<String?>(null) }
    var filter by rememberSaveable(sourceId, workId) { mutableStateOf("all") }
    LaunchedEffect(sourceId, workId, attempt) {
        loading = true
        error = null
        try { detail = load(sourceId, workId) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) {
            error = if (failure is Music032HttpException && failure.status == 404) Strings.workNotFound else Strings.workLoadFailed(failure.message)
        } finally { loading = false }
    }
    LaunchedEffect(sourceId, workId, detail?.work?.seriesId, seriesAttempt) {
        val id = detail?.work?.seriesId ?: return@LaunchedEffect
        seriesError = null
        try { series = loadSeries(sourceId, id) }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { seriesError = Strings.workLoadFailed(failure.message) }
    }
    val palette = rememberAnimatedArtworkPalette(detail?.work?.artworkUrl)
    val padding = LocalAppLayoutInfo.current.horizontalPadding
    CompositionLocalProvider(LocalArtworkPalette provides palette) {
        LazyColumn(
            // Paint behind the status bar as well as the content. Applying safe padding
            // outside this page exposes the now-playing palette from the app shell.
            Modifier.fillMaxSize()
                .background(Brush.verticalGradient(listOf(palette.backgroundTop, palette.backgroundBottom)))
                .padding(safePadding),
            contentPadding = PaddingValues(start = padding, end = padding, top = 8.dp, bottom = bottomPadding),
        ) {
            item("back") { BareIcon(Icons.AutoMirrored.Filled.ArrowBack, Strings.back, onBack) }
            if (loading && detail == null) item("loading") { LoadingCard(Strings.workLoading) }
            error?.let { message -> item("error") {
                EmptyStateCard(Strings.error, message, Strings.retry, { attempt++ })
            } }
            detail?.let { data ->
                val work = data.work
                val songs = data.tracks.filter { it.isSong }.distinctBy { Triple(it.track.ratingKey, it.role, it.sequence) }
                val themes = songs.filter { it.role in setOf("op", "ed", "theme") }
                val inserts = songs.filter { it.role == "insert" }
                val shown = when (filter) { "theme" -> themes; "insert" -> inserts; else -> songs }
                item("hero") {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Bottom) {
                        AsyncAlbumArtwork(work.artworkUrl, work.title, Modifier.width(108.dp).aspectRatio(108f / 152f)
                            .clip(RoundedCornerShape(14.dp)), ContentScale.Crop, thumbnailSize = 240.dp)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(work.kindLabel(), style = MaterialTheme.typography.labelLarge, color = AppColors.TextSecondary)
                            Text(work.title, style = MaterialTheme.typography.headlineMedium, color = AppColors.TextPrimary)
                            if (work.translatedTitle.isNotBlank() && work.translatedTitle != work.title) Text(
                                work.translatedTitle, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
                            Text(Strings.workStats(data.albums.size, data.tracks.uniqueStreams().size),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                item("actions") {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SplitPlayKey(Strings.playOn(outputName), { onPlay(work, false, false) }, { onPlay(work, true, false) },
                            Modifier.weight(1f), enabled = !playbackBusy && data.tracks.isNotEmpty(), isLoading = playbackBusy)
                        IconKey(Icons.Filled.PlaylistAdd, Strings.addToQueueEnd,
                            { onQueue(data.tracks.uniqueStreams(), work.title) }, enabled = data.tracks.isNotEmpty() && !playbackBusy)
                    }
                    Text(Strings.workPlayHint, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                        modifier = Modifier.padding(top = 8.dp))
                }
                item("filters") {
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(Triple("all", Strings.workSongs, songs.size), Triple("theme", Strings.workThemes, themes.size),
                            Triple("insert", Strings.workInserts, inserts.size)).forEach { (key, title, count) ->
                            LabelKey("$title $count", { filter = key }, style = if (filter == key) KeyStyle.Hi else KeyStyle.Tonal)
                        }
                    }
                }
                if (shown.isEmpty()) item("no-songs") {
                    Text(Strings.workNoSongs, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary,
                        modifier = Modifier.padding(vertical = 20.dp))
                }
                items(shown, key = { "${it.track.ratingKey}:${it.role}:${it.sequence}" }) { entry ->
                    val marker = LocalNowPlaying.current?.takeIf { it.matches(entry.track) }
                    Row(Modifier.fillMaxWidth().clickable {
                        val queue = shown.uniqueStreams()
                        onTrack(work, queue, queue.indexOfFirst { it.ratingKey == entry.track.ratingKey })
                    }.heightIn(min = 64.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val role = Strings.workRole(entry.role) + if (entry.sequence > 0) entry.sequence else ""
                        TrackNumberSlot(role, marker, Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall)
                        Column(Modifier.weight(1f)) {
                            Text(entry.track.title, style = MaterialTheme.typography.bodyLarge,
                                color = nowPlayingTitleColor(marker != null), fontWeight = if (marker != null) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(role.takeIf { marker != null }, entry.track.artistName, entry.track.albumTitle).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        entry.track.durationMillis?.let { duration ->
                            Text("${duration / 60000}:${(duration / 1000 % 60).toString().padStart(2, '0')}",
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        }
                    }
                    Hairline()
                }
                if (data.albums.isNotEmpty()) {
                    item("album-heading") { WorkSectionHeading(Strings.albums) }
                    item("albums") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(data.albums, key = { it.album.ratingKey }) { entry ->
                                Column(Modifier.width(124.dp).clickable { onAlbum(entry.album) },
                                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    AsyncAlbumArtwork(entry.album.thumbUrl, entry.album.title,
                                        Modifier.size(124.dp).clip(RoundedCornerShape(14.dp)), ContentScale.Crop, thumbnailSize = 160.dp)
                                    Text(entry.album.title, style = MaterialTheme.typography.titleSmall, color = AppColors.TextPrimary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                    Text((listOf(entry.albumRole).filter { it.isNotBlank() } + entry.trackRoles).distinct()
                                        .joinToString(" · ") { Strings.workRole(it) }, style = MaterialTheme.typography.bodySmall,
                                        color = AppColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                if (work.seriesId != null) {
                    item("series-heading") {
                        Row(Modifier.padding(top = 26.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(Strings.workSeries, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
                                Text(series?.title ?: work.seriesTitle, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                            }
                            if (series != null) LabelKey(Strings.workPlaySeries, { onPlay(work, false, true) }, icon = Icons.Filled.PlayArrow,
                                enabled = !playbackBusy)
                        }
                    }
                    seriesError?.let { message -> item("series-error") {
                        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        LabelKey(Strings.retry, { seriesAttempt++ })
                    } }
                    series?.let { group -> item("series") {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(group.members, key = { it.id }) { member ->
                                WorkPosterCard(member, onClick = { if (member.id != workId) onOpen(sourceId, member.id) }, small = true,
                                    current = if (member.id == workId) Strings.workPosition(work.seriesPosition.takeIf { it > 0 }
                                        ?: (group.members.indexOf(member) + 1)) else null)
                            }
                        }
                    } }
                }
            }
        }
    }
}

@Composable
private fun WorkSectionHeading(title: String) {
    Text(title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary,
        modifier = Modifier.padding(top = 26.dp, bottom = 12.dp))
}
