package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.isFavorite
import com.lux032.musicbridge.storage.DownloadTaskItem
import com.lux032.musicbridge.storage.DownloadTaskStatus
import com.lux032.musicbridge.storage.containsDownloadedTrack
import com.lux032.musicbridge.storage.downloadedTrackKey

internal data class PlaylistFilterState(
    val query: String = "",
    val downloadedOnly: Boolean = false,
    val favoritedOnly: Boolean = false,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || downloadedOnly || favoritedOnly
}

internal data class FilteredPlaylistTrack(
    val track: MusicTrack,
    val originalIndex: Int,
)

internal enum class PlaylistDownloadPhase {
    None,
    Partial,
    Downloading,
    Paused,
    AllDone,
}

internal data class PlaylistDownloadSummary(
    val total: Int,
    val downloadedCount: Int,
    val activeTaskKeys: List<String>,
    val pausedTaskKeys: List<String>,
    val failedTaskKeys: List<String>,
    val fraction: Float,
    val phase: PlaylistDownloadPhase,
) {
    val remainingCount: Int
        get() = (total - downloadedCount).coerceAtLeast(0)

    val allPendingTaskKeys: List<String>
        get() = buildList {
            addAll(activeTaskKeys)
            addAll(pausedTaskKeys)
            addAll(failedTaskKeys)
        }
}

internal fun tokenizePlaylistQuery(query: String): List<String> =
    query.trim()
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

internal fun filterPlaylistTracks(
    tracks: List<MusicTrack>,
    filter: PlaylistFilterState,
    downloadedTrackKeys: Set<String>,
): List<FilteredPlaylistTrack> {
    val tokens = tokenizePlaylistQuery(filter.query).map { it.lowercase() }
    return buildList(tracks.size) {
        tracks.forEachIndexed { index, track ->
            if (filter.downloadedOnly && !downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)) {
                return@forEachIndexed
            }
            if (filter.favoritedOnly && !track.isFavorite) {
                return@forEachIndexed
            }
            if (tokens.isNotEmpty()) {
                val haystack = buildString {
                    append(track.title)
                    track.artistName?.takeIf { it.isNotBlank() }?.let {
                        append(' ')
                        append(it)
                    }
                    track.albumTitle?.takeIf { it.isNotBlank() }?.let {
                        append(' ')
                        append(it)
                    }
                }.lowercase()
                if (!tokens.all { token -> haystack.contains(token) }) {
                    return@forEachIndexed
                }
            }
            add(FilteredPlaylistTrack(track = track, originalIndex = index))
        }
    }
}

/**
 * Finds sorted, merged, non-overlapping case-insensitive match ranges in [text] for [tokens].
 * Used to highlight matched substrings in playlist search results.
 */
internal fun findHighlightRanges(text: String, tokens: List<String>): List<IntRange> {
    if (text.isEmpty() || tokens.isEmpty()) return emptyList()
    val lowerText = text.lowercase()
    val raw = mutableListOf<IntRange>()
    tokens.asSequence()
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .distinct()
        .forEach { token ->
            var start = 0
            while (start < lowerText.length) {
                val idx = lowerText.indexOf(token, startIndex = start)
                if (idx < 0) break
                raw += idx until (idx + token.length)
                start = idx + token.length
            }
        }
    if (raw.isEmpty()) return emptyList()
    raw.sortWith(compareBy<IntRange> { it.first }.thenBy { it.last })
    val merged = mutableListOf<IntRange>()
    for (range in raw) {
        val last = merged.lastOrNull()
        if (last == null || range.first > last.last + 1) {
            merged += range
        } else if (range.last > last.last) {
            merged[merged.lastIndex] = last.first..range.last
        }
    }
    return merged
}

internal fun summarizePlaylistDownload(
    tracks: List<MusicTrack>,
    downloadedTrackKeys: Set<String>,
    downloadTasks: List<DownloadTaskItem>,
    downloadProgress: Map<String, Float>,
): PlaylistDownloadSummary {
    val total = tracks.size
    if (total == 0) {
        return PlaylistDownloadSummary(
            total = 0,
            downloadedCount = 0,
            activeTaskKeys = emptyList(),
            pausedTaskKeys = emptyList(),
            failedTaskKeys = emptyList(),
            fraction = 0f,
            phase = PlaylistDownloadPhase.None,
        )
    }

    val taskByKey = downloadTasks.associateBy { it.taskKey }
    var downloadedCount = 0
    var progressSum = 0f
    val activeKeys = linkedSetOf<String>()
    val pausedKeys = linkedSetOf<String>()
    val failedKeys = linkedSetOf<String>()

    tracks.forEach { track ->
        val key = downloadedTrackKey(track.sourceId, track.ratingKey)
        if (downloadedTrackKeys.containsDownloadedTrack(track.sourceId, track.ratingKey)) {
            downloadedCount++
            progressSum += 1f
            return@forEach
        }
        val task = taskByKey[key]
        val liveProgress = downloadProgress[key]
        when (task?.status) {
            DownloadTaskStatus.Queued,
            DownloadTaskStatus.Downloading -> {
                activeKeys += key
                progressSum += (liveProgress ?: task.progress).coerceIn(0f, 1f)
            }
            DownloadTaskStatus.WaitingForWifi,
            DownloadTaskStatus.Paused -> {
                pausedKeys += key
                progressSum += task.progress.coerceIn(0f, 1f)
            }
            DownloadTaskStatus.Failed -> {
                failedKeys += key
            }
            null -> {
                if (liveProgress != null) {
                    activeKeys += key
                    progressSum += liveProgress.coerceIn(0f, 1f)
                }
            }
        }
    }

    val fraction = (progressSum / total.toFloat()).coerceIn(0f, 1f)
    val phase = when {
        downloadedCount == total -> PlaylistDownloadPhase.AllDone
        activeKeys.isNotEmpty() -> PlaylistDownloadPhase.Downloading
        pausedKeys.isNotEmpty() -> PlaylistDownloadPhase.Paused
        downloadedCount > 0 -> PlaylistDownloadPhase.Partial
        else -> PlaylistDownloadPhase.None
    }

    return PlaylistDownloadSummary(
        total = total,
        downloadedCount = downloadedCount,
        activeTaskKeys = activeKeys.toList(),
        pausedTaskKeys = pausedKeys.toList(),
        failedTaskKeys = failedKeys.toList(),
        fraction = fraction,
        phase = phase,
    )
}

internal fun hasPlaylistOrderChanged(original: List<MusicTrack>, reordered: List<MusicTrack>): Boolean {
    if (original.size != reordered.size) return true
    for (index in original.indices) {
        if (original[index].ratingKey != reordered[index].ratingKey) return true
    }
    return false
}
