package com.lux032.musicbridge.music

import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexAlbumsResult
import com.lux032.musicbridge.plex.PlexArtist
import com.lux032.musicbridge.plex.PlexArtistsResult
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexPlaylistsResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant

class Music032Client(
    private val profile: MusicServerProfile,
) : MusicSourceClient {
    override val sourceId: String = profile.id
    private val baseUrl = profile.baseUrl.trim().trimEnd('/')
    private val apiToken = profile.token.trim()
    private val mediaToken = profile.mediaToken.trim().ifBlank { apiToken }
    private val section by lazy { PlexSection("032", profile.displayName, "artist") }

    init {
        require(baseUrl.isNotBlank()) { "缺少 032 Music Server 地址。" }
        require(apiToken.isNotBlank()) { "缺少 032 Music Server API Token。" }
    }

    override suspend fun fetchAlbums(): PlexAlbumsResult = withContext(Dispatchers.IO) {
        val albums = runCatching {
            fetchCursorPaged("/api/v1/sync/albums") { parseAlbum(it) }
        }.getOrElse {
            fetchPaged("/api/v1/albums") { parseAlbum(it) }
        }
        PlexAlbumsResult(profile.displayName, listOf(section), albums)
    }

    override suspend fun fetchArtists(): PlexArtistsResult = withContext(Dispatchers.IO) {
        val artists = fetchPaged("/api/v1/artists") { value ->
            PlexArtist(
                ratingKey = value.optLong("id").toString(),
                title = value.optString("name").trim(),
                thumbUrl = mediaUrl(value.optString("imageUrl")),
                sourceId = sourceId,
            )
        }.filter { it.title.isNotBlank() }
        PlexArtistsResult(profile.displayName, artists)
    }

    override suspend fun fetchFavoriteTracks(): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        fetchPaged("/api/v1/favorites/tracks") { parseTrack(it) }
    }

    override suspend fun fetchAlbumTrackStreams(album: PlexAlbum): PlexAlbumTracksResult = withContext(Dispatchers.IO) {
        val body = request("GET", "/api/v1/albums/${album.ratingKey}")
        val root = JSONObject(body)
        val resolvedAlbum = root.optJSONObject("album")?.let(::parseAlbum) ?: album
        val tracks = root.optJSONArray("tracks").toObjects().map(::parseTrack)
        PlexAlbumTracksResult(profile.displayName, resolvedAlbum, tracks)
    }

    override suspend fun fetchAllAlbumTracks(
        albums: List<PlexAlbum>,
        onProgress: (current: Int, total: Int) -> Unit,
    ): Map<String, List<PlexTrackStream>> = withContext(Dispatchers.IO) {
        runCatching {
            val tracks = fetchCursorPaged("/api/v1/sync/tracks", onProgress) { parseTrack(it) }
            tracks.groupBy { it.albumRatingKey.orEmpty() }.filterKeys(String::isNotBlank)
        }.getOrElse {
            buildMap {
                albums.forEachIndexed { index, album ->
                    runCatching { fetchAlbumTrackStreams(album).tracks }
                        .onSuccess { put(album.ratingKey, it) }
                    onProgress(index + 1, albums.size)
                }
            }
        }
    }

    override suspend fun fetchPlaylists(): PlexPlaylistsResult = withContext(Dispatchers.IO) {
        val playlists = fetchPaged("/api/v1/playlists") { parsePlaylist(it) }
        PlexPlaylistsResult(profile.displayName, playlists)
    }

    override suspend fun fetchPlaylistTracks(playlist: PlexPlaylist): PlexPlaylistTracksResult = withContext(Dispatchers.IO) {
        val root = JSONObject(request("GET", "/api/v1/playlists/${playlist.ratingKey}"))
        val resolvedPlaylist = root.optJSONObject("playlist")?.let(::parsePlaylist) ?: playlist
        val tracks = root.optJSONArray("tracks").toObjects().map(::parseTrack)
        PlexPlaylistTracksResult(profile.displayName, resolvedPlaylist, tracks)
    }

    override suspend fun setAlbumFavorite(ratingKey: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        request(if (isFavorite) "PUT" else "DELETE", "/api/v1/albums/$ratingKey/favorite")
        Unit
    }

    override suspend fun setTrackFavorite(ratingKey: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        request(if (isFavorite) "PUT" else "DELETE", "/api/v1/tracks/$ratingKey/favorite")
        Unit
    }

    override suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("trackId", ratingKey.toLongOrNull() ?: error("无效的 032 曲目 ID。"))
            .put("state", state)
            .put("positionMillis", timeMillis.coerceAtLeast(0L))
            .put("durationMillis", durationMillis?.coerceAtLeast(0L) ?: 0L)
            .put("continuing", continuing)
        request("POST", "/api/v1/playback/timeline", payload)
        Unit
    }

    override suspend fun scrobble(
        ratingKey: String,
        positionMillis: Long,
        durationMillis: Long?,
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("trackId", ratingKey.toLongOrNull() ?: error("无效的 032 曲目 ID。"))
            .put("positionMillis", positionMillis.coerceAtLeast(0L))
            .put("durationMillis", durationMillis?.coerceAtLeast(0L) ?: 0L)
        request("POST", "/api/v1/playback/scrobble", payload)
        Unit
    }

    private fun parseAlbum(value: JSONObject): PlexAlbum {
        val year = value.optInt("year").takeIf { it > 0 }
        return PlexAlbum(
            ratingKey = value.optLong("id").toString(),
            title = value.optString("title").trim().ifBlank { "未知专辑" },
            artistName = value.optString("artist").trim().ifBlank { null },
            year = year,
            thumbUrl = mediaUrl(value.optString("artworkUrl")),
            userRating = if (value.optBoolean("isFavorite")) 10f else null,
            addedAtEpochSeconds = parseEpochSeconds(value.optString("addedAt")),
            lastViewedAtEpochSeconds = parseEpochSeconds(value.optString("lastPlayedAt")),
            section = section,
            sourceId = sourceId,
        )
    }

    private fun parseTrack(value: JSONObject): PlexTrackStream {
        val id = value.optLong("id").toString()
        val relativeStream = value.optString("streamUrl").ifBlank { "/api/v1/tracks/$id/stream" }
        return PlexTrackStream(
            ratingKey = id,
            title = value.optString("title").trim().ifBlank { "未知曲目" },
            streamUrl = mediaUrl(relativeStream).orEmpty(),
            partKey = relativeStream,
            durationMillis = value.optLong("durationMillis").takeIf { it > 0L },
            userRating = if (value.optBoolean("isFavorite")) 10f else null,
            albumRatingKey = value.optLong("albumId").takeIf { it > 0L }?.toString(),
            albumTitle = value.optString("album").trim().ifBlank { null },
            artistName = value.optString("artist").trim().ifBlank { null },
            thumbUrl = mediaUrl(value.optString("artworkUrl")),
            sourceId = sourceId,
        )
    }

    private fun parsePlaylist(value: JSONObject): PlexPlaylist = PlexPlaylist(
        ratingKey = value.optLong("id").toString(),
        title = value.optString("name").trim().ifBlank { "未命名歌单" },
        thumbUrl = mediaUrl(value.optString("artworkUrl")),
        leafCount = value.optInt("itemCount"),
        sourceId = sourceId,
    )

    private fun <T> fetchCursorPaged(
        path: String,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
        parser: (JSONObject) -> T,
    ): List<T> {
        val result = mutableListOf<T>()
        var cursor = "0"
        while (true) {
            val separator = if ('?' in path) '&' else '?'
            val root = JSONObject(request("GET", "$path${separator}limit=$SYNC_PAGE_SIZE&cursor=$cursor"))
            val page = root.optJSONArray("items").toObjects()
            result += page.map(parser)
            val total = root.optInt("total", result.size)
            onProgress(result.size.coerceAtMost(total), total)
            if (!root.optBoolean("hasMore") || page.isEmpty()) break
            cursor = root.optString("nextCursor").ifBlank { break }
        }
        return result
    }

    private fun <T> fetchPaged(path: String, parser: (JSONObject) -> T): List<T> {
        val result = mutableListOf<T>()
        var offset = 0
        while (true) {
            val separator = if ('?' in path) '&' else '?'
            val root = JSONObject(request("GET", "$path${separator}limit=$PAGE_SIZE&offset=$offset"))
            val page = root.optJSONArray("items").toObjects()
            result += page.map(parser)
            val total = root.optLong("total", result.size.toLong())
            if (page.size < PAGE_SIZE || result.size >= total) break
            offset += page.size
        }
        return result
    }

    private fun request(method: String, path: String, body: JSONObject? = null): String {
        val target = if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl$path"
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            doInput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $apiToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(body.toString()) }
            }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(response).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty().ifBlank { response.take(300) }
                error("032 Music Server HTTP $status：$message")
            }
            response
        } finally {
            connection.disconnect()
        }
    }

    private fun mediaUrl(path: String): String? {
        val normalized = path.trim()
        if (normalized.isEmpty()) return null
        val isAbsolute = normalized.startsWith("http://") || normalized.startsWith("https://")
        val absolute = if (isAbsolute) {
            normalized
        } else {
            "$baseUrl/${normalized.trimStart('/')}"
        }
        if (isAbsolute && !isSameOrigin(absolute, baseUrl)) return absolute
        if (mediaToken.isBlank()) return absolute
        val separator = if ('?' in absolute) '&' else '?'
        return "$absolute${separator}mediaToken=${mediaToken.urlEncode()}"
    }

    private fun isSameOrigin(first: String, second: String): Boolean = runCatching {
        val firstUrl = URL(first)
        val secondUrl = URL(second)
        firstUrl.protocol.equals(secondUrl.protocol, ignoreCase = true) &&
            firstUrl.host.equals(secondUrl.host, ignoreCase = true) &&
            firstUrl.effectivePort() == secondUrl.effectivePort()
    }.getOrDefault(false)

    private fun URL.effectivePort(): Int = if (port >= 0) port else defaultPort

    private fun parseEpochSeconds(value: String): Long? =
        value.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it).epochSecond }.getOrNull() }

    private fun JSONArray?.toObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, StandardCharsets.UTF_8.name())

    private companion object {
        const val PAGE_SIZE = 500
        const val SYNC_PAGE_SIZE = 1000
    }
}
