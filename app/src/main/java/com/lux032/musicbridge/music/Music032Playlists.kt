package com.lux032.musicbridge.music

import com.lux032.musicbridge.plex.PlexPlaylist
import org.json.JSONArray
import org.json.JSONObject

/** Result of an append / remove / insert call (`PlaylistItemResult` on the server). */
data class Music032PlaylistItemResult(
    val added: Int = 0,
    val removed: Int = 0,
    val skippedDuplicate: Int = 0,
    val skippedInvalid: Int = 0,
    val total: Int = 0,
    val revision: Long? = null,
)

/** A freshly created playlist plus, when tracks were sent along, what the server kept. */
data class Music032PlaylistCreated(
    val playlist: PlexPlaylist,
    val stats: Music032PlaylistItemResult?,
)

/** Pure request builders / response parsers for the 032 playlist write API. */
internal object Music032PlaylistRequests {
    fun trackIdArray(trackIds: List<String>): JSONArray =
        JSONArray().apply { trackIds.mapNotNull { it.trim().toLongOrNull() }.forEach { put(it) } }

    fun create(name: String, description: String, trackIds: List<String>): JSONObject {
        val body = JSONObject()
            .put("name", name.trim())
            .put("description", description.trim())
        val ids = trackIdArray(trackIds)
        if (ids.length() > 0) {
            body.put("trackIds", ids)
            body.put("invalidTracks", "skip")
        }
        return body
    }

    /**
     * Strict create returns the playlist itself; the skip policy wraps it as
     * `{playlist, stats}`. Both shapes are accepted.
     */
    fun parseCreated(root: JSONObject, parsePlaylist: (JSONObject) -> PlexPlaylist): Music032PlaylistCreated {
        val wrapped = root.optJSONObject("playlist")
        return if (wrapped != null) {
            Music032PlaylistCreated(parsePlaylist(wrapped), root.optJSONObject("stats")?.let(::parseItemResult))
        } else {
            Music032PlaylistCreated(parsePlaylist(root), null)
        }
    }

    fun parseItemResult(root: JSONObject): Music032PlaylistItemResult = Music032PlaylistItemResult(
        added = root.optInt("added"),
        removed = root.optInt("removed"),
        skippedDuplicate = root.optInt("skippedDuplicate"),
        skippedInvalid = root.optInt("skippedInvalid"),
        total = root.optInt("total"),
        revision = if (root.has("revision")) root.optLong("revision") else null,
    )
}
