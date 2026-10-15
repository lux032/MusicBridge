package com.lux032.musicbridge.plex

import android.os.Build
import android.util.Base64
import com.lux032.musicbridge.LocalPlaybackPolicy
import com.lux032.musicbridge.LOSSLESS_AUDIO_CODECS
import com.lux032.musicbridge.OpusTranscodeMode
import com.lux032.musicbridge.SONOS_MAX_SAMPLE_RATE_HZ
import com.lux032.musicbridge.SonosHiResTranscodeMode
import com.lux032.musicbridge.music.Music032LocalTranscode
import com.lux032.musicbridge.music.MusicSourceClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class PlexAuthConfig(
    val username: String?,
    val password: String?,
    val preferredServer: String?,
    val baseUrl: String?,
    val token: String?,
    val clientIdentifier: String,
    val sourceId: String = "",
    val opusTranscodeMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT,
    val sonosHiResTranscodeMode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.DEFAULT,
)

data class PlexServer(val name: String, val uri: String, val accessToken: String?)

data class PlexSection(val key: String, val title: String, val type: String)

/** Server-side artist identity (032 artist id + display name). Plex sources leave refs empty
 *  and keep navigating by name. */
data class ArtistRef(val id: String, val name: String)

data class PlexAlbum(
    val ratingKey: String,
    val title: String,
    val artistName: String?,
    val year: Int?,
    val thumbUrl: String?,
    val userRating: Float?,
    val addedAtEpochSeconds: Long?,
    val lastViewedAtEpochSeconds: Long?,
    val section: PlexSection,
    val sourceId: String = "",
    val leafCount: Int? = null,
    val musicAnalysisVersion: String? = null,
    val format: String? = null,
    val subformat: String? = null,
    /** Full release date as served (Plex originallyAvailableAt / 032 releaseDate); may be
     *  only a year or year-month. Use [releaseSortKey] for ordering. */
    val releaseDate: String? = null,
    /** Credited album artists in credit order; [artistName] is only their joined text. */
    val artists: List<ArtistRef> = emptyList(),
) {
    val isFavorite: Boolean
        get() = (userRating ?: 0f) > 0f
}

data class PlexTrackStream(
    val ratingKey: String,
    val title: String,
    val streamUrl: String,
    val partKey: String,
    val durationMillis: Long?,
    val userRating: Float?,
    val albumRatingKey: String? = null,
    val albumTitle: String? = null,
    val artistName: String? = null,
    val thumbUrl: String? = null,
    val sourceAudioCodec: String? = null,
    val sourceAudioBitrateKbps: Int? = null,
    val sourceAudioSampleRate: Int? = null,
    val bitDepth: Int? = null,
    val playbackMimeType: String? = null,
    /** Plex lyric stream (Stream streamType="4") when the server exposes one. */
    val lyricUrl: String? = null,
    val sourceId: String = "",
    val musicAnalysisVersion: String? = null,
    val viewCount: Long? = null,
    val lastViewedAtEpochSeconds: Long? = null,
    val skipCount: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    /** Performing track artists; empty means the album artists apply. */
    val artists: List<ArtistRef> = emptyList(),
)

internal data class PlexTrackBehavior(
    val viewCount: Long?,
    val lastViewedAtEpochSeconds: Long?,
    val skipCount: Long?,
    val musicAnalysisVersion: String?,
)

internal fun parsePlexTrackBehavior(
    viewCount: String?,
    lastViewedAt: String?,
    skipCount: String?,
    musicAnalysisVersion: String?,
): PlexTrackBehavior = PlexTrackBehavior(
    viewCount = viewCount?.trim()?.toLongOrNull(),
    lastViewedAtEpochSeconds = lastViewedAt?.trim()?.toLongOrNull(),
    skipCount = skipCount?.trim()?.toLongOrNull(),
    musicAnalysisVersion = musicAnalysisVersion?.trim()?.ifBlank { null },
)

private fun Element.trackBehavior(): PlexTrackBehavior = parsePlexTrackBehavior(
    getAttribute("viewCount"),
    getAttribute("lastViewedAt"),
    getAttribute("skipCount"),
    getAttribute("musicAnalysisVersion"),
)

private fun JSONObject.trackBehavior(): PlexTrackBehavior {
    fun value(name: String): String? = if (isNull(name)) null else opt(name)?.toString()
    return parsePlexTrackBehavior(
        value("viewCount"),
        value("lastViewedAt"),
        value("skipCount"),
        value("musicAnalysisVersion"),
    )
}

val PlexTrackStream.isFavorite: Boolean
    get() = (userRating ?: 0f) > 0f

data class PlexAlbumResult(
    val serverName: String,
    val sections: List<PlexSection>,
    val albums: List<String>,
)

data class PlexAlbumsResult(
    val serverName: String,
    val sections: List<PlexSection>,
    val albums: List<PlexAlbum>,
)

data class PlexArtist(
    val ratingKey: String,
    val title: String,
    val thumbUrl: String?,
    val userRating: Float? = null,
    val sourceId: String = "",
    /** 032 sync model counts (discography albums / performed tracks); null for Plex. */
    val albumCount: Int? = null,
    val trackCount: Int? = null,
    val sortName: String? = null,
) {
    // Plex has no artist "follow"; a 10-star rating is what Plexamp shows as a liked artist.
    val isFavorite: Boolean
        get() = (userRating ?: 0f) > 0f
}

data class PlexArtistsResult(
    val serverName: String,
    val artists: List<PlexArtist>,
)

data class PlexAlbumTracksResult(
    val serverName: String,
    val album: PlexAlbum,
    val tracks: List<PlexTrackStream>,
)

data class PlexPlaylist(
    val ratingKey: String,
    val title: String,
    val thumbUrl: String?,
    val leafCount: Int,
    val sourceId: String = "",
    /** Free-text note (032 only; Plex playlists have none). */
    val description: String = "",
    /** 032 optimistic-concurrency revision; null when unknown (Plex, local cache rows). */
    val revision: Long? = null,
)

data class PlexPlaylistsResult(
    val serverName: String,
    val playlists: List<PlexPlaylist>,
)

data class PlexPlaylistTracksResult(
    val serverName: String,
    val playlist: PlexPlaylist,
    val tracks: List<PlexTrackStream>,
)

private const val FAVORITE_TRACK_RATING = 10
private const val PAGED_CONTAINER_SIZE = 200
private const val ALBUM_TRACK_SYNC_PAGE_SIZE = 600
private const val FAVORITE_TRACKS_CONTAINER_SIZE = 1000

class PlexClient(private val config: PlexAuthConfig) : MusicSourceClient, SonicCapableSource {
    override val sourceId: String = config.sourceId
    private val appName = "MusicBridge"
    private val appVersion = "0.1.0"
    private val product = "Android App"
    private val platform = "Android ${Build.VERSION.RELEASE ?: "Unknown"}"
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedServer: PlexServer? = null
    @Volatile private var cachedMachineIdentifier: String? = null

    suspend fun fetchAlbumNames(): PlexAlbumResult = withContext(Dispatchers.IO) {
        val albumsResult = fetchAlbums()
        PlexAlbumResult(
            serverName = albumsResult.serverName,
            sections = albumsResult.sections,
            albums = albumsResult.albums.map(PlexAlbum::title),
        )
    }

    override suspend fun fetchAlbums(): PlexAlbumsResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val sections = listMusicSections(server, token)
        if (sections.isEmpty()) {
            error("服务器 '${server.name}' 上没有找到音乐库。")
        }

        val albums = sections
            .flatMap { section -> listAlbums(server, section, accessToken) }
            .distinctBy { it.ratingKey }
            .sortedBy { it.title.lowercase() }

        PlexAlbumsResult(
            serverName = server.name,
            sections = sections,
            albums = albums,
        )
    }

    override suspend fun fetchArtists(): PlexArtistsResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val sections = listMusicSections(server, token)
        if (sections.isEmpty()) {
            error("服务器 '${server.name}' 上没有找到音乐库。")
        }

        val artists = sections
            .flatMap { section -> listArtists(server, section, accessToken) }
            .distinctBy { it.ratingKey }
            .sortedBy { it.title.lowercase() }

        PlexArtistsResult(
            serverName = server.name,
            artists = artists,
        )
    }

    suspend fun fetchFavoriteAlbums(): PlexAlbumsResult = withContext(Dispatchers.IO) {
        val albumsResult = fetchAlbums()
        albumsResult.copy(
            albums = albumsResult.albums.filter(PlexAlbum::isFavorite)
        )
    }

    override suspend fun fetchFavoriteTracks(): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        runCatching {
            listFavoriteTracksGlobal(server, accessToken)
        }.recoverCatching {
            val sections = listMusicSections(server, token)
            if (sections.isEmpty()) {
                emptyList()
            } else {
                sections
                    .flatMap { section -> listFavoriteTracksBySection(server, section, accessToken) }
            }
        }.getOrThrow()
            .distinctBy(PlexTrackStream::ratingKey)
            .sortedBy { it.title.lowercase() }
    }

    suspend fun fetchAlbumTrackStreams(albumTitle: String): PlexAlbumTracksResult = withContext(Dispatchers.IO) {
        val normalizedAlbumTitle = albumTitle.trim()
        require(normalizedAlbumTitle.isNotEmpty()) { "缺少专辑名称。" }

        val matchedAlbum = fetchAlbums().albums
            .firstOrNull { it.title.equals(normalizedAlbumTitle, ignoreCase = true) }
            ?: error("未找到专辑 '$normalizedAlbumTitle'。")

        fetchAlbumTrackStreams(matchedAlbum)
    }

    override suspend fun fetchAlbumTrackStreams(album: PlexAlbum): PlexAlbumTracksResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        PlexAlbumTracksResult(
            serverName = server.name,
            album = album,
            tracks = listAlbumTracks(server, album, token),
        )
    }

    suspend fun fetchRecentlyPlayedTracks(limit: Int = 100): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        if (limit <= 0) return@withContext emptyList()
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        listMusicSections(server, token).flatMap { section ->
            val response = request(
                method = "GET",
                url = buildPlexUrl(
                    serverUri = server.uri,
                    path = "/library/sections/${section.key}/all",
                    queryParameters = listOf(
                        "type" to "10",
                        "sort" to "lastViewedAt:desc",
                        "includeElements" to "Stream",
                        "includeStreams" to "1",
                        "X-Plex-Container-Start" to "0",
                        "X-Plex-Container-Size" to limit.toString(),
                    ),
                ),
                headers = mapOf("X-Plex-Token" to accessToken),
            )
            parseXml(response.body).getElementsByTagName("Track").toTrackStreams(
                server.uri,
                accessToken,
                config.sourceId,
                config.clientIdentifier,
                config.opusTranscodeMode,
                config.sonosHiResTranscodeMode,
            )
        }.filter { it.lastViewedAtEpochSeconds != null }
            .sortedByDescending { it.lastViewedAtEpochSeconds }
            .distinctBy { it.ratingKey }
            .take(limit)
    }

    override suspend fun fetchAllAlbumTracks(
        albums: List<PlexAlbum>,
        onProgress: (current: Int, total: Int) -> Unit,
    ): Map<String, List<PlexTrackStream>> = withContext(Dispatchers.IO) {
        val token = resolvedToken()
        val server = resolvedServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }

        val batched = runCatching {
            val sections = listMusicSections(server, token)
            val tracks = mutableListOf<PlexTrackStream>()
            var completedSectionTracks = 0
            sections.forEach { section ->
                var containerStart = 0
                var sectionTotal: Int? = null
                while (true) {
                    val response = request(
                        method = "GET",
                        url = buildPlexUrl(
                            serverUri = server.uri,
                            path = "/library/sections/${section.key}/all",
                            queryParameters = listOf(
                                "type" to "10",
                                "includeElements" to "Stream",
                                "includeStreams" to "1",
                                "sort" to "addedAt:asc",
                                "X-Plex-Container-Start" to containerStart.toString(),
                                "X-Plex-Container-Size" to ALBUM_TRACK_SYNC_PAGE_SIZE.toString(),
                            ),
                        ),
                        headers = mapOf("X-Plex-Token" to accessToken),
                    )
                    val root = parseXml(response.body)
                    val nodes = root.getElementsByTagName("Track")
                    val returned = nodes.length
                    if (returned == 0) break
                    val pageTracks = nodes.toTrackStreams(
                        server.uri,
                        accessToken,
                        config.sourceId,
                        config.clientIdentifier,
                        config.opusTranscodeMode,
                        config.sonosHiResTranscodeMode,
                    )
                    sectionTotal = root.getAttribute("totalSize").toIntOrNull() ?: sectionTotal
                    tracks += pageTracks
                    containerStart += returned
                    onProgress(
                        completedSectionTracks + containerStart,
                        completedSectionTracks + maxOf(sectionTotal ?: containerStart, containerStart),
                    )
                    if (returned < ALBUM_TRACK_SYNC_PAGE_SIZE ||
                        sectionTotal?.let { containerStart >= it } == true
                    ) break
                }
                completedSectionTracks += containerStart
            }
            tracks.toAlbumTrackMap()
        }.getOrNull()

        if (batched != null && (batched.isNotEmpty() || albums.isEmpty())) {
            return@withContext batched
        }

        val semaphore = Semaphore(4)
        val completed = java.util.concurrent.atomic.AtomicInteger(0)
        albums.map { album ->
            async {
                semaphore.withPermit {
                    val tracks = runCatching { listAlbumTracks(server, album, accessToken) }
                        .getOrDefault(emptyList())
                    onProgress(completed.incrementAndGet(), albums.size)
                    album.ratingKey to tracks
                }
            }
        }.awaitAll().filter { it.second.isNotEmpty() }.toMap()
    }

    private fun List<PlexTrackStream>.toAlbumTrackMap(): Map<String, List<PlexTrackStream>> =
        distinctBy(PlexTrackStream::ratingKey)
            .filter { !it.albumRatingKey.isNullOrBlank() }
            .groupBy { requireNotNull(it.albumRatingKey) }
            .mapValues { (_, tracks) ->
                tracks.sortedWith(
                    compareBy<PlexTrackStream> { it.discNumber ?: 1 }
                        .thenBy { it.trackNumber ?: Int.MAX_VALUE }
                        .thenBy { it.title.lowercase() },
                )
            }

    override suspend fun fetchPlaylists(): PlexPlaylistsResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val response = request(
            method = "GET",
            url = "${server.uri}/playlists?playlistType=audio",
            headers = mapOf("X-Plex-Token" to accessToken),
        )
        val root = parseXml(response.body)
        val playlists = root.getElementsByTagName("Playlist")
        val playlistList = buildList {
            for (i in 0 until playlists.length) {
                val playlist = playlists.item(i) as? Element ?: continue
                add(PlexPlaylist(
                    ratingKey = playlist.getAttribute("ratingKey"),
                    title = playlist.getAttribute("title"),
                    thumbUrl = playlist.getAttribute("thumb").takeIf { it.isNotBlank() }?.let { "${server.uri}$it?X-Plex-Token=$accessToken" },
                    leafCount = playlist.getAttribute("leafCount").toIntOrNull() ?: 0,
                    sourceId = config.sourceId,
                ))
            }
        }
        PlexPlaylistsResult(serverName = server.name, playlists = playlistList)
    }

    override suspend fun fetchPlaylistTracks(playlist: PlexPlaylist): PlexPlaylistTracksResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val response = request(
            method = "GET",
            url = buildPlexUrl(server.uri, "/playlists/${playlist.ratingKey}/items", listOf("includeElements" to "Stream", "includeStreams" to "1")),
            headers = mapOf("X-Plex-Token" to accessToken),
        )
        val root = parseXml(response.body)
        val tracks = root.getElementsByTagName("Track")
        val trackList = buildList {
            for (i in 0 until tracks.length) {
                val track = tracks.item(i) as? Element ?: continue
                val media = track.getElementsByTagName("Media").item(0) as? Element ?: continue
                val part = media.getElementsByTagName("Part").item(0) as? Element ?: continue
                val ratingKey = track.getAttribute("ratingKey").trim()
                val partKey = part.getAttribute("key").trim()
                if (ratingKey.isBlank() || partKey.isBlank()) continue
                val sourceAudioCodec = track.firstAudioCodec()
                val sourceAudioSampleRate = track.firstAudioSampleRate()
                val behavior = track.trackBehavior()
                add(PlexTrackStream(
                    ratingKey = ratingKey,
                    title = track.getAttribute("title"),
                    streamUrl = buildPlaybackUrl(
                        serverUri = server.uri,
                        ratingKey = ratingKey,
                        partKey = partKey,
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        token = accessToken,
                        clientIdentifier = config.clientIdentifier,
                        opusMode = config.opusTranscodeMode,
                hiResMode = config.sonosHiResTranscodeMode,
                    ),
                    partKey = partKey,
                    durationMillis = track.getAttribute("duration").toLongOrNull(),
                    userRating = track.getAttribute("userRating").toFloatOrNull(),
                    albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                    albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                    artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                    thumbUrl = track.trackThumbUrl(server.uri, accessToken),
                    sourceAudioCodec = sourceAudioCodec,
                    sourceAudioBitrateKbps = track.firstAudioBitrateKbps(),
                    sourceAudioSampleRate = sourceAudioSampleRate,
                    bitDepth = track.firstAudioBitDepth(),
                    playbackMimeType = sourceAudioCodec.transcodedPlaybackMimeType(config.opusTranscodeMode, sourceAudioSampleRate, config.sonosHiResTranscodeMode),
                    lyricUrl = track.firstLyricUrl(server.uri, accessToken),
                    sourceId = config.sourceId,
                    musicAnalysisVersion = behavior.musicAnalysisVersion,
                    viewCount = behavior.viewCount,
                    lastViewedAtEpochSeconds = behavior.lastViewedAtEpochSeconds,
                    skipCount = behavior.skipCount,
                ))
            }
        }
        PlexPlaylistTracksResult(serverName = server.name, playlist = playlist, tracks = trackList)
    }

    suspend fun setFavorite(ratingKey: String, isFavorite: Boolean): Unit = withContext(Dispatchers.IO) {
        val normalizedRatingKey = ratingKey.trim()
        require(normalizedRatingKey.isNotEmpty()) { "缺少 Plex ratingKey。" }

        val token = resolvedToken()
        val server = resolvedServer(token)
        request(
            method = "PUT",
            url = buildRateUrl(
                serverUri = server.uri,
                ratingKey = normalizedRatingKey,
                rating = if (isFavorite) 10 else 0,
            ),
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
    }

    override suspend fun setAlbumFavorite(ratingKey: String, isFavorite: Boolean) {
        setFavorite(ratingKey, isFavorite)
    }

    override suspend fun setTrackFavorite(ratingKey: String, isFavorite: Boolean) {
        setFavorite(ratingKey, isFavorite)
    }

    override suspend fun setArtistFavorite(ratingKey: String, isFavorite: Boolean) {
        setFavorite(ratingKey, isFavorite)
    }

    override suspend fun fetchTrackStream(ratingKey: String): PlexTrackStream? = withContext(Dispatchers.IO) {
        val normalizedKey = ratingKey.trim()
        if (normalizedKey.isEmpty()) return@withContext null
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val root = parseXml(
            request(
                method = "GET",
                url = buildPlexUrl(server.uri, "/library/metadata/$normalizedKey", listOf("includeElements" to "Stream", "includeStreams" to "1")),
                headers = mapOf("X-Plex-Token" to accessToken),
            ).body,
        )
        root.getElementsByTagName("Track").toTrackStreams(
            server.uri,
            accessToken,
            config.sourceId,
            config.clientIdentifier,
            config.opusTranscodeMode,
            config.sonosHiResTranscodeMode,
        ).firstOrNull()
    }

    override suspend fun fetchArtistTracks(artist: PlexArtist): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val sections = listMusicSections(server, token)
        if (sections.isEmpty()) {
            error("服务器 '${server.name}' 上没有找到音乐库。")
        }

        sections
            .flatMap { section ->
                runCatching { listArtistTracks(server, section, artist, accessToken) }
                    .getOrDefault(emptyList())
            }
            .distinctBy(PlexTrackStream::ratingKey)
    }

    override suspend fun fetchArtistAlbums(artistRatingKey: String): List<PlexAlbum> = withContext(Dispatchers.IO) {
        val normalizedRatingKey = artistRatingKey.trim()
        if (normalizedRatingKey.isEmpty()) return@withContext emptyList()

        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val sections = listMusicSections(server, token)
        if (sections.isEmpty()) {
            error("服务器 '${server.name}' 上没有找到音乐库。")
        }
        val root = parseXml(
            request(
                method = "GET",
                url = "${server.uri}/library/metadata/$normalizedRatingKey/children",
                headers = mapOf("X-Plex-Token" to accessToken),
            ).body,
        )
        val responseSectionKey = root.getAttribute("librarySectionID").trim()
        val responseSection = sections.firstOrNull { it.key == responseSectionKey } ?: sections.first()
        val directories = root.getElementsByTagName("Directory")
        buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                if (directory.getAttribute("type") != "album") continue
                val title = directory.getAttribute("title").trim()
                val ratingKey = directory.getAttribute("ratingKey").trim()
                if (title.isBlank() || ratingKey.isBlank()) continue

                var format: String? = null
                val subformats = mutableListOf<String>()
                val childNodes = directory.childNodes
                for (childIndex in 0 until childNodes.length) {
                    val child = childNodes.item(childIndex) as? Element ?: continue
                    val tag = child.getAttribute("tag").trim().ifBlank { null } ?: continue
                    when (child.tagName) {
                        "Format" -> if (format == null) format = tag
                        "Subformat" -> subformats += tag
                    }
                }
                add(
                    PlexAlbum(
                        ratingKey = ratingKey,
                        title = title,
                        artistName = directory.getAttribute("parentTitle").trim().ifBlank { null },
                        year = directory.getAttribute("year").trim().toIntOrNull(),
                        thumbUrl = directory.getAttribute("thumb").trim().takeIf(String::isNotBlank)
                            ?.let { buildMediaUrl(server.uri, it, accessToken) },
                        userRating = directory.getAttribute("userRating").trim().toFloatOrNull(),
                        addedAtEpochSeconds = directory.getAttribute("addedAt").trim().toLongOrNull(),
                        lastViewedAtEpochSeconds = directory.getAttribute("lastViewedAt").trim().toLongOrNull(),
                        section = responseSection,
                        sourceId = config.sourceId,
                        leafCount = directory.getAttribute("leafCount").trim().toIntOrNull()?.takeIf { it > 0 },
                        musicAnalysisVersion = directory.getAttribute("musicAnalysisVersion").trim().ifBlank { null },
                        format = format,
                        subformat = subformats.joinToString(",").ifBlank { null },
                        releaseDate = directory.getAttribute("originallyAvailableAt").trim().ifBlank { null },
                    )
                )
            }
        }
    }

    override suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        val normalizedRatingKey = ratingKey.trim()
        require(normalizedRatingKey.isNotEmpty()) { "缺少 Plex ratingKey。" }

        val token = resolvedToken()
        val server = resolvedServer(token)
        request(
            method = "POST",
            url = buildTimelineUrl(
                serverUri = server.uri,
                ratingKey = normalizedRatingKey,
                state = state,
                timeMillis = timeMillis.coerceAtLeast(0L),
                durationMillis = durationMillis?.coerceAtLeast(0L),
                continuing = continuing,
            ),
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
    }

    override suspend fun scrobble(
        ratingKey: String,
        positionMillis: Long,
        durationMillis: Long?,
    ): Unit = withContext(Dispatchers.IO) {
        val normalizedRatingKey = ratingKey.trim()
        require(normalizedRatingKey.isNotEmpty()) { "缺少 Plex ratingKey。" }

        val token = resolvedToken()
        val server = resolvedServer(token)
        request(
            method = "PUT",
            url = buildScrobbleUrl(server.uri, normalizedRatingKey),
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
    }

    private fun resolvedToken(): String =
        cachedToken ?: resolveToken().also { cachedToken = it }

    private fun resolvedServer(token: String): PlexServer =
        cachedServer ?: resolveServer(token).also { cachedServer = it }

    private fun resolveToken(): String {
        cachedToken?.let { return it }
        config.token?.takeIf { it.isNotBlank() }?.let { token ->
            cachedToken = token
            return token
        }
        val username = config.username?.takeIf { it.isNotBlank() }
            ?: error("缺少 Plex 用户名。")
        val password = config.password?.takeIf { it.isNotBlank() }
            ?: error("缺少 Plex 密码。")

        val authValue = Base64.encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        val response = request(
            method = "POST",
            url = "https://plex.tv/users/sign_in.xml",
            headers = mapOf("Authorization" to "Basic $authValue"),
        )
        val root = parseXml(response.body)
        return (root.childText("authenticationToken")
            ?: error("Plex 登录成功，但没有返回 token。"))
            .also { cachedToken = it }
    }

    private fun resolveServer(token: String): PlexServer {
        cachedServer?.let { return it }
        config.baseUrl?.takeIf { it.isNotBlank() }?.let {
            return PlexServer(
                name = config.preferredServer?.ifBlank { null } ?: "DirectServer",
                uri = it.trimEnd('/'),
                accessToken = token,
            ).also { cachedServer = it }
        }

        val response = request(
            method = "GET",
            url = "https://plex.tv/api/resources?includeHttps=1",
            headers = mapOf("X-Plex-Token" to token),
        )
        val root = parseXml(response.body)
        val devices = root.getElementsByTagName("Device")
        val candidates = buildList {
            for (index in 0 until devices.length) {
                val device = devices.item(index) as? Element ?: continue
                if (!device.getAttribute("provides").contains("server")) {
                    continue
                }
                val accessToken = device.getAttribute("accessToken").ifBlank { token }
                val connections = device.getElementsByTagName("Connection")
                for (connectionIndex in 0 until connections.length) {
                    val connection = connections.item(connectionIndex) as? Element ?: continue
                    val uri = connection.getAttribute("uri")
                    if (uri.isBlank()) continue
                    add(
                        PlexServerCandidate(
                            server = PlexServer(device.getAttribute("name"), uri.trimEnd('/'), accessToken),
                            isPublicDirect = connection.getAttribute("local") == "0" &&
                                connection.getAttribute("relay") != "1",
                        ),
                    )
                }
            }
        }

        if (candidates.isEmpty()) {
            error("当前账号下没有可用的 Plex 服务器。")
        }

        config.preferredServer?.takeIf { it.isNotBlank() }?.let { preferred ->
            val preferredCandidates = candidates.filter { it.server.name.equals(preferred, ignoreCase = true) }
            preferredCandidates.firstOrNull(PlexServerCandidate::isPublicDirect)?.let {
                cachedServer = it.server
                return it.server
            }
            preferredCandidates.firstOrNull()?.let {
                cachedServer = it.server
                return it.server
            }
            error(
                "未找到服务器 '$preferred'。可用服务器: ${
                    candidates.map { it.server.name }.distinct().sorted().joinToString()
                }"
            )
        }

        return (candidates.firstOrNull(PlexServerCandidate::isPublicDirect)?.server
            ?: candidates.first().server).also { cachedServer = it }
    }

    private fun listMusicSections(server: PlexServer, token: String): List<PlexSection> {
        val response = request(
            method = "GET",
            url = "${server.uri}/library/sections",
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
        val root = parseXml(response.body)
        val directories = root.getElementsByTagName("Directory")
        return buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                if (directory.getAttribute("type") != "artist") {
                    continue
                }
                add(
                    PlexSection(
                        key = directory.getAttribute("key"),
                        title = directory.getAttribute("title"),
                        type = directory.getAttribute("type"),
                    )
                )
            }
        }
    }

    private fun listAlbums(server: PlexServer, section: PlexSection, token: String): List<PlexAlbum> {
        val response = request(
            method = "GET",
            url = "${server.uri}/library/sections/${section.key}/all?type=9&includeMeta=1",
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
        val root = parseXml(response.body)
        val directories = root.getElementsByTagName("Directory")
        return buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                if (directory.getAttribute("type") != "album") continue
                val title = directory.getAttribute("title").trim()
                val ratingKey = directory.getAttribute("ratingKey").trim()
                if (title.isBlank() || ratingKey.isBlank()) {
                    continue
                }
                var format: String? = null
                val subformats = mutableListOf<String>()
                val childNodes = directory.childNodes
                for (childIndex in 0 until childNodes.length) {
                    val child = childNodes.item(childIndex) as? Element ?: continue
                    val tag = child.getAttribute("tag").trim().ifBlank { null } ?: continue
                    when (child.tagName) {
                        "Format" -> if (format == null) format = tag
                        "Subformat" -> subformats += tag
                    }
                }
                add(
                    PlexAlbum(
                        ratingKey = ratingKey,
                        title = title,
                        artistName = directory.getAttribute("parentTitle").trim().ifBlank { null },
                        year = directory.getAttribute("year").trim().toIntOrNull(),
                        thumbUrl = directory.getAttribute("thumb")
                            .trim()
                            .takeIf { it.isNotBlank() }
                            ?.let { buildMediaUrl(server.uri, it, token) },
                        userRating = directory.getAttribute("userRating").trim().toFloatOrNull(),
                        addedAtEpochSeconds = directory.getAttribute("addedAt").trim().toLongOrNull(),
                        lastViewedAtEpochSeconds = directory.getAttribute("lastViewedAt").trim().toLongOrNull(),
                        section = section,
                        sourceId = config.sourceId,
                        leafCount = directory.getAttribute("leafCount").trim().toIntOrNull(),
                        musicAnalysisVersion = directory.getAttribute("musicAnalysisVersion").trim().ifBlank { null },
                        format = format,
                        subformat = subformats.joinToString(",").ifBlank { null },
                        releaseDate = directory.getAttribute("originallyAvailableAt").trim().ifBlank { null },
                    )
                )
            }
        }
    }

    private fun listArtists(server: PlexServer, section: PlexSection, token: String): List<PlexArtist> {
        val response = request(
            method = "GET",
            url = "${server.uri}/library/sections/${section.key}/all?type=8",
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
        val root = parseXml(response.body)
        val directories = root.getElementsByTagName("Directory")
        return buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                val title = directory.getAttribute("title").trim()
                val ratingKey = directory.getAttribute("ratingKey").trim()
                if (title.isBlank() || ratingKey.isBlank()) continue
                add(
                    PlexArtist(
                        ratingKey = ratingKey,
                        title = title,
                        thumbUrl = directory.getAttribute("thumb")
                            .trim()
                            .takeIf { it.isNotBlank() }
                            ?.let { buildMediaUrl(server.uri, it, token) },
                        userRating = directory.getAttribute("userRating").trim().toFloatOrNull(),
                        sourceId = config.sourceId,
                    )
                )
            }
        }
    }

    /**
     * Tracks belonging to one artist, most-played first (Plex records a per-track viewCount;
     * there is no dedicated "popular tracks" endpoint). Unplayed tracks keep library order
     * after the played ones.
     */
    private fun listArtistTracks(
        server: PlexServer,
        section: PlexSection,
        artist: PlexArtist,
        token: String,
    ): List<PlexTrackStream> {
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val response = request(
            method = "GET",
            url = buildPlexUrl(
                serverUri = server.uri,
                path = "/library/sections/${section.key}/all",
                queryParameters = listOf(
                    "type" to "10",
                    "artist.id" to artist.ratingKey,
                    "includeElements" to "Stream",
                    "includeStreams" to "1",
                ),
            ),
            headers = mapOf("X-Plex-Token" to accessToken),
        )
        val root = parseXml(response.body)
        val tracks = root.getElementsByTagName("Track")
        val parsed = buildList {
            for (index in 0 until tracks.length) {
                val track = tracks.item(index) as? Element ?: continue
                val title = track.getAttribute("title").trim()
                val ratingKey = track.getAttribute("ratingKey").trim()
                if (title.isBlank() || ratingKey.isBlank()) continue

                val partKey = track.firstPartKey() ?: continue
                val sourceAudioCodec = track.firstAudioCodec()
                val sourceAudioSampleRate = track.firstAudioSampleRate()
                val behavior = track.trackBehavior()
                add(
                    (behavior.viewCount ?: 0L) to PlexTrackStream(
                        ratingKey = ratingKey,
                        title = title,
                        partKey = partKey,
                        streamUrl = buildPlaybackUrl(
                            serverUri = server.uri,
                            ratingKey = ratingKey,
                            partKey = partKey,
                            sourceAudioCodec = sourceAudioCodec,
                            sourceAudioSampleRate = sourceAudioSampleRate,
                            token = accessToken,
                            clientIdentifier = config.clientIdentifier,
                            opusMode = config.opusTranscodeMode,
                hiResMode = config.sonosHiResTranscodeMode,
                        ),
                        durationMillis = track.getAttribute("duration").trim().toLongOrNull(),
                        userRating = track.getAttribute("userRating").trim().toFloatOrNull(),
                        albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                        albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                        artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                        thumbUrl = track.trackThumbUrl(server.uri, accessToken),
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioBitrateKbps = track.firstAudioBitrateKbps(),
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        bitDepth = track.firstAudioBitDepth(),
                        playbackMimeType = sourceAudioCodec.transcodedPlaybackMimeType(config.opusTranscodeMode, sourceAudioSampleRate, config.sonosHiResTranscodeMode),
                        lyricUrl = track.firstLyricUrl(server.uri, accessToken),
                        sourceId = config.sourceId,
                        musicAnalysisVersion = behavior.musicAnalysisVersion,
                        viewCount = behavior.viewCount,
                        lastViewedAtEpochSeconds = behavior.lastViewedAtEpochSeconds,
                        skipCount = behavior.skipCount,
                    )
                )
            }
        }
        // Most-played first; unplayed tracks keep their library order afterwards.
        return parsed
            .sortedWith(compareByDescending<Pair<Long, PlexTrackStream>> { it.first }.thenBy { it.second.title.lowercase() })
            .map { it.second }
    }

    private fun listAlbumTracks(server: PlexServer, album: PlexAlbum, token: String): List<PlexTrackStream> {
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val response = request(
            method = "GET",
            url = buildPlexUrl(
                server.uri,
                "/library/metadata/${album.ratingKey}/children",
                listOf("includeElements" to "Stream", "includeStreams" to "1"),
            ),
            headers = mapOf("X-Plex-Token" to accessToken),
        )
        val root = parseXml(response.body)
        val tracks = root.getElementsByTagName("Track")
        return buildList {
            for (index in 0 until tracks.length) {
                val track = tracks.item(index) as? Element ?: continue
                val title = track.getAttribute("title").trim()
                val ratingKey = track.getAttribute("ratingKey").trim()
                val durationMillis = track.getAttribute("duration").trim().toLongOrNull()
                if (title.isBlank() || ratingKey.isBlank()) {
                    continue
                }

                val partKey = track.firstPartKey() ?: continue
                val sourceAudioCodec = track.firstAudioCodec()
                val sourceAudioSampleRate = track.firstAudioSampleRate()
                val behavior = track.trackBehavior()
                add(
                    PlexTrackStream(
                        ratingKey = ratingKey,
                        title = title,
                        partKey = partKey,
                        streamUrl = buildPlaybackUrl(
                            serverUri = server.uri,
                            ratingKey = ratingKey,
                            partKey = partKey,
                            sourceAudioCodec = sourceAudioCodec,
                            sourceAudioSampleRate = sourceAudioSampleRate,
                            token = accessToken,
                            clientIdentifier = config.clientIdentifier,
                            opusMode = config.opusTranscodeMode,
                hiResMode = config.sonosHiResTranscodeMode,
                        ),
                        durationMillis = durationMillis,
                        userRating = track.getAttribute("userRating").trim().toFloatOrNull(),
                        albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                        albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                        artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                        thumbUrl = track.trackThumbUrl(server.uri, accessToken),
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioBitrateKbps = track.firstAudioBitrateKbps(),
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        bitDepth = track.firstAudioBitDepth(),
                        playbackMimeType = sourceAudioCodec.transcodedPlaybackMimeType(config.opusTranscodeMode, sourceAudioSampleRate, config.sonosHiResTranscodeMode),
                        lyricUrl = track.firstLyricUrl(server.uri, accessToken),
                        sourceId = config.sourceId,
                        musicAnalysisVersion = behavior.musicAnalysisVersion,
                        viewCount = behavior.viewCount,
                        lastViewedAtEpochSeconds = behavior.lastViewedAtEpochSeconds,
                        skipCount = behavior.skipCount,
                        discNumber = track.getAttribute("parentIndex").trim().toIntOrNull(),
                        trackNumber = track.getAttribute("index").trim().toIntOrNull(),
                    )
                )
            }
        }
    }

    private fun listFavoriteTracksGlobal(server: PlexServer, token: String): List<PlexTrackStream> {
        val tracks = mutableListOf<PlexTrackStream>()
        var containerStart = 0

        while (true) {
            val response = request(
                method = "GET",
                url = buildPlexUrl(
                    serverUri = server.uri,
                    path = "/library/all",
                    queryParameters = listOf(
                        "type" to "10",
                        "userRating" to FAVORITE_TRACK_RATING.toString(),
                        "includeElements" to "Stream",
                        "includeStreams" to "1",
                        "sort" to "addedAt:asc",
                        "X-Plex-Container-Start" to containerStart.toString(),
                        "X-Plex-Container-Size" to FAVORITE_TRACKS_CONTAINER_SIZE.toString(),
                    ),
                ),
                headers = mapOf("X-Plex-Token" to token),
                accept = "application/json",
            )
            val returned = favoriteTrackMetadataCount(response.body)
            if (returned == 0) break
            val pageTracks = parseFavoriteTracksJson(
                body = response.body,
                serverUri = server.uri,
                token = token,
                sourceId = config.sourceId,
                clientIdentifier = config.clientIdentifier,
                opusMode = config.opusTranscodeMode,
                hiResMode = config.sonosHiResTranscodeMode,
            )
            tracks += pageTracks
            containerStart += returned
            if (returned < FAVORITE_TRACKS_CONTAINER_SIZE) break
        }

        return tracks
    }

    private fun listFavoriteTracksBySection(server: PlexServer, section: PlexSection, token: String): List<PlexTrackStream> {
        val tracks = mutableListOf<PlexTrackStream>()
        var containerStart = 0

        while (true) {
            val response = request(
                method = "GET",
                url = buildPlexUrl(
                    serverUri = server.uri,
                    path = "/library/sections/${section.key}/all",
                    queryParameters = listOf(
                        "type" to "10",
                        "userRating>=" to FAVORITE_TRACK_RATING.toString(),
                        "includeElements" to "Stream",
                        "includeStreams" to "1",
                        "sort" to "addedAt:asc",
                        "X-Plex-Container-Start" to containerStart.toString(),
                        "X-Plex-Container-Size" to PAGED_CONTAINER_SIZE.toString(),
                    ),
                ),
                headers = mapOf("X-Plex-Token" to token),
            )
            val root = parseXml(response.body)
            val nodes = root.getElementsByTagName("Track")
            val returned = nodes.length
            if (returned == 0) break
            val pageTracks = nodes.toTrackStreams(
                serverUri = server.uri,
                token = token,
                sourceId = config.sourceId,
                clientIdentifier = config.clientIdentifier,
                opusMode = config.opusTranscodeMode,
                hiResMode = config.sonosHiResTranscodeMode,
            )
            tracks += pageTracks
            containerStart += returned
            if (returned < PAGED_CONTAINER_SIZE) break
        }

        return tracks
    }

    override suspend fun fetchNearestTracks(ratingKey: String, limit: Int): List<SonicTrackMatch> = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val nearest = parseXml(request(
            "GET",
            buildPlexUrl(server.uri, "/library/metadata/$ratingKey/nearest", listOf("limit" to limit.toString())),
            mapOf("X-Plex-Token" to accessToken),
        ).body).getElementsByTagName("Track")
        val distances = buildList {
            for (index in 0 until nearest.length) {
                val node = nearest.item(index) as? Element ?: continue
                val key = node.getAttribute("ratingKey").trim()
                if (key.isNotBlank()) add(key to node.getAttribute("distance").toDoubleOrNull())
            }
        }
        if (distances.isEmpty()) return@withContext emptyList()
        val hydratedRoot = parseXml(request(
            "GET", "${server.uri}/library/metadata/${distances.joinToString(",") { it.first }}",
            mapOf("X-Plex-Token" to accessToken),
        ).body)
        val hydrated = hydratedRoot.getElementsByTagName("Track").toTrackStreams(
            server.uri, accessToken, config.sourceId, config.clientIdentifier, config.opusTranscodeMode, config.sonosHiResTranscodeMode,
        ).associateBy { it.ratingKey }
        distances.mapNotNull { (key, distance) -> hydrated[key]?.let { SonicTrackMatch(key, distance, it) } }
    }

    override suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val token = resolveToken(); val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val pathRoot = parseXml(request("GET", buildPlexUrl(server.uri, "/library/sections/$sectionKey/computePath", listOf(
            "startID" to startRatingKey, "endID" to endRatingKey,
        )), mapOf("X-Plex-Token" to accessToken)).body)
        val keys = buildList {
            val nodes = pathRoot.getElementsByTagName("Track")
            for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.getAttribute("ratingKey")?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
        }
        if (keys.isEmpty()) return@withContext emptyList()
        parseXml(request("GET", "${server.uri}/library/metadata/${keys.joinToString(",")}", mapOf("X-Plex-Token" to accessToken)).body)
            .getElementsByTagName("Track").toTrackStreams(server.uri, accessToken, config.sourceId, config.clientIdentifier, config.opusTranscodeMode, config.sonosHiResTranscodeMode)
            .associateBy { it.ratingKey }.let { map -> keys.mapNotNull(map::get) }
    }

    override suspend fun createPlaylist(title: String, trackRatingKeys: List<String>): PlexPlaylist = withContext(Dispatchers.IO) {
        require(trackRatingKeys.isNotEmpty()) { "Playlist cannot be empty." }
        val token = resolveToken(); val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val machineId = cachedMachineIdentifier ?: parseXml(request("GET", "${server.uri}/identity", mapOf("X-Plex-Token" to accessToken)).body)
            .getAttribute("machineIdentifier").trim().ifBlank { error("Plex identity omitted machineIdentifier.") }
            .also { cachedMachineIdentifier = it }
        val uri = "server://$machineId/com.plexapp.plugins.library/library/metadata/${trackRatingKeys.joinToString(",")}"
        val root = parseXml(request("POST", buildPlexUrl(server.uri, "/playlists", listOf(
            "uri" to uri, "type" to "audio", "title" to title, "smart" to "0",
        )), mapOf("X-Plex-Token" to accessToken)).body)
        val node = root.getElementsByTagName("Playlist").item(0) as? Element
        PlexPlaylist(node?.getAttribute("ratingKey").orEmpty(), title, null, trackRatingKeys.size, config.sourceId)
    }

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        accept: String = "application/xml",
    ): HttpResponse {
        try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = true
                connectTimeout = 15000
                readTimeout = 30000
                doInput = true
                setRequestProperty("Accept", accept)
                defaultHeaders().plus(headers).forEach { (name, value) ->
                    setRequestProperty(name, value)
                }
            }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            return HttpResponse(
                status = status,
                body = stream?.readFully().orEmpty(),
            ).also {
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    cachedToken = null
                    cachedServer = null
                }
                if (status !in 200..299) {
                    error("HTTP $status from $url: ${it.body.take(400)}")
                }
            }
        } catch (error: IOException) {
            if (config.baseUrl.isNullOrBlank()) cachedServer = null
            throw error
        }
    }

    private fun defaultHeaders(): Map<String, String> = mapOf(
        "X-Plex-Client-Identifier" to config.clientIdentifier,
        "X-Plex-Product" to product,
        "X-Plex-Version" to appVersion,
        "X-Plex-Platform" to platform,
        "X-Plex-Device-Name" to appName,
        "X-Plex-Provides" to "controller",
    )
}

private data class HttpResponse(
    val status: Int,
    val body: String,
)

private data class PlexServerCandidate(
    val server: PlexServer,
    val isPublicDirect: Boolean,
)

private const val ACCESS_EXTERNAL_DTD = "http://javax.xml.XMLConstants/property/accessExternalDTD"
private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

private fun InputStream.readFully(): String =
    bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

private fun parseXml(body: String): Element {
    val normalized = body.trimStart()
    if (!normalized.startsWith("<")) {
        error("响应不是 XML。内容前缀: ${body.take(200)}")
    }

    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = false

    return runCatching {
        factory.newDocumentBuilder()
            .parse(ByteArrayInputStream(body.toByteArray(StandardCharsets.UTF_8)))
            .documentElement
    }.getOrElse { throwable ->
        error("XML 解析失败: ${throwable.message}. 内容前缀: ${body.take(200)}")
    }
}

private fun DocumentBuilderFactory.setSafeFeature(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
}

private fun buildStreamUrl(serverUri: String, partKey: String, token: String): String {
    val separator = if (partKey.contains("?")) "&" else "?"
    return "${serverUri.trimEnd('/')}$partKey$separator${"X-Plex-Token"}=$token"
}

internal const val PLEX_MP3_320K_TRANSCODE_PROFILE =
    "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=mp3&audioCodec=mp3&maxAudioBitrate=320&replace=true)"

internal const val PLEX_FLAC_TRANSCODE_PROFILE =
    "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=flac&audioCodec=flac&replace=true)"

internal fun buildPlaybackUrl(
    serverUri: String,
    ratingKey: String,
    partKey: String,
    sourceAudioCodec: String?,
    sourceAudioSampleRate: Int? = null,
    token: String,
    clientIdentifier: String,
    opusMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT,
    hiResMode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.DEFAULT,
): String = if (sourceAudioCodec.equals("opus", ignoreCase = true)) {
    when (opusMode) {
        OpusTranscodeMode.DIRECT_MP3 -> buildPlexUrl(
            serverUri = serverUri,
            path = "/music/:/transcode/universal/start.mp3",
            queryParameters = listOf(
                "path" to "/library/metadata/$ratingKey",
                "mediaIndex" to "0",
                "partIndex" to "0",
                "protocol" to "http",
                "offset" to "0",
                "directPlay" to "0",
                "directStream" to "0",
                "directStreamAudio" to "0",
                "audioCodec" to "mp3",
                "maxAudioBitrate" to "320",
                "audioChannelCount" to "2",
                "location" to "lan",
                "X-Plex-Client-Identifier" to clientIdentifier,
                "X-Plex-Client-Profile-Name" to "generic",
                "X-Plex-Client-Profile-Extra" to PLEX_MP3_320K_TRANSCODE_PROFILE,
                "X-Plex-Product" to "MusicBridge",
                "X-Plex-Version" to "0.1.0",
                "X-Plex-Platform" to "Android",
                "X-Plex-Device-Name" to "MusicBridge",
                "X-Plex-Token" to token,
            ),
        )
        OpusTranscodeMode.PROXY_FLAC -> buildPlexUrl(
            serverUri = serverUri,
            path = "/music/:/transcode/universal/start.flac",
            queryParameters = listOf(
                "path" to "/library/metadata/$ratingKey",
                "mediaIndex" to "0",
                "partIndex" to "0",
                "protocol" to "http",
                "offset" to "0",
                "directPlay" to "0",
                "directStream" to "0",
                "directStreamAudio" to "0",
                "audioChannelCount" to "2",
                "location" to "lan",
                "X-Plex-Client-Identifier" to clientIdentifier,
                "X-Plex-Client-Profile-Name" to "generic",
                "X-Plex-Client-Profile-Extra" to PLEX_FLAC_TRANSCODE_PROFILE,
                "X-Plex-Product" to "MusicBridge",
                "X-Plex-Version" to "0.1.0",
                "X-Plex-Platform" to "Android",
                "X-Plex-Device-Name" to "MusicBridge",
                "X-Plex-Token" to token,
            ),
        )
    }
} else if (
    hiResMode == SonosHiResTranscodeMode.TRANSCODE_MP3 &&
    sourceAudioCodec?.lowercase() in LOSSLESS_AUDIO_CODECS &&
    (sourceAudioSampleRate ?: 0) > SONOS_MAX_SAMPLE_RATE_HZ
) {
    buildPlexUrl(
        serverUri = serverUri,
        path = "/music/:/transcode/universal/start.mp3",
        queryParameters = listOf(
            "path" to "/library/metadata/$ratingKey",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "http",
            "offset" to "0",
            "directPlay" to "0",
            "directStream" to "0",
            "directStreamAudio" to "0",
            "audioCodec" to "mp3",
            "maxAudioBitrate" to "320",
            "audioChannelCount" to "2",
            "location" to "lan",
            "X-Plex-Client-Identifier" to clientIdentifier,
            "X-Plex-Client-Profile-Name" to "generic",
            "X-Plex-Client-Profile-Extra" to PLEX_MP3_320K_TRANSCODE_PROFILE,
            "X-Plex-Product" to "MusicBridge",
            "X-Plex-Version" to "0.1.0",
            "X-Plex-Platform" to "Android",
            "X-Plex-Device-Name" to "MusicBridge",
            "X-Plex-Token" to token,
        ),
    )
} else {
    buildStreamUrl(serverUri, partKey, token)
}

internal fun String?.transcodedPlaybackMimeType(
    opusMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT,
    sourceAudioSampleRate: Int? = null,
    hiResMode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.DEFAULT,
): String? = when {
    equals("opus", ignoreCase = true) -> opusMode.mimeType
    hiResMode == SonosHiResTranscodeMode.TRANSCODE_MP3 &&
        this?.lowercase() in LOSSLESS_AUDIO_CODECS &&
        (sourceAudioSampleRate ?: 0) > SONOS_MAX_SAMPLE_RATE_HZ -> "audio/mpeg"
    else -> null
}

internal fun PlexTrackStream.withSonosHiResTranscodeMode(
    targetMode: SonosHiResTranscodeMode,
    clientIdentifier: String = "",
): PlexTrackStream {
    val codec = sourceAudioCodec?.lowercase()
    if (codec !in LOSSLESS_AUDIO_CODECS) return this
    val baseUrl = streamUrl.substringBefore('?')
    val query = streamUrl.substringAfter('?', "")
    val token = query.split('&').firstOrNull { it.startsWith("X-Plex-Token=") }
        ?.substringAfter("X-Plex-Token=").orEmpty()
    val serverUri = when {
        baseUrl.contains("/library/parts") -> baseUrl.substringBefore("/library/parts")
        baseUrl.contains("/music/:/transcode") -> baseUrl.substringBefore("/music/:/transcode")
        else -> ""
    }
    if (serverUri.isBlank() || token.isBlank()) return this
    val updatedUrl = buildPlaybackUrl(
        serverUri = serverUri,
        ratingKey = ratingKey,
        partKey = partKey,
        sourceAudioCodec = sourceAudioCodec,
        sourceAudioSampleRate = sourceAudioSampleRate,
        token = token,
        clientIdentifier = clientIdentifier,
        opusMode = OpusTranscodeMode.DEFAULT,
        hiResMode = targetMode,
    )
    return copy(
        streamUrl = updatedUrl,
        playbackMimeType = sourceAudioCodec.transcodedPlaybackMimeType(
            sourceAudioSampleRate = sourceAudioSampleRate,
            hiResMode = targetMode,
        ),
    )
}

/**
 * Starts a new Plex universal-transcode stream at the requested source-track time.
 * A Sonos REL_TIME seek is not reliable for Plex's live MP3 response, so Opus direct
 * playback uses this URL to create a fresh, time-offset transcode session instead.
 */
internal fun String.withPlexTranscodeOffsetSeconds(offsetSeconds: Int): String {
    if (!contains("/:/transcode/universal/start", ignoreCase = true)) return this

    val safeOffset = offsetSeconds.coerceAtLeast(0).toString()
    val fragment = substringAfter('#', "").takeIf { contains('#') }
    val withoutFragment = substringBefore('#')
    val baseUrl = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', "")
    var replaced = false
    val updatedQuery = query
        .split('&')
        .filter { it.isNotBlank() }
        .map { parameter ->
            if (parameter.substringBefore('=').equals("offset", ignoreCase = true)) {
                replaced = true
                "offset=$safeOffset"
            } else {
                parameter
            }
        }
        .toMutableList()
        .apply {
            if (!replaced) add("offset=$safeOffset")
        }
        .joinToString("&")

    return buildString {
        append(baseUrl)
        if (updatedQuery.isNotEmpty()) append('?').append(updatedQuery)
        fragment?.let { append('#').append(it) }
    }
}

internal fun String.withPlexTranscodeSession(sessionId: String): String {
    if (!contains("/:/transcode/universal/start", ignoreCase = true)) return this

    val fragment = substringAfter('#', "").takeIf { contains('#') }
    val withoutFragment = substringBefore('#')
    val baseUrl = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', "")
    val replacements = linkedMapOf(
        "session" to sessionId,
        "X-Plex-Session-Identifier" to sessionId,
    )
    val seen = mutableSetOf<String>()
    val updated = query.split('&')
        .filter(String::isNotBlank)
        .map { parameter ->
            val name = parameter.substringBefore('=')
            val replacement = replacements.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            if (replacement == null) {
                parameter
            } else {
                seen += replacement.key
                "${replacement.key}=${replacement.value}"
            }
        }
        .toMutableList()
        .apply {
            replacements.forEach { (name, value) -> if (name !in seen) add("$name=$value") }
        }

    return buildString {
        append(baseUrl)
        if (updated.isNotEmpty()) append('?').append(updated.joinToString("&"))
        fragment?.let { append('#').append(it) }
    }
}

private val plexTranscodeStopExecutor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "plex-transcode-stop").apply { isDaemon = true }
}

/** Best-effort release of a PMS music transcoder process. Never blocks the caller. */
internal fun stopPlexTranscodeSession(serverUri: String, token: String, sessionId: String) {
    runCatching {
        plexTranscodeStopExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val url = URL(
                    "${serverUri.trimEnd('/')}/music/:/transcode/universal/stop" +
                        "?session=${URLEncoder.encode(sessionId, StandardCharsets.UTF_8.name())}" +
                        "&X-Plex-Token=${URLEncoder.encode(token, StandardCharsets.UTF_8.name())}",
                )
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 2_000
                    readTimeout = 2_000
                    requestMethod = "GET"
                }
                connection.responseCode
            } catch (_: Exception) {
                // Session cleanup is best effort and must not affect playback.
            } finally {
                connection?.disconnect()
            }
        }
    }
}

internal fun PlexTrackStream.withOpusTranscodeMode(
    targetMode: OpusTranscodeMode,
    clientIdentifier: String = "",
): PlexTrackStream {
    if (!sourceAudioCodec.equals("opus", ignoreCase = true)) {
        return this
    }
    val targetMime = targetMode.mimeType
    val updatedUrl = when (targetMode) {
        OpusTranscodeMode.DIRECT_MP3 -> {
            if (streamUrl.contains("/music/:/transcode/universal/start.mp3")) {
                streamUrl
            } else if (streamUrl.contains("/music/:/transcode/universal/start.flac")) {
                val base = streamUrl
                    .replace("/music/:/transcode/universal/start.flac", "/music/:/transcode/universal/start.mp3")
                    .replace(PLEX_FLAC_TRANSCODE_PROFILE.urlEncode(), PLEX_MP3_320K_TRANSCODE_PROFILE.urlEncode())
                    .replace(PLEX_FLAC_TRANSCODE_PROFILE, PLEX_MP3_320K_TRANSCODE_PROFILE)
                if (!base.contains("audioCodec=")) {
                    val separator = if (base.contains("?")) "&" else "?"
                    "$base${separator}audioCodec=mp3&maxAudioBitrate=320"
                } else {
                    base
                }
            } else {
                val baseUrl = streamUrl.substringBefore('?')
                val query = streamUrl.substringAfter('?', "")
                val token = query.split('&')
                    .firstOrNull { it.startsWith("X-Plex-Token=") }
                    ?.substringAfter("X-Plex-Token=").orEmpty()
                val serverUri = baseUrl.substringBefore("/library/parts")
                if (serverUri.isNotBlank() && token.isNotBlank()) {
                    buildPlaybackUrl(
                        serverUri = serverUri,
                        ratingKey = ratingKey,
                        partKey = partKey,
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        token = token,
                        clientIdentifier = clientIdentifier,
                        opusMode = targetMode,
                    )
                } else {
                    streamUrl
                }
            }
        }
        OpusTranscodeMode.PROXY_FLAC -> {
            if (streamUrl.contains("/music/:/transcode/universal/start.flac")) {
                streamUrl
            } else if (streamUrl.contains("/music/:/transcode/universal/start.mp3")) {
                streamUrl
                    .replace("/music/:/transcode/universal/start.mp3", "/music/:/transcode/universal/start.flac")
                    .replace(PLEX_MP3_320K_TRANSCODE_PROFILE.urlEncode(), PLEX_FLAC_TRANSCODE_PROFILE.urlEncode())
                    .replace(PLEX_MP3_320K_TRANSCODE_PROFILE, PLEX_FLAC_TRANSCODE_PROFILE)
                    .replace("&audioCodec=mp3", "")
                    .replace("audioCodec=mp3&", "")
                    .replace("?audioCodec=mp3", "?")
                    .replace("&maxAudioBitrate=320", "")
                    .replace("maxAudioBitrate=320&", "")
                    .replace("?maxAudioBitrate=320", "?")
            } else {
                val baseUrl = streamUrl.substringBefore('?')
                val query = streamUrl.substringAfter('?', "")
                val token = query.split('&')
                    .firstOrNull { it.startsWith("X-Plex-Token=") }
                    ?.substringAfter("X-Plex-Token=").orEmpty()
                val serverUri = baseUrl.substringBefore("/library/parts")
                if (serverUri.isNotBlank() && token.isNotBlank()) {
                    buildPlaybackUrl(
                        serverUri = serverUri,
                        ratingKey = ratingKey,
                        partKey = partKey,
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        token = token,
                        clientIdentifier = clientIdentifier,
                        opusMode = targetMode,
                    )
                } else {
                    streamUrl
                }
            }
        }
    }
    return copy(
        streamUrl = updatedUrl,
        playbackMimeType = targetMime,
    )
}

/**
 * Rewrites a Plex track's stream URL for on-device playback at the requested quality.
 * Sources at or below the selected network ceiling are played directly. Larger Plex files
 * use an Ogg/Opus universal transcode at the independently selected Opus bitrate. Non-Plex
 * sources (032) are returned unchanged for now.
 *
 * Note [streamUrl] is built for Sonos: opus tracks arrive as live-transcode URLs that
 * support neither ranges nor reuse. Those are normalized back to the direct file URL
 * first — on-device playback has no use for the Sonos opus workarounds.
 */
internal fun PlexTrackStream.willTranscodeLocally(policy: LocalPlaybackPolicy): Boolean {
    // 032 源走服务端 transcode.ogg，判定见 Music032LocalTranscode；以下为 Plex 逻辑。
    if (Music032LocalTranscode.isMusic032Track(this)) {
        return Music032LocalTranscode.transcodeUrl(this, policy) != null
    }
    val direct = withDirectFileUrl()
    val limitKbps = policy.streamingLimit.maxSourceBitrateKbps ?: return false
    val sourceBitrate = direct.sourceAudioBitrateKbps
    val isNativeOpusWithUnknownBitrate =
        direct.sourceAudioCodec.equals("opus", ignoreCase = true) && sourceBitrate == null
    if (isNativeOpusWithUnknownBitrate || (sourceBitrate != null && sourceBitrate <= limitKbps)) return false

    val baseUrl = direct.streamUrl.substringBefore('?')
    val token = direct.streamUrl.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("X-Plex-Token=") }
        ?.substringAfter("X-Plex-Token=")
        .orEmpty()
    return baseUrl.contains("/library/parts") && token.isNotBlank()
}

internal fun PlexTrackStream.withLocalPlaybackPolicy(
    policy: LocalPlaybackPolicy,
    clientIdentifier: String,
): PlexTrackStream {
    if (Music032LocalTranscode.isMusic032Track(this)) {
        val transcodeUrl = Music032LocalTranscode.transcodeUrl(this, policy)
        android.util.Log.i(
            "LocalPlaybackPolicy",
            "source=032 codec=${sourceAudioCodec.orEmpty()}, sourceKbps=${sourceAudioBitrateKbps ?: -1}, " +
                "limitKbps=${policy.streamingLimit.maxSourceBitrateKbps ?: -1}, transcode=${transcodeUrl != null}",
        )
        return if (transcodeUrl == null) this else copy(streamUrl = transcodeUrl, playbackMimeType = "audio/ogg")
    }
    val direct = withDirectFileUrl()
    val shouldTranscode = direct.willTranscodeLocally(policy)
    android.util.Log.i(
        "LocalPlaybackPolicy",
        "codec=${direct.sourceAudioCodec.orEmpty()}, sourceKbps=${direct.sourceAudioBitrateKbps ?: -1}, " +
            "limitKbps=${policy.streamingLimit.maxSourceBitrateKbps ?: -1}, transcode=$shouldTranscode",
    )
    if (!shouldTranscode) return direct
    val bitrateKbps = policy.opusBitrate.kbps
    // Direct file URLs look like "<server>/library/parts/<id>/<ts>/file.ext?X-Plex-Token=...".
    val baseUrl = direct.streamUrl.substringBefore('?')
    val serverUri = baseUrl.substringBefore("/library/parts")
    val token = direct.streamUrl.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("X-Plex-Token=") }
        ?.substringAfter("X-Plex-Token=")
        .orEmpty()

    val profile =
        "add-transcode-target(type=musicProfile&context=streaming&protocol=http&container=ogg&audioCodec=opus&maxAudioBitrate=$bitrateKbps&replace=true)"
    val transcodedUrl = buildPlexUrl(
        serverUri = serverUri,
        path = "/music/:/transcode/universal/start.ogg",
        queryParameters = listOf(
            "path" to "/library/metadata/$ratingKey",
            "mediaIndex" to "0",
            "partIndex" to "0",
            "protocol" to "http",
            "offset" to "0",
            "directPlay" to "0",
            "directStream" to "0",
            "directStreamAudio" to "0",
            "audioCodec" to "opus",
            "maxAudioBitrate" to bitrateKbps.toString(),
            "audioChannelCount" to "2",
            "location" to "lan",
            "X-Plex-Client-Identifier" to clientIdentifier,
            "X-Plex-Client-Profile-Name" to "generic",
            "X-Plex-Client-Profile-Extra" to profile,
            "X-Plex-Product" to "MusicBridge",
            "X-Plex-Version" to "0.1.0",
            "X-Plex-Platform" to "Android",
            "X-Plex-Device-Name" to "MusicBridge",
            "X-Plex-Token" to token,
        ),
    )
    return direct.copy(streamUrl = transcodedUrl, playbackMimeType = "audio/ogg")
}

/**
 * Returns this track with [streamUrl] pointing at the original file on the server.
 * Sonos-targeted live-transcode URLs (the opus workarounds) are rebuilt from [partKey];
 * everything else is already a direct URL. ExoPlayer sniffs the container itself, so the
 * Sonos-specific [playbackMimeType] override is dropped as well.
 */
private fun PlexTrackStream.withDirectFileUrl(): PlexTrackStream {
    if (!streamUrl.contains("/:/transcode/universal/start", ignoreCase = true)) return this
    if (partKey.isBlank()) return this
    val serverUri = streamUrl.substringBefore('?')
        .substringBefore("/music/:/transcode", missingDelimiterValue = "")
    val token = streamUrl.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("X-Plex-Token=") }
        ?.substringAfter("X-Plex-Token=")
        .orEmpty()
    if (serverUri.isBlank() || token.isBlank()) return this
    return copy(streamUrl = buildStreamUrl(serverUri, partKey, token), playbackMimeType = null)
}

private fun buildMediaUrl(serverUri: String, mediaPath: String, token: String): String {
    val separator = if (mediaPath.contains("?")) "&" else "?"
    return "${serverUri.trimEnd('/')}$mediaPath$separator${"X-Plex-Token"}=$token"
}

private fun buildRateUrl(serverUri: String, ratingKey: String, rating: Int): String =
    "${serverUri.trimEnd('/')}/:/rate?identifier=com.plexapp.plugins.library&key=$ratingKey&rating=$rating"

private fun buildTimelineUrl(
    serverUri: String,
    ratingKey: String,
    state: String,
    timeMillis: Long,
    durationMillis: Long?,
    continuing: Boolean,
): String = buildPlexUrl(
    serverUri = serverUri,
    path = "/:/timeline",
    queryParameters = listOfNotNull(
        "containerKey" to "/playQueues/0",
        "key" to "/library/metadata/$ratingKey",
        "ratingKey" to ratingKey,
        "identifier" to "com.plexapp.plugins.library",
        "state" to state,
        "time" to timeMillis.toString(),
        durationMillis?.let { "duration" to it.toString() },
        "continuing" to if (continuing) "1" else "0",
    ),
)

private fun buildScrobbleUrl(serverUri: String, ratingKey: String): String = buildPlexUrl(
    serverUri = serverUri,
    path = "/:/scrobble",
    queryParameters = listOf(
        "identifier" to "com.plexapp.plugins.library",
        "key" to ratingKey,
    ),
)

private fun buildPlexUrl(
    serverUri: String,
    path: String,
    queryParameters: List<Pair<String, String>>,
): String {
    val query = queryParameters.joinToString("&") { (key, value) ->
        "${key.urlEncode()}=${value.urlEncode()}"
    }
    return "${serverUri.trimEnd('/')}$path?$query"
}

private fun Element.childText(tagName: String): String? {
    val nodes = getElementsByTagName(tagName)
    if (nodes.length == 0) return null
    return nodes.item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
}

private fun Element.firstPartKey(): String? {
    val parts = getElementsByTagName("Part")
    for (index in 0 until parts.length) {
        val part = parts.item(index) as? Element ?: continue
        val key = part.getAttribute("key").trim()
        if (key.isNotBlank()) {
            return key
        }
    }
    return null
}

private fun Element.firstLyricUrl(serverUri: String, token: String): String? {
    val streams = getElementsByTagName("Stream")
    for (index in 0 until streams.length) {
        val stream = streams.item(index) as? Element ?: continue
        if (stream.getAttribute("streamType").trim() != "4") continue
        val key = stream.getAttribute("key").trim().ifBlank { null } ?: continue
        return buildStreamUrl(serverUri, key, token)
    }
    return null
}

private fun Element.firstAudioCodec(): String? {
    val mediaNodes = getElementsByTagName("Media")
    for (index in 0 until mediaNodes.length) {
        val media = mediaNodes.item(index) as? Element ?: continue
        media.getAttribute("audioCodec").trim().ifBlank { null }?.let { return it }

        val streams = media.getElementsByTagName("Stream")
        for (streamIndex in 0 until streams.length) {
            val stream = streams.item(streamIndex) as? Element ?: continue
            if (stream.getAttribute("streamType").trim() != "2") continue
            stream.getAttribute("codec").trim().ifBlank { null }?.let { return it }
        }
    }
    return null
}

internal fun parseFirstAudioSampleRateFromXml(body: String): Int? =
    parseXml(body).firstAudioSampleRate()

internal fun parseFirstAudioSampleRateFromJson(body: String): Int? {
    val root = JSONObject(body)
    val media = root.optJSONObject("MediaContainer")
        ?.optJSONArray("Metadata")
        ?.optJSONObject(0)
        ?.optJSONArray("Media")
        ?.optJSONObject(0)
        ?: root.optJSONObject("Media")
        ?: root
    return media.firstAudioSampleRate()
}

internal fun Element.firstAudioSampleRate(): Int? {
    val mediaNodes = getElementsByTagName("Media")
    for (index in 0 until mediaNodes.length) {
        val media = mediaNodes.item(index) as? Element ?: continue
        val streams = media.getElementsByTagName("Stream")
        for (streamIndex in 0 until streams.length) {
            val stream = streams.item(streamIndex) as? Element ?: continue
            if (stream.getAttribute("streamType").trim() != "2") continue
            stream.getAttribute("samplingRate").trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        media.getAttribute("samplingRate").trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    }
    return null
}

internal fun Element.firstAudioBitDepth(): Int? {
    val mediaNodes = getElementsByTagName("Media")
    for (index in 0 until mediaNodes.length) {
        val media = mediaNodes.item(index) as? Element ?: continue
        val streams = media.getElementsByTagName("Stream")
        for (streamIndex in 0 until streams.length) {
            val stream = streams.item(streamIndex) as? Element ?: continue
            if (stream.getAttribute("streamType").trim() != "2") continue
            stream.getAttribute("bitDepth").trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
        }
        media.getAttribute("bitDepth").trim().toIntOrNull()?.takeIf { it > 0 }?.let { return it }
    }
    return null
}

private fun Element.firstAudioBitrateKbps(): Int? {
    val mediaNodes = getElementsByTagName("Media")
    for (index in 0 until mediaNodes.length) {
        val media = mediaNodes.item(index) as? Element ?: continue
        media.getAttribute("bitrate").trim().toIntOrNull()?.let { return it }
    }
    val durationMillis = getAttribute("duration").trim().toLongOrNull()?.takeIf { it > 0L }
        ?: return null
    val partNodes = getElementsByTagName("Part")
    for (index in 0 until partNodes.length) {
        val part = partNodes.item(index) as? Element ?: continue
        val sizeBytes = part.getAttribute("size").trim().toLongOrNull()?.takeIf { it > 0L } ?: continue
        return ((sizeBytes * 8L) / durationMillis).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
    return null
}

private fun Element.trackThumbUrl(serverUri: String, token: String): String? {
    val mediaPath = getAttribute("thumb")
        .trim()
        .ifBlank { getAttribute("parentThumb").trim() }
        .ifBlank { null }
        ?: return null
    return buildMediaUrl(serverUri, mediaPath, token)
}

private fun org.w3c.dom.NodeList.toTrackStreams(
    serverUri: String,
    token: String,
    sourceId: String,
    clientIdentifier: String,
    opusMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT,
    hiResMode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.DEFAULT,
): List<PlexTrackStream> =
    buildList {
        for (index in 0 until length) {
            val track = item(index) as? Element ?: continue
            val title = track.getAttribute("title").trim()
            val ratingKey = track.getAttribute("ratingKey").trim()
            val durationMillis = track.getAttribute("duration").trim().toLongOrNull()
            if (title.isBlank() || ratingKey.isBlank()) {
                continue
            }

            val partKey = track.firstPartKey() ?: continue
            val sourceAudioCodec = track.firstAudioCodec()
            val sourceAudioSampleRate = track.firstAudioSampleRate()
            val behavior = track.trackBehavior()
            add(
                PlexTrackStream(
                    ratingKey = ratingKey,
                    title = title,
                    streamUrl = buildPlaybackUrl(
                        serverUri = serverUri,
                        ratingKey = ratingKey,
                        partKey = partKey,
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        token = token,
                        clientIdentifier = clientIdentifier,
                        opusMode = opusMode,
                        hiResMode = hiResMode,
                    ),
                    partKey = partKey,
                    durationMillis = durationMillis,
                    userRating = track.getAttribute("userRating").trim().toFloatOrNull(),
                    albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                    albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                    artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                    thumbUrl = track.trackThumbUrl(serverUri, token),
                    sourceAudioCodec = sourceAudioCodec,
                    sourceAudioBitrateKbps = track.firstAudioBitrateKbps(),
                    sourceAudioSampleRate = sourceAudioSampleRate,
                    bitDepth = track.firstAudioBitDepth(),
                    playbackMimeType = sourceAudioCodec.transcodedPlaybackMimeType(opusMode, sourceAudioSampleRate, hiResMode),
                    lyricUrl = track.firstLyricUrl(serverUri, token),
                    sourceId = sourceId,
                    musicAnalysisVersion = behavior.musicAnalysisVersion,
                    viewCount = behavior.viewCount,
                    lastViewedAtEpochSeconds = behavior.lastViewedAtEpochSeconds,
                    skipCount = behavior.skipCount,
                    discNumber = track.getAttribute("parentIndex").trim().toIntOrNull(),
                    trackNumber = track.getAttribute("index").trim().toIntOrNull(),
                )
            )
        }
    }

private fun favoriteTrackMetadataCount(body: String): Int =
    JSONObject(body)
        .optJSONObject("MediaContainer")
        ?.optJSONArray("Metadata")
        ?.length()
        ?: 0

private fun parseFavoriteTracksJson(
    body: String,
    serverUri: String,
    token: String,
    sourceId: String,
    clientIdentifier: String,
    opusMode: OpusTranscodeMode = OpusTranscodeMode.DEFAULT,
    hiResMode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.DEFAULT,
): List<PlexTrackStream> {
    val metadata = JSONObject(body)
        .optJSONObject("MediaContainer")
        ?.optJSONArray("Metadata")
        ?: return emptyList()

    return buildList {
        for (index in 0 until metadata.length()) {
            val track = metadata.optJSONObject(index) ?: continue
            val title = track.optString("title").trim()
            val ratingKey = track.optString("ratingKey").trim()
            if (title.isBlank() || ratingKey.isBlank()) {
                continue
            }

            val media = track.optJSONArray("Media")?.optJSONObject(0) ?: continue
            val part = media.optJSONArray("Part")?.optJSONObject(0) ?: continue
            val partKey = part.optString("key").trim()
            if (partKey.isBlank()) {
                continue
            }
            val sourceAudioCodec = media.firstAudioCodec()
            val sourceAudioSampleRate = media.firstAudioSampleRate()
            val behavior = track.trackBehavior()

            add(
                PlexTrackStream(
                    ratingKey = ratingKey,
                    title = title,
                    streamUrl = buildPlaybackUrl(
                        serverUri = serverUri,
                        ratingKey = ratingKey,
                        partKey = partKey,
                        sourceAudioCodec = sourceAudioCodec,
                        sourceAudioSampleRate = sourceAudioSampleRate,
                        token = token,
                        clientIdentifier = clientIdentifier,
                        opusMode = opusMode,
                        hiResMode = hiResMode,
                    ),
                    partKey = partKey,
                    durationMillis = track.optLongOrNull("duration"),
                    userRating = track.optFloatOrNull("userRating"),
                    albumRatingKey = track.optString("parentRatingKey").trim().ifBlank { null },
                    albumTitle = track.optString("parentTitle").trim().ifBlank { null },
                    artistName = track.optString("grandparentTitle").trim().ifBlank { null },
                    thumbUrl = track.optTrackThumbUrl(serverUri, token),
                    sourceAudioCodec = sourceAudioCodec,
                    sourceAudioBitrateKbps = media.optInt("bitrate").takeIf { it > 0 }
                        ?: part.optLong("size").takeIf { it > 0L }?.let { sizeBytes ->
                            track.optLongOrNull("duration")?.takeIf { it > 0L }?.let { durationMillis ->
                                ((sizeBytes * 8L) / durationMillis).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                            }
                        },
                    sourceAudioSampleRate = sourceAudioSampleRate,
                    bitDepth = media.firstAudioBitDepth(),
                    playbackMimeType = sourceAudioCodec.transcodedPlaybackMimeType(opusMode, sourceAudioSampleRate, hiResMode),
                    sourceId = sourceId,
                    musicAnalysisVersion = behavior.musicAnalysisVersion,
                    viewCount = behavior.viewCount,
                    lastViewedAtEpochSeconds = behavior.lastViewedAtEpochSeconds,
                    skipCount = behavior.skipCount,
                )
            )
        }
    }
}

internal fun JSONObject.firstAudioSampleRate(): Int? {
    fun JSONObject.positiveInt(name: String): Int? = opt(name)?.toString()?.toIntOrNull()?.takeIf { it > 0 }
    val parts = optJSONArray("Part")
    if (parts != null) {
        for (partIndex in 0 until parts.length()) {
            val streams = parts.optJSONObject(partIndex)?.optJSONArray("Stream") ?: continue
            for (streamIndex in 0 until streams.length()) {
                val stream = streams.optJSONObject(streamIndex) ?: continue
                if (stream.opt("streamType")?.toString() != "2") continue
                stream.positiveInt("samplingRate")?.let { return it }
            }
        }
    }
    return positiveInt("samplingRate")
}

internal fun JSONObject.firstAudioBitDepth(): Int? {
    fun JSONObject.positiveInt(name: String): Int? = opt(name)?.toString()?.toIntOrNull()?.takeIf { it > 0 }
    val parts = optJSONArray("Part")
    if (parts != null) {
        for (partIndex in 0 until parts.length()) {
            val streams = parts.optJSONObject(partIndex)?.optJSONArray("Stream") ?: continue
            for (streamIndex in 0 until streams.length()) {
                val stream = streams.optJSONObject(streamIndex) ?: continue
                if (stream.opt("streamType")?.toString() != "2") continue
                stream.positiveInt("bitDepth")?.let { return it }
            }
        }
    }
    return positiveInt("bitDepth")
}

private fun JSONObject.firstAudioCodec(): String? {
    optString("audioCodec").trim().ifBlank { null }?.let { return it }
    val parts = optJSONArray("Part") ?: return null
    for (partIndex in 0 until parts.length()) {
        val streams = parts.optJSONObject(partIndex)?.optJSONArray("Stream") ?: continue
        for (streamIndex in 0 until streams.length()) {
            val stream = streams.optJSONObject(streamIndex) ?: continue
            if (stream.optInt("streamType") != 2) continue
            stream.optString("codec").trim().ifBlank { null }?.let { return it }
        }
    }
    return null
}

internal fun playbackUrlIdentity(url: String): String {
    val baseUrl = url.substringBefore('?')
    if (!baseUrl.contains("/:/transcode/universal/start", ignoreCase = true)) {
        return baseUrl
    }
    val path = url.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.substringBefore('=').equals("path", ignoreCase = true) }
        ?.substringAfter('=', "")
        ?.takeIf { it.isNotBlank() }
        ?.let(::decodeUrlComponent)
    return path?.let { "$baseUrl?path=$it" } ?: baseUrl
}

/**
 * Sonos may expose a percent-decoded TrackURI even when SetAVTransportURI received
 * Plex's encoded query string. Compare the decoded media path so these equivalent
 * representations do not look like a track change.
 */
private fun decodeUrlComponent(value: String): String =
    runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }
        .getOrDefault(value)

private fun JSONObject.optTrackThumbUrl(serverUri: String, token: String): String? {
    val mediaPath = optString("thumb")
        .trim()
        .ifBlank { optString("parentThumb").trim() }
        .ifBlank { null }
        ?: return null
    return buildMediaUrl(serverUri, mediaPath, token)
}

private fun JSONObject.optLongOrNull(name: String): Long? =
    when (val value = opt(name)) {
        is Number -> value.toLong()
        is String -> value.trim().toLongOrNull()
        else -> null
    }

private fun JSONObject.optFloatOrNull(name: String): Float? =
    when (val value = opt(name)) {
        is Number -> value.toFloat()
        is String -> value.trim().toFloatOrNull()
        else -> null
    }

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())
