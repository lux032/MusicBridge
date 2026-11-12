package com.lux032.musicbridge.music

import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack
import org.json.JSONObject
import org.json.JSONArray

data class Music032Work(
    val id: Long,
    val title: String,
    val translatedTitle: String = "",
    val type: String = "",
    val year: Int = 0,
    val artworkUrl: String? = null,
    val trackCount: Int = 0,
    val roleCounts: List<Pair<String, Int>> = emptyList(),
    val seriesId: Long? = null,
    val seriesTitle: String = "",
    val seriesPosition: Int = 0,
)

data class Music032TrackWork(val work: Music032Work, val role: String)
data class Music032WorkTrack(val track: MusicTrack, val role: String, val sequence: Int, val source: String) {
    // Album-derived soundtrack tracks remain available through album cards and Play all.
    val isSong: Boolean get() = source != "album" && role in WorkSongRoles
}
internal val WorkSongRoles = setOf("op", "ed", "theme", "insert", "character", "image_song")

data class Music032WorkAlbum(val album: MusicAlbum, val albumRole: String, val trackRoles: List<String>)
data class Music032WorkRotation(
    val items: List<Music032Work> = emptyList(),
    val batch: Int = 0,
    val pages: Int = 0,
    val generation: String = "",
    val renewed: Boolean = false,
)
data class Music032WorkDetail(
    val work: Music032Work,
    val tracks: List<Music032WorkTrack>,
    val albums: List<Music032WorkAlbum>,
)
data class Music032WorkSeries(val title: String, val members: List<Music032Work>)

/** Wire adapters shared by the home shelf, player links and work/series details. */
internal object Music032WorksJson {
    fun work(value: JSONObject, mediaUrl: (String) -> String?, detail: Boolean = false): Music032Work {
        val series = value.optJSONObject("series")
        // On detail responses posterUrl is the upstream editing URL, not the media endpoint.
        val art = if (detail) value.optString("posterApiUrl") else
            listOf("artworkUrl", "posterUrl", "coverUrl").firstNotNullOfOrNull { key ->
                value.optString(key).takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()
        return Music032Work(
            id = value.getLong("id"), title = value.optString("title"),
            translatedTitle = value.optString("translatedTitle"),
            type = value.optString("workType").ifBlank { value.optString("type") },
            year = value.optInt("year"), artworkUrl = mediaUrl(art), trackCount = value.optInt("trackCount"),
            roleCounts = value.optJSONArray("roleCounts").toObjects().map { it.optString("role") to it.optInt("count") },
            seriesId = series?.optLong("id")?.takeIf { it > 0 } ?: value.optLong("seriesId").takeIf { it > 0 },
            seriesTitle = series?.optString("title").orEmpty(), seriesPosition = series?.optInt("position") ?: 0,
        )
    }

    fun rotation(root: JSONObject, mediaUrl: (String) -> String?) = Music032WorkRotation(
        items = root.optJSONArray("items").toObjects().map { work(it, mediaUrl) },
        batch = root.optInt("batch").coerceAtLeast(0), pages = root.optInt("pages").coerceAtLeast(0),
        generation = root.optString("generation"), renewed = root.optBoolean("renewed"),
    )

    fun trackWorks(root: JSONObject, mediaUrl: (String) -> String?): List<Music032TrackWork> =
        root.optJSONArray("items").toObjects().map { Music032TrackWork(work(it, mediaUrl), it.optString("role")) }
            .sortedBy { rolePriority(it.role) }
            .distinctBy { it.work.id }

    fun tracks(root: JSONObject, parseTrack: (JSONObject) -> MusicTrack): List<Music032WorkTrack> =
        root.optJSONArray("items").toObjects().map {
            Music032WorkTrack(parseTrack(it), it.optString("role"), it.optInt("sequence"), it.optString("source"))
        }

    fun albums(root: JSONObject, parseAlbum: (JSONObject) -> MusicAlbum): List<Music032WorkAlbum> =
        root.optJSONArray("items").toObjects().map { value ->
            // AlbumWork has albumId, whereas the library's Album adapter expects id.
            val album = JSONObject(value.toString()).put("id", value.getLong("albumId"))
            Music032WorkAlbum(parseAlbum(album), value.optString("albumRole").ifBlank { value.optString("role") },
                value.optJSONArray("trackRoles").let { roles ->
                    (0 until (roles?.length() ?: 0)).map { roles!!.optString(it) }
                })
        }

    fun series(root: JSONObject, mediaUrl: (String) -> String?) = Music032WorkSeries(
        root.optJSONObject("series")?.optString("title").orEmpty(),
        root.optJSONArray("members").toObjects().map { work(it, mediaUrl) },
    )

    private fun JSONArray?.toObjects(): List<JSONObject> =
        (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }

    private fun rolePriority(role: String): Int =
        listOf("op", "ed", "theme", "insert", "character", "image_song", "ost", "other").indexOf(role)
            .takeIf { it >= 0 } ?: Int.MAX_VALUE
}

internal fun List<Music032WorkTrack>.uniqueStreams(): List<MusicTrack> =
    map { it.track }.distinctBy { it.sourceId to it.ratingKey }
