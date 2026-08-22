package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexArtist
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppStateLibraryCoordinator(
    private val state: AppState,
) {
    suspend fun loadAlbumsFromLocalStore() {
        val sourceId = state.connectionPreferences.id
        val albums = withContext(Dispatchers.IO) {
            state.albumLocalStore.getAllAlbums(sourceId)
        }
        val favorites = withContext(Dispatchers.IO) {
            state.albumLocalStore.getFavoriteTracks(sourceId)
        }
        if (state.connectionPreferences.id != sourceId) return
        state.allAlbums = albums
        state.favoriteTracks = favorites
        state.hasLoadedLocalAlbums = true
    }

    suspend fun searchAlbums(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            state.albumSearchResults = emptyList()
            state.isAlbumSearchLoading = false
            return
        }

        state.isAlbumSearchLoading = true
        val sourceId = state.connectionPreferences.id
        val results = withContext(Dispatchers.IO) {
            state.albumLocalStore.searchAlbums(sourceId, normalizedQuery)
        }
        if (state.connectionPreferences.id != sourceId) return
        state.albumSearchResults = results
        state.isAlbumSearchLoading = false
    }

    suspend fun refreshAlbums(showLoading: Boolean) {
        val profile = state.connectionPreferences
        val sourceId = profile.id
        if (!profile.hasCredentials()) {
            state.selectedAlbum = null
            state.trackResult = null
            return
        }

        if (showLoading) {
            state.isLoading = true
        }
        state.errorMessage = null
        state.actionMessage = null
        try {
            val client = state.client(profile)
            val fetched = client.fetchAlbums()
            withContext(Dispatchers.IO) {
                state.albumLocalStore.replaceAllAlbums(sourceId, fetched.albums)
            }
            if (state.connectionPreferences.id != sourceId) return
            state.allAlbums = fetched.albums
            loadArtists()
            state.lastAlbumSyncEpochMillis = System.currentTimeMillis()
            state.appPreferences.saveLastAlbumSyncEpochMillis(sourceId, state.lastAlbumSyncEpochMillis!!)
            if (state.selectedAlbum?.let { selected ->
                    fetched.albums.none { album -> sameAlbum(album, selected) }
                } == true
            ) {
                state.selectedAlbum = null
                state.trackResult = null
            }
            if (profile.type == MusicServerType.Music032 && showLoading) {
                state.isLoading = false
            }
            state.actionMessage = if (profile.type == MusicServerType.Music032) {
                "已同步 ${fetched.albums.size} 张专辑，正在批量同步曲目..."
            } else {
                "已同步 ${fetched.albums.size} 张专辑，正在同步曲目..."
            }
            val allTracks = client.fetchAllAlbumTracks(fetched.albums) { current, total ->
                if (state.connectionPreferences.id == sourceId) {
                    state.actionMessage = if (profile.type == MusicServerType.Music032) {
                        "正在批量同步曲目 $current/$total"
                    } else {
                        "正在同步曲目 $current/$total"
                    }
                }
            }
            withContext(Dispatchers.IO) {
                state.albumLocalStore.replaceAllAlbumTracks(sourceId, allTracks)
            }
            if (state.connectionPreferences.id != sourceId) return
            val totalTrackCount = allTracks.values.sumOf { tracks -> tracks.size }
            state.actionMessage = "已同步 ${fetched.albums.size} 张专辑、${totalTrackCount} 首曲目"
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            state.selectedAlbum = null
            state.trackResult = null
            state.actionMessage = null
            state.errorMessage = error.message ?: "未知错误"
        } finally {
            if (showLoading) {
                state.isLoading = false
            }
        }
    }

    fun openAlbumDetail(album: PlexAlbum) {
        state.errorMessage = null
        state.actionMessage = null
        state.selectedAlbum = album
        state.scope.launch {
            val cachedTracks = withContext(Dispatchers.IO) {
                state.albumLocalStore.getAlbumTracks(album.sourceId, album.ratingKey)
            }
            if (cachedTracks.isNotEmpty()) {
                state.trackResult = PlexAlbumTracksResult("", album, cachedTracks)
                state.navigateTo(AppSection.AlbumDetail)
            } else {
                state.isLoading = true
                runCatching {
                    state.clientFor(album.sourceId).fetchAlbumTrackStreams(album)
                }.onSuccess {
                    state.trackResult = it
                    state.navigateTo(AppSection.AlbumDetail)
                }.onFailure {
                    state.errorMessage = it.message ?: "未知错误"
                }
                state.isLoading = false
            }
        }
    }

    fun openArtistAlbums(artist: ArtistSummary) {
        state.selectedArtistName = artist.name
        if (state.activeSection == AppSection.ArtistAlbums) return
        state.navigateTo(AppSection.ArtistAlbums)
    }

    fun openArtistAlbumsByName(artistName: String?) {
        val normalizedName = artistName?.trim().orEmpty().ifBlank { "未知艺人" }
        state.selectedArtistName = normalizedName
        state.navigateTo(AppSection.ArtistAlbums)
    }

    fun shuffleArtist(artist: ArtistSummary) {
        if (state.isPlaybackCommandLoading || artist.albums.isEmpty()) return

        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.actionMessage = "正在整理 ${artist.name} 的歌曲..."
        state.scope.launch {
            runCatching {
                val tracks = artist.albums.flatMap { album ->
                    val cachedTracks = withContext(Dispatchers.IO) {
                        state.albumLocalStore.getAlbumTracks(album.sourceId, album.ratingKey)
                    }
                    cachedTracks.ifEmpty {
                        runCatching {
                            state.clientFor(album.sourceId).fetchAlbumTrackStreams(album).tracks
                        }.getOrDefault(emptyList())
                    }
                }
                    .distinctBy(PlexTrackStream::ratingKey)
                    .shuffled()

                check(tracks.isNotEmpty()) { "未找到 ${artist.name} 的可播放歌曲" }
                PlexPlaylistTracksResult(
                    serverName = "",
                    playlist = PlexPlaylist(
                        ratingKey = "artist:${artist.name}",
                        title = artist.name,
                        thumbUrl = artist.coverUrl,
                        leafCount = tracks.size,
                        sourceId = tracks.firstOrNull()?.sourceId ?: state.connectionPreferences.id,
                    ),
                    tracks = tracks,
                )
            }.onSuccess { result ->
                state.startPlaylistPlayback(result, shuffle = false)
                state.actionMessage = "正在随机播放 ${artist.name} 的 ${result.tracks.size} 首歌曲"
            }.onFailure {
                state.errorMessage = it.message ?: "加载歌手歌曲失败"
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun loadPlaylists() {
        val profile = state.connectionPreferences
        if (!profile.hasCredentials()) {
            state.playlists = emptyList()
            return
        }
        state.scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { state.client(profile).fetchPlaylists() }
            }.onSuccess { result ->
                if (state.connectionPreferences.id == profile.id) state.playlists = result.playlists
            }.onFailure {
                if (state.connectionPreferences.id == profile.id) {
                    state.errorMessage = it.message ?: "加载歌单失败"
                }
            }
        }
    }

    fun loadFavoriteTracks() {
        val profile = state.connectionPreferences
        state.scope.launch {
            runCatching {
                loadFavoriteTracksFromCacheOrRemote(profile)
            }.onSuccess { tracks ->
                if (state.connectionPreferences.id == profile.id) state.favoriteTracks = tracks
            }.onFailure {
                if (state.connectionPreferences.id == profile.id) {
                    state.errorMessage = it.message ?: "加载收藏单曲失败"
                }
            }
        }
    }

    fun openFavoriteTracks() {
        val profile = state.connectionPreferences
        state.isLoading = true
        state.errorMessage = null
        state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksFromCacheOrRemote(profile)
                val fakePlaylist = PlexPlaylist(
                    ratingKey = "favorites",
                    title = Strings.myFavoriteTracks,
                    thumbUrl = null,
                    leafCount = tracks.size,
                    sourceId = profile.id,
                )
                PlexPlaylistTracksResult("", fakePlaylist, tracks)
            }.onSuccess { result ->
                if (state.connectionPreferences.id == profile.id) {
                    state.favoriteTracks = result.tracks
                    state.playlistTrackResult = result
                    state.navigateTo(AppSection.PlaylistDetail)
                }
            }.onFailure {
                if (state.connectionPreferences.id == profile.id) {
                    state.errorMessage = it.message ?: "加载收藏单曲失败"
                }
            }
            state.isLoading = false
        }
    }

    fun openPlaylistDetail(playlist: PlexPlaylist) {
        state.selectedPlaylist = playlist
        state.scope.launch {
            runCatching {
                state.playlistTrackResult = withContext(Dispatchers.IO) {
                    state.clientFor(playlist.sourceId).fetchPlaylistTracks(playlist)
                }
                state.navigateTo(AppSection.PlaylistDetail)
            }.onFailure {
                state.errorMessage = it.message ?: "加载歌单详情失败"
            }
        }
    }

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) {
        state.allAlbums = state.allAlbums.map { album ->
            if (sameAlbum(album, updatedAlbum)) updatedAlbum else album
        }
        state.scope.launch(Dispatchers.IO) {
            state.albumLocalStore.upsertAlbum(updatedAlbum)
        }
        if (state.selectedAlbum?.let { sameAlbum(it, updatedAlbum) } == true) {
            state.selectedAlbum = updatedAlbum
        }
        state.trackResult = state.trackResult?.let { current ->
            if (sameAlbum(current.album, updatedAlbum)) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
        state.miniPlayerState = state.miniPlayerState?.let { current ->
            if (sameAlbum(current.album, updatedAlbum)) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
    }

    fun replaceTrackInState(updatedTrack: PlexTrackStream) {
        state.trackResult = state.trackResult?.let { current ->
            if (current.tracks.none { sameTrack(it, updatedTrack) }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (sameTrack(track, updatedTrack)) updatedTrack else track
                    }
                )
            }
        }
        state.playlistTrackResult = state.playlistTrackResult?.let { current ->
            if (current.tracks.none { sameTrack(it, updatedTrack) }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (sameTrack(track, updatedTrack)) updatedTrack else track
                    }
                )
            }
        }
        state.favoriteTracks = state.favoriteTracks.mapNotNull { track ->
            when {
                !sameTrack(track, updatedTrack) -> track
                updatedTrack.isFavorite -> updatedTrack
                else -> null
            }
        }
        state.scope.launch(Dispatchers.IO) {
            if (updatedTrack.isFavorite) {
                state.albumLocalStore.upsertFavoriteTrack(updatedTrack)
            } else {
                state.albumLocalStore.deleteFavoriteTrack(updatedTrack.sourceId, updatedTrack.ratingKey)
            }
            state.albumLocalStore.updateAlbumTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
        }
        state.miniPlayerState = state.miniPlayerState?.let { current ->
            if (current.tracks.none { sameTrack(it, updatedTrack) }) {
                current
            } else {
                current.copy(
                    album = current.tracks
                        .getOrNull(current.currentIndex)
                        ?.takeIf { sameTrack(it, updatedTrack) }
                        ?.let { resolveAlbumForTrack(updatedTrack, current.album) }
                        ?: current.album,
                    tracks = current.tracks.map { track ->
                        if (sameTrack(track, updatedTrack)) updatedTrack else track
                    }
                )
            }
        }
    }

    fun toggleAlbumFavorite(album: PlexAlbum) {
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !album.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                state.clientFor(album.sourceId).setAlbumFavorite(album.ratingKey, targetFavorite)
            }.onSuccess {
                replaceAlbumInState(album.copy(userRating = if (targetFavorite) 10f else null))
                state.actionMessage = if (targetFavorite) {
                    "已收藏专辑：${album.title}"
                } else {
                    "已取消收藏专辑：${album.title}"
                }
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isFavoriteMutationLoading = false
        }
    }

    fun toggleArtistFavorite(artist: ArtistSummary) {
        val ratingKey = artist.ratingKey ?: return
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !artist.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                state.clientFor(artist.sourceId).setArtistFavorite(ratingKey, targetFavorite)
            }.onSuccess {
                val newRating = if (targetFavorite) 10f else null
                state.artists = state.artists.map { current ->
                    if (current.ratingKey == ratingKey && current.sourceId == artist.sourceId) {
                        current.copy(userRating = newRating)
                    } else {
                        current
                    }
                }
                state.actionMessage = if (targetFavorite) {
                    "已收藏歌手：${artist.name}"
                } else {
                    "已取消收藏歌手：${artist.name}"
                }
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isFavoriteMutationLoading = false
        }
    }

    fun loadArtistTracks(artist: ArtistSummary) {
        val ratingKey = artist.ratingKey
        state.artistTracks = emptyList()
        if (ratingKey == null) {
            state.isArtistTracksLoading = false
            return
        }
        state.isArtistTracksLoading = true
        state.scope.launch {
            runCatching {
                state.clientFor(artist.sourceId).fetchArtistTracks(
                    PlexArtist(
                        ratingKey = ratingKey,
                        title = artist.name,
                        thumbUrl = artist.coverUrl,
                        sourceId = artist.sourceId,
                    )
                )
            }.onSuccess { tracks ->
                if (state.selectedArtistName == artist.name) {
                    state.artistTracks = tracks
                }
            }
            if (state.selectedArtistName == artist.name) {
                state.isArtistTracksLoading = false
            }
        }
    }

    fun toggleTrackFavorite(track: PlexTrackStream) {
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !track.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                state.clientFor(track.sourceId).setTrackFavorite(track.ratingKey, targetFavorite)
            }.onSuccess {
                replaceTrackInState(track.copy(userRating = if (targetFavorite) 10f else null))
                state.actionMessage = if (targetFavorite) {
                    "已收藏单曲：${track.title}"
                } else {
                    "已取消收藏单曲：${track.title}"
                }
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isFavoriteMutationLoading = false
        }
    }

    suspend fun loadArtists() {
        val profile = state.connectionPreferences
        val cachedArtists = withContext(Dispatchers.IO) {
            state.albumLocalStore.getArtistSummaries(profile.id)
        }
        if (state.connectionPreferences.id != profile.id) return
        if (cachedArtists.isNotEmpty()) {
            state.artists = cachedArtists.map { info ->
                ArtistSummary(
                    name = info.name,
                    coverUrl = info.coverUrl,
                    albumCount = info.albumCount,
                )
            }
        }

        if (!profile.hasCredentials()) return
        try {
            val plexArtists = state.client(profile).fetchArtists().artists
            if (state.connectionPreferences.id != profile.id) return
            val artistInfoMap = cachedArtists.associateBy { it.name }
            state.artists = plexArtists.map { plexArtist ->
                val info = artistInfoMap[plexArtist.title]
                ArtistSummary(
                    name = plexArtist.title,
                    coverUrl = plexArtist.thumbUrl,
                    albumCount = info?.albumCount ?: 0,
                    ratingKey = plexArtist.ratingKey,
                    sourceId = plexArtist.sourceId,
                    userRating = plexArtist.userRating,
                )
            }.filter { it.albumCount > 0 }
        } catch (_: Exception) {
            // Cached data is already visible.
        }
    }

    private suspend fun loadFavoriteTracksFromCacheOrRemote(profile: MusicServerProfile): List<PlexTrackStream> {
        val cachedTracks = withContext(Dispatchers.IO) {
            state.albumLocalStore.getFavoriteTracks(profile.id)
        }
        if (cachedTracks.isNotEmpty()) {
            refreshFavoriteTracksInBackground(profile)
            return cachedTracks
        }

        check(profile.hasCredentials()) { "请先完善服务器连接信息。" }
        val remoteTracks = loadFavoriteTracksInternal(profile)
        withContext(Dispatchers.IO) {
            state.albumLocalStore.replaceAllFavoriteTracks(profile.id, remoteTracks)
        }
        return remoteTracks
    }

    private suspend fun loadFavoriteTracksInternal(profile: MusicServerProfile): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        state.client(profile).fetchFavoriteTracks()
    }

    private fun refreshFavoriteTracksInBackground(profile: MusicServerProfile) {
        state.favoriteTrackSyncJob?.cancel()
        state.favoriteTrackSyncJob = state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksInternal(profile)
                withContext(Dispatchers.IO) {
                    state.albumLocalStore.replaceAllFavoriteTracks(profile.id, tracks)
                }
                if (state.connectionPreferences.id == profile.id) state.favoriteTracks = tracks
            }
        }
    }

    private fun resolveAlbumForTrack(
        track: PlexTrackStream,
        fallbackAlbum: PlexAlbum? = null,
    ): PlexAlbum {
        val matchedAlbum = track.albumRatingKey?.let { ratingKey ->
            state.allAlbums.firstOrNull { album -> album.ratingKey == ratingKey && album.sourceId == track.sourceId }
        }
        if (matchedAlbum != null) {
            return matchedAlbum
        }

        val fallback = fallbackAlbum ?: state.selectedAlbum
        return PlexAlbum(
            ratingKey = track.albumRatingKey ?: fallback?.ratingKey ?: track.ratingKey,
            title = track.albumTitle ?: fallback?.title ?: track.title,
            artistName = track.artistName ?: fallback?.artistName,
            year = fallback?.year,
            thumbUrl = track.thumbUrl ?: fallback?.thumbUrl,
            userRating = fallback?.userRating,
            addedAtEpochSeconds = fallback?.addedAtEpochSeconds,
            lastViewedAtEpochSeconds = fallback?.lastViewedAtEpochSeconds,
            section = fallback?.section ?: PlexSection("", "", ""),
            sourceId = track.sourceId.ifBlank { fallback?.sourceId.orEmpty() },
        )
    }

    private fun sameAlbum(first: PlexAlbum, second: PlexAlbum): Boolean =
        first.sourceId == second.sourceId && first.ratingKey == second.ratingKey

    private fun sameTrack(first: PlexTrackStream, second: PlexTrackStream): Boolean =
        first.sourceId == second.sourceId && first.ratingKey == second.ratingKey
}
