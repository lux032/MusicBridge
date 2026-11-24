package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032SimilarTrack
import com.lux032.musicbridge.music.MusicTrack

/** Seed track that initiated an explicit Track Radio session. */
data class SimilarRadioSeed(
    val sourceId: String,
    val ratingKey: String,
    val title: String,
)

enum class SimilarRadioErrorKind {
    OfflineOrFailed,
    Busy,
    Empty,
}

/**
 * UI & coordinator state for 032 Track Radio and Queue Auto-Continue.
 *
 * Tracks in `[0, manualQueueCount)` belong to the user's explicit queue; tracks in
 * `[manualQueueCount, tracks.size)` are auto-appended similar recommendations.
 */
data class SimilarRadioState(
    val autoPlayEnabled: Boolean = true,
    val manualQueueCount: Int = 0,
    val radioSeed: SimilarRadioSeed? = null,
    val lastAutoSeedTitle: String? = null,
    val similarReasons: Map<String, List<String>> = emptyMap(),
    val isLoading: Boolean = false,
    val errorKind: SimilarRadioErrorKind? = null,
    val similarityMethod: String? = null,
) {
    val hasAutoTracks: Boolean
        get() = lastAutoSeedTitle != null || radioSeed != null

    fun primaryReasonLabelFor(track: MusicTrack): String? {
        val reasons = similarReasons[similarTrackIdentityKey(track)] ?: return null
        return primarySimilarReasonLabel(reasons)
    }

    val isHybridMethod: Boolean?
        get() = when (similarityMethod?.lowercase()) {
            "hybrid" -> true
            "metadata" -> false
            else -> null
        }
}

internal fun similarTrackIdentityKey(track: MusicTrack): String =
    "${track.sourceId}:${track.ratingKey}"

private val STRUCTURAL_SIMILAR_REASONS = setOf(
    "sameWork",
    "sharedCredit",
    "coPlaylist",
    "sameArtist",
)

private fun hasStructuralSimilarReason(reasons: List<String>): Boolean =
    reasons.any { it.trim() in STRUCTURAL_SIMILAR_REASONS }

private fun isAcousticOnlySimilarReason(reasons: List<String>): Boolean {
    var hasSoundsSimilar = false
    for (reason in reasons) {
        val trimmed = reason.trim()
        if (trimmed in STRUCTURAL_SIMILAR_REASONS) return false
        if (trimmed == "soundsSimilar") hasSoundsSimilar = true
    }
    return hasSoundsSimilar
}

/**
 * Maps raw 032 similarity reasons to the most informative user-facing short label.
 * Specific structural relationships (`sameWork`, `sharedCredit`, `coPlaylist`, `sameArtist`)
 * take precedence over `soundsSimilar` so hybrid matches do not collapse into "听感相似",
 * while pure acoustic matches still display "听感相似" ahead of broad `genre`/`era` tags.
 */
internal fun primarySimilarReasonLabel(reasons: List<String>): String? {
    if (reasons.isEmpty()) return null
    val normalized = reasons.map { it.trim() }.toSet()
    return when {
        "sameWork" in normalized -> Strings.similarReasonSameWork
        "sharedCredit" in normalized -> Strings.similarReasonSharedCredit
        "coPlaylist" in normalized -> Strings.similarReasonCoPlaylist
        "sameArtist" in normalized -> Strings.similarReasonSameArtist
        "soundsSimilar" in normalized -> Strings.similarReasonSoundsSimilar
        "genre" in normalized -> Strings.similarReasonGenre
        "era" in normalized -> Strings.similarReasonEra
        else -> null
    }
}

/**
 * Pure check for whether the playback queue sheet should show the "Up Next · Similar"
 * divider between manual tracks and auto-appended recommendations.
 * Requires [SimilarRadioState.hasAutoTracks] so a manual queue can never be falsely split
 * if no similar tracks have been added to the active session.
 */
internal fun shouldShowAutoSimilarDivider(
    isSimilarRadioSupported: Boolean,
    radioState: SimilarRadioState,
    manualBoundary: Int,
    totalCount: Int,
): Boolean =
    isSimilarRadioSupported &&
        radioState.autoPlayEnabled &&
        radioState.hasAutoTracks &&
        manualBoundary < totalCount

/**
 * Pure check for whether auto-continue should proactively prefetch a new batch.
 * Never triggers under RepeatOne/RepeatAll or when a sleep timer is set to stop after
 * the current track.
 *
 * When [remainingInPlayOrder] is provided (e.g., from ExoPlayer's live ShuffleOrder),
 * it overrides linear index distance so Shuffle mode never falsely triggers just because
 * a random jump landed near the end of the track list.
 */
internal fun shouldTriggerSimilarAutoContinue(
    autoPlayEnabled: Boolean,
    playbackMode: PlaybackMode,
    sleepStopAfterCurrent: Boolean,
    tracksSize: Int,
    currentIndex: Int,
    isLoading: Boolean,
    remainingThreshold: Int = 2,
    remainingInPlayOrder: Int? = null,
): Boolean {
    if (!autoPlayEnabled || isLoading || sleepStopAfterCurrent) return false
    if (playbackMode == PlaybackMode.RepeatOne || playbackMode == PlaybackMode.RepeatAll) return false
    if (tracksSize <= 0 || currentIndex !in 0 until tracksSize) return false
    val remainingAfterCurrent = when {
        remainingInPlayOrder != null -> remainingInPlayOrder.coerceAtLeast(0)
        playbackMode == PlaybackMode.Shuffle -> {
            if (tracksSize - 1 <= remainingThreshold) tracksSize - 1 else return false
        }
        else -> tracksSize - 1 - currentIndex
    }
    return remainingAfterCurrent <= remainingThreshold
}

private val ACCOMPANIMENT_TITLE_REGEX = Regex(
    """(?i)(off[\s._-]*(vocal|vo\.?)|オフ[\s・._-]*(ボーカル|ヴォーカル)|karaoke|カラオケ|からおけ|backing[\s._-]*track|minus[\s._-]*one|(less|without|no|w/o)[\s._-]*vocal|vocal[\s._-]*less|ボーカル(なし|レス)|歌なし|伴奏|\binstrumentals?\b|(^|[\s(\[【「\-–—~～])inst\.?(\s*(ver\.?|version|mix|edit))?($|[\s)\]】」\-–—~～.])|インスト(ゥルメンタル|ルメンタル)?|纯音乐版|純音楽版)""",
)

/**
 * Returns true when a track's title looks like an accompaniment / off-vocal / instrumental
 * bonus track (common as the final track(s) on J-Pop / ACG single CDs).
 */
internal fun isLikelyAccompanimentTitle(title: String): Boolean {
    if (title.isBlank()) return false
    val folded = buildString(title.length) {
        for (ch in title) {
            when {
                ch in '\uFF01'..'\uFF5E' -> append((ch.code - 0xFEE0).toChar())
                ch == '\u3000' -> append(' ')
                else -> append(ch.lowercaseChar())
            }
        }
    }
    return ACCOMPANIMENT_TITLE_REGEX.containsMatchIn(folded)
}

/**
 * Selects the next rolling seed track for auto-continue: prefers the last non-accompaniment
 * track in the queue so single CDs ending with "(instrumental)" / "(Off Vocal)" still seed
 * from the main vocal track instead of the backing track. Falls back to the tail track only
 * when every eligible track in the queue is instrumental/accompaniment.
 */
internal fun selectRollingSimilarSeed(
    tracks: List<MusicTrack>,
    currentIndex: Int,
    isEligible: (MusicTrack) -> Boolean,
): MusicTrack? {
    if (tracks.isEmpty()) return null
    // First pass: prefer the latest eligible non-accompaniment track.
    for (i in tracks.lastIndex downTo 0) {
        val candidate = tracks[i]
        if (isEligible(candidate) && !isLikelyAccompanimentTitle(candidate.title)) {
            return candidate
        }
    }
    // Second pass: all eligible tracks look instrumental; fall back to the tail track.
    for (i in tracks.lastIndex downTo 0) {
        val candidate = tracks[i]
        if (isEligible(candidate)) return candidate
    }
    return tracks.getOrNull(currentIndex)?.takeIf(isEligible)
}

/**
 * Client-side deduplication, two-pass artist cap, and acoustic/structural interleaving safeguard.
 * Ensures clean, varied radio queues even when talking to an older 032 server that does not
 * yet implement `exclude`, `diverse=1`, or balanced signal interleaving.
 */
internal fun filterAndDiversifySimilarTracks(
    candidates: List<Music032SimilarTrack>,
    excludedTrackKeys: Set<String>,
    maxPerArtist: Int = 2,
    limit: Int = 12,
): List<Music032SimilarTrack> {
    if (candidates.isEmpty() || limit <= 0) return emptyList()
    val seenKeys = excludedTrackKeys.toMutableSet()
    val unique = mutableListOf<Music032SimilarTrack>()
    for (candidate in candidates) {
        val key = similarTrackIdentityKey(candidate.track)
        if (seenKeys.add(key)) {
            unique += candidate
        }
    }
    if (unique.isEmpty() || maxPerArtist <= 0) return unique.take(limit)

    fun artistKeyFor(candidate: Music032SimilarTrack): String =
        candidate.track.artists.firstOrNull()?.id
            ?.takeIf { it.isNotBlank() }
            ?: candidate.track.artistName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: "unknown:${candidate.track.ratingKey}"

    val topScore = unique.maxOf { it.score }
    val minStructuralScore = maxOf(0.18, topScore * 0.50)
    val used = BooleanArray(unique.size)
    val artistCounts = mutableMapOf<String, Int>()
    val picked = ArrayList<Music032SimilarTrack>(minOf(limit, unique.size))
    var consecutiveAcousticOnly = 0
    var nextGeneral = 0
    var nextStructural = 0

    fun advanceGeneral(): Int {
        while (nextGeneral < unique.size) {
            if (!used[nextGeneral] && (artistCounts[artistKeyFor(unique[nextGeneral])] ?: 0) < maxPerArtist) {
                return nextGeneral
            }
            nextGeneral++
        }
        return -1
    }

    fun advanceStructural(): Int {
        while (nextStructural < unique.size) {
            val candidate = unique[nextStructural]
            if (!used[nextStructural] &&
                (artistCounts[artistKeyFor(candidate)] ?: 0) < maxPerArtist &&
                hasStructuralSimilarReason(candidate.reasons) &&
                candidate.score >= minStructuralScore
            ) {
                return nextStructural
            }
            nextStructural++
        }
        return -1
    }

    while (picked.size < limit) {
        val generalIndex = advanceGeneral()
        if (generalIndex < 0) break
        val chosenIndex = if (
            consecutiveAcousticOnly >= 2 &&
            isAcousticOnlySimilarReason(unique[generalIndex].reasons)
        ) {
            advanceStructural().takeIf { it >= 0 } ?: generalIndex
        } else {
            generalIndex
        }
        used[chosenIndex] = true
        val chosen = unique[chosenIndex]
        val artistKey = artistKeyFor(chosen)
        artistCounts[artistKey] = (artistCounts[artistKey] ?: 0) + 1
        picked += chosen
        consecutiveAcousticOnly = if (isAcousticOnlySimilarReason(chosen.reasons)) {
            consecutiveAcousticOnly + 1
        } else {
            0
        }
    }
    for (index in unique.indices) {
        if (picked.size >= limit) break
        if (!used[index]) {
            picked += unique[index]
        }
    }
    return picked
}

/**
 * Keeps the boundary between manual queue tracks (`0 until manualCount`) and auto-continued
 * similar tracks (`manualCount until size`) consistent after a single-item drag reorder.
 * Dragging an auto track above the boundary promotes it into the manual queue; dragging a
 * manual track below the boundary demotes it into the auto section.
 */
internal fun remapManualQueueCountAfterMove(
    manualQueueCount: Int,
    fromIndex: Int,
    toIndex: Int,
    queueSize: Int,
): Int {
    val clamped = manualQueueCount.coerceIn(0, queueSize)
    if (fromIndex == toIndex) return clamped
    val fromManual = fromIndex < clamped
    val toManual = toIndex < clamped
    return when {
        !fromManual && toManual -> (clamped + 1).coerceAtMost(queueSize)
        fromManual && !toManual -> (clamped - 1).coerceAtLeast(0)
        else -> clamped
    }
}

/** Keeps [manualQueueCount] aligned after removing a track at [removeIndex]. */
internal fun remapManualQueueCountAfterRemove(
    manualQueueCount: Int,
    removeIndex: Int,
    newQueueSize: Int,
): Int {
    val adjusted = if (removeIndex < manualQueueCount) manualQueueCount - 1 else manualQueueCount
    return adjusted.coerceIn(0, newQueueSize)
}

/**
 * Computes the insertion index for a manual `QueuePlacement` while respecting the
 * manual/auto-continue boundary: `End` inserts at the end of the manual queue (before
 * auto-continued tracks) so user-queued songs always play before auto-recommendations.
 */
internal fun manualQueueInsertIndex(
    placement: QueuePlacement,
    currentIndex: Int,
    tracksSize: Int,
    manualQueueCount: Int,
): Int {
    val boundary = manualQueueCount.coerceIn(0, tracksSize)
    return when (placement) {
        QueuePlacement.Next -> (currentIndex + 1).coerceIn(0, tracksSize)
        QueuePlacement.End -> maxOf(boundary, currentIndex + 1).coerceIn(0, tracksSize)
    }
}
