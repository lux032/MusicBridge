package com.lux032.musicbridge

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.music.LyricsTranslationMode
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.Music032LyricsCandidate
import com.lux032.musicbridge.music.Music032LyricsJson
import com.lux032.musicbridge.music.Music032TrackLyrics
import com.lux032.musicbridge.lyrics.LyricLine
import com.lux032.musicbridge.lyrics.ParsedLyrics
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.TrackCredit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "LyricsPanel"
private const val LYRIC_ANCHOR_VIEWPORT_FRACTION = 0.36f
private const val LYRIC_CLOCK_TICK_MILLIS = 60L
private const val LYRIC_SCROLL_DURATION_MILLIS = 480
private const val LYRIC_SCALE_DURATION_MILLIS = 380
private const val LYRIC_FADE_DURATION_MILLIS = 340
private val LyricScrollEasing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
private val LyricLineEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

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
    track: MusicTrack,
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
internal fun TrackLyricsUiState.shownLyrics(settings: LyricsTranslationSettings): ParsedLyrics {
    val server = data.server
    val translated = translation?.takeIf { it.first == settings.targetLanguage }?.second
    if (!settings.enabled || server == null || translated == null) return data.lyrics
    return server.withTranslation(translated)
}

/** Lyrics page body: source / translation header, the lines, and the options sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LyricsPanel(
    track: MusicTrack,
    state: TrackLyricsUiState,
    settings: LyricsTranslationSettings,
    onSettingsChange: (LyricsTranslationSettings) -> Unit,
    repository: TrackLyricsRepository,
    positionMillis: Long,
    listState: LazyListState,
    palette: ArtworkPalette,
    onSeekLine: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    durationMillis: Long? = null,
    readLivePositionMillis: (() -> Long?)? = null,
    loadCredits: suspend (MusicTrack) -> List<TrackCredit> = { emptyList() },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isOptionsVisible by remember { mutableStateOf(false) }
    var isSearchVisible by remember { mutableStateOf(false) }
    val data = state.data
    val translatable = data.canTranslate(settings.targetLanguage)
    val hasOptions = data.canSearch || data.server != null
    val translationFailed = settings.enabled && state.translationFailed
    // Who wrote the song, above the first line; the full list lives in the More sheet.
    val credits by produceState(emptyList<TrackCredit>(), track.sourceId, track.ratingKey) {
        value = emptyList()
        value = loadCredits(track).filter { it.role in LyricsCreditRoles }
    }
    val shownLyrics = remember(state.data, state.translation, settings.enabled, settings.targetLanguage) {
        state.shownLyrics(settings)
    }
    val lines = shownLyrics.lines
    val visibleCredits = remember(lines, credits) {
        if (lyricsCarryCredits(lines)) emptyList() else credits
    }

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
            trackKey = "${track.sourceId}:${track.ratingKey}",
            lines = lines,
            isSynced = data.lyrics.isSynced,
            translationOnly = settings.enabled && settings.mode == LyricsTranslationMode.TranslationOnly,
            positionMillis = positionMillis,
            isPlaying = isPlaying,
            durationMillis = durationMillis,
            readLivePositionMillis = readLivePositionMillis,
            listState = listState,
            isLoading = state.isLoading,
            palette = palette,
            onSeekLine = onSeekLine,
            modifier = Modifier.fillMaxWidth().weight(1f),
            credits = visibleCredits,
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
    track: MusicTrack,
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

internal data class LyricClockAnchor(
    val positionMillis: Long,
    val elapsedRealtimeMillis: Long,
    val lastReportedMillis: Long,
)

internal fun findActiveLyricIndex(lines: List<LyricLine>, positionMillis: Long): Int {
    if (lines.isEmpty()) return -1
    return lines.indexOfLast { it.timeMillis <= positionMillis }.coerceAtLeast(0)
}

internal fun computeLyricScrollDeltaPx(
    itemOffset: Int,
    itemSize: Int,
    viewportStartOffset: Int,
    viewportHeight: Int,
    anchorFraction: Float = LYRIC_ANCHOR_VIEWPORT_FRACTION,
): Float {
    if (viewportHeight <= 0) return 0f
    val currentCenter = itemOffset + itemSize / 2f
    val targetCenter = viewportStartOffset + viewportHeight * anchorFraction
    return currentCenter - targetCenter
}

internal fun computeLyricTargetScrollOffset(
    viewportStartOffset: Int,
    viewportHeight: Int,
    estimatedItemHeightPx: Int,
    anchorFraction: Float = LYRIC_ANCHOR_VIEWPORT_FRACTION,
): Int {
    if (viewportHeight <= 0) return 0
    val targetTop = viewportStartOffset + (viewportHeight * anchorFraction - estimatedItemHeightPx / 2f)
    return (-targetTop).roundToInt()
}

internal fun reconcileLyricClockAnchor(
    previous: LyricClockAnchor?,
    reportedPositionMillis: Long,
    nowElapsedRealtimeMillis: Long,
    isPlaying: Boolean,
    durationMillis: Long? = null,
): LyricClockAnchor {
    val clampedReported = clampLyricPosition(reportedPositionMillis, durationMillis)
    if (previous == null || !isPlaying) {
        return LyricClockAnchor(
            positionMillis = clampedReported,
            elapsedRealtimeMillis = nowElapsedRealtimeMillis,
            lastReportedMillis = clampedReported,
        )
    }
    val estimatedNow = interpolateLyricPositionMillis(
        anchor = previous,
        nowElapsedRealtimeMillis = nowElapsedRealtimeMillis,
        isPlaying = true,
        durationMillis = durationMillis,
    )
    val reportedDelta = clampedReported - previous.lastReportedMillis
    val looksLikeSeek = reportedDelta < 0L || reportedDelta > 2_500L
    if (looksLikeSeek) {
        return LyricClockAnchor(
            positionMillis = clampedReported,
            elapsedRealtimeMillis = nowElapsedRealtimeMillis,
            lastReportedMillis = clampedReported,
        )
    }
    val behindEstimate = estimatedNow - clampedReported
    return if (behindEstimate in 1L..1_500L) {
        LyricClockAnchor(
            positionMillis = estimatedNow,
            elapsedRealtimeMillis = nowElapsedRealtimeMillis,
            lastReportedMillis = clampedReported,
        )
    } else {
        LyricClockAnchor(
            positionMillis = clampedReported,
            elapsedRealtimeMillis = nowElapsedRealtimeMillis,
            lastReportedMillis = clampedReported,
        )
    }
}

internal fun interpolateLyricPositionMillis(
    anchor: LyricClockAnchor,
    nowElapsedRealtimeMillis: Long,
    isPlaying: Boolean,
    durationMillis: Long? = null,
): Long {
    if (!isPlaying) return clampLyricPosition(anchor.positionMillis, durationMillis)
    val elapsed = (nowElapsedRealtimeMillis - anchor.elapsedRealtimeMillis).coerceAtLeast(0L)
    return clampLyricPosition(anchor.positionMillis + elapsed, durationMillis)
}

private fun clampLyricPosition(positionMillis: Long, durationMillis: Long?): Long {
    val nonNegative = positionMillis.coerceAtLeast(0L)
    return if (durationMillis != null && durationMillis > 0L) {
        nonNegative.coerceAtMost(durationMillis)
    } else {
        nonNegative
    }
}

@Composable
internal fun LyricsView(
    lines: List<LyricLine>,
    isSynced: Boolean,
    translationOnly: Boolean,
    positionMillis: Long,
    listState: LazyListState,
    isLoading: Boolean,
    palette: ArtworkPalette,
    onSeekLine: (LyricLine) -> Unit,
    modifier: Modifier = Modifier,
    trackKey: String = "",
    isPlaying: Boolean = false,
    durationMillis: Long? = null,
    readLivePositionMillis: (() -> Long?)? = null,
    credits: List<TrackCredit> = emptyList(),
) {
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    var autoFollowSuspended by remember { mutableStateOf(false) }
    var suspensionGeneration by remember { mutableIntStateOf(0) }
    val currentReadLivePosition by rememberUpdatedState(readLivePositionMillis)
    var clockAnchor by remember(trackKey) { mutableStateOf<LyricClockAnchor?>(null) }
    val livePositionMillis = remember(trackKey) { mutableLongStateOf(positionMillis) }

    LaunchedEffect(trackKey, positionMillis, isPlaying, durationMillis) {
        val now = SystemClock.elapsedRealtime()
        val liveNow = if (isPlaying) currentReadLivePosition?.invoke() else null
        if (liveNow != null) {
            clockAnchor = LyricClockAnchor(liveNow, now, positionMillis)
            livePositionMillis.longValue = liveNow
        } else {
            val reconciled = reconcileLyricClockAnchor(
                previous = clockAnchor,
                reportedPositionMillis = positionMillis,
                nowElapsedRealtimeMillis = now,
                isPlaying = isPlaying,
                durationMillis = durationMillis,
            )
            clockAnchor = reconciled
            livePositionMillis.longValue = reconciled.positionMillis
        }
    }

    LaunchedEffect(trackKey, isSynced, isPlaying, lines.isNotEmpty(), durationMillis) {
        if (!isSynced || !isPlaying || lines.isEmpty()) return@LaunchedEffect
        while (isActive) {
            val liveNow = currentReadLivePosition?.invoke()
            if (liveNow != null) {
                livePositionMillis.longValue = liveNow
            } else {
                clockAnchor?.let { anchor ->
                    livePositionMillis.longValue = interpolateLyricPositionMillis(
                        anchor = anchor,
                        nowElapsedRealtimeMillis = SystemClock.elapsedRealtime(),
                        isPlaying = true,
                        durationMillis = durationMillis,
                    )
                }
            }
            delay(LYRIC_CLOCK_TICK_MILLIS)
        }
    }

    // Only invalidate downstream readers when the highlighted line index actually changes.
    val activeIndex by remember(lines, isSynced) {
        derivedStateOf {
            if (isSynced) findActiveLyricIndex(lines, livePositionMillis.longValue) else -1
        }
    }

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

    val hasCredits = credits.isNotEmpty() && lines.isNotEmpty()
    val headerItemCount = if (hasCredits) 1 else 0
    val fallbackItemHeightPx = with(LocalDensity.current) { 56.dp.roundToPx() }
    var hasPositioned by remember(trackKey, lines) { mutableStateOf(false) }

    LaunchedEffect(trackKey, isSynced) {
        if (!isSynced) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(activeIndex, isSynced, autoFollowSuspended, trackKey, lines, headerItemCount) {
        if (!isSynced || activeIndex < 0 || autoFollowSuspended) return@LaunchedEffect
        if (listState.layoutInfo.viewportSize.height <= 0) {
            snapshotFlow { listState.layoutInfo.viewportSize.height }
                .filter { it > 0 }
                .first()
        }
        val layoutInfo = listState.layoutInfo
        val viewportHeight = layoutInfo.viewportSize.height
        val viewportStart = layoutInfo.viewportStartOffset
        val targetItemIndex = activeIndex + headerItemCount
        val visibleTarget = layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetItemIndex }
        val estimatedItemHeight = visibleTarget?.size
            ?: layoutInfo.visibleItemsInfo.takeIf { it.isNotEmpty() }?.let { items ->
                items.sumOf { it.size } / items.size
            }
            ?: fallbackItemHeightPx

        if (!hasPositioned) {
            hasPositioned = true
            val scrollOffset = computeLyricTargetScrollOffset(
                viewportStartOffset = viewportStart,
                viewportHeight = viewportHeight,
                estimatedItemHeightPx = estimatedItemHeight,
            )
            listState.scrollToItem(targetItemIndex, scrollOffset)
        } else if (visibleTarget != null) {
            val delta = computeLyricScrollDeltaPx(
                itemOffset = visibleTarget.offset,
                itemSize = visibleTarget.size,
                viewportStartOffset = viewportStart,
                viewportHeight = viewportHeight,
            )
            if (abs(delta) > 1f) {
                listState.animateScrollBy(
                    value = delta,
                    animationSpec = tween(
                        durationMillis = LYRIC_SCROLL_DURATION_MILLIS,
                        easing = LyricScrollEasing,
                    ),
                )
            }
        } else {
            val scrollOffset = computeLyricTargetScrollOffset(
                viewportStartOffset = viewportStart,
                viewportHeight = viewportHeight,
                estimatedItemHeightPx = estimatedItemHeight,
            )
            listState.animateScrollToItem(targetItemIndex, scrollOffset)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        // Extra bottom padding lets the final lines glide cleanly to the upper-middle anchor.
        contentPadding = PaddingValues(
            top = if (!hasCredits) 160.dp else 96.dp,
            bottom = 240.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(if (isSynced) 10.dp else 16.dp),
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
        if (hasCredits) {
            item(key = "lyrics-credits", contentType = "lyrics-credits") {
                LyricsCredits(credits)
            }
        }
        itemsIndexed(
            items = lines,
            key = { index, line -> "$index:${line.timeMillis}" },
            contentType = { _, _ -> "lyric-line" },
        ) { index, line ->
            val isActive = isSynced && index == activeIndex
            val translation = line.translation
            // Translation-only replaces the line; lines without a translation keep the original.
            val primary = if (translationOnly && translation != null) translation else line.text
            val secondary = translation.takeIf { !translationOnly }
            val onRowClick = remember(line, onSeekLine) {
                {
                    autoFollowSuspended = false
                    suspensionGeneration++
                    val now = SystemClock.elapsedRealtime()
                    clockAnchor = LyricClockAnchor(line.timeMillis, now, line.timeMillis)
                    livePositionMillis.longValue = line.timeMillis
                    onSeekLine(line)
                }
            }
            LyricLineRow(
                primary = primary,
                secondary = secondary,
                isActive = isActive,
                isSynced = isSynced,
                accentColor = palette.accent,
                onClick = onRowClick,
            )
        }
    }
}

/**
 * Single lyric row with constant layout metrics across active/inactive states so transitioning
 * between lines never triggers text re-wrapping or height jumps that fight the scroll animation.
 */
@Composable
private fun LyricLineRow(
    primary: String,
    secondary: String?,
    isActive: Boolean,
    isSynced: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (!isSynced || isActive) 1f else 0.80f,
        animationSpec = tween(durationMillis = LYRIC_SCALE_DURATION_MILLIS, easing = LyricLineEasing),
        label = "lyric_scale",
    )
    val layerAlpha by animateFloatAsState(
        targetValue = if (!isSynced || isActive) 1f else 0.68f,
        animationSpec = tween(durationMillis = LYRIC_FADE_DURATION_MILLIS, easing = LyricLineEasing),
        label = "lyric_alpha",
    )
    val primaryColor by animateColorAsState(
        targetValue = when {
            !isSynced -> AppColors.TextPrimary.copy(alpha = 0.9f)
            isActive -> accentColor
            else -> AppColors.TextSecondary
        },
        animationSpec = tween(durationMillis = LYRIC_FADE_DURATION_MILLIS, easing = LyricLineEasing),
        label = "lyric_primary_color",
    )
    val secondaryColor by animateColorAsState(
        targetValue = when {
            !isSynced -> AppColors.TextSecondary
            isActive -> accentColor.copy(alpha = 0.85f)
            else -> AppColors.TextTertiary
        },
        animationSpec = tween(durationMillis = LYRIC_FADE_DURATION_MILLIS, easing = LyricLineEasing),
        label = "lyric_secondary_color",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = isSynced, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = layerAlpha
                transformOrigin = TransformOrigin.Center
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = primary,
            modifier = Modifier.fillMaxWidth(),
            color = primaryColor,
            style = if (isSynced) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            fontWeight = if (isSynced) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        if (secondary != null) {
            Text(
                text = secondary,
                modifier = Modifier.fillMaxWidth(),
                color = secondaryColor,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Roles shown above the lyrics; the producer only appears in the More sheet. */
private val LyricsCreditRoles = setOf("lyricist", "composer", "arranger")

private val LyricsCreditLine = Regex("""^\s*(作词|作詞|作曲|编曲|編曲)\s*[:：]""")

/** Online lyrics (NetEase) often open with their own "作词 : …" lines; don't say it twice. */
internal fun lyricsCarryCredits(lines: List<LyricLine>): Boolean =
    lines.take(6).any { LyricsCreditLine.containsMatchIn(it.text) }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LyricsCredits(credits: List<TrackCredit>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        credits.forEach { credit ->
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = AppColors.TextTertiary)) { append(Strings.creditRole(credit.role)) }
                    append("  ")
                    append(credit.artists.joinToString(" · ") { it.name })
                },
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
        }
    }
}
