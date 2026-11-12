package com.lux032.musicbridge.music

import org.json.JSONArray
import org.json.JSONObject

/**
 * An unsaved AI playlist from `POST /api/v1/ai/playlists` (`features.aiPlaylists`): the
 * server's model reads the prompt into conditions (vocabulary moods/styles, library
 * artists/works, energy, familiarity) and the server picks the tracks.
 */
data class Music032AiPlaylist(
    val title: String,
    val description: String,
    /** Human labels of what the server understood ("治愈温柔", "钢琴", "梶浦由记"…). */
    val conditions: List<String>,
    val tracks: List<MusicTrack>,
    val exploreTrackIds: Set<String>,
    /** Non-blank when some of the model's conditions were dropped. */
    val note: String,
)

internal object Music032AiPlaylistJson {
    const val FEATURE = "aiPlaylists"
    const val MAX_PROMPT_LENGTH = 200

    fun request(prompt: String, trackCount: Int): JSONObject = JSONObject()
        .put("prompt", prompt.trim().take(MAX_PROMPT_LENGTH))
        .apply { if (trackCount > 0) put("trackCount", trackCount.coerceAtMost(50)) }

    fun parse(root: JSONObject, parseTrack: (JSONObject) -> MusicTrack): Music032AiPlaylist {
        val tracks = mutableListOf<MusicTrack>()
        val explore = mutableSetOf<String>()
        root.optJSONArray("tracks").objects().forEach { item ->
            val trackJson = item.optJSONObject("track") ?: return@forEach
            if (trackJson.optBoolean("missing") || trackJson.optLong("id") <= 0) return@forEach
            val track = parseTrack(trackJson)
            tracks += track
            if (item.optString("bucket") == "explore") explore += track.ratingKey
        }
        val conditions = root.optJSONObject("conditions")
        val labels = buildList {
            conditions?.optJSONArray("works").objects().mapTo(this) { it.optString("name") }
            conditions?.optJSONArray("people").objects().mapTo(this) { it.optString("name") }
            conditions?.optJSONArray("moods").objects().mapTo(this) { it.optString("label") }
            conditions?.optJSONArray("styles").objects().mapTo(this) { it.optString("label") }
        }.map(String::trim).filter(String::isNotEmpty).distinct()
        return Music032AiPlaylist(
            title = root.optString("title").trim(),
            description = root.optString("description").trim(),
            conditions = labels,
            tracks = tracks,
            exploreTrackIds = explore,
            note = root.optString("note").trim(),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
