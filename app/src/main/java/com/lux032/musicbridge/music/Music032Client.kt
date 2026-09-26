package com.lux032.musicbridge.music

import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

class Music032Client(
    private val profile: MusicServerProfile,
) : MusicSourceClient {
    override val sourceId: String = profile.id
    private val baseUrl = profile.baseUrl.trim().trimEnd('/')
    private val apiToken = profile.token.trim()
    private val mediaToken = profile.mediaToken.trim().ifBlank { apiToken }
    private val capabilitiesKey = Music032CapabilitiesStore.Key.of(baseUrl, apiToken)
    private val artistDetails = mutableMapOf<String, Pair<Long, String>>()
    private val artistRequests = mutableMapOf<String, CompletableDeferred<String>>()
    private val artistGeneration = AtomicLong()
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
                userRating = if (value.optBoolean("isFavorite")) 10f else null,
                sourceId = sourceId,
            )
        }.filter { it.title.isNotBlank() }
        PlexArtistsResult(profile.displayName, artists)
    }

    override suspend fun fetchArtistTracks(artist: PlexArtist): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val root = artistDetail(artist.ratingKey)
        val tracks = root.optJSONArray("tracks").toObjects().map(::parseTrack)
        if (root.optInt("tracksTotal") > tracks.size) Log.w("Music032Client", "Artist tracks truncated: ${artist.ratingKey} (${tracks.size}/${root.optInt("tracksTotal")})")
        tracks
    }

    override suspend fun fetchArtistAlbums(artistRatingKey: String): List<PlexAlbum> = withContext(Dispatchers.IO) {
        artistDetail(artistRatingKey).optJSONArray("albums").toObjects().map(::parseAlbum)
    }

    private suspend fun artistDetail(id: String): JSONObject {
        val now = System.currentTimeMillis()
        val (pending, owner, generation) = synchronized(artistDetails) {
            artistDetails.entries.removeAll { now - it.value.first >= 30_000 }
            artistDetails[id]?.second?.let { return JSONObject(it) }
            val request = artistRequests[id]?.let { it to false }
                ?: (CompletableDeferred<String>().also { artistRequests[id] = it } to true)
            Triple(request.first, request.second, artistGeneration.get())
        }
        val text = if (owner) {
            try {
                val response = request("GET", "/api/v1/artists/$id")
                synchronized(artistDetails) {
                    if (shouldCacheArtistDetail(generation, artistGeneration.get())) {
                        artistDetails[id] = System.currentTimeMillis() to response
                    }
                    if (artistRequests[id] === pending) artistRequests.remove(id)
                }
                pending.complete(response)
                response
            } catch (error: Throwable) {
                synchronized(artistDetails) { if (artistRequests[id] === pending) artistRequests.remove(id) }
                pending.completeExceptionally(error)
                throw error
            }
        } else pending.await()
        return JSONObject(text)
    }

    private fun clearArtistDetails() = synchronized(artistDetails) {
        artistGeneration.incrementAndGet()
        artistDetails.clear()
        artistRequests.clear()
    }

    internal fun capabilities(): Music032Capabilities? = Music032CapabilitiesStore.snapshot(capabilitiesKey)

    internal suspend fun ensureCapabilities(): Music032Capabilities = withContext(Dispatchers.IO) {
        Music032CapabilitiesStore.ensure(capabilitiesKey) { request("GET", "/api/v1/capabilities") }
    }

    suspend fun probeMediaToken(): MediaTokenProbeResult = withContext(Dispatchers.IO) {
        try {
            val url = Music032Json.mediaUrl("/api/v1/tracks/0/stream", baseUrl, mediaToken)!!
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = false
                mediaTokenProbeStatus(connection.responseCode)
            } finally { connection.disconnect() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            MediaTokenProbeResult.UNKNOWN
        }
    }

    override suspend fun fetchTrackStream(ratingKey: String): PlexTrackStream? = withContext(Dispatchers.IO) {
        try { parseTrack(JSONObject(request("GET", "/api/v1/tracks/$ratingKey"))) }
        catch (error: Music032HttpException) { if (trackDetailNotFound(error.status)) null else throw error }
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
        clearArtistDetails()
        Unit
    }

    override suspend fun setTrackFavorite(ratingKey: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        request(if (isFavorite) "PUT" else "DELETE", "/api/v1/tracks/$ratingKey/favorite")
        clearArtistDetails()
        Unit
    }

    override suspend fun setArtistFavorite(ratingKey: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        request(if (isFavorite) "PUT" else "DELETE", "/api/v1/artists/$ratingKey/favorite")
        clearArtistDetails()
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

    private fun parseAlbum(value: JSONObject) = Music032Json.parseAlbum(value, baseUrl, mediaToken, section, sourceId)
    private fun parseTrack(value: JSONObject) = Music032Json.parseTrack(value, baseUrl, mediaToken, sourceId)
    private fun parsePlaylist(value: JSONObject) = Music032Json.parsePlaylist(value, baseUrl, mediaToken, sourceId)
    private fun mediaUrl(path: String) = Music032Json.mediaUrl(path, baseUrl, mediaToken)

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
            throw Music032HttpException(status, "032 Music Server HTTP $status：$message")
        }
        return response
    }

    private fun JSONArray?.toObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }


    private class Music032HttpException(val status: Int, message: String) : IllegalStateException(message)

    private companion object {
        const val PAGE_SIZE = 500
        const val SYNC_PAGE_SIZE = 1000
    }
}

internal fun shouldCacheArtistDetail(requestGeneration: Long, currentGeneration: Long): Boolean =
    requestGeneration == currentGeneration

enum class MediaTokenProbeResult { OK, INVALID, UNKNOWN }

internal fun mediaTokenProbeStatus(status: Int): MediaTokenProbeResult = when (status) {
    404 -> MediaTokenProbeResult.OK
    401, 403 -> MediaTokenProbeResult.INVALID
    else -> MediaTokenProbeResult.UNKNOWN
}

internal fun trackDetailNotFound(status: Int): Boolean = status == 404
