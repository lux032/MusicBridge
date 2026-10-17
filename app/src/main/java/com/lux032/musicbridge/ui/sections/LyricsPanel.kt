package com.lux032.musicbridge

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.music.LyricsTranslationMode
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.Music032LyricsCandidate
import com.lux032.musicbridge.music.Music032LyricsJson
import com.lux032.musicbridge.music.Music032TrackLyrics
import com.lux032.musicbridge.plex.PlexLyricLine
import com.lux032.musicbridge.plex.PlexLyrics
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "LyricsPanel"

/** Lyrics of the playing track plus its server translation; one instance per track. */
@Stable
internal class TrackLyricsUiState {
    var data by mutableStateOf(TrackLyrics.Empty)
    var isLoading by mutableStateOf(true)
    /** Target language to translated server lines. */
    var translation by mutableStateOf<Pair<String, List<String>>?>(null)
    var isTranslating by mutableStateOf(false)
    var translationFailed by mutableStateOf(false)
    var reloadGeneration by mutableIntStateOf(0)
    var translateGeneration by mutableIntStateOf(0)
}

@Composable
internal fun rememberTrackLyricsState(
    track: PlexTrackStream,
    repository: TrackLyricsRepository,
    settings: LyricsTranslationSettings,
): TrackLyricsUiState {
    val state = remember(track.sourceId, track.ratingKey) { TrackLyricsUiState() }
    LaunchedEffect(track.sourceId, track.ratingKey, track.lyricUrl, state.reloadGeneration) {
        state.isLoading = true
        state.data = repository.load(track)
        state.translation = null
        state.translationFailed = false
        state.isLoading = false
    }
    val language = settings.targetLanguage
    val wantsTranslation = settings.enabled && !state.isLoading && state.data.canTranslate(language)
    LaunchedEffect(state.data, wantsTranslation, language, state.translateGeneration) {
        if (!wantsTranslation || state.translation?.first == language) return@LaunchedEffect
        state.isTranslating = true
        state.translationFailed = false
        try {
            state.translation = language to repository.translate(track, language)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.d(TAG, "Translation failed for ${track.ratingKey}", error)
            state.translationFailed = true
        } finally {
            state.isTranslating = false
        }
    }
    return state
}

/** Lines to show: the server lyrics with their translation when translation is on and ready. */
internal fun TrackLyricsUiState.shownLyrics(settings: LyricsTranslationSettings): PlexLyrics {
    val server = data.server
    val translated = translation?.takeIf { it.first == settings.targetLanguage }?.second
    if (!settings.enabled || server == null || translated == null) return data.lyrics
    return server.withTranslation(translated)
}

/** Lyrics page body: source / translation header, the lines, and the options sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsPanel(
    track: PlexTrackStream,
    state: TrackLyricsUiState,
    settings: LyricsTranslationSettings,
    onSettingsChange: (LyricsTranslationSettings) -> Unit,
    repository: TrackLyricsRepository,
    positionMillis: Long,
    listState: LazyListState,
    palette: ArtworkPalette,
    onSeekLine: (PlexLyricLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isOptionsVisible by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    val data = state.data
    val translatable = data.canTranslate(settings.targetLanguage)
    val hasOptions = data.canSearch || data.server != null
    val translationFailed = settings.enabled && state.translationFailed

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lyricsHeaderText(state, settings),
                color = if (translationFailed) palette.accent else AppColors.TextTertiary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = translationFailed) { state.translateGeneration++ }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
            if (translatable) {
                IconButton(onClick = { onSettingsChange(settings.copy(enabled = !settings.enabled)) }) {
                    Icon(
                        Icons.Filled.Translate,
                        contentDescription = Strings.lyricsTranslate,
                        tint = if (settings.enabled) palette.accent else AppColors.TextSecondary,
                    )
                }
            }
            if (hasOptions) {
                IconButton(onClick = { isOptionsVisible = true }) {
                    Icon(Icons.Filled.MoreHoriz, contentDescription = Strings.lyricsOptions, tint = AppColors.TextSecondary)
                }
            }
        }
        LyricsView(
            lines = state.shownLyrics(settings).lines,
            isSynced = data.lyrics.isSynced,
            translationOnly = settings.enabled && settings.mode == LyricsTranslationMode.TranslationOnly,
            positionMillis = positionMillis,
            listState = listState,
            isLoading = state.isLoading,
            palette = palette,
            onSeekLine = onSeekLine,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }

    if (isOptionsVisible) {
        val server = data.server
        ModalBottomSheet(
            onDismissRequest = { isOptionsVisible = false },
            containerColor = palette.backgroundBottom,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(Strings.lyricsOptions, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold)
                if (server != null && (data.serverTranslation || server.source == Music032TrackLyrics.SOURCE_NETEASE)) {
                    LyricsTranslationOptions(settings = settings, serverTranslation = data.serverTranslation, onSettingsChange = onSettingsChange)
                } else if (server != null) {
                    Text(Strings.lyricsTranslationUnavailable, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                }
                if (data.canSearch) {
                    MoreSheetAction(Icons.Filled.Search, Strings.lyricsSearch) {
                        isOptionsVisible = false
                        isSearchVisible = true
                    }
                }
                if (data.canSearch && server?.isOnline == true) {
                    MoreSheetAction(Icons.Filled.DeleteOutline, Strings.lyricsClear) {
                        isOptionsVisible = false
                        scope.launch {
                            try {
                                repository.clear(track)
                                state.reloadGeneration++
                            } catch (cancel: CancellationException) {
                                throw cancel
                            } catch (error: Exception) {
                                Log.w(TAG, "Clearing lyrics failed", error)
                                Toast.makeText(context, Strings.lyricsUpdateFailed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    Text(Strings.lyricsClearDesc, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (isSearchVisible) {
        LyricsSearchDialog(
            track = track,
            repository = repository,
            palette = palette,
            onDismiss = { isSearchVisible = false },
            onPicked = {
                isSearchVisible = false
                state.reloadGeneration++
            },
        )
    }
}

private fun lyricsHeaderText(state: TrackLyricsUiState, settings: LyricsTranslationSettings): String {
    if (settings.enabled && state.isTranslating) return Strings.lyricsTranslating
    if (settings.enabled && state.translationFailed) return Strings.lyricsTranslationFailed
    val server = state.data.server ?: return ""
    if (!server.isOnline) return Strings.lyricsSourceLocal
    return listOfNotNull(
        server.sourceName.ifBlank { Music032LyricsJson.sourceLabel(server.source) },
        Strings.lyricsSourceManual.takeIf { server.manual },
        listOfNotNull(server.remoteTitle, server.remoteArtist).joinToString(" - ").ifBlank { null },
    ).joinToString(" · ")
}

@Composable
private fun LyricsTranslationOptions(
    settings: LyricsTranslationSettings,
    serverTranslation: Boolean,
    onSettingsChange: (LyricsTranslationSettings) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(Strings.lyricsShowTranslation, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
            Text(Strings.lyricsShowTranslationDesc, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = settings.enabled, onCheckedChange = { onSettingsChange(settings.copy(enabled = it)) })
    }
    Text(Strings.lyricsTranslationMode, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
    LyricsTranslationMode.entries.forEach { mode ->
        ChoiceRow(
            selected = settings.mode == mode,
            onClick = { onSettingsChange(settings.copy(mode = mode)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (mode == LyricsTranslationMode.Bilingual) Strings.lyricsModeBilingual else Strings.lyricsModeTranslationOnly,
                color = AppColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
        }
    }
    // Without server AI only NetEase's Simplified Chinese translation exists: no language choice.
    if (serverTranslation) {
        Text(Strings.lyricsTargetLanguage, color = AppColors.TextSecondary, fontWeight = FontWeight.SemiBold)
        LyricsTranslationSettings.LANGUAGES.forEach { code ->
            ChoiceRow(
                selected = settings.targetLanguage == code,
                onClick = { onSettingsChange(settings.copy(targetLanguage = code)) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(Strings.lyricsLanguageName(code), color = AppColors.TextPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LyricsSearchDialog(
    track: PlexTrackStream,
    repository: TrackLyricsRepository,
    palette: ArtworkPalette,
    onDismiss: () -> Unit,
    onPicked: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(track.title) }
    var artist by remember { mutableStateOf(track.artists.firstOrNull()?.name ?: track.artistName.orEmpty()) }
    var results by remember { mutableStateOf<List<Music032LyricsCandidate>?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchGeneration by remember { mutableIntStateOf(0) }

    LaunchedEffect(searchGeneration) {
        if (name.isBlank() && artist.isBlank()) return@LaunchedEffect
        isBusy = true
        error = null
        try {
            results = repository.search(track, name, artist)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "Lyrics search failed", e)
            error = Strings.lyricsSearchFailed
        } finally {
            isBusy = false
        }
    }

    fun pick(candidate: Music032LyricsCandidate) {
        if (isBusy) return
        isBusy = true
        error = null
        scope.launch {
            try {
                repository.bind(track, candidate)
                onPicked()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, "Binding lyrics failed", e)
                error = Strings.lyricsUpdateFailed
            } finally {
                isBusy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(enabled = !isBusy, onClick = { searchGeneration++ }) { Text(Strings.lyricsSearchAction) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.cancel) } },
        title = { Text(Strings.lyricsSearch, color = AppColors.TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(Strings.lyricsSearchDesc, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(Strings.lyricsSearchTitle) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text(Strings.lyricsSearchArtist) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 320.dp), contentAlignment = Alignment.Center) {
                    val list = results
                    val message = error
                    when {
                        isBusy -> CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        message != null -> Text(message, color = AppColors.TextSecondary)
                        list == null -> Unit
                        list.isEmpty() -> Text(Strings.lyricsSearchEmpty, color = AppColors.TextSecondary)
                        else -> LazyColumn(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            items(list, key = { "${it.source}:${it.id}" }) { candidate ->
                                LyricsCandidateRow(candidate) { pick(candidate) }
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun LyricsCandidateRow(candidate: Music032LyricsCandidate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(candidate.name, color = AppColors.TextPrimary, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val details = listOfNotNull(
            candidate.artist.ifBlank { null },
            Music032LyricsJson.sourceLabel(candidate.source),
            when (candidate.synced) {
                true -> Strings.lyricsSynced
                false -> Strings.lyricsPlain
                null -> null
            },
            candidate.durationMillis?.let(::formatDuration),
        ).joinToString(" · ")
        Text(details, color = AppColors.TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun LyricsView(
    lines: List<PlexLyricLine>,
    isSynced: Boolean,
    translationOnly: Boolean,
    positionMillis: Long,
    listState: LazyListState,
    isLoading: Boolean,
    palette: ArtworkPalette,
    onSeekLine: (PlexLyricLine) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollowSuspended by remember { mutableStateOf(false) }
    var suspensionGeneration by remember { mutableIntStateOf(0) }
    val activeIndex = if (isSynced) lines.indexOfLast { it.timeMillis <= positionMillis }.coerceAtLeast(0) else -1

    LaunchedEffect(isUserDragging) {
        if (isUserDragging) {
            autoFollowSuspended = true
            suspensionGeneration++
        } else if (autoFollowSuspended) {
            val generation = ++suspensionGeneration
            snapshotFlow { listState.isScrollInProgress }
                .filter { inProgress -> !inProgress }
                .first()
            delay(3_000)
            if (generation == suspensionGeneration) autoFollowSuspended = false
        }
    }
    LaunchedEffect(activeIndex, isSynced, autoFollowSuspended) {
        if (isSynced && activeIndex >= 0 && !autoFollowSuspended) {
            listState.animateScrollToItem(activeIndex, scrollOffset = -120)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 160.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (lines.isEmpty()) {
            item(key = "empty-lyrics") {
                Box(modifier = Modifier.fillParentMaxHeight().fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Crossfade(targetState = isLoading, label = "lyrics_loading") { loading ->
                        if (loading) {
                            CircularProgressIndicator(color = palette.accent, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                        } else {
                            Text(Strings.noLyrics, color = AppColors.TextSecondary, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        itemsIndexed(lines) { index, line ->
            val isActive = isSynced && index == activeIndex
            val translation = line.translation
            // Translation-only replaces the line; lines without a translation keep the original.
            val primary = if (translationOnly && translation != null) translation else line.text
            val secondary = translation.takeIf { !translationOnly }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = isSynced) {
                        autoFollowSuspended = false
                        suspensionGeneration++
                        onSeekLine(line)
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = primary,
                    modifier = Modifier.fillMaxWidth(),
                    color = if (isActive) palette.accent else AppColors.TextSecondary.copy(alpha = 0.72f),
                    style = if (isActive) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Center,
                )
                if (secondary != null) {
                    Text(
                        text = secondary,
                        modifier = Modifier.fillMaxWidth(),
                        color = if (isActive) palette.accent.copy(alpha = 0.85f) else AppColors.TextTertiary.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
