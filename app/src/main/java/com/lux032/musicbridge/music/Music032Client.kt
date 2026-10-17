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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

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

    /** Performer list of the sync model (album artists and primary track artists, canonical ids). */
    override suspend fun fetchArtists(): PlexArtistsResult = withContext(Dispatchers.IO) {
        val artists = fetchCursorPaged("/api/v1/sync/artists") { parseArtist(it) }.filterNotNull()
        PlexArtistsResult(profile.displayName, artists)
    }

    /**
     * Artist detail as the web artist page sees it. The server follows merge links, so a stale
     * id resolves to the canonical artist ([Music032ArtistDetail.artist] carries the new id).
     */
    suspend fun fetchArtistDetail(id: String, refresh: Boolean = false): Music032ArtistDetail = withContext(Dispatchers.IO) {
        if (refresh) clearArtistDetails()
        val root = artistDetail(id)
        val artist = root.optJSONObject("artist")?.let { parseArtist(it) }
            ?: error("未找到歌手")
        val releases = root.optJSONArray("releases").toObjects()
        Music032ArtistDetail(
            artist = artist,
            albums = releases.map(::parseAlbum),
            appearanceAlbumKeys = releases
                .filter { it.optString("relation") == "appearance" }
                .map { it.optLong("id").toString() }
                .toSet(),
            topTracks = root.optJSONArray("topTracks").toObjects().map(::parseTrack),
            topTracksArePopular = root.optBoolean("topTracksArePopular", false),
            tracksTotal = root.optInt("tracksTotal"),
        )
    }

    /** Name lookup for links that only carry text (old caches, Plex-style names). */
    suspend fun searchArtists(name: String): List<PlexArtist> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(name.trim(), StandardCharsets.UTF_8.name())
        val root = JSONObject(request("GET", "/api/v1/artists?q=$query&limit=50"))
        root.optJSONArray("items").toObjects().mapNotNull { parseArtist(it) }
    }

    override suspend fun fetchArtistTracks(artist: PlexArtist): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val id = fetchArtistDetail(artist.ratingKey).artist.ratingKey
        val tracks = mutableListOf<PlexTrackStream>()
        var offset = 0
        do {
            val page = fetchArtistTracksPage(id, limit = 500, offset = offset)
            tracks += page.items
            offset += page.limit
        } while (page.items.isNotEmpty() && offset < page.total)
        tracks
    }

    override suspend fun fetchArtistAlbums(artistRatingKey: String): List<PlexAlbum> = withContext(Dispatchers.IO) {
        artistDetail(artistRatingKey).optJSONArray("albums").toObjects().map(::parseAlbum)
    }

    suspend fun fetchArtistTracksPage(
        id: String,
        sort: ArtistTrackSort = ArtistTrackSort.Album,
        order: String = sort.defaultOrder,
        limit: Int = 100,
        offset: Int = 0,
    ): Music032ArtistTracksPage {
        require(limit in 1..500 && offset >= 0)
        require(order == "asc" || order == "desc")
        val root = JSONObject(cancellableGet("/api/v1/artists/$id/tracks?sort=${sort.apiValue}&order=$order&limit=$limit&offset=$offset"))
        return Music032ArtistTracksPage(
            root.optJSONArray("items").toObjects().map(::parseTrack),
            root.optInt("total"), root.optInt("limit", limit).coerceAtLeast(1), root.optInt("offset", offset),
        )
    }

    private suspend fun cancellableGet(path: String): String = suspendCancellableCoroutine { continuation ->
        val reference = AtomicReference<HttpURLConnection?>()
        continuation.invokeOnCancellation { reference.get()?.disconnect() }
        playbackEventExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("$baseUrl$path").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $apiToken")
                }
                reference.set(connection)
                if (!continuation.isActive) return@execute
                val status = connection.responseCode
                val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val message = runCatching { JSONObject(response).optJSONObject("error")?.optString("message") }.getOrNull()
                    throw Music032HttpException(status, message.orEmpty().ifBlank { "HTTP $status" })
                }
                continuation.resumeWith(Result.success(response))
            } catch (error: Exception) {
                continuation.resumeWith(Result.failure(error))
            } finally {
                connection?.disconnect()
            }
        }
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
                val response = cancellableGet("/api/v1/artists/$id")
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

    internal suspend fun ensureCapabilities(): Music032Capabilities? = withContext(Dispatchers.IO) {
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

    /**
     * Creates a playlist. Tracks are added in the same server transaction; ids the server no
     * longer knows are skipped (opt-in `invalidTracks: skip`) instead of failing the create.
     * `invalidTracks` is only sent with tracks: the server rejects unknown JSON fields, so an
     * empty create stays compatible with servers that predate the skip policy.
     */
    suspend fun createPlaylist(
        name: String,
        description: String,
        trackIds: List<String> = emptyList(),
    ): Music032PlaylistCreated = withContext(Dispatchers.IO) {
        val root = JSONObject(request("POST", "/api/v1/playlists", Music032PlaylistRequests.create(name, description, trackIds)))
        Music032PlaylistRequests.parseCreated(root) { parsePlaylist(it) }
    }

    suspend fun updatePlaylist(id: String, name: String, description: String): PlexPlaylist = withContext(Dispatchers.IO) {
        val body = JSONObject().put("name", name.trim()).put("description", description.trim())
        parsePlaylist(JSONObject(request("PATCH", "/api/v1/playlists/${id.playlistId()}", body)))
    }

    suspend fun deletePlaylist(id: String) = withContext(Dispatchers.IO) {
        request("DELETE", "/api/v1/playlists/${id.playlistId()}")
        Unit
    }

    /** Appends tracks (first occurrence order, duplicates skipped server-side). */
    suspend fun appendPlaylistTracks(id: String, trackIds: List<String>): Music032PlaylistItemResult = withContext(Dispatchers.IO) {
        val body = JSONObject().put("trackIds", Music032PlaylistRequests.trackIdArray(trackIds))
        Music032PlaylistRequests.parseItemResult(JSONObject(request("POST", "/api/v1/playlists/${id.playlistId()}/items/append", body)))
    }

    /** Appends a whole album in its effective disc/track order. */
    suspend fun appendPlaylistAlbum(id: String, albumId: String): Music032PlaylistItemResult = withContext(Dispatchers.IO) {
        val album = albumId.toLongOrNull()?.takeIf { it > 0 } ?: error("无效的专辑 ID")
        val body = JSONObject().put("albumId", album)
        Music032PlaylistRequests.parseItemResult(JSONObject(request("POST", "/api/v1/playlists/${id.playlistId()}/items/append", body)))
    }

    /** Removes tracks; a stale [expectedRevision] answers 409 `playlist_conflict` without mutation. */
    suspend fun removePlaylistTracks(id: String, trackIds: List<String>, expectedRevision: Long): Music032PlaylistItemResult = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("trackIds", Music032PlaylistRequests.trackIdArray(trackIds))
            .put("expectedRevision", expectedRevision)
        Music032PlaylistRequests.parseItemResult(JSONObject(request("POST", "/api/v1/playlists/${id.playlistId()}/items/remove", body)))
    }

    /** Additive undo: re-inserts one track at [index] (clamped server-side). */
    suspend fun insertPlaylistTrack(id: String, trackId: String, index: Int): Music032PlaylistItemResult = withContext(Dispatchers.IO) {
        val track = trackId.toLongOrNull() ?: error("无效的曲目 ID")
        val body = JSONObject().put("trackId", track).put("index", index.coerceAtLeast(0))
        Music032PlaylistRequests.parseItemResult(JSONObject(request("POST", "/api/v1/playlists/${id.playlistId()}/items/insert", body)))
    }

    private fun String.playlistId(): Long = toLongOrNull()?.takeIf { it > 0 } ?: error("无效的歌单 ID")

    // --- Daily mixes (server feature `dailyMixes`) ---------------------------------------

    /** Today's mixes; the first call of a day may wait for generation and answer `generating`. */
    suspend fun fetchDailyMixes(): Music032DailyMixes = withContext(Dispatchers.IO) {
        Music032DailyMixJson.parseList(JSONObject(request("GET", "/api/v1/daily-mixes")), ::mediaUrl)
    }

    suspend fun fetchDailyMix(id: Long): Music032DailyMixDetail = withContext(Dispatchers.IO) {
        Music032DailyMixJson.parseDetail(JSONObject(request("GET", "/api/v1/daily-mixes/$id")), ::mediaUrl, ::parseTrack)
    }

    /** [value] 1 like, -1 dislike (with optional reasons), 0 clears the rating. */
    suspend fun rateDailyMix(id: Long, value: Int, reasons: List<String> = emptyList()): Music032DailyMix = withContext(Dispatchers.IO) {
        val body = Music032DailyMixJson.ratingBody(value, reasons)
        Music032DailyMixJson.parseMix(JSONObject(request("PUT", "/api/v1/daily-mixes/$id/rating", body)), ::mediaUrl)
    }

    /** Replaces one of today's mixes; 429 `replacement_limit` when the daily budget is spent. */
    suspend fun regenerateDailyMix(id: Long): Music032DailyMix = withContext(Dispatchers.IO) {
        Music032DailyMixJson.parseMix(JSONObject(request("POST", "/api/v1/daily-mixes/$id/regenerate")), ::mediaUrl)
    }

    /** Copies the mix into a new ordinary playlist (default name “title · day”). */
    suspend fun saveDailyMix(id: Long): PlexPlaylist = withContext(Dispatchers.IO) {
        val root = JSONObject(request("POST", "/api/v1/daily-mixes/$id/save"))
        parsePlaylist(root.optJSONObject("playlist") ?: root)
    }

    /** Excludes a track from (or returns it to) future recommendations. */
    suspend fun setRecommendationBlock(trackId: String, blocked: Boolean) = withContext(Dispatchers.IO) {
        val id = trackId.toLongOrNull()?.takeIf { it > 0 } ?: error("无效的曲目 ID")
        request(if (blocked) "PUT" else "DELETE", "/api/v1/tracks/$id/recommendation-block")
        Unit
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

    /**
     * Playback sessions (plan C, apiRevision 3): the legacy timeline/scrobble endpoints
     * were removed server-side and answer 410. 032 playback is reported exclusively
     * through [Music032PlaybackReporter]; this method is intentionally unsupported and
     * must never fall back to the removed endpoints.
     */
    override suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        Log.w(
            "Music032Client",
            "reportTimeline() is unsupported for 032 (playback sessions replaced it); call ignored",
        )
    }

    /** See [reportTimeline]: counting is derived server-side from session events now. */
    override suspend fun scrobble(
        ratingKey: String,
        positionMillis: Long,
        durationMillis: Long?,
    ) {
        Log.w(
            "Music032Client",
            "scrobble() is unsupported for 032 (playback sessions replaced it); call ignored",
        )
    }

    /** Transport used by [Music032PlaybackReporter] to POST one playback session event. */
    internal val playbackEventTransport = PlaybackEventTransport { payload -> postPlaybackEvent(payload) }

    // Daemon threads: a dying process never joins them; cancellation disconnects the
    // in-flight connection instead of interrupting the worker.
    private val playbackEventExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "music032-playback-events").apply { isDaemon = true }
    }

    /**
     * POSTs one session event without throwing on protocol statuses: the reporter needs
     * the exact 404/409 codes to drive its recovery rules. Status 0 means the request
     * never produced a response (network failure, cancellation or deadline). The call is
     * genuinely cancellable: cancelling the coroutine disconnects the connection, which
     * unblocks the worker thread instead of waiting out connect+read timeouts on the
     * calling thread (this is what bounds app-teardown reporting). Short timeouts keep
     * the reporter's serialized queue from stalling behind a dead connection.
     */
    internal suspend fun postPlaybackEvent(payload: JSONObject): PlaybackEventHttpResult =
        suspendCancellableCoroutine { continuation ->
            val connectionReference = AtomicReference<HttpURLConnection?>()
            continuation.invokeOnCancellation {
                connectionReference.get()?.let { connection -> runCatching { connection.disconnect() } }
            }
            playbackEventExecutor.execute {
                try {
                    val connection = (URL("$baseUrl/api/v1/playback/events").openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = PLAYBACK_EVENT_CONNECT_TIMEOUT_MILLIS
                        readTimeout = PLAYBACK_EVENT_READ_TIMEOUT_MILLIS
                        instanceFollowRedirects = true
                        doInput = true
                        doOutput = true
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Authorization", "Bearer $apiToken")
                    }
                    connectionReference.set(connection)
                    connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload.toString()) }
                    val status = connection.responseCode
                    val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                        ?.bufferedReader(StandardCharsets.UTF_8)
                        ?.use { it.readText() }
                    continuation.resume(PlaybackEventHttpResult(status, body))
                } catch (_: Exception) {
                    // Includes the SocketException caused by a cancellation disconnect.
                    continuation.resume(PlaybackEventHttpResult(0, null))
                }
            }
        }

    private fun parseAlbum(value: JSONObject) = Music032Json.parseAlbum(value, baseUrl, mediaToken, section, sourceId)
    private fun parseTrack(value: JSONObject) = Music032Json.parseTrack(value, baseUrl, mediaToken, sourceId)
    private fun parsePlaylist(value: JSONObject) = Music032Json.parsePlaylist(value, baseUrl, mediaToken, sourceId)
    private fun parseArtist(value: JSONObject) = Music032Json.parseArtist(value, baseUrl, mediaToken, sourceId)
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
            val error = runCatching { JSONObject(response).optJSONObject("error") }.getOrNull()
            val message = error?.optString("message").orEmpty().ifBlank { response.take(300) }
            throw Music032HttpException(
                status,
                "032 Music Server HTTP $status：$message",
                code = error?.optString("code")?.takeIf { it.isNotBlank() },
            )
        }
        return response
    }

    private fun JSONArray?.toObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }



    private companion object {
        const val PAGE_SIZE = 500
        const val SYNC_PAGE_SIZE = 1000
        const val PLAYBACK_EVENT_CONNECT_TIMEOUT_MILLIS = 5_000
        const val PLAYBACK_EVENT_READ_TIMEOUT_MILLIS = 5_000
    }
}

internal fun shouldCacheArtistDetail(requestGeneration: Long, currentGeneration: Long): Boolean =
    requestGeneration == currentGeneration

internal class Music032HttpException(
    val status: Int,
    message: String,
    val code: String? = null,
) : IllegalStateException(message)

enum class MediaTokenProbeResult { OK, INVALID, UNKNOWN }

internal fun mediaTokenProbeStatus(status: Int): MediaTokenProbeResult = when (status) {
    404 -> MediaTokenProbeResult.OK
    401, 403 -> MediaTokenProbeResult.INVALID
    else -> MediaTokenProbeResult.UNKNOWN
}

internal fun trackDetailNotFound(status: Int): Boolean = status == 404

/** Online artist page payload: canonical artist, web-style releases and performed tracks. */
data class Music032ArtistDetail(
    val artist: PlexArtist,
    val albums: List<PlexAlbum>,
    val appearanceAlbumKeys: Set<String>,
    val topTracks: List<PlexTrackStream>,
    val topTracksArePopular: Boolean = false,
    val tracksTotal: Int,
)

data class Music032ArtistTracksPage(
    val items: List<PlexTrackStream>, val total: Int, val limit: Int, val offset: Int,
)

enum class ArtistTrackSort(val apiValue: String, val defaultOrder: String) {
    Album("album", "asc"), Plays("plays", "desc"), Recent("recent", "desc"),
    Title("title", "asc"), Added("added", "desc"), Duration("duration", "asc"),
}
