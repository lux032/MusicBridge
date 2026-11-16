package com.lux032.musicbridge.music

import org.json.JSONArray
import org.json.JSONObject

/** Category filter for `GET /api/v1/search`. */
enum class SearchCategory(val apiValue: String) {
    Artists("artists"),
    Albums("albums"),
    Tracks("tracks"),
    Composers("composers");

    companion object {
        fun fromApi(value: String?): SearchCategory? =
            entries.firstOrNull { it.apiValue == value?.trim()?.lowercase() }
    }
}

/** Top hit returned in `GET /api/v1/search` overview (`bestMatch`). */
data class SearchBestMatch(
    val category: SearchCategory,
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val albumId: String?,
)

data class SearchPage<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    val limit: Int = 0,
    val offset: Int = 0,
)

/** Answer of `GET /api/v1/search` (overview or single-category page). */
data class UnifiedSearchResults(
    val query: String,
    val category: String,
    val bestMatch: SearchBestMatch?,
    val artists: SearchPage<MusicArtist>,
    val albums: SearchPage<MusicAlbum>,
    val tracks: SearchPage<MusicTrack>,
    val composers: SearchPage<MusicArtist>,
) {
    val isEmpty: Boolean
        get() = bestMatch == null &&
            artists.total <= 0 &&
            albums.total <= 0 &&
            tracks.total <= 0 &&
            composers.total <= 0
}

internal object Music032SearchJson {
    fun parse(
        text: String,
        baseUrl: String,
        mediaToken: String,
        section: LibrarySection,
        sourceId: String,
    ): UnifiedSearchResults {
        val root = JSONObject(text)
        val bestMatch = root.optJSONObject("bestMatch")?.let { obj ->
            parseBestMatch(obj, baseUrl, mediaToken)
        }
        return UnifiedSearchResults(
            query = root.optString("query").trim(),
            category = root.optString("category").trim(),
            bestMatch = bestMatch,
            artists = parsePage(root.optJSONObject("artists")) { item ->
                Music032Json.parseArtist(item, baseUrl, mediaToken, sourceId)
            },
            albums = parsePage(root.optJSONObject("albums")) { item ->
                Music032Json.parseAlbum(item, baseUrl, mediaToken, section, sourceId)
            },
            tracks = parsePage(root.optJSONObject("tracks")) { item ->
                Music032Json.parseTrack(item, baseUrl, mediaToken, sourceId)
            },
            composers = parsePage(root.optJSONObject("composers")) { item ->
                Music032Json.parseArtist(item, baseUrl, mediaToken, sourceId)
            },
        )
    }

    private fun parseBestMatch(
        obj: JSONObject,
        baseUrl: String,
        mediaToken: String,
    ): SearchBestMatch? {
        val category = SearchCategory.fromApi(obj.optString("type")) ?: return null
        val id = obj.optLong("id").takeIf { it > 0L }?.toString() ?: return null
        val title = obj.optString("title").trim().ifBlank { return null }
        val albumId = obj.optLong("albumId").takeIf { it > 0L }?.toString()
        val imageUrl = Music032Json.mediaUrl(obj.optString("imageUrl"), baseUrl, mediaToken)
        return SearchBestMatch(
            category = category,
            id = id,
            title = title,
            subtitle = obj.optString("subtitle").trim(),
            imageUrl = imageUrl,
            albumId = albumId,
        )
    }

    private inline fun <T> parsePage(
        obj: JSONObject?,
        mapper: (JSONObject) -> T?,
    ): SearchPage<T> {
        if (obj == null) return SearchPage()
        val itemsArray: JSONArray? = obj.optJSONArray("items")
        val items = buildList {
            if (itemsArray != null) {
                for (index in 0 until itemsArray.length()) {
                    val item = itemsArray.optJSONObject(index) ?: continue
                    mapper(item)?.let(::add)
                }
            }
        }
        return SearchPage(
            items = items,
            total = obj.optInt("total", items.size),
            limit = obj.optInt("limit", 0),
            offset = obj.optInt("offset", 0),
        )
    }
}
