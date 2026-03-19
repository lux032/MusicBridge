package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppStateLibraryCoordinator(
    private val state: AppState,
) {
    suspend fun loadAlbumsFromLocalStore() {
        state.allAlbums = withContext(Dispatchers.IO) {
            state.albumLocalStore.getAllAlbums()
        }
        state.favoriteTracks = withContext(Dispatchers.IO) {
            state.albumLocalStore.getFavoriteTracks()
        }
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
        state.albumSearchResults = withContext(Dispatchers.IO) {
            state.albumLocalStore.searchAlbums(normalizedQuery)
        }
        state.isAlbumSearchLoading = false
    }

    suspend fun refreshAlbums(showLoading: Boolean) {
        if (!state.connectionPreferences.hasCredentials()) {
            state.selectedAlbum = null
            state.trackResult = null
            return
        }

        if (showLoading) {
            state.isLoading = true
        }
        state.errorMessage = null
        state.actionMessage = null
        runCatching {
            state.client().fetchAlbums()
        }.onSuccess {
            state.allAlbums = it.albums
            withContext(Dispatchers.IO) {
                state.albumLocalStore.replaceAllAlbums(it.albums)
            }
            state.lastAlbumSyncEpochMillis = System.currentTimeMillis()
            state.appPreferences.saveLastAlbumSyncEpochMillis(state.lastAlbumSyncEpochMillis!!)
            if (state.selectedAlbum?.ratingKey?.let { key ->
                    it.albums.none { album -> album.ratingKey == key }
                } == true
            ) {
                state.selectedAlbum = null
                state.trackResult = null
            }
            state.actionMessage = "已同步 ${it.albums.size} 张专辑到本地"
        }.onFailure {
            state.selectedAlbum = null
            state.trackResult = null
            state.errorMessage = it.message ?: "未知错误"
        }
        if (showLoading) {
            state.isLoading = false
        }
    }

    fun saveSettings(
        username: String,
        password: String,
        token: String,
        server: String,
        baseUrl: String,
        refreshAfterSave: Boolean,
    ) {
        val newPreferences = PlexConnectionPreferences(
            username = username.trim(),
            password = password,
            token = token.trim(),
            server = server.trim(),
            baseUrl = baseUrl.trim(),
        )
        state.connectionPreferences = newPreferences
        state.appPreferences.savePlexConnectionPreferences(newPreferences)
        state.actionMessage = "Plex 连接信息已保存到设置"
        state.errorMessage = null
        if (refreshAfterSave) {
            state.scope.launch {
                refreshAlbums(showLoading = true)
                if (state.allAlbums.isNotEmpty()) {
                    state.switchPrimarySection(AppSection.Home)
                }
            }
        }
    }

    fun openAlbumDetail(album: PlexAlbum) {
        state.isLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.selectedAlbum = album
        state.scope.launch {
            runCatching {
                state.client().fetchAlbumTrackStreams(album)
            }.onSuccess {
                state.trackResult = it
                state.navigateTo(AppSection.AlbumDetail)
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isLoading = false
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

    fun loadPlaylists() {
        state.scope.launch {
            runCatching {
                val result = withContext(Dispatchers.IO) { state.client().fetchPlaylists() }
                state.playlists = result.playlists
            }.onFailure {
                state.errorMessage = it.message ?: "加载歌单失败"
            }
        }
    }

    fun loadFavoriteTracks() {
        state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksFromCacheOrRemote()
                state.favoriteTracks = tracks
            }.onFailure {
                state.errorMessage = it.message ?: "加载收藏单曲失败"
            }
        }
    }

    fun openFavoriteTracks() {
        state.isLoading = true
        state.errorMessage = null
        state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksFromCacheOrRemote()
                state.favoriteTracks = tracks
                val fakePlaylist = PlexPlaylist(
                    ratingKey = "favorites",
                    title = Strings.myFavoriteTracks,
                    thumbUrl = null,
                    leafCount = tracks.size,
                )
                state.playlistTrackResult = PlexPlaylistTracksResult("", fakePlaylist, tracks)
                state.navigateTo(AppSection.PlaylistDetail)
            }.onFailure {
                state.errorMessage = it.message ?: "加载收藏单曲失败"
            }
            state.isLoading = false
        }
    }

    fun openPlaylistDetail(playlist: PlexPlaylist) {
        state.selectedPlaylist = playlist
        state.scope.launch {
            runCatching {
                state.playlistTrackResult = withContext(Dispatchers.IO) {
                    state.client().fetchPlaylistTracks(playlist)
                }
                state.navigateTo(AppSection.PlaylistDetail)
            }.onFailure {
                state.errorMessage = it.message ?: "加载歌单详情失败"
            }
        }
    }

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) {
        state.allAlbums = state.allAlbums.map { album ->
            if (album.ratingKey == updatedAlbum.ratingKey) updatedAlbum else album
        }
        state.scope.launch(Dispatchers.IO) {
            state.albumLocalStore.upsertAlbum(updatedAlbum)
        }
        if (state.selectedAlbum?.ratingKey == updatedAlbum.ratingKey) {
            state.selectedAlbum = updatedAlbum
        }
        state.trackResult = state.trackResult?.let { current ->
            if (current.album.ratingKey == updatedAlbum.ratingKey) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
        state.miniPlayerState = state.miniPlayerState?.let { current ->
            if (current.album.ratingKey == updatedAlbum.ratingKey) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
    }

    fun replaceTrackInState(updatedTrack: PlexTrackStream) {
        state.trackResult = state.trackResult?.let { current ->
            if (current.tracks.none { it.ratingKey == updatedTrack.ratingKey }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (track.ratingKey == updatedTrack.ratingKey) updatedTrack else track
                    }
                )
            }
        }
        state.playlistTrackResult = state.playlistTrackResult?.let { current ->
            if (current.tracks.none { it.ratingKey == updatedTrack.ratingKey }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (track.ratingKey == updatedTrack.ratingKey) updatedTrack else track
                    }
                )
            }
        }
        state.favoriteTracks = state.favoriteTracks.mapNotNull { track ->
            when {
                track.ratingKey != updatedTrack.ratingKey -> track
                updatedTrack.isFavorite -> updatedTrack
                else -> null
            }
        }
        state.scope.launch(Dispatchers.IO) {
            if (updatedTrack.isFavorite) {
                state.albumLocalStore.upsertFavoriteTrack(updatedTrack)
            } else {
                state.albumLocalStore.deleteFavoriteTrack(updatedTrack.ratingKey)
            }
        }
        state.miniPlayerState = state.miniPlayerState?.let { current ->
            if (current.tracks.none { it.ratingKey == updatedTrack.ratingKey }) {
                current
            } else {
                current.copy(
                    album = current.tracks
                        .getOrNull(current.currentIndex)
                        ?.takeIf { it.ratingKey == updatedTrack.ratingKey }
                        ?.let { resolveAlbumForTrack(updatedTrack, current.album) }
                        ?: current.album,
                    tracks = current.tracks.map { track ->
                        if (track.ratingKey == updatedTrack.ratingKey) updatedTrack else track
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
                state.client().setFavorite(album.ratingKey, targetFavorite)
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

    fun toggleTrackFavorite(track: PlexTrackStream) {
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !track.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                state.client().setFavorite(track.ratingKey, targetFavorite)
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
        val cachedArtists = withContext(Dispatchers.IO) {
            state.albumLocalStore.getArtistSummaries()
        }
        if (cachedArtists.isNotEmpty()) {
            state.artists = cachedArtists.map { info ->
                ArtistSummary(
                    name = info.name,
                    coverUrl = info.coverUrl,
                    albumCount = info.albumCount,
                )
            }
        }

        if (state.connectionPreferences.token.isNullOrBlank()) return
        try {
            val plexArtists = state.client().fetchArtists().artists
            val artistInfoMap = cachedArtists.associateBy { it.name }
            state.artists = plexArtists.map { plexArtist ->
                val info = artistInfoMap[plexArtist.title]
                ArtistSummary(
                    name = plexArtist.title,
                    coverUrl = plexArtist.thumbUrl,
                    albumCount = info?.albumCount ?: 0,
                )
            }.filter { it.albumCount > 0 }
        } catch (_: Exception) {
            // Cached data is already visible.
        }
    }

    private suspend fun loadFavoriteTracksFromCacheOrRemote(): List<PlexTrackStream> {
        val cachedTracks = withContext(Dispatchers.IO) {
            state.albumLocalStore.getFavoriteTracks()
        }
        if (cachedTracks.isNotEmpty()) {
            refreshFavoriteTracksInBackground()
            return cachedTracks
        }

        val remoteTracks = loadFavoriteTracksInternal()
        withContext(Dispatchers.IO) {
            state.albumLocalStore.replaceAllFavoriteTracks(remoteTracks)
        }
        return remoteTracks
    }

    private suspend fun loadFavoriteTracksInternal(): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        state.client().fetchFavoriteTracks()
    }

    private fun refreshFavoriteTracksInBackground() {
        state.favoriteTrackSyncJob?.cancel()
        state.favoriteTrackSyncJob = state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksInternal()
                withContext(Dispatchers.IO) {
                    state.albumLocalStore.replaceAllFavoriteTracks(tracks)
                }
                state.favoriteTracks = tracks
            }
        }
    }

    private fun resolveAlbumForTrack(
        track: PlexTrackStream,
        fallbackAlbum: PlexAlbum? = null,
    ): PlexAlbum {
        val matchedAlbum = track.albumRatingKey?.let { ratingKey ->
            state.allAlbums.firstOrNull { album -> album.ratingKey == ratingKey }
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
        )
    }
}
