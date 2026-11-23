package com.lux032.musicbridge.music

import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.core.AppHttpClient
import com.lux032.musicbridge.core.awaitResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
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
    private val section by lazy { LibrarySection("032", profile.displayName, "artist") }

    init {
        require(baseUrl.isNotBlank()) { "缺少 032 Music Server 地址。" }
        require(apiToken.isNotBlank()) { "缺少 032 Music Server API Token。" }
    }

    override suspend fun fetchAlbums(): AlbumsResult = withContext(Dispatchers.IO) {
        val albums = runCatching {
            fetchCursorPaged("/api/v1/sync/albums") { parseAlbum(it) }
        }.getOrElse {
            fetchPaged("/api/v1/albums") { parseAlbum(it) }
        }
        AlbumsResult(profile.displayName, listOf(section), albums)
    }

    /** Performer list of the sync model (album artists and primary track artists, canonical ids). */
    override suspend fun fetchArtists(): ArtistsResult = withContext(Dispatchers.IO) {
        val artists = fetchCursorPaged("/api/v1/sync/artists") { parseArtist(it) }.filterNotNull()
        ArtistsResult(profile.displayName, artists)
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

    /** Name lookup for links that only carry text (old caches, name-only links). */
    suspend fun searchArtists(name: String): List<MusicArtist> = withContext(Dispatchers.IO) {
        val query = URLEncoder.encode(name.trim(), StandardCharsets.UTF_8.name())
        val root = JSONObject(request("GET", "/api/v1/artists?q=$query&limit=50"))
        root.optJSONArray("items").toObjects().mapNotNull { parseArtist(it) }
    }

    /** Unified local-library search (`GET /api/v1/search`), matching 032player-rs. */
    suspend fun searchUnified(
        query: String,
        category: SearchCategory? = null,
        offset: Int = 0,
        limit: Int = 50,
    ): UnifiedSearchResults {
        val trimmed = query.trim().take(200)
        val params = buildList {
            if (trimmed.isNotEmpty()) {
                val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
                add("q=$encoded")
            }
            if (category != null) {
                add("category=${category.apiValue}")
                add("offset=${offset.coerceAtLeast(0)}")
                add("limit=${limit.coerceAtLeast(1)}")
            }
        }.joinToString("&")
        val path = if (params.isEmpty()) "/api/v1/search" else "/api/v1/search?$params"
        val body = cancellableGet(path)
        return Music032SearchJson.parse(body, baseUrl, mediaToken, section, sourceId)
    }

    override suspend fun fetchArtistTracks(artist: MusicArtist): List<MusicTrack> = withContext(Dispatchers.IO) {
        val id = fetchArtistDetail(artist.ratingKey).artist.ratingKey
        val tracks = mutableListOf<MusicTrack>()
        var offset = 0
        do {
            val page = fetchArtistTracksPage(id, limit = 500, offset = offset)
            tracks += page.items
            offset += page.limit
        } while (page.items.isNotEmpty() && offset < page.total)
        tracks
    }

    override suspend fun fetchArtistAlbums(artistRatingKey: String): List<MusicAlbum> = withContext(Dispatchers.IO) {
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

    suspend fun fetchHomeWorks(batch: Int? = null, generation: String = ""): Music032WorkRotation {
        val path = if (batch == null) "/api/v1/home" else {
            val encoded = URLEncoder.encode(generation, StandardCharsets.UTF_8.name())
            "/api/v1/home/works?type=all&batch=${batch.coerceAtLeast(0)}&generation=$encoded"
        }
        val root = JSONObject(cancellableGet(path))
        return Music032WorksJson.rotation(if (batch == null) root.optJSONObject("works") ?: JSONObject() else root, ::mediaUrl)
    }

    suspend fun fetchTrackWorks(id: String): List<Music032TrackWork> {
        require(id.toLongOrNull() != null)
        return Music032WorksJson.trackWorks(JSONObject(cancellableGet("/api/v1/tracks/$id/works")), ::mediaUrl)
    }

    /** Backstage credits (lyricist, composer, arranger, producer) from the track detail. */
    suspend fun fetchTrackCredits(id: String): List<TrackCredit> {
        require(id.toLongOrNull() != null)
        return Music032Json.parseTrackCredits(JSONObject(cancellableGet("/api/v1/tracks/$id")))
    }

    /** Tracks the artist wrote, composed, arranged or produced, optionally one [role] only. */
    suspend fun fetchArtistCredits(id: String, role: String? = null, limit: Int = 30, offset: Int = 0): Music032ArtistCreditsPage {
        require(id.toLongOrNull() != null && limit in 1..500 && offset >= 0)
        val roleQuery = role?.let { "&role=" + URLEncoder.encode(it, StandardCharsets.UTF_8.name()) }.orEmpty()
        val root = JSONObject(cancellableGet("/api/v1/artists/$id/credits?limit=$limit&offset=$offset$roleQuery"))
        val items = root.optJSONArray("items").toObjects().map { item ->
            val roles = Music032Json.parseTrackCredits(item)
                .filter { credit -> credit.artists.any { it.id == id } }
                .map(TrackCredit::role)
            Music032CreditTrack(parseTrack(item), roles)
        }
        val roles = root.optJSONArray("roles").toObjects()
            .mapNotNull { it.optString("role").ifBlank { null }?.let { name -> name to it.optInt("count") } }
            .filter { it.second > 0 }
        return Music032ArtistCreditsPage(items, root.optInt("total"), roles)
    }

    suspend fun fetchWorkInfo(id: Long): Music032Work {
        require(id > 0)
        return Music032WorksJson.work(JSONObject(cancellableGet("/api/v1/works/$id")), ::mediaUrl, detail = true)
    }

    suspend fun fetchWork(id: Long): Music032WorkDetail {
        val work = fetchWorkInfo(id)
        val (tracks, albums) = coroutineScope {
            val tracksDeferred = async { fetchWorkTracks(id) }
            val albumsDeferred = async {
                Music032WorksJson.albums(JSONObject(cancellableGet("/api/v1/works/$id/albums")), ::parseAlbum)
            }
            tracksDeferred.await() to albumsDeferred.await()
        }
        return Music032WorkDetail(work.copy(artworkUrl = work.artworkUrl ?: albums.firstOrNull()?.album?.thumbUrl), tracks, albums)
    }

    suspend fun fetchWorkTracks(id: Long): List<Music032WorkTrack> {
        require(id > 0)
        return Music032WorksJson.tracks(JSONObject(cancellableGet("/api/v1/works/$id/tracks")), ::parseTrack)
    }

    suspend fun fetchWorkSeries(id: Long): Music032WorkSeries {
        require(id > 0)
        return Music032WorksJson.series(JSONObject(cancellableGet("/api/v1/series/$id")), ::mediaUrl)
    }

    suspend fun fetchSeriesTracks(id: Long): List<MusicTrack> {
        require(id > 0)
        return Music032WorksJson.tracks(JSONObject(cancellableGet("/api/v1/series/$id/tracks")), ::parseTrack).uniqueStreams()
    }

    private suspend fun cancellableGet(path: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .get()
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .build()
        AppHttpClient.defaultClient.newCall(request).awaitResponse().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()
                throw Music032HttpException(response.code, message.orEmpty().ifBlank { "HTTP ${response.code}" })
            }
            body
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
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            AppHttpClient.noRedirectClient.newCall(request).awaitResponse().use { response ->
                mediaTokenProbeStatus(response.code)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            MediaTokenProbeResult.UNKNOWN
        }
    }

    /**
     * Verifies server reachability, API Token authentication, and mediaToken validity
     * without mutating local library state.
     */
    suspend fun testConnection(): Music032ConnectionTestResult = withContext(Dispatchers.IO) {
        try {
            cancellableGet("/api/v1/albums?limit=1&offset=0")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Music032HttpException) {
            if (error.status == 401 || error.status == 403) {
                return@withContext Music032ConnectionTestResult.InvalidApiToken
            }
            return@withContext Music032ConnectionTestResult.Failure(
                error.serverMessage ?: error.message ?: "HTTP ${error.status}",
            )
        } catch (error: Exception) {
            return@withContext Music032ConnectionTestResult.Failure(error.message ?: error.javaClass.simpleName)
        }

        val mediaProbe = probeMediaToken()
        if (mediaProbe == MediaTokenProbeResult.INVALID) {
            return@withContext Music032ConnectionTestResult.InvalidMediaToken
        }
        runCatching { ensureCapabilities() }
        Music032ConnectionTestResult.Success(mediaTokenVerified = mediaProbe == MediaTokenProbeResult.OK)
    }

    override suspend fun fetchTrackStream(ratingKey: String): MusicTrack? = withContext(Dispatchers.IO) {
        try { parseTrack(JSONObject(request("GET", "/api/v1/tracks/$ratingKey"))) }
        catch (error: Music032HttpException) { if (trackDetailNotFound(error.status)) null else throw error }
    }

    /**
     * Fetches similar tracks from `GET /api/v1/tracks/{id}/similar`.
     * Retries once on HTTP 503 (similarity concurrency slot saturated) and falls back to
     * the legacy `?limit=` query if an older server rejects `exclude`/`diverse` with HTTP 400.
     */
    suspend fun fetchSimilarTracks(
        trackId: String,
        limit: Int = 20,
        excludeIds: Collection<Long> = emptyList(),
        diverse: Boolean = true,
    ): List<Music032SimilarTrack> = withContext(Dispatchers.IO) {
        val numericId = trackId.trackIdPath()
        val query = Music032SimilarJson.buildQuery(limit = limit, excludeIds = excludeIds, diverse = diverse)
        val path = "/api/v1/tracks/$numericId/similar?$query"
        val body = try {
            fetchSimilarWithBusyRetry(path)
        } catch (error: Music032HttpException) {
            if (error.status == 400 && (diverse || excludeIds.isNotEmpty())) {
                // Backward compatibility with older 032 servers before exclude/diverse params existed.
                fetchSimilarWithBusyRetry("/api/v1/tracks/$numericId/similar?limit=${limit.coerceIn(1, 100)}")
            } else {
                throw error
            }
        }
        Music032SimilarJson.parse(body, baseUrl, mediaToken, sourceId)
    }

    private suspend fun fetchSimilarWithBusyRetry(path: String): String {
        return try {
            cancellableGet(path)
        } catch (error: Music032HttpException) {
            if (error.status == 503) {
                kotlinx.coroutines.delay(1_100L)
                cancellableGet(path)
            } else {
                throw error
            }
        }
    }

    override suspend fun fetchFavoriteTracks(): List<MusicTrack> = withContext(Dispatchers.IO) {
        fetchPaged("/api/v1/favorites/tracks") { parseTrack(it) }
    }

    override suspend fun fetchAlbumTrackStreams(album: MusicAlbum): AlbumTracksResult = withContext(Dispatchers.IO) {
        val body = request("GET", "/api/v1/albums/${album.ratingKey}")
        val root = JSONObject(body)
        val resolvedAlbum = root.optJSONObject("album")?.let(::parseAlbum) ?: album
        val tracks = root.optJSONArray("tracks").toObjects().map(::parseTrack).sortedInDiscOrder()
        AlbumTracksResult(profile.displayName, resolvedAlbum, tracks)
    }

    override suspend fun fetchAllAlbumTracks(
        albums: List<MusicAlbum>,
        onProgress: (current: Int, total: Int) -> Unit,
    ): Map<String, List<MusicTrack>> = withContext(Dispatchers.IO) {
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

    override suspend fun fetchPlaylists(): PlaylistsResult = withContext(Dispatchers.IO) {
        val playlists = fetchPaged("/api/v1/playlists") { parsePlaylist(it) }
        PlaylistsResult(profile.displayName, playlists)
    }

    override suspend fun fetchPlaylistTracks(playlist: MusicPlaylist): PlaylistTracksResult = withContext(Dispatchers.IO) {
        val root = JSONObject(request("GET", "/api/v1/playlists/${playlist.ratingKey}"))
        val resolvedPlaylist = root.optJSONObject("playlist")?.let(::parsePlaylist) ?: playlist
        val tracks = root.optJSONArray("tracks").toObjects().map(::parseTrack)
        PlaylistTracksResult(profile.displayName, resolvedPlaylist, tracks)
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

    suspend fun updatePlaylist(id: String, name: String, description: String): MusicPlaylist = withContext(Dispatchers.IO) {
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

    /** Reorders tracks; [trackIds] must be an exact permutation of current tracks, guarded by [expectedRevision]. */
    suspend fun reorderPlaylistTracks(
        id: String,
        trackIds: List<String>,
        expectedRevision: Long,
    ): Music032PlaylistItemResult = withContext(Dispatchers.IO) {
        val body = Music032PlaylistRequests.reorder(trackIds, expectedRevision)
        Music032PlaylistRequests.parseItemResult(JSONObject(request("PUT", "/api/v1/playlists/${id.playlistId()}/order", body)))
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

    /**
     * One-sentence AI playlist (`features.aiPlaylists`); not saved on the server. The
     * server makes one model call, hence the long read timeout.
     */
    suspend fun generateAiPlaylist(prompt: String, trackCount: Int = 0): Music032AiPlaylist = withContext(Dispatchers.IO) {
        val body = Music032AiPlaylistJson.request(prompt, trackCount)
        Music032AiPlaylistJson.parse(
            JSONObject(request("POST", "/api/v1/ai/playlists", body, readTimeoutMillis = AI_PLAYLIST_READ_TIMEOUT_MILLIS)),
            ::parseTrack,
        )
    }

    /** Copies the mix into a new ordinary playlist (default name “title · day”). */
    suspend fun saveDailyMix(id: Long): MusicPlaylist = withContext(Dispatchers.IO) {
        val root = JSONObject(request("POST", "/api/v1/daily-mixes/$id/save"))
        parsePlaylist(root.optJSONObject("playlist") ?: root)
    }

    /** Excludes a track from (or returns it to) future recommendations. */
    suspend fun setRecommendationBlock(trackId: String, blocked: Boolean) = withContext(Dispatchers.IO) {
        val id = trackId.toLongOrNull()?.takeIf { it > 0 } ?: error("无效的曲目 ID")
        request(if (blocked) "PUT" else "DELETE", "/api/v1/tracks/$id/recommendation-block")
        Unit
    }

    // --- Lyrics (features.lyricsOnline / lyricsTranslation) --------------------------------

    /** Lyrics resolved by the server; null when the track has none (404). */
    internal suspend fun fetchLyrics(trackId: String): Music032TrackLyrics? = withContext(Dispatchers.IO) {
        try {
            Music032LyricsJson.parseLyrics(request("GET", "/api/v1/tracks/${trackId.trackIdPath()}/lyrics"))
        } catch (error: Music032HttpException) {
            if (error.status == 404) null else throw error
        }
    }

    internal suspend fun searchLyrics(name: String, artist: String, durationMillis: Long?): List<Music032LyricsCandidate> =
        withContext(Dispatchers.IO) {
            val query = buildList {
                add("name=${URLEncoder.encode(name.trim(), "UTF-8")}")
                add("artist=${URLEncoder.encode(artist.trim(), "UTF-8")}")
                durationMillis?.takeIf { it > 0 }?.let { add("durationMs=$it") }
            }.joinToString("&")
            Music032LyricsJson.parseSearch(request("GET", "/api/v1/lyrics/search?$query", readTimeoutMillis = LYRICS_READ_TIMEOUT_MILLIS))
        }

    /** Uses one search result as this track's lyrics on the server (every client sees it). */
    internal suspend fun bindLyrics(trackId: String, candidate: Music032LyricsCandidate): Music032TrackLyrics =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("source", candidate.source)
                .put("id", candidate.id)
                .put("name", candidate.name)
                .put("artist", candidate.artist)
            Music032LyricsJson.parseLyrics(
                request("PUT", "/api/v1/tracks/${trackId.trackIdPath()}/lyrics/source", body, LYRICS_READ_TIMEOUT_MILLIS),
            )
        }

    /** Drops the online lyrics and stops automatic lookups; local lyrics are unaffected. */
    internal suspend fun clearLyrics(trackId: String) = withContext(Dispatchers.IO) {
        request("DELETE", "/api/v1/tracks/${trackId.trackIdPath()}/lyrics/source")
        Unit
    }

    /** Line-by-line translation aligned to the server lyric lines (AI or NetEase's own). */
    internal suspend fun translateLyrics(trackId: String, targetLanguage: String): List<String> = withContext(Dispatchers.IO) {
        val body = JSONObject().put("targetLanguage", targetLanguage)
        Music032LyricsJson.parseTranslation(
            request("POST", "/api/v1/tracks/${trackId.trackIdPath()}/lyrics/translation", body, TRANSLATION_READ_TIMEOUT_MILLIS),
        )
    }

    private fun String.trackIdPath(): Long = toLongOrNull()?.takeIf { it > 0 } ?: error("无效的曲目 ID")

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

    /** Transport used by [Music032PlaybackReporter] to POST one playback session event. */
    internal val playbackEventTransport = PlaybackEventTransport { payload -> postPlaybackEvent(payload) }

    /**
     * POSTs one session event without throwing on protocol statuses: the reporter needs
     * the exact 404/409 codes to drive its recovery rules. Status 0 means the request
     * never produced a response (network failure, cancellation or deadline).
     */
    internal suspend fun postPlaybackEvent(payload: JSONObject): PlaybackEventHttpResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/v1/playback/events")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .build()
        try {
            AppHttpClient.playbackEventClient.newCall(request).awaitResponse().use { response ->
                PlaybackEventHttpResult(response.code, response.body?.string())
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            PlaybackEventHttpResult(0, null)
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

    private fun request(method: String, path: String, body: JSONObject? = null, readTimeoutMillis: Int = 30_000): String {
        val target = if (path.startsWith("http://") || path.startsWith("https://")) path else "$baseUrl$path"
        val requestBody = when {
            body != null -> body.toString().toRequestBody(JSON_MEDIA_TYPE)
            method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val request = Request.Builder()
            .url(target)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .build()
        val client = AppHttpClient.withReadTimeout(readTimeoutMillis.toLong())
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = runCatching { JSONObject(responseBody).optJSONObject("error") }.getOrNull()
                val message = error?.optString("message").orEmpty().ifBlank { responseBody.take(300) }
                throw Music032HttpException(
                    response.code,
                    "032 Music Server HTTP ${response.code}：$message",
                    code = error?.optString("code")?.takeIf { it.isNotBlank() },
                    serverMessage = error?.optString("message")?.takeIf { it.isNotBlank() },
                )
            }
            responseBody
        }
    }

    private fun JSONArray?.toObjects(): List<JSONObject> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) optJSONObject(index)?.let(::add)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val PAGE_SIZE = 500
        const val SYNC_PAGE_SIZE = 1000
        /** Server online lookups take up to ~20 s; AI translation of a long song takes longer. */
        const val LYRICS_READ_TIMEOUT_MILLIS = 45_000
        const val TRANSLATION_READ_TIMEOUT_MILLIS = 180_000
        /** The server bounds its model call at 30 s and then loads the library. */
        const val AI_PLAYLIST_READ_TIMEOUT_MILLIS = 60_000
    }
}

sealed interface Music032ConnectionTestResult {
    data class Success(val mediaTokenVerified: Boolean) : Music032ConnectionTestResult
    data object InvalidApiToken : Music032ConnectionTestResult
    data object InvalidMediaToken : Music032ConnectionTestResult
    data class Failure(val detail: String) : Music032ConnectionTestResult
}

internal fun shouldCacheArtistDetail(requestGeneration: Long, currentGeneration: Long): Boolean =
    requestGeneration == currentGeneration

internal class Music032HttpException(
    val status: Int,
    message: String,
    val code: String? = null,
    /** The server's own `error.message`, when it sent one. */
    val serverMessage: String? = null,
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
    val artist: MusicArtist,
    val albums: List<MusicAlbum>,
    val appearanceAlbumKeys: Set<String>,
    val topTracks: List<MusicTrack>,
    val topTracksArePopular: Boolean = false,
    val tracksTotal: Int,
)

/** A track from an artist's credits page and the roles this artist had on it. */
data class Music032CreditTrack(val track: MusicTrack, val roles: List<String>)

/** One page of `/api/v1/artists/{id}/credits`; [roles] counts every role, whatever the filter. */
data class Music032ArtistCreditsPage(
    val items: List<Music032CreditTrack>, val total: Int, val roles: List<Pair<String, Int>>,
)

data class Music032ArtistTracksPage(
    val items: List<MusicTrack>, val total: Int, val limit: Int, val offset: Int,
)

enum class ArtistTrackSort(val apiValue: String, val defaultOrder: String) {
    Album("album", "asc"), Plays("plays", "desc"), Recent("recent", "desc"),
    Title("title", "asc"), Added("added", "desc"), Duration("duration", "asc"),
}
