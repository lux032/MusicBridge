package com.lux032.musicbridge.storage

import android.content.Context
import android.util.Log
import com.lux032.musicbridge.PlaybackMode
import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosRoom
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The last playback session, as needed to put the player back after a process death. */
data class SavedPlaybackSession(
    val queueId: Long,
    val album: PlexAlbum,
    val tracks: List<PlexTrackStream>,
    val currentIndex: Int,
    val positionMillis: Long,
    val durationMillis: Long?,
    /** Null = this device. */
    val sonosRoom: SonosRoom?,
    val playbackMode: PlaybackMode,
)

/**
 * Persists the last playback session so a cold start can restore it.
 *
 * Split in two on purpose:
 * - the queue (album + tracks, possibly hundreds of entries) lives in a JSON file that is
 *   rewritten only when the queue itself changes;
 * - the cursor (index, position, output, mode) is tiny and lives in SharedPreferences, so
 *   it can be saved every few seconds during playback without rewriting the queue.
 * A queue id ties the two together; a cursor that belongs to another queue is ignored.
 *
 * All methods do blocking I/O; call them off the main thread.
 */
class PlaybackSessionStore(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "playback_session")
    private val queueFile = File(dir, "queue.json")
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveQueue(queueId: Long, album: PlexAlbum, tracks: List<PlexTrackStream>) {
        runCatching {
            val json = JSONObject()
                .put("version", FORMAT_VERSION)
                .put("queueId", queueId)
                .put("album", album.toJson())
                .put("tracks", JSONArray().apply { tracks.forEach { put(it.toJson()) } })
            dir.mkdirs()
            // Write-then-rename so a kill mid-write never leaves a truncated queue behind.
            val tmp = File(dir, "queue.json.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(queueFile)) {
                queueFile.delete()
                tmp.renameTo(queueFile)
            }
        }.onFailure { Log.w(LOG_TAG, "saveQueue failed", it) }
    }

    fun saveCursor(
        queueId: Long,
        currentIndex: Int,
        positionMillis: Long,
        durationMillis: Long?,
        sonosRoom: SonosRoom?,
        playbackMode: PlaybackMode,
    ) {
        prefs.edit()
            .putLong(KEY_QUEUE_ID, queueId)
            .putInt(KEY_INDEX, currentIndex)
            .putLong(KEY_POSITION, positionMillis.coerceAtLeast(0L))
            .putLong(KEY_DURATION, durationMillis ?: -1L)
            .putString(KEY_SONOS_ROOM, sonosRoom?.toJson()?.toString())
            .putString(KEY_MODE, playbackMode.name)
            .apply()
    }

    fun load(): SavedPlaybackSession? = runCatching {
        if (!queueFile.exists()) return null
        val json = JSONObject(queueFile.readText())
        if (json.optInt("version") != FORMAT_VERSION) return null
        val queueId = json.getLong("queueId")
        // The queue is written before its cursor, so a kill in between leaves a cursor of
        // the previous queue: keep the queue, but start it from the top.
        val cursorMatches = prefs.getLong(KEY_QUEUE_ID, Long.MIN_VALUE) == queueId
        val album = json.getJSONObject("album").toAlbum()
        val tracksJson = json.getJSONArray("tracks")
        val tracks = (0 until tracksJson.length()).map { tracksJson.getJSONObject(it).toTrack() }
        if (tracks.isEmpty()) return null
        SavedPlaybackSession(
            queueId = queueId,
            album = album,
            tracks = tracks,
            currentIndex = if (cursorMatches) prefs.getInt(KEY_INDEX, 0).coerceIn(0, tracks.lastIndex) else 0,
            positionMillis = if (cursorMatches) prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L) else 0L,
            durationMillis = prefs.getLong(KEY_DURATION, -1L).takeIf { cursorMatches && it > 0L },
            sonosRoom = prefs.getString(KEY_SONOS_ROOM, null)?.let { JSONObject(it).toSonosRoom() },
            playbackMode = runCatching { PlaybackMode.valueOf(prefs.getString(KEY_MODE, null).orEmpty()) }
                .getOrDefault(PlaybackMode.Sequential),
        )
    }.onFailure { Log.w(LOG_TAG, "load failed, discarding saved session", it) }.getOrNull()

    fun clear() {
        queueFile.delete()
        prefs.edit().clear().apply()
    }

    private companion object {
        const val LOG_TAG = "PlaybackSessionStore"
        const val PREFS_NAME = "playback_session"
        const val FORMAT_VERSION = 1
        const val KEY_QUEUE_ID = "queue_id"
        const val KEY_INDEX = "index"
        const val KEY_POSITION = "position_ms"
        const val KEY_DURATION = "duration_ms"
        const val KEY_SONOS_ROOM = "sonos_room"
        const val KEY_MODE = "mode"
    }
}

// --- JSON mapping. Nullable fields are simply omitted (JSONObject.put(k, null) removes k). ---

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) getString(key) else null

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) getLong(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) getInt(key) else null

private fun JSONObject.optFloatOrNull(key: String): Float? =
    if (has(key) && !isNull(key)) getDouble(key).toFloat() else null

private fun List<ArtistRef>.toJson(): JSONArray =
    JSONArray().apply { forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }

private fun JSONObject.optArtistRefs(key: String): List<ArtistRef> {
    val array = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        ArtistRef(item.optString("id"), item.optString("name"))
    }
}

private fun PlexAlbum.toJson(): JSONObject = JSONObject()
    .put("ratingKey", ratingKey)
    .put("title", title)
    .put("artistName", artistName)
    .put("year", year)
    .put("thumbUrl", thumbUrl)
    .put("userRating", userRating?.toDouble())
    .put("addedAt", addedAtEpochSeconds)
    .put("lastViewedAt", lastViewedAtEpochSeconds)
    .put("section", JSONObject().put("key", section.key).put("title", section.title).put("type", section.type))
    .put("sourceId", sourceId)
    .put("leafCount", leafCount)
    .put("musicAnalysisVersion", musicAnalysisVersion)
    .put("format", format)
    .put("subformat", subformat)
    .put("releaseDate", releaseDate)
    .put("artists", artists.toJson())

private fun JSONObject.toAlbum(): PlexAlbum {
    val section = getJSONObject("section")
    return PlexAlbum(
        ratingKey = getString("ratingKey"),
        title = getString("title"),
        artistName = optStringOrNull("artistName"),
        year = optIntOrNull("year"),
        thumbUrl = optStringOrNull("thumbUrl"),
        userRating = optFloatOrNull("userRating"),
        addedAtEpochSeconds = optLongOrNull("addedAt"),
        lastViewedAtEpochSeconds = optLongOrNull("lastViewedAt"),
        section = PlexSection(section.getString("key"), section.getString("title"), section.getString("type")),
        sourceId = optString("sourceId"),
        leafCount = optIntOrNull("leafCount"),
        musicAnalysisVersion = optStringOrNull("musicAnalysisVersion"),
        format = optStringOrNull("format"),
        subformat = optStringOrNull("subformat"),
        releaseDate = optStringOrNull("releaseDate"),
        artists = optArtistRefs("artists"),
    )
}

private fun PlexTrackStream.toJson(): JSONObject = JSONObject()
    .put("ratingKey", ratingKey)
    .put("title", title)
    .put("streamUrl", streamUrl)
    .put("partKey", partKey)
    .put("durationMillis", durationMillis)
    .put("userRating", userRating?.toDouble())
    .put("albumRatingKey", albumRatingKey)
    .put("albumTitle", albumTitle)
    .put("artistName", artistName)
    .put("thumbUrl", thumbUrl)
    .put("sourceAudioCodec", sourceAudioCodec)
    .put("sourceAudioBitrateKbps", sourceAudioBitrateKbps)
    .put("sourceAudioSampleRate", sourceAudioSampleRate)
    .put("bitDepth", bitDepth)
    .put("playbackMimeType", playbackMimeType)
    .put("lyricUrl", lyricUrl)
    .put("sourceId", sourceId)
    .put("musicAnalysisVersion", musicAnalysisVersion)
    .put("viewCount", viewCount)
    .put("lastViewedAt", lastViewedAtEpochSeconds)
    .put("skipCount", skipCount)
    .put("discNumber", discNumber)
    .put("trackNumber", trackNumber)
    .put("artists", artists.toJson())

private fun JSONObject.toTrack(): PlexTrackStream = PlexTrackStream(
    ratingKey = getString("ratingKey"),
    title = getString("title"),
    streamUrl = getString("streamUrl"),
    partKey = getString("partKey"),
    durationMillis = optLongOrNull("durationMillis"),
    userRating = optFloatOrNull("userRating"),
    albumRatingKey = optStringOrNull("albumRatingKey"),
    albumTitle = optStringOrNull("albumTitle"),
    artistName = optStringOrNull("artistName"),
    thumbUrl = optStringOrNull("thumbUrl"),
    sourceAudioCodec = optStringOrNull("sourceAudioCodec"),
    sourceAudioBitrateKbps = optIntOrNull("sourceAudioBitrateKbps"),
    sourceAudioSampleRate = optIntOrNull("sourceAudioSampleRate"),
    bitDepth = optIntOrNull("bitDepth"),
    playbackMimeType = optStringOrNull("playbackMimeType"),
    lyricUrl = optStringOrNull("lyricUrl"),
    sourceId = optString("sourceId"),
    musicAnalysisVersion = optStringOrNull("musicAnalysisVersion"),
    viewCount = optLongOrNull("viewCount"),
    lastViewedAtEpochSeconds = optLongOrNull("lastViewedAt"),
    skipCount = optLongOrNull("skipCount"),
    discNumber = optIntOrNull("discNumber"),
    trackNumber = optIntOrNull("trackNumber"),
    artists = optArtistRefs("artists"),
)

private fun SonosRoom.toJson(): JSONObject = JSONObject()
    .put("roomName", roomName)
    .put("coordinatorUuid", coordinatorUuid)
    .put("coordinatorBaseUrl", coordinatorBaseUrl)
    .put("memberCount", memberCount)
    .put("rawCoordinatorName", rawCoordinatorName)
    .put("memberBaseUrls", JSONArray(memberBaseUrls))

private fun JSONObject.toSonosRoom(): SonosRoom {
    val members = optJSONArray("memberBaseUrls")
    return SonosRoom(
        roomName = getString("roomName"),
        coordinatorUuid = getString("coordinatorUuid"),
        coordinatorBaseUrl = getString("coordinatorBaseUrl"),
        memberCount = optInt("memberCount", 1),
        rawCoordinatorName = optString("rawCoordinatorName"),
        memberBaseUrls = members?.let { array -> (0 until array.length()).map { array.getString(it) } }.orEmpty(),
    )
}
