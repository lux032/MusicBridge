package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.playbackUrlIdentity
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.sonos.SonosPlaybackStatus
import com.lux032.musicbridge.sonos.SonosQueueBrowseEntry

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

// --- Adopting a queue already on the speaker -----------------------------------------

/** A 032 server the app knows: its id, base URL and the tokens its media URLs may carry. */
internal data class Music032ServerRef(val sourceId: String, val baseUrl: String, val tokens: Set<String>)

/** One speaker queue entry recognised as a 032 track. [url] is without `offsetMs`. */
internal data class AdoptedQueueRef(
    val sourceId: String,
    val ratingKey: String,
    val kind: SonosPlanKind,
    val url: String,
    val mime: String?,
    val offsetMs: Long,
) {
    fun entry() = SonosQueueEntry(sourceId, ratingKey, kind, url, mime)
}

private val music032MediaPath = Regex("^(https?://[^?#]*?)/api/v1/tracks/(\\d+)/(stream|transcode\\.(?:flac|mp3|ogg))$", RegexOption.IGNORE_CASE)

/**
 * Maps every URL of a speaker queue to a 032 track, or null when any entry is foreign
 * (another service, Plex, unknown server) — such a queue is left alone. The server is
 * matched by media token first (the web panel pushes LAN addresses while the app may use
 * a domain), then by base URL, then the only configured 032 server.
 */
internal fun resolveAdoptableQueue(
    queue: List<SonosQueueBrowseEntry>,
    servers: List<Music032ServerRef>,
): List<AdoptedQueueRef>? {
    if (queue.isEmpty() || queue.size > SONOS_QUEUE_MAX_TRACKS || servers.isEmpty()) return null
    return queue.map { item ->
        val path = item.url.substringBefore('#').substringBefore('?')
        val match = music032MediaPath.matchEntire(path) ?: return null
        val params = item.url.substringBefore('#').substringAfter('?', "").split('&')
            .associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val token = params["mediaToken"]?.takeIf { it.isNotBlank() } ?: return null
        val base = match.groupValues[1].trimEnd('/')
        val server = servers.firstOrNull { token in it.tokens }
            ?: servers.firstOrNull { it.baseUrl.trim().trimEnd('/').equals(base, ignoreCase = true) }
            ?: servers.singleOrNull()
            ?: return null
        val kind = when (match.groupValues[3].lowercase()) {
            "stream" -> SonosPlanKind.ORIGINAL
            "transcode.flac" -> SonosPlanKind.STATIC_FLAC
            else -> SonosPlanKind.LIVE_MP3
        }
        val offset = params["offsetMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        AdoptedQueueRef(server.sourceId, match.groupValues[2], kind,
            Music032TranscodeUrls.withOffsetMs(item.url, 0L, null), item.mimeType, offset)
    }
}

/** Reuses [known] when it is exactly the speaker's list (the usual cold-start case). */
internal fun reusableTracks(refs: List<AdoptedQueueRef>, known: List<PlexTrackStream>): Boolean =
    refs.size == known.size && refs.indices.all { i ->
        refs[i].sourceId == known[i].sourceId && refs[i].ratingKey == known[i].ratingKey
    }

internal fun playbackModeFromSonos(mode: String?): PlaybackMode? = when (mode?.uppercase()) {
    "NORMAL" -> PlaybackMode.Sequential
    "REPEAT_ALL" -> PlaybackMode.RepeatAll
    "REPEAT_ONE" -> PlaybackMode.RepeatOne
    "SHUFFLE_NOREPEAT", "SHUFFLE", "SHUFFLE_REPEAT_ONE" -> PlaybackMode.Shuffle
    else -> null
}

/** Compares by path: a live entry re-opened at an offset keeps its identity. */
private fun sameQueueUrl(entryUrl: String, reportedIdentity: String): Boolean =
    playbackUrlIdentity(entryUrl) == reportedIdentity
