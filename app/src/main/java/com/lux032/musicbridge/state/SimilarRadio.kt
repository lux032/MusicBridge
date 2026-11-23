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

/** Maps raw 032 similarity reasons to the highest-priority user-facing short label. */
internal fun primarySimilarReasonLabel(reasons: List<String>): String? {
    if (reasons.isEmpty()) return null
    val normalized = reasons.map { it.trim() }.toSet()
    return when {
        "soundsSimilar" in normalized -> Strings.similarReasonSoundsSimilar
        "sameWork" in normalized -> Strings.similarReasonSameWork
        "sharedCredit" in normalized -> Strings.similarReasonSharedCredit
        "coPlaylist" in normalized -> Strings.similarReasonCoPlaylist
        "sameArtist" in normalized -> Strings.similarReasonSameArtist
        "genre" in normalized -> Strings.similarReasonGenre
        "era" in normalized -> Strings.similarReasonEra
        else -> null
    }
}

/**
 * Pure check for whether auto-continue should proactively prefetch a new batch.
 * Never triggers under RepeatOne/RepeatAll or when a sleep timer is set to stop after
 * the current track.
 */
internal fun shouldTriggerSimilarAutoContinue(
    autoPlayEnabled: Boolean,
    playbackMode: PlaybackMode,
    sleepStopAfterCurrent: Boolean,
    tracksSize: Int,
    currentIndex: Int,
    isLoading: Boolean,
    remainingThreshold: Int = 2,
): Boolean {
    if (!autoPlayEnabled || isLoading || sleepStopAfterCurrent) return false
    if (playbackMode == PlaybackMode.RepeatOne || playbackMode == PlaybackMode.RepeatAll) return false
    if (tracksSize <= 0 || currentIndex !in 0 until tracksSize) return false
    val remainingAfterCurrent = tracksSize - 1 - currentIndex
    return remainingAfterCurrent <= remainingThreshold
}

/**
 * Selects the next rolling seed track for auto-continue: prefers the tail of the queue
 * so endless playback smoothly drifts forward instead of hammering the original seed.
 */
internal fun selectRollingSimilarSeed(
    tracks: List<MusicTrack>,
    currentIndex: Int,
    isEligible: (MusicTrack) -> Boolean,
): MusicTrack? {
    if (tracks.isEmpty()) return null
    // Prefer the last track in the queue (newest vibe), falling back toward currentIndex.
    for (i in tracks.lastIndex downTo 0) {
        val candidate = tracks[i]
        if (isEligible(candidate)) return candidate
    }
    return tracks.getOrNull(currentIndex)?.takeIf(isEligible)
}

/**
 * Client-side deduplication and two-pass artist diversification safeguard.
 * Ensures clean radio queues even when talking to an older 032 server that does not
 * yet implement `exclude` or `diverse=1`.
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
    if (maxPerArtist <= 0) return unique.take(limit)
    val artistCounts = mutableMapOf<String, Int>()
    val picked = mutableListOf<Music032SimilarTrack>()
    val deferred = mutableListOf<Music032SimilarTrack>()
    for (candidate in unique) {
        val artistKey = candidate.track.artists.firstOrNull()?.id
            ?.takeIf { it.isNotBlank() }
            ?: candidate.track.artistName?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: "unknown:${candidate.track.ratingKey}"
        val count = artistCounts[artistKey] ?: 0
        if (count >= maxPerArtist) {
            deferred += candidate
            continue
        }
        artistCounts[artistKey] = count + 1
        picked += candidate
        if (picked.size >= limit) return picked
    }
    for (candidate in deferred) {
        if (picked.size >= limit) break
        picked += candidate
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
