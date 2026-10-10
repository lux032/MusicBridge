package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.playbackUrlIdentity
import com.lux032.musicbridge.sonos.SonosPlaybackStatus

/**
 * Sonos device-queue playback (032 sources): the whole list is loaded into the speaker's
 * queue, which then advances on its own. [SonosQueueSession] mirrors that queue entry by
 * entry so the app can map the speaker's position back to its own track list.
 */
internal data class SonosQueueEntry(
    val sourceId: String,
    val ratingKey: String,
    val kind: SonosPlanKind,
    val url: String,
    val mime: String?,
)

internal data class SonosQueueSession(
    val roomUuid: String,
    val entries: List<SonosQueueEntry>,
) {
    /** True while the app's track list still matches the speaker queue one-to-one. */
    fun mirrors(tracks: List<PlexTrackStream>): Boolean =
        entries.size == tracks.size && entries.indices.all { i ->
            entries[i].sourceId == tracks[i].sourceId && entries[i].ratingKey == tracks[i].ratingKey
        }
}

/** Queue mode is only used when every track is served by a 032 server (no phone proxy). */
internal fun canUseSonosQueue(tracks: List<PlexTrackStream>, is032: (PlexTrackStream) -> Boolean): Boolean =
    tracks.size in 1..SONOS_QUEUE_MAX_TRACKS && tracks.all(is032)

internal const val SONOS_QUEUE_MAX_TRACKS = 2_000

internal fun sonosPlayModeFor(mode: PlaybackMode): String = when (mode) {
    PlaybackMode.Sequential -> "NORMAL"
    PlaybackMode.RepeatAll -> "REPEAT_ALL"
    PlaybackMode.RepeatOne -> "REPEAT_ONE"
    PlaybackMode.Shuffle -> "SHUFFLE_NOREPEAT"
}

/**
 * The queue index Sonos is playing: its 1-based Track number when the entry there matches
 * the reported TrackURI (or no URI is reported), otherwise the first entry with that URI.
 */
internal fun resolveSonosQueueIndex(entries: List<SonosQueueEntry>, status: SonosPlaybackStatus): Int? {
    val uri = status.currentTrackUri?.let(::playbackUrlIdentity).orEmpty()
    val byNumber = status.queueTrackNumber?.minus(1)?.takeIf { it in entries.indices }
    if (byNumber != null && (uri.isBlank() || sameQueueUrl(entries[byNumber].url, uri))) return byNumber
    if (uri.isBlank()) return null
    return entries.indexOfFirst { sameQueueUrl(it.url, uri) }.takeIf { it >= 0 }
}

/** Compares by path: a live entry re-opened at an offset keeps its identity. */
private fun sameQueueUrl(entryUrl: String, reportedIdentity: String): Boolean =
    playbackUrlIdentity(entryUrl) == reportedIdentity
