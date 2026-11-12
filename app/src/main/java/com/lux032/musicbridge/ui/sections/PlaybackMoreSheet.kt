package com.lux032.musicbridge

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.Music032TrackWork
import com.lux032.musicbridge.music.Music032Work
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.TrackCredit
import kotlinx.coroutines.CancellationException

/** Option A: the works are direct destinations in More, never a second sheet or a badge. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackMoreSheet(
    track: MusicTrack,
    artworkUrl: String?,
    artistAlbum: String,
    technicalDetails: String,
    palette: ArtworkPalette,
    loadWorks: suspend (MusicTrack) -> List<Music032TrackWork>,
    loadWorkInfo: suspend (String, Long) -> Music032Work?,
    loadCredits: suspend (MusicTrack) -> List<TrackCredit>,
    onOpenWork: (String, Long) -> Unit,
    onOpenArtist: (ArtistRef) -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.backgroundBottom) {
        CompositionLocalProvider(LocalArtworkPalette provides palette) {
            PlaybackMoreContent(
                track, artworkUrl, artistAlbum, technicalDetails, loadWorks, loadWorkInfo,
                onOpenWork = { source, id -> onDismiss(); onOpenWork(source, id) },
                onAddToPlaylist = onAddToPlaylist?.let { action -> { onDismiss(); action() } },
                onDismiss = onDismiss,
                loadCredits = loadCredits,
                onOpenArtist = { ref -> onDismiss(); onOpenArtist(ref) },
            )
        }
    }
}

@Composable
internal fun PlaybackMoreContent(
    track: MusicTrack,
    artworkUrl: String?,
    artistAlbum: String,
    technicalDetails: String,
    loadWorks: suspend (MusicTrack) -> List<Music032TrackWork>,
    loadWorkInfo: suspend (String, Long) -> Music032Work?,
    onOpenWork: (String, Long) -> Unit,
    onAddToPlaylist: (() -> Unit)?,
    onDismiss: () -> Unit,
    loadCredits: suspend (MusicTrack) -> List<TrackCredit> = { emptyList() },
    onOpenArtist: (ArtistRef) -> Unit = {},
) {
    // Mounted only when More is open, and restarted on track/source changes. Position ticks
    // do not fetch again; a cancelled result cannot become another song's association.
    var links by remember(track.sourceId, track.ratingKey) { mutableStateOf(emptyList<Music032TrackWork>()) }
    var loading by remember(track.sourceId, track.ratingKey) { mutableStateOf(true) }
    var error by remember(track.sourceId, track.ratingKey) { mutableStateOf<String?>(null) }
    var retry by remember(track.sourceId, track.ratingKey) { mutableIntStateOf(0) }
    var showDetails by rememberSaveable(track.sourceId, track.ratingKey) { mutableStateOf(false) }
    LaunchedEffect(track.sourceId, track.ratingKey, retry) {
        loading = true
        error = null
        try { links = loadWorks(track).distinctBy { it.work.id } }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: Exception) { error = Strings.workLoadFailed(failure.message) }
        finally { loading = false }
    }
    // Credits load beside the works and never show a spinner or an error: no data, no group.
    var credits by remember(track.sourceId, track.ratingKey) { mutableStateOf(emptyList<TrackCredit>()) }
    LaunchedEffect(track.sourceId, track.ratingKey) { credits = loadCredits(track) }
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item("header") {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(Strings.more, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f))
                BareIcon(Icons.Filled.Close, Strings.close, onDismiss)
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                AsyncAlbumArtwork(artworkUrl, track.albumTitle ?: track.title,
                    Modifier.size(44.dp).clip(RoundedCornerShape(9.dp)), ContentScale.Crop, thumbnailSize = 64.dp)
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artistAlbum, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Hairline()
        }
        if (loading) item("works-loading") {
            Row(Modifier.padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalArtworkPalette.current.accent)
                Text(Strings.workLoading, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
            }
        }
        error?.let { message -> item("works-error") {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp))
            LabelKey(Strings.retry, { retry++ }, modifier = Modifier.padding(vertical = 8.dp))
        } }
        if (links.isNotEmpty()) {
            item("works-heading") {
                Text(Strings.workAssociations(links.size), style = MaterialTheme.typography.labelMedium,
                    color = AppColors.TextSecondary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            }
            items(links, key = { "work:${it.work.id}" }) { link ->
                PlaybackWorkRow(track.sourceId, link, loadWorkInfo) { onOpenWork(track.sourceId, link.work.id) }
                Hairline()
            }
        }
        onAddToPlaylist?.let { add -> item("add-to-playlist") {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 58.dp).clickable(onClick = add),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LibraryAdd, null, tint = AppColors.TextSecondary, modifier = Modifier.size(22.dp))
                Text(Strings.addToPlaylist, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary,
                    modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
            }
        } }
        if (credits.isNotEmpty()) item("credits") {
            // Works and the header already end on a hairline; only the playlist row needs one.
            if (onAddToPlaylist != null) Hairline(Modifier.padding(top = 4.dp))
            Text(Strings.trackCredits, style = MaterialTheme.typography.labelMedium,
                color = AppColors.TextSecondary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            credits.forEach { credit -> PlaybackCreditRow(credit, onOpenArtist) }
        }
        item("details") {
            Hairline(Modifier.padding(top = if (credits.isEmpty()) 16.dp else 8.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { showDetails = !showDetails },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.Info, null, tint = AppColors.TextSecondary, modifier = Modifier.size(16.dp))
                Text(Strings.trackDetails, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                    modifier = Modifier.weight(1f))
                Icon(if (showDetails) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null,
                    tint = AppColors.TextSecondary, modifier = Modifier.size(18.dp))
            }
            if (showDetails) Text(technicalDetails, style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

@Composable
private fun PlaybackWorkRow(
    sourceId: String,
    link: Music032TrackWork,
    loadInfo: suspend (String, Long) -> Music032Work?,
    onClick: () -> Unit,
) {
    // TrackWorks omits year/poster; enrich visible rows without blocking navigation.
    var info by remember(sourceId, link.work.id) { mutableStateOf(link.work) }
    LaunchedEffect(sourceId, link.work.id) {
        try { loadInfo(sourceId, link.work.id)?.let { info = it } }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* The original name and destination remain usable. */ }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncAlbumArtwork(info.artworkUrl, info.title,
            Modifier.size(38.dp, 54.dp).clip(RoundedCornerShape(7.dp)), ContentScale.Crop, thumbnailSize = 64.dp)
        Column(Modifier.weight(1f)) {
            Text(info.title, style = MaterialTheme.typography.bodyLarge, color = AppColors.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("${info.kindLabel()} · ${Strings.workRole(link.role)}", style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary, modifier = Modifier.padding(top = 3.dp))
        }
        Icon(Icons.Filled.ChevronRight, null, tint = AppColors.TextSecondary, modifier = Modifier.size(20.dp))
    }
}

/** One role and its people. Names with an artist id are accent links; the rest are plain text. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaybackCreditRow(credit: TrackCredit, onOpenArtist: (ArtistRef) -> Unit) {
    val nameHeight = Modifier.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically)
    Row(Modifier.fillMaxWidth()) {
        Text(Strings.creditRole(credit.role), style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary, modifier = Modifier.width(64.dp).then(nameHeight))
        FlowRow(Modifier.weight(1f)) {
            credit.artists.forEachIndexed { index, artist ->
                Row {
                    if (index > 0) Text(" · ", style = MaterialTheme.typography.bodyLarge,
                        color = AppColors.TextTertiary, modifier = nameHeight)
                    if (artist.id.isBlank()) {
                        Text(artist.name, style = MaterialTheme.typography.bodyLarge,
                            color = AppColors.TextPrimary, modifier = nameHeight)
                    } else {
                        Text(artist.name, style = MaterialTheme.typography.bodyLarge,
                            color = LocalArtworkPalette.current.accent, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .clickable { onOpenArtist(artist) }.then(nameHeight))
                    }
                }
            }
        }
    }
}
