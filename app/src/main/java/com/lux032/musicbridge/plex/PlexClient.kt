package com.lux032.musicbridge.plex

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class PlexAuthConfig(
    val username: String?,
    val password: String?,
    val preferredServer: String?,
    val baseUrl: String?,
    val token: String?,
    val clientIdentifier: String = UUID.randomUUID().toString(),
)

data class PlexServer(val name: String, val uri: String, val accessToken: String?)

data class PlexSection(val key: String, val title: String, val type: String)

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
)

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
)

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

class PlexClient(private val config: PlexAuthConfig) {
    private val appName = "PlexToSonosPlayer"
    private val appVersion = "0.1.0"
    private val product = "Android App"
    private val platform = "Android ${Build.VERSION.RELEASE ?: "Unknown"}"
    private var cachedToken: String? = null
    private var cachedServer: PlexServer? = null

    suspend fun fetchAlbumNames(): PlexAlbumResult = withContext(Dispatchers.IO) {
        val albumsResult = fetchAlbums()
        PlexAlbumResult(
            serverName = albumsResult.serverName,
            sections = albumsResult.sections,
            albums = albumsResult.albums.map(PlexAlbum::title),
        )
    }

    suspend fun fetchAlbums(): PlexAlbumsResult = withContext(Dispatchers.IO) {
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

    suspend fun fetchArtists(): PlexArtistsResult = withContext(Dispatchers.IO) {
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

    suspend fun fetchFavoriteTracks(): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val sections = listMusicSections(server, token)
        if (sections.isEmpty()) {
            emptyList()
        } else {
            sections
                .flatMap { section -> listFavoriteTracks(server, section, accessToken) }
                .distinctBy(PlexTrackStream::ratingKey)
                .sortedBy { it.title.lowercase() }
        }
    }

    suspend fun fetchAlbumTrackStreams(albumTitle: String): PlexAlbumTracksResult = withContext(Dispatchers.IO) {
        val normalizedAlbumTitle = albumTitle.trim()
        require(normalizedAlbumTitle.isNotEmpty()) { "缺少专辑名称。" }

        val matchedAlbum = fetchAlbums().albums
            .firstOrNull { it.title.equals(normalizedAlbumTitle, ignoreCase = true) }
            ?: error("未找到专辑 '$normalizedAlbumTitle'。")

        fetchAlbumTrackStreams(matchedAlbum)
    }

    suspend fun fetchAlbumTrackStreams(album: PlexAlbum): PlexAlbumTracksResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        PlexAlbumTracksResult(
            serverName = server.name,
            album = album,
            tracks = listAlbumTracks(server, album, token),
        )
    }

    suspend fun fetchAllAlbumTracks(
        albums: List<PlexAlbum>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, List<PlexTrackStream>> = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val total = albums.size
        val result = mutableMapOf<String, List<PlexTrackStream>>()
        albums.forEachIndexed { index, album ->
            runCatching {
                result[album.ratingKey] = listAlbumTracks(server, album, token)
            }
            onProgress(index + 1, total)
        }
        result
    }

    suspend fun fetchPlaylists(): PlexPlaylistsResult = withContext(Dispatchers.IO) {
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
                ))
            }
        }
        PlexPlaylistsResult(serverName = server.name, playlists = playlistList)
    }

    suspend fun fetchPlaylistTracks(playlist: PlexPlaylist): PlexPlaylistTracksResult = withContext(Dispatchers.IO) {
        val token = resolveToken()
        val server = resolveServer(token)
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val response = request(
            method = "GET",
            url = "${server.uri}/playlists/${playlist.ratingKey}/items",
            headers = mapOf("X-Plex-Token" to accessToken),
        )
        val root = parseXml(response.body)
        val tracks = root.getElementsByTagName("Track")
        val trackList = buildList {
            for (i in 0 until tracks.length) {
                val track = tracks.item(i) as? Element ?: continue
                val media = track.getElementsByTagName("Media").item(0) as? Element ?: continue
                val part = media.getElementsByTagName("Part").item(0) as? Element ?: continue
                val partKey = part.getAttribute("key")
                add(PlexTrackStream(
                    ratingKey = track.getAttribute("ratingKey"),
                    title = track.getAttribute("title"),
                    streamUrl = "${server.uri}$partKey?X-Plex-Token=$accessToken",
                    partKey = partKey,
                    durationMillis = track.getAttribute("duration").toLongOrNull(),
                    userRating = track.getAttribute("userRating").toFloatOrNull(),
                    albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                    albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                    artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                    thumbUrl = track.trackThumbUrl(server.uri, accessToken),
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

    suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean = false,
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

    suspend fun scrobble(ratingKey: String): Unit = withContext(Dispatchers.IO) {
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
        config.token?.takeIf { it.isNotBlank() }?.let { return it }
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
        return root.childText("authenticationToken")
            ?: error("Plex 登录成功，但没有返回 token。")
    }

    private fun resolveServer(token: String): PlexServer {
        config.baseUrl?.takeIf { it.isNotBlank() }?.let {
            return PlexServer(
                name = config.preferredServer?.ifBlank { null } ?: "DirectServer",
                uri = it.trimEnd('/'),
                accessToken = token,
            )
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
                    if (uri.isBlank()) {
                        continue
                    }
                    add(PlexServer(device.getAttribute("name"), uri.trimEnd('/'), accessToken))
                }
            }
        }

        if (candidates.isEmpty()) {
            error("当前账号下没有可用的 Plex 服务器。")
        }

        config.preferredServer?.takeIf { it.isNotBlank() }?.let { preferred ->
            candidates.firstOrNull { it.name.equals(preferred, ignoreCase = true) }?.let { return it }
            error(
                "未找到服务器 '$preferred'。可用服务器: ${
                    candidates.map { it.name }.distinct().sorted().joinToString()
                }"
            )
        }

        return candidates.first()
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
            url = "${server.uri}/library/sections/${section.key}/all?type=9",
            headers = mapOf("X-Plex-Token" to server.accessToken.orEmpty().ifBlank { token }),
        )
        val root = parseXml(response.body)
        val directories = root.getElementsByTagName("Directory")
        return buildList {
            for (index in 0 until directories.length) {
                val directory = directories.item(index) as? Element ?: continue
                val title = directory.getAttribute("title").trim()
                val ratingKey = directory.getAttribute("ratingKey").trim()
                if (title.isBlank() || ratingKey.isBlank()) {
                    continue
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
                    )
                )
            }
        }
    }

    private fun listAlbumTracks(server: PlexServer, album: PlexAlbum, token: String): List<PlexTrackStream> {
        val accessToken = server.accessToken.orEmpty().ifBlank { token }
        val response = request(
            method = "GET",
            url = "${server.uri}/library/metadata/${album.ratingKey}/children",
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
                add(
                    PlexTrackStream(
                        ratingKey = ratingKey,
                        title = title,
                        partKey = partKey,
                        streamUrl = buildStreamUrl(server.uri, partKey, accessToken),
                        durationMillis = durationMillis,
                        userRating = track.getAttribute("userRating").trim().toFloatOrNull(),
                        albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                        albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                        artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                        thumbUrl = track.trackThumbUrl(server.uri, accessToken),
                    )
                )
            }
        }
    }

    private fun listFavoriteTracks(server: PlexServer, section: PlexSection, token: String): List<PlexTrackStream> {
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
                        "X-Plex-Container-Start" to containerStart.toString(),
                        "X-Plex-Container-Size" to PAGED_CONTAINER_SIZE.toString(),
                    ),
                ),
                headers = mapOf("X-Plex-Token" to token),
            )
            val root = parseXml(response.body)
            val pageTracks = root.getElementsByTagName("Track").toTrackStreams(server.uri, token)
            if (pageTracks.isEmpty()) {
                break
            }

            tracks += pageTracks
            if (pageTracks.size < PAGED_CONTAINER_SIZE) {
                break
            }
            containerStart += pageTracks.size
        }

        return tracks
    }

    private fun request(method: String, url: String, headers: Map<String, String>): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = true
            connectTimeout = 15000
            readTimeout = 30000
            doInput = true
            setRequestProperty("Accept", "application/xml")
            defaultHeaders().plus(headers).forEach { (name, value) ->
                setRequestProperty(name, value)
            }
        }

        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            HttpResponse(
                status = status,
                body = stream?.readFully().orEmpty(),
            ).also {
                if (status !in 200..299) {
                    error("HTTP $status from $url: ${it.body.take(400)}")
                }
            }
        } finally {
            connection.disconnect()
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

private fun Element.trackThumbUrl(serverUri: String, token: String): String? {
    val mediaPath = getAttribute("thumb")
        .trim()
        .ifBlank { getAttribute("parentThumb").trim() }
        .ifBlank { null }
        ?: return null
    return buildMediaUrl(serverUri, mediaPath, token)
}

private fun org.w3c.dom.NodeList.toTrackStreams(serverUri: String, token: String): List<PlexTrackStream> =
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
            add(
                PlexTrackStream(
                    ratingKey = ratingKey,
                    title = title,
                    streamUrl = buildStreamUrl(serverUri, partKey, token),
                    partKey = partKey,
                    durationMillis = durationMillis,
                    userRating = track.getAttribute("userRating").trim().toFloatOrNull(),
                    albumRatingKey = track.getAttribute("parentRatingKey").trim().ifBlank { null },
                    albumTitle = track.getAttribute("parentTitle").trim().ifBlank { null },
                    artistName = track.getAttribute("grandparentTitle").trim().ifBlank { null },
                    thumbUrl = track.trackThumbUrl(serverUri, token),
                )
            )
        }
    }

private fun String.urlEncode(): String =
    URLEncoder.encode(this, StandardCharsets.UTF_8.name())
