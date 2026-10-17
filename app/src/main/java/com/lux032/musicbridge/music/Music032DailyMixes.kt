package com.lux032.musicbridge.music

import com.lux032.musicbridge.plex.PlexTrackStream
import org.json.JSONArray
import org.json.JSONObject

/** One mood / style label of a daily mix theme. */
data class Music032DailyMixTag(val kind: String, val tag: String, val label: String)

/** A server-generated daily mix (`/api/v1/daily-mixes`). */
data class Music032DailyMix(
    val id: Long,
    val day: String,
    val status: String,
    val title: String,
    val description: String,
    val themeType: String,
    val themeTypeLabel: String,
    val tags: List<Music032DailyMixTag>,
    val trackCount: Int,
    val durationMillis: Long,
    /** Absolute artwork URLs of the first (up to) four distinct albums. */
    val coverUrls: List<String>,
    /** 1 liked, -1 disliked, 0 none. */
    val rating: Int,
    val ratingReasons: List<String>,
    val savedPlaylistId: Long?,
) {
    val isActive: Boolean get() = status == "active"
}

/** `ready`, `generating` (HTTP 202), `not_ready` or `disabled`. */
enum class Music032DailyMixStatus { Ready, Generating, NotReady, Disabled }

data class Music032DailyMixes(
    val status: Music032DailyMixStatus,
    val day: String,
    val items: List<Music032DailyMix>,
    val replacementsLeft: Int,
)

/** A mix with its playable tracks (missing files dropped) and per-track markers. */
data class Music032DailyMixDetail(
    val mix: Music032DailyMix,
    val tracks: List<PlexTrackStream>,
    /** Track ids from the explore bucket (rarely or never played). */
    val exploreTrackIds: Set<String>,
    /** Track ids the user excluded from future recommendations. */
    val blockedTrackIds: Set<String>,
)

/** Reasons accepted by `PUT /api/v1/daily-mixes/{id}/rating` (server design §6). */
val Music032DailyMixReasons = listOf("repetitive", "theme", "tracks", "flow", "mismatch")

/** Pure parsers / request builders for the 032 daily mix API. */
internal object Music032DailyMixJson {
    fun parseStatus(value: String): Music032DailyMixStatus = when (value) {
        "ready" -> Music032DailyMixStatus.Ready
        "generating" -> Music032DailyMixStatus.Generating
        "disabled" -> Music032DailyMixStatus.Disabled
        else -> Music032DailyMixStatus.NotReady
    }

    fun parseMix(value: JSONObject, mediaUrl: (String) -> String?): Music032DailyMix = Music032DailyMix(
        id = value.optLong("id"),
        day = value.optString("day"),
        status = value.optString("status"),
        title = value.optString("title").trim().ifBlank { "今日推荐" },
        description = value.optString("description").trim(),
        themeType = value.optString("themeType"),
        themeTypeLabel = value.optString("themeTypeLabel").trim(),
        tags = value.optJSONArray("tags").objects().map {
            Music032DailyMixTag(it.optString("kind"), it.optString("tag"), it.optString("label").trim())
        }.filter { it.label.isNotBlank() },
        trackCount = value.optInt("trackCount"),
        durationMillis = value.optLong("durationMillis"),
        coverUrls = value.optJSONArray("coverUrls").strings().mapNotNull { url -> url.takeIf { it.isNotBlank() }?.let(mediaUrl) },
        rating = value.optInt("rating").coerceIn(-1, 1),
        ratingReasons = value.optJSONArray("ratingReasons").strings(),
        savedPlaylistId = value.optLong("savedPlaylistId").takeIf { it > 0 },
    )

    fun parseList(root: JSONObject, mediaUrl: (String) -> String?): Music032DailyMixes = Music032DailyMixes(
        status = parseStatus(root.optString("status")),
        day = root.optString("day"),
        items = root.optJSONArray("items").objects().map { parseMix(it, mediaUrl) }.filter { it.id > 0 },
        replacementsLeft = root.optInt("replacementsLeft").coerceAtLeast(0),
    )

    fun parseDetail(
        root: JSONObject,
        mediaUrl: (String) -> String?,
        parseTrack: (JSONObject) -> PlexTrackStream,
    ): Music032DailyMixDetail {
        val mix = parseMix(root.optJSONObject("mix") ?: JSONObject(), mediaUrl)
        val tracks = mutableListOf<PlexTrackStream>()
        val explore = mutableSetOf<String>()
        val blocked = mutableSetOf<String>()
        root.optJSONArray("tracks").objects().forEach { item ->
            val trackJson = item.optJSONObject("track") ?: return@forEach
            if (trackJson.optBoolean("missing") || trackJson.optLong("id") <= 0) return@forEach
            val track = parseTrack(trackJson)
            tracks += track
            if (item.optString("bucket") == "explore") explore += track.ratingKey
            if (item.optBoolean("blocked")) blocked += track.ratingKey
        }
        return Music032DailyMixDetail(mix, tracks, explore, blocked)
    }

    fun ratingBody(value: Int, reasons: List<String>): JSONObject = JSONObject()
        .put("value", value.coerceIn(-1, 1))
        .put("reasons", JSONArray().apply {
            if (value == -1) reasons.filter { it in Music032DailyMixReasons }.distinct().forEach { put(it) }
        })

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONArray?.strings(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { optString(it) }.filter { it.isNotBlank() }
    }
}
