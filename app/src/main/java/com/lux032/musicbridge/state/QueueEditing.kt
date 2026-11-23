package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicTrack

/** Transient notice shown inside the playback queue sheet (undo, mode change, rollback). */
data class QueueNotice(
    val id: Long,
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val isError: Boolean = false,
)

/** Result of applying a single-item move to a queue snapshot. */
internal data class QueueMoveResult(
    val tracks: List<MusicTrack>,
    val currentIndex: Int,
)

/** Result of removing one track from a queue snapshot. */
internal data class QueueRemoveResult(
    val tracks: List<MusicTrack>,
    /** Next active index, or -1 when the removed track was the only one in the queue. */
    val currentIndex: Int,
    val removedTrack: MusicTrack,
    val removedWasCurrent: Boolean,
)

/**
 * Pure index remap when an item moves from [fromIndex] to [toIndex].
 * Keeps [currentIndex] pinned to the same logical track regardless of direction.
 */
internal fun remapIndexAfterMove(currentIndex: Int, fromIndex: Int, toIndex: Int): Int {
    if (currentIndex < 0 || fromIndex == toIndex) return currentIndex
    return when {
        currentIndex == fromIndex -> toIndex
        fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
        fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
        else -> currentIndex
    }
}

/**
 * Computes Sonos UPnP `AVTransport::ReorderTracksInQueue`'s 1-based `InsertBefore`
 * parameter for moving a single track from 0-based [fromIndex] to 0-based [toIndex].
 *
 * Sonos evaluates `InsertBefore` against the queue BEFORE the moved track is extracted:
 * moving downward (`to > from`) therefore targets `to + 2`, while moving upward targets `to + 1`.
 */
internal fun sonosInsertBeforeForMove(fromIndex: Int, toIndex: Int): Int =
    if (toIndex > fromIndex) toIndex + 2 else toIndex + 1

internal fun moveTrackInQueueState(
    tracks: List<MusicTrack>,
    currentIndex: Int,
    fromIndex: Int,
    toIndex: Int,
): QueueMoveResult? {
    if (fromIndex !in tracks.indices || toIndex !in tracks.indices || fromIndex == toIndex) return null
    val updated = tracks.toMutableList().apply {
        val item = removeAt(fromIndex)
        add(toIndex, item)
    }
    return QueueMoveResult(
        tracks = updated,
        currentIndex = remapIndexAfterMove(currentIndex, fromIndex, toIndex),
    )
}

internal fun removeTrackFromQueueState(
    tracks: List<MusicTrack>,
    currentIndex: Int,
    removeIndex: Int,
): QueueRemoveResult? {
    if (removeIndex !in tracks.indices) return null
    val removed = tracks[removeIndex]
    val wasCurrent = removeIndex == currentIndex
    val updated = tracks.toMutableList().apply { removeAt(removeIndex) }
    val nextIndex = when {
        updated.isEmpty() -> -1
        removeIndex < currentIndex -> (currentIndex - 1).coerceIn(0, updated.lastIndex)
        wasCurrent -> removeIndex.coerceAtMost(updated.lastIndex)
        else -> currentIndex.coerceIn(0, updated.lastIndex)
    }
    return QueueRemoveResult(
        tracks = updated,
        currentIndex = nextIndex,
        removedTrack = removed,
        removedWasCurrent = wasCurrent,
    )
}

/** Keeps only the track at [currentIndex], clearing everything before and after it. */
internal fun clearUpcomingKeepCurrentState(
    tracks: List<MusicTrack>,
    currentIndex: Int,
): List<MusicTrack>? {
    if (tracks.size <= 1 || currentIndex !in tracks.indices) return null
    return listOf(tracks[currentIndex])
}

/**
 * Total remaining duration from [currentIndex] to the end of [tracks], minus the already
 * played portion of the current track. Returns null when no track has a known duration.
 */
internal fun remainingQueueDurationMillis(
    tracks: List<MusicTrack>,
    currentIndex: Int,
    currentPositionMillis: Long = 0L,
): Long? {
    if (tracks.isEmpty() || currentIndex !in tracks.indices) return null
    var hasKnownDuration = false
    var totalMillis = 0L
    for (i in currentIndex until tracks.size) {
        val d = tracks[i].durationMillis?.takeIf { it > 0L } ?: continue
        hasKnownDuration = true
        totalMillis += if (i == currentIndex) {
            (d - currentPositionMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
        } else {
            d
        }
    }
    return totalMillis.takeIf { hasKnownDuration }
}
