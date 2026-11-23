package com.lux032.musicbridge.storage

import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.SearchBestMatch
import com.lux032.musicbridge.music.SearchCategory
import com.lux032.musicbridge.music.SearchPage
import com.lux032.musicbridge.music.UnifiedSearchResults

internal data class LocalLibrarySearchSnapshot(
    val artists: List<MusicArtist> = emptyList(),
    val albums: List<MusicAlbum> = emptyList(),
    val tracks: List<MusicTrack> = emptyList(),
    val downloadedTracks: List<DownloadedAudioTrack> = emptyList(),
) {
    val hasAnyContent: Boolean
        get() = artists.isNotEmpty() || albums.isNotEmpty() || tracks.isNotEmpty() || downloadedTracks.isNotEmpty()
}

private const val OVERVIEW_ARTIST_LIMIT = 12
private const val OVERVIEW_ALBUM_LIMIT = 12
private const val OVERVIEW_TRACK_LIMIT = 20

/**
 * Pure local search engine over SQLite snapshots and downloaded tracks. Supports
 * case-insensitive multi-token matching (CJK + Latin), downloaded-only filtering,
 * synthetic album/artist derivation when only downloaded tracks are present, relevance
 * ranking with [SearchBestMatch], and offset/limit pagination.
 */
internal fun searchLocalLibrary(
    snapshot: LocalLibrarySearchSnapshot,
    query: String,
    downloadedOnly: Boolean,
    category: SearchCategory? = null,
    offset: Int = 0,
    limit: Int = OVERVIEW_TRACK_LIMIT,
): UnifiedSearchResults {
    val normalizedQuery = query.trim()
    val queryLower = normalizedQuery.lowercase()
    val tokens = queryLower.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return UnifiedSearchResults(
            query = normalizedQuery,
            category = category?.apiValue ?: "all",
            bestMatch = null,
            artists = SearchPage(),
            albums = SearchPage(),
            tracks = SearchPage(),
            composers = SearchPage(),
        )
    }

    val downloadedMusicTracks = snapshot.downloadedTracks.map { it.toTrackStream() }
    val downloadedByKey = downloadedMusicTracks.associateBy { trackIdentity(it.sourceId, it.ratingKey) }

    val trackPool: List<MusicTrack> = if (downloadedOnly) {
        downloadedMusicTracks
    } else {
        mergeTracks(snapshot.tracks, downloadedMusicTracks)
    }

    val albumPool: List<MusicAlbum> = if (downloadedOnly) {
        buildDownloadedAlbumsPool(snapshot.albums, snapshot.downloadedTracks)
    } else {
        mergeAlbums(snapshot.albums, buildDownloadedAlbumsPool(snapshot.albums, snapshot.downloadedTracks))
    }

    val artistPool: List<MusicArtist> = if (downloadedOnly) {
        buildDownloadedArtistsPool(snapshot.artists, snapshot.downloadedTracks)
    } else {
        mergeArtists(snapshot.artists, albumPool, downloadedMusicTracks)
    }

    val scoredArtists = artistPool.mapNotNull { artist ->
        val score = scoreMatch(
            queryLower = queryLower,
            tokens = tokens,
            primary = artist.title,
            secondary = emptyList(),
        )
        if (score > 0) ScoredItem(artist, score, artist.title.lowercase(), isDownloaded = false) else null
    }.sortedWith(
        compareByDescending<ScoredItem<MusicArtist>> { it.score }
            .thenBy { it.sortKey },
    )

    val scoredAlbums = albumPool.mapNotNull { album ->
        val secondary = buildList {
            album.artistName?.let(::add)
            album.artists.forEach { add(it.name) }
        }
        val score = scoreMatch(
            queryLower = queryLower,
            tokens = tokens,
            primary = album.title,
            secondary = secondary,
        )
        if (score > 0) ScoredItem(album, score, album.title.lowercase(), isDownloaded = false) else null
    }.sortedWith(
        compareByDescending<ScoredItem<MusicAlbum>> { it.score }
            .thenBy { it.sortKey },
    )

    val scoredTracks = trackPool.mapNotNull { track ->
        val secondary = buildList {
            track.artistName?.let(::add)
            track.albumTitle?.let(::add)
            track.artists.forEach { add(it.name) }
        }
        val score = scoreMatch(
            queryLower = queryLower,
            tokens = tokens,
            primary = track.title,
            secondary = secondary,
        )
        if (score > 0) {
            val isDownloaded = trackIdentity(track.sourceId, track.ratingKey) in downloadedByKey
            ScoredItem(track, score, track.title.lowercase(), isDownloaded = isDownloaded)
        } else {
            null
        }
    }.sortedWith(
        compareByDescending<ScoredItem<MusicTrack>> { it.score }
            .thenByDescending { it.isDownloaded }
            .thenBy { it.sortKey },
    )

    if (category != null) {
        val safeOffset = offset.coerceAtLeast(0)
        val safeLimit = limit.coerceAtLeast(1)
        return UnifiedSearchResults(
            query = normalizedQuery,
            category = category.apiValue,
            bestMatch = null,
            artists = if (category == SearchCategory.Artists) {
                pageOf(scoredArtists.map { it.item }, safeOffset, safeLimit)
            } else {
                SearchPage()
            },
            albums = if (category == SearchCategory.Albums) {
                pageOf(scoredAlbums.map { it.item }, safeOffset, safeLimit)
            } else {
                SearchPage()
            },
            tracks = if (category == SearchCategory.Tracks) {
                pageOf(scoredTracks.map { it.item }, safeOffset, safeLimit)
            } else {
                SearchPage()
            },
            composers = SearchPage(),
        )
    }

    val bestMatch = pickBestMatch(scoredArtists.firstOrNull(), scoredAlbums.firstOrNull(), scoredTracks.firstOrNull())

    return UnifiedSearchResults(
        query = normalizedQuery,
        category = "all",
        bestMatch = bestMatch,
        artists = pageOf(scoredArtists.map { it.item }, 0, OVERVIEW_ARTIST_LIMIT),
        albums = pageOf(scoredAlbums.map { it.item }, 0, OVERVIEW_ALBUM_LIMIT),
        tracks = pageOf(scoredTracks.map { it.item }, 0, OVERVIEW_TRACK_LIMIT),
        composers = SearchPage(),
    )
}

private data class ScoredItem<T>(
    val item: T,
    val score: Int,
    val sortKey: String,
    val isDownloaded: Boolean,
)

private fun <T> pageOf(allItems: List<T>, offset: Int, limit: Int): SearchPage<T> {
    val sliced = if (offset >= allItems.size) {
        emptyList()
    } else {
        allItems.subList(offset, (offset + limit).coerceAtMost(allItems.size))
    }
    return SearchPage(
        items = sliced,
        total = allItems.size,
        limit = limit,
        offset = offset,
    )
}

private fun scoreMatch(
    queryLower: String,
    tokens: List<String>,
    primary: String,
    secondary: List<String>,
): Int {
    val primaryLower = primary.trim().lowercase()
    val secondaryLower = secondary.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    val allFields = buildList {
        if (primaryLower.isNotEmpty()) add(primaryLower)
        addAll(secondaryLower)
    }
    if (allFields.isEmpty()) return 0

    // Every query token must match at least one field.
    val allTokensMatch = tokens.all { token -> allFields.any { field -> field.contains(token) } }
    if (!allTokensMatch) return 0

    return when {
        primaryLower == queryLower -> 100
        primaryLower.startsWith(queryLower) -> 85
        primaryLower.contains(queryLower) -> 70
        tokens.all { token -> primaryLower.contains(token) } -> 60
        secondaryLower.any { it == queryLower } -> 55
        secondaryLower.any { it.startsWith(queryLower) } -> 45
        else -> 35
    }
}

private fun pickBestMatch(
    topArtist: ScoredItem<MusicArtist>?,
    topAlbum: ScoredItem<MusicAlbum>?,
    topTrack: ScoredItem<MusicTrack>?,
): SearchBestMatch? {
    val candidates = buildList {
        topArtist?.let {
            add(
                Triple(
                    it.score * 10 + 3,
                    SearchBestMatch(
                        category = SearchCategory.Artists,
                        id = it.item.ratingKey,
                        title = it.item.title,
                        subtitle = "",
                        imageUrl = it.item.thumbUrl,
                        albumId = null,
                    ),
                    it.sortKey,
                ),
            )
        }
        topAlbum?.let {
            add(
                Triple(
                    it.score * 10 + 2,
                    SearchBestMatch(
                        category = SearchCategory.Albums,
                        id = it.item.ratingKey,
                        title = it.item.title,
                        subtitle = it.item.artistName.orEmpty(),
                        imageUrl = it.item.thumbUrl,
                        albumId = it.item.ratingKey,
                    ),
                    it.sortKey,
                ),
            )
        }
        topTrack?.let {
            add(
                Triple(
                    it.score * 10 + 1,
                    SearchBestMatch(
                        category = SearchCategory.Tracks,
                        id = it.item.ratingKey,
                        title = it.item.title,
                        subtitle = listOfNotNull(
                            it.item.artistName?.takeIf(String::isNotBlank),
                            it.item.albumTitle?.takeIf(String::isNotBlank),
                        ).joinToString(" · "),
                        imageUrl = it.item.thumbUrl,
                        albumId = it.item.albumRatingKey,
                    ),
                    it.sortKey,
                ),
            )
        }
    }
    return candidates.maxWithOrNull(compareBy<Triple<Int, SearchBestMatch, String>> { it.first }.thenByDescending { it.third })?.second
}

private fun trackIdentity(sourceId: String, ratingKey: String): String = "$sourceId:$ratingKey"

private fun mergeTracks(
    catalogTracks: List<MusicTrack>,
    downloadedTracks: List<MusicTrack>,
): List<MusicTrack> {
    val byKey = LinkedHashMap<String, MusicTrack>()
    catalogTracks.forEach { track ->
        byKey[trackIdentity(track.sourceId, track.ratingKey)] = track
    }
    downloadedTracks.forEach { downloaded ->
        val key = trackIdentity(downloaded.sourceId, downloaded.ratingKey)
        val existing = byKey[key]
        byKey[key] = if (existing != null) {
            // Keep local file playback URL, mime type & local artwork/lyrics while preserving richer catalog metadata.
            existing.copy(
                streamUrl = downloaded.streamUrl,
                playbackMimeType = downloaded.playbackMimeType ?: existing.playbackMimeType,
                thumbUrl = downloaded.thumbUrl ?: existing.thumbUrl,
                lyricUrl = downloaded.lyricUrl ?: existing.lyricUrl,
                userRating = existing.userRating ?: downloaded.userRating,
                artists = existing.artists.ifEmpty { downloaded.artists },
            )
        } else {
            downloaded
        }
    }
    return byKey.values.toList()
}

private fun buildDownloadedAlbumsPool(
    catalogAlbums: List<MusicAlbum>,
    downloadedTracks: List<DownloadedAudioTrack>,
): List<MusicAlbum> {
    if (downloadedTracks.isEmpty()) return emptyList()
    val catalogByKey = catalogAlbums.associateBy { "${it.sourceId}:${it.ratingKey}" }
    val result = LinkedHashMap<String, MusicAlbum>()
    downloadedTracks.groupBy { track ->
        val albumKey = track.albumRatingKey?.takeIf(String::isNotBlank)
            ?: track.albumTitle?.takeIf(String::isNotBlank)
            ?: return@groupBy null
        "${track.sourceId}:$albumKey"
    }.forEach { (key, tracks) ->
        if (key == null) return@forEach
        val first = tracks.first()
        val albumKey = first.albumRatingKey?.takeIf(String::isNotBlank)
            ?: first.albumTitle?.takeIf(String::isNotBlank)
            ?: return@forEach
        val localCover = tracks.firstNotNullOfOrNull { it.localThumbPath }?.let { "file://$it" }
        val existing = catalogByKey[key]
        result[key] = if (existing != null) {
            existing.copy(thumbUrl = localCover ?: existing.thumbUrl)
        } else {
            MusicAlbum(
                ratingKey = albumKey,
                title = first.albumTitle?.takeIf(String::isNotBlank) ?: first.title,
                artistName = first.artistName,
                year = null,
                thumbUrl = localCover,
                userRating = null,
                addedAtEpochSeconds = first.downloadedAtEpochSeconds,
                lastViewedAtEpochSeconds = null,
                section = LibrarySection("", "", ""),
                sourceId = first.sourceId,
                leafCount = tracks.size,
                artists = first.artists,
            )
        }
    }
    return result.values.toList()
}

private fun mergeAlbums(
    catalogAlbums: List<MusicAlbum>,
    downloadedAlbums: List<MusicAlbum>,
): List<MusicAlbum> {
    val byKey = LinkedHashMap<String, MusicAlbum>()
    catalogAlbums.forEach { album ->
        byKey["${album.sourceId}:${album.ratingKey}"] = album
    }
    downloadedAlbums.forEach { album ->
        val key = "${album.sourceId}:${album.ratingKey}"
        val existing = byKey[key]
        byKey[key] = if (existing != null) {
            existing.copy(thumbUrl = album.thumbUrl ?: existing.thumbUrl)
        } else {
            album
        }
    }
    return byKey.values.toList()
}

private fun buildDownloadedArtistsPool(
    catalogArtists: List<MusicArtist>,
    downloadedTracks: List<DownloadedAudioTrack>,
): List<MusicArtist> {
    if (downloadedTracks.isEmpty()) return emptyList()
    val catalogById = catalogArtists.associateBy { "${it.sourceId}:${it.ratingKey}" }
    val catalogByName = catalogArtists.associateBy { "${it.sourceId}:${it.title.trim().lowercase()}" }
    val result = LinkedHashMap<String, MusicArtist>()

    downloadedTracks.forEach { track ->
        val localCover = track.localThumbPath?.let { "file://$it" }
        val refs = if (track.artists.isNotEmpty()) {
            track.artists
        } else {
            val rawName = track.artistName?.trim()?.takeIf(String::isNotEmpty)
            if (rawName != null) listOf(ArtistRef(id = "", name = rawName)) else emptyList()
        }
        refs.forEach { ref ->
            val matched = ref.id.takeIf(String::isNotBlank)?.let { catalogById["${track.sourceId}:$it"] }
                ?: catalogByName["${track.sourceId}:${ref.name.trim().lowercase()}"]
            if (matched != null) {
                val key = "${matched.sourceId}:${matched.ratingKey}"
                result.putIfAbsent(key, matched.copy(thumbUrl = matched.thumbUrl ?: localCover))
            } else if (ref.name.isNotBlank()) {
                val syntheticKey = "${track.sourceId}:name:${ref.name.trim().lowercase()}"
                result.putIfAbsent(
                    syntheticKey,
                    MusicArtist(
                        ratingKey = ref.id,
                        title = ref.name.trim(),
                        thumbUrl = localCover,
                        sourceId = track.sourceId,
                    ),
                )
            }
        }
    }
    return result.values.toList()
}

private fun mergeArtists(
    catalogArtists: List<MusicArtist>,
    albums: List<MusicAlbum>,
    downloadedTracks: List<MusicTrack>,
): List<MusicArtist> {
    val byId = LinkedHashMap<String, MusicArtist>()
    val seenNames = HashSet<String>()
    catalogArtists.forEach { artist ->
        val key = if (artist.ratingKey.isNotBlank()) {
            "${artist.sourceId}:${artist.ratingKey}"
        } else {
            "${artist.sourceId}:name:${artist.title.trim().lowercase()}"
        }
        byId[key] = artist
        seenNames.add("${artist.sourceId}:${artist.title.trim().lowercase()}")
    }
    // Derive fallback artists from albums/downloaded tracks if artists table hasn't synced yet.
    albums.forEach { album ->
        val name = album.artistName?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
        val nameKey = "${album.sourceId}:${name.lowercase()}"
        if (seenNames.add(nameKey)) {
            byId["${album.sourceId}:name:${name.lowercase()}"] = MusicArtist(
                ratingKey = album.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id.orEmpty(),
                title = name,
                thumbUrl = album.thumbUrl,
                sourceId = album.sourceId,
            )
        }
    }
    downloadedTracks.forEach { track ->
        val name = track.artistName?.trim()?.takeIf(String::isNotEmpty) ?: return@forEach
        val nameKey = "${track.sourceId}:${name.lowercase()}"
        if (seenNames.add(nameKey)) {
            byId["${track.sourceId}:name:${name.lowercase()}"] = MusicArtist(
                ratingKey = track.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id.orEmpty(),
                title = name,
                thumbUrl = track.thumbUrl,
                sourceId = track.sourceId,
            )
        }
    }
    return byId.values.toList()
}
