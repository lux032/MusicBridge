package com.lux032.musicbridge

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexAuthConfig
import com.lux032.musicbridge.plex.PlexClient
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.sonos.SonosPlaybackStatus
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosDiscovery
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.storage.AlbumLocalStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Stable
class AppState(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val appPreferences = AppPreferences(context)
    private val albumLocalStore = AlbumLocalStore(context)
    private val sonosController = SonosController()
    private val initialPreferences = appPreferences.loadPlexConnectionPreferences()

    val navigationStack = mutableStateListOf(AppSection.Home)
    var primarySection by mutableStateOf(AppSection.Home)
    var connectionPreferences by mutableStateOf(initialPreferences)

    var isLoading by mutableStateOf(false)
    var isSonosLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var actionMessage by mutableStateOf<String?>(null)
    var allAlbums by mutableStateOf<List<PlexAlbum>>(emptyList())
    var hasLoadedLocalAlbums by mutableStateOf(false)
    var lastAlbumSyncEpochMillis by mutableStateOf(appPreferences.loadLastAlbumSyncEpochMillis())
    var albumSearchQuery by mutableStateOf("")
    var albumSearchResults by mutableStateOf<List<PlexAlbum>>(emptyList())
    var isAlbumSearchLoading by mutableStateOf(false)
    var selectedAlbum by mutableStateOf<PlexAlbum?>(null)
    var selectedArtistName by mutableStateOf<String?>(null)
    var artistPresentation by mutableStateOf(ArtistPresentation.Covers)
    var trackResult by mutableStateOf<PlexAlbumTracksResult?>(null)
    var sonosRooms by mutableStateOf<List<SonosRoom>>(emptyList())
    var selectedSonosRoom by mutableStateOf<SonosRoom?>(null)
    var sonosDiscoveryAttempted by mutableStateOf(false)
    var sonosVolume by mutableFloatStateOf(0f)
    var hasLoadedSonosVolume by mutableStateOf(false)
    var isVolumeLoading by mutableStateOf(false)
    var isVolumeChanging by mutableStateOf(false)
    var isPlaybackCommandLoading by mutableStateOf(false)
    var isFavoriteMutationLoading by mutableStateOf(false)
    var volumeSyncKey by mutableIntStateOf(0)
    var volumeChangeJob by mutableStateOf<Job?>(null)
    var albumPlaybackJob by mutableStateOf<Job?>(null)
    var playbackReportingJob by mutableStateOf<Job?>(null)
    var favoriteTrackSyncJob by mutableStateOf<Job?>(null)
    var miniPlayerState by mutableStateOf<MiniPlayerState?>(null)
    var playbackMode by mutableStateOf(PlaybackMode.Sequential)
    var recentPlayedAlbumKeys by mutableStateOf(appPreferences.loadRecentPlayedAlbumKeys())
    var artists by mutableStateOf<List<ArtistSummary>>(emptyList())
    var playlists by mutableStateOf<List<PlexPlaylist>>(emptyList())
    var selectedPlaylist by mutableStateOf<PlexPlaylist?>(null)
    var playlistTrackResult by mutableStateOf<PlexPlaylistTracksResult?>(null)
    var favoriteTracks by mutableStateOf<List<PlexTrackStream>>(emptyList())

    val activeSection: AppSection
        get() = navigationStack.lastOrNull() ?: AppSection.Home

    val favoriteAlbums: List<PlexAlbum>
        get() = allAlbums
            .filter(PlexAlbum::isFavorite)
            .sortedWith(
                compareBy<PlexAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

    val recentPlayedAlbums: List<PlexAlbum>
        get() = allAlbums
            .filter { album ->
                album.ratingKey in recentPlayedAlbumKeys || (album.lastViewedAtEpochSeconds ?: 0L) > 0L
            }
            .sortedWith(
                compareBy<PlexAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

    val recentAddedAlbums: List<PlexAlbum>
        get() = allAlbums
            .sortedWith(
                compareByDescending<PlexAlbum> { it.addedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

    val selectedArtist: ArtistSummary?
        get() = selectedArtistName?.let { selectedName ->
            val albums = albumLocalStore.getAlbumsByArtist(selectedName)
            val summary = artists.firstOrNull { it.name == selectedName }
            summary?.let {
                ArtistSummary(
                    name = it.name,
                    coverUrl = it.coverUrl,
                    albumCount = it.albumCount,
                    albums = albums
                )
            }
        }

    fun navigateTo(section: AppSection) {
        if (navigationStack.lastOrNull() == section) return
        navigationStack.add(section)
    }

    fun replaceWith(section: AppSection) {
        navigationStack.clear()
        navigationStack.add(section)
    }

    fun switchPrimarySection(section: AppSection) {
        primarySection = section
        replaceWith(section)
    }

    fun navigateBack() {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.lastIndex)
        }
    }

    fun client(preferences: PlexConnectionPreferences = connectionPreferences) = PlexClient(
        PlexAuthConfig(
            username = preferences.username,
            password = preferences.password,
            preferredServer = preferences.server,
            baseUrl = preferences.baseUrl,
            token = preferences.token,
        )
    )

    suspend fun loadAlbumsFromLocalStore() {
        allAlbums = withContext(Dispatchers.IO) {
            albumLocalStore.getAllAlbums()
        }
        favoriteTracks = withContext(Dispatchers.IO) {
            albumLocalStore.getFavoriteTracks()
        }
        hasLoadedLocalAlbums = true
    }

    suspend fun searchAlbums(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            albumSearchResults = emptyList()
            isAlbumSearchLoading = false
            return
        }

        isAlbumSearchLoading = true
        albumSearchResults = withContext(Dispatchers.IO) {
            albumLocalStore.searchAlbums(normalizedQuery)
        }
        isAlbumSearchLoading = false
    }

    fun startSingleTrackPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        trackIndex: Int,
        room: SonosRoom,
        initialPositionMillis: Long = 0L,
    ) {
        val targetTrack = tracks.getOrNull(trackIndex) ?: return
        val displayAlbum = resolveAlbumForTrack(targetTrack, album)
        albumPlaybackJob?.cancel()
        playbackReportingJob?.cancel()
        appPreferences.markAlbumPlayed(displayAlbum.ratingKey)
        recentPlayedAlbumKeys = appPreferences.loadRecentPlayedAlbumKeys()
        selectedSonosRoom = room
        volumeSyncKey += 1
        miniPlayerState = MiniPlayerState(
            album = displayAlbum,
            tracks = tracks,
            currentIndex = trackIndex,
            room = room,
            isPaused = false,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = targetTrack.durationMillis,
        )
        isPlaybackCommandLoading = true
        errorMessage = null
        actionMessage = null
        scope.launch {
            runCatching {
                sonosController.playTrack(
                    room = room,
                    trackUrl = targetTrack.streamUrl,
                    title = targetTrack.title,
                    albumTitle = targetTrack.albumTitle ?: displayAlbum.title,
                )
                if (initialPositionMillis > 0L) {
                    sonosController.seek(room, (initialPositionMillis / 1_000L).toInt())
                }
            }.onSuccess {
                startPlaybackReporting(
                    room = room,
                    tracks = tracks,
                    initialTrackIndex = trackIndex,
                    initialPositionMillis = initialPositionMillis,
                )
                actionMessage = "已将 ${targetTrack.title} 推送到 ${room.roomName}"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isPlaybackCommandLoading = false
        }
    }

    fun startAlbumPlayback(
        albumTrackResult: PlexAlbumTracksResult,
        room: SonosRoom,
        startIndex: Int = 0,
        initialPositionMillis: Long = 0L,
    ) {
        albumPlaybackJob?.cancel()
        playbackReportingJob?.cancel()
        appPreferences.markAlbumPlayed(albumTrackResult.album.ratingKey)
        recentPlayedAlbumKeys = appPreferences.loadRecentPlayedAlbumKeys()
        selectedSonosRoom = room
        volumeSyncKey += 1
        miniPlayerState = MiniPlayerState(
            album = albumTrackResult.album,
            tracks = albumTrackResult.tracks,
            currentIndex = startIndex,
            room = room,
            isPaused = false,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = albumTrackResult.tracks.getOrNull(startIndex)?.durationMillis,
        )
        isPlaybackCommandLoading = true
        errorMessage = null
        actionMessage = null
        albumPlaybackJob = scope.launch {
            runCatching<Unit> {
                var didStartFirstTrack = false
                playAlbumSequentially(
                    sonosController = sonosController,
                    room = room,
                    trackResult = albumTrackResult,
                    startIndex = startIndex,
                    initialPositionMillis = initialPositionMillis,
                    getNextIndex = { currentIndex: Int ->
                        resolveNextPlaybackIndex(
                            trackCount = albumTrackResult.tracks.size,
                            currentIndex = currentIndex,
                        )
                    },
                    onTrackChanged = { index: Int, track: PlexTrackStream, startPositionMillis: Long ->
                        if (!didStartFirstTrack) {
                            didStartFirstTrack = true
                            isPlaybackCommandLoading = false
                            startPlaybackReporting(
                                room = room,
                                tracks = albumTrackResult.tracks,
                                initialTrackIndex = index,
                                initialPositionMillis = startPositionMillis,
                            )
                        }
                        miniPlayerState = miniPlayerState?.copy(
                            currentIndex = index,
                            isPaused = false,
                            currentPositionMillis = startPositionMillis,
                            durationMillis = track.durationMillis,
                            room = room,
                        )
                        actionMessage = "正在连续播放：${track.title}"
                    },
                )
            }.onSuccess { _: Unit ->
                actionMessage = "专辑播放结束：${albumTrackResult.album.title}"
            }.onFailure { e: Throwable ->
                errorMessage = e.message ?: "未知错误"
                isPlaybackCommandLoading = false
            }
        }
    }

    suspend fun refreshAlbums(showLoading: Boolean = true) {
        if (!connectionPreferences.hasCredentials()) {
            selectedAlbum = null
            trackResult = null
            return
        }

        if (showLoading) {
            isLoading = true
        }
        errorMessage = null
        actionMessage = null
        runCatching {
            client().fetchAlbums()
        }.onSuccess {
            allAlbums = it.albums
            withContext(Dispatchers.IO) {
                albumLocalStore.replaceAllAlbums(it.albums)
            }
            lastAlbumSyncEpochMillis = System.currentTimeMillis()
            appPreferences.saveLastAlbumSyncEpochMillis(lastAlbumSyncEpochMillis!!)
            if (selectedAlbum?.ratingKey?.let { key -> it.albums.none { album -> album.ratingKey == key } } == true) {
                selectedAlbum = null
                trackResult = null
            }
            actionMessage = "已同步 ${it.albums.size} 张专辑到本地"
        }.onFailure {
            selectedAlbum = null
            trackResult = null
            errorMessage = it.message ?: "未知错误"
        }
        if (showLoading) {
            isLoading = false
        }
    }

    fun saveSettings(
        username: String,
        password: String,
        token: String,
        server: String,
        baseUrl: String,
        refreshAfterSave: Boolean
    ) {
        val newPreferences = PlexConnectionPreferences(
            username = username.trim(),
            password = password,
            token = token.trim(),
            server = server.trim(),
            baseUrl = baseUrl.trim(),
        )
        connectionPreferences = newPreferences
        appPreferences.savePlexConnectionPreferences(newPreferences)
        actionMessage = "Plex 连接信息已保存到设置"
        errorMessage = null
        if (refreshAfterSave) {
            scope.launch {
                refreshAlbums()
                if (allAlbums.isNotEmpty()) {
                    switchPrimarySection(AppSection.Home)
                }
            }
        }
    }

    fun refreshSonosRooms() {
        isSonosLoading = true
        errorMessage = null
        actionMessage = null
        sonosDiscoveryAttempted = true
        scope.launch {
            runCatching {
                SonosDiscovery(context).discoverRooms()
            }.onSuccess { rooms ->
                sonosRooms = rooms
                selectedSonosRoom = selectedSonosRoom
                    ?.let { current -> rooms.firstOrNull { room -> room.coordinatorUuid == current.coordinatorUuid } }
                    ?: rooms.firstOrNull()
                actionMessage = if (rooms.isEmpty()) {
                    "当前未发现 Sonos 房间"
                } else {
                    "已同步 ${rooms.size} 个 Sonos 房间"
                }
                volumeSyncKey += 1
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isSonosLoading = false
        }
    }

    suspend fun loadVolume(room: SonosRoom) {
        isVolumeLoading = true
        hasLoadedSonosVolume = false
        errorMessage = null
        runCatching {
            sonosController.getVolume(room)
        }.onSuccess {
            sonosVolume = it.toFloat()
            hasLoadedSonosVolume = true
        }.onFailure {
            errorMessage = it.message ?: "未知错误"
        }
        isVolumeLoading = false
    }

    fun applyVolumeChange(room: SonosRoom, newValue: Float) {
        sonosVolume = newValue
        hasLoadedSonosVolume = true
        errorMessage = null
        volumeChangeJob?.cancel()
        volumeChangeJob = scope.launch {
            delay(180)
            val targetVolume = sonosVolume.toInt().coerceIn(0, 100)
            isVolumeChanging = true
            runCatching {
                sonosController.setVolume(room, targetVolume)
            }.onSuccess {
                actionMessage = "已将 ${room.roomName} 音量设置为 $targetVolume"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
                volumeSyncKey += 1
            }
            isVolumeChanging = false
        }
    }

    fun openAlbumDetail(album: PlexAlbum) {
        isLoading = true
        errorMessage = null
        actionMessage = null
        selectedAlbum = album
        scope.launch {
            runCatching {
                client().fetchAlbumTrackStreams(album)
            }.onSuccess {
                trackResult = it
                navigateTo(AppSection.AlbumDetail)
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isLoading = false
        }
    }

    fun openArtistAlbums(artist: ArtistSummary) {
        selectedArtistName = artist.name
        if (activeSection == AppSection.ArtistAlbums) return
        navigateTo(AppSection.ArtistAlbums)
    }

    fun openArtistAlbumsByName(artistName: String?) {
        val normalizedName = artistName?.trim().orEmpty().ifBlank { "未知艺人" }
        selectedArtistName = normalizedName
        navigateTo(AppSection.ArtistAlbums)
    }

    fun loadPlaylists() {
        scope.launch {
            runCatching {
                val result = withContext(Dispatchers.IO) { client().fetchPlaylists() }
                playlists = result.playlists
            }.onFailure {
                errorMessage = it.message ?: "加载歌单失败"
            }
        }
    }

    fun loadFavoriteTracks() {
        scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksFromCacheOrRemote()
                favoriteTracks = tracks
            }.onFailure {
                errorMessage = it.message ?: "加载收藏单曲失败"
            }
        }
    }

    fun openFavoriteTracks() {
        isLoading = true
        errorMessage = null
        scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksFromCacheOrRemote()
                favoriteTracks = tracks
                val fakePlaylist = PlexPlaylist(
                    ratingKey = "favorites",
                    title = Strings.myFavoriteTracks,
                    thumbUrl = null,
                    leafCount = tracks.size,
                )
                playlistTrackResult = PlexPlaylistTracksResult("", fakePlaylist, tracks)
                navigateTo(AppSection.PlaylistDetail)
            }.onFailure {
                errorMessage = it.message ?: "加载收藏单曲失败"
            }
            isLoading = false
        }
    }

    fun openPlaylistDetail(playlist: PlexPlaylist) {
        selectedPlaylist = playlist
        scope.launch {
            runCatching {
                playlistTrackResult = withContext(Dispatchers.IO) { client().fetchPlaylistTracks(playlist) }
                navigateTo(AppSection.PlaylistDetail)
            }.onFailure {
                errorMessage = it.message ?: "加载歌单详情失败"
            }
        }
    }

    fun startPlaylistPlayback(playlistResult: PlexPlaylistTracksResult, room: SonosRoom, shuffle: Boolean = false) {
        if (playlistResult.tracks.isEmpty()) return
        albumPlaybackJob?.cancel()
        playbackReportingJob?.cancel()
        selectedSonosRoom = room
        volumeSyncKey += 1
        val tracks = if (shuffle) playlistResult.tracks.shuffled() else playlistResult.tracks
        val fakeAlbum = PlexAlbum(
            ratingKey = playlistResult.playlist.ratingKey,
            title = playlistResult.playlist.title,
            artistName = null,
            year = null,
            thumbUrl = playlistResult.playlist.thumbUrl,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("", "", ""),
        )
        val initialAlbum = tracks.firstOrNull()?.let { resolveAlbumForTrack(it, fakeAlbum) } ?: fakeAlbum
        miniPlayerState = MiniPlayerState(
            album = initialAlbum,
            tracks = tracks,
            currentIndex = 0,
            room = room,
            isPaused = false,
        )
        isPlaybackCommandLoading = true
        albumPlaybackJob = scope.launch {
            runCatching {
                playAlbumSequentially(
                    sonosController = sonosController,
                    room = room,
                    trackResult = PlexAlbumTracksResult("", fakeAlbum, tracks),
                    startIndex = 0,
                    initialPositionMillis = 0L,
                    getNextIndex = { currentIndex ->
                        when (playbackMode) {
                            PlaybackMode.Sequential -> if (currentIndex < tracks.lastIndex) currentIndex + 1 else null
                            PlaybackMode.RepeatAll -> (currentIndex + 1) % tracks.size
                            PlaybackMode.RepeatOne -> currentIndex
                            PlaybackMode.Shuffle -> Random.nextInt(tracks.size)
                        }
                    },
                    onTrackChanged = { index, track, positionMillis ->
                        miniPlayerState = miniPlayerState?.copy(
                            album = resolveAlbumForTrack(track, fakeAlbum),
                            currentIndex = index,
                            currentPositionMillis = positionMillis,
                            durationMillis = track.durationMillis,
                        )
                    },
                )
            }.onFailure {
                errorMessage = it.message ?: "播放失败"
            }
            isPlaybackCommandLoading = false
        }
    }

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) {
        allAlbums = allAlbums.map { album ->
            if (album.ratingKey == updatedAlbum.ratingKey) updatedAlbum else album
        }
        scope.launch(Dispatchers.IO) {
            albumLocalStore.upsertAlbum(updatedAlbum)
        }
        if (selectedAlbum?.ratingKey == updatedAlbum.ratingKey) {
            selectedAlbum = updatedAlbum
        }
        trackResult = trackResult?.let { current ->
            if (current.album.ratingKey == updatedAlbum.ratingKey) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
        miniPlayerState = miniPlayerState?.let { current ->
            if (current.album.ratingKey == updatedAlbum.ratingKey) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
    }

    fun replaceTrackInState(updatedTrack: PlexTrackStream) {
        trackResult = trackResult?.let { current ->
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
        playlistTrackResult = playlistTrackResult?.let { current ->
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
        favoriteTracks = favoriteTracks.mapNotNull { track ->
            when {
                track.ratingKey != updatedTrack.ratingKey -> track
                updatedTrack.isFavorite -> updatedTrack
                else -> null
            }
        }
        scope.launch(Dispatchers.IO) {
            if (updatedTrack.isFavorite) {
                albumLocalStore.upsertFavoriteTrack(updatedTrack)
            } else {
                albumLocalStore.deleteFavoriteTrack(updatedTrack.ratingKey)
            }
        }
        miniPlayerState = miniPlayerState?.let { current ->
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

    private suspend fun loadFavoriteTracksFromCacheOrRemote(): List<PlexTrackStream> {
        val cachedTracks = withContext(Dispatchers.IO) {
            albumLocalStore.getFavoriteTracks()
        }
        if (cachedTracks.isNotEmpty()) {
            refreshFavoriteTracksInBackground()
            return cachedTracks
        }

        val remoteTracks = loadFavoriteTracksInternal()
        withContext(Dispatchers.IO) {
            albumLocalStore.replaceAllFavoriteTracks(remoteTracks)
        }
        return remoteTracks
    }

    private suspend fun loadFavoriteTracksInternal(): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        val plexClient = client()
        plexClient.fetchFavoriteTracks()
    }

    private fun refreshFavoriteTracksInBackground() {
        favoriteTrackSyncJob?.cancel()
        favoriteTrackSyncJob = scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksInternal()
                withContext(Dispatchers.IO) {
                    albumLocalStore.replaceAllFavoriteTracks(tracks)
                }
                favoriteTracks = tracks
            }
        }
    }

    fun toggleAlbumFavorite(album: PlexAlbum) {
        if (isFavoriteMutationLoading) return

        val targetFavorite = !album.isFavorite
        isFavoriteMutationLoading = true
        errorMessage = null
        actionMessage = null
        scope.launch {
            runCatching {
                client().setFavorite(album.ratingKey, targetFavorite)
            }.onSuccess {
                replaceAlbumInState(
                    album.copy(userRating = if (targetFavorite) 10f else null)
                )
                actionMessage = if (targetFavorite) {
                    "已收藏专辑：${album.title}"
                } else {
                    "已取消收藏专辑：${album.title}"
                }
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isFavoriteMutationLoading = false
        }
    }

    fun toggleTrackFavorite(track: PlexTrackStream) {
        if (isFavoriteMutationLoading) return

        val targetFavorite = !track.isFavorite
        isFavoriteMutationLoading = true
        errorMessage = null
        actionMessage = null
        scope.launch {
            runCatching {
                client().setFavorite(track.ratingKey, targetFavorite)
            }.onSuccess {
                replaceTrackInState(
                    track.copy(userRating = if (targetFavorite) 10f else null)
                )
                actionMessage = if (targetFavorite) {
                    "已收藏单曲：${track.title}"
                } else {
                    "已取消收藏单曲：${track.title}"
                }
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isFavoriteMutationLoading = false
        }
    }

    suspend fun loadArtists() {
        // Show cached artists immediately, then refresh from network
        val cachedArtists = withContext(Dispatchers.IO) {
            albumLocalStore.getArtistSummaries()
        }
        if (cachedArtists.isNotEmpty()) {
            artists = cachedArtists.map { info ->
                ArtistSummary(
                    name = info.name,
                    coverUrl = info.coverUrl,
                    albumCount = info.albumCount,
                )
            }
        }

        if (connectionPreferences.token.isNullOrBlank()) return
        try {
            val plexArtists = client().fetchArtists().artists
            val artistInfoMap = cachedArtists.associateBy { it.name }
            artists = plexArtists.map { plexArtist ->
                val info = artistInfoMap[plexArtist.title]
                ArtistSummary(
                    name = plexArtist.title,
                    coverUrl = plexArtist.thumbUrl,
                    albumCount = info?.albumCount ?: 0,
                )
            }.filter { it.albumCount > 0 }
        } catch (_: Exception) {
            // Already showing cached data, no need to update on failure
        }
    }

    fun togglePause(state: MiniPlayerState) {
        isPlaybackCommandLoading = true
        scope.launch {
            runCatching {
                if (state.isPaused) sonosController.resume(state.room) else sonosController.pause(state.room)
            }.onSuccess {
                miniPlayerState = miniPlayerState?.copy(isPaused = !state.isPaused)
                actionMessage = if (state.isPaused) "已继续播放" else "已暂停播放"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isPlaybackCommandLoading = false
        }
    }

    fun selectSonosRoom(room: SonosRoom) {
        selectedSonosRoom = room
        actionMessage = "已选择 Sonos 房间: ${room.roomName}"
        volumeSyncKey += 1
    }

    fun updatePlaybackMode(mode: PlaybackMode) {
        playbackMode = mode
        actionMessage = "播放模式已切换为：${mode.label}"
    }

    fun playQueueIndex(index: Int, positionMillis: Long = 0L, room: SonosRoom? = null) {
        val playerState = miniPlayerState ?: return
        val targetRoom = room ?: playerState.room
        val targetIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        val currentTrackResult = trackResult?.takeIf { it.album.ratingKey == playerState.album.ratingKey }
        if (currentTrackResult != null) {
            startAlbumPlayback(
                albumTrackResult = currentTrackResult,
                room = targetRoom,
                startIndex = targetIndex,
                initialPositionMillis = positionMillis,
            )
        } else {
            startSingleTrackPlayback(
                album = resolveAlbumForTrack(playerState.tracks[targetIndex], playerState.album),
                tracks = playerState.tracks,
                trackIndex = targetIndex,
                room = targetRoom,
                initialPositionMillis = positionMillis,
            )
        }
    }

    fun switchPlaybackRoom(room: SonosRoom) {
        val playerState = miniPlayerState ?: run {
            selectSonosRoom(room)
            return
        }
        if (room.coordinatorUuid == playerState.room.coordinatorUuid) {
            selectSonosRoom(room)
            return
        }
        playQueueIndex(
            index = playerState.currentIndex,
            positionMillis = playerState.currentPositionMillis,
            room = room,
        )
        actionMessage = "正在切换到 ${room.roomName}"
    }

    fun seekPlayback(state: MiniPlayerState, positionMillis: Long) {
        val durationMillis = state.durationMillis?.takeIf { it > 0L } ?: return
        val targetMillis = positionMillis.coerceIn(0L, durationMillis)
        miniPlayerState = miniPlayerState?.takeIf { current ->
            current.room.coordinatorUuid == state.room.coordinatorUuid
        }?.copy(currentPositionMillis = targetMillis)

        isPlaybackCommandLoading = true
        scope.launch {
            runCatching {
                sonosController.seek(state.room, (targetMillis / 1_000L).toInt())
            }.onSuccess {
                actionMessage = "已跳转到 ${formatDuration(targetMillis)}"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isPlaybackCommandLoading = false
        }
    }

    private fun startPlaybackReporting(
        room: SonosRoom,
        tracks: List<PlexTrackStream>,
        initialTrackIndex: Int,
        initialPositionMillis: Long = 0L,
    ) {
        val normalizedInitialIndex = initialTrackIndex.coerceIn(0, tracks.lastIndex)
        playbackReportingJob?.cancel()
        playbackReportingJob = scope.launch {
            val plexClient = client()
            val scrobbledTrackKeys = mutableSetOf<String>()
            var currentTrack = tracks.getOrNull(normalizedInitialIndex) ?: return@launch
            var currentTrackTimeMillis = initialPositionMillis.coerceAtLeast(0L)
            var currentTrackDurationMillis = currentTrack.durationMillis
            var lastReportedState: String? = null
            var lastTimelineReportAt = 0L
            var stoppedPollCount = 0

            reportTimelineSafely(
                plexClient = plexClient,
                track = currentTrack,
                state = "playing",
                timeMillis = currentTrackTimeMillis,
                durationMillis = currentTrackDurationMillis,
                continuing = false,
            )
            lastReportedState = "playing"
            lastTimelineReportAt = System.currentTimeMillis()

            while (true) {
                delay(1_000)

                val latestMiniPlayerState = miniPlayerState ?: break
                if (latestMiniPlayerState.room.coordinatorUuid != room.coordinatorUuid) break

                val status = runCatching { sonosController.getPlaybackStatus(room) }.getOrNull() ?: continue
                val resolvedTrack = resolveActiveTrack(tracks, latestMiniPlayerState, status) ?: continue
                val playbackState = status.toPlexPlaybackState()
                val positionMillis = ((status.relTimeSeconds ?: 0) * 1_000L).coerceAtLeast(0L)
                val durationMillis = resolvedTrack.durationMillis
                    ?: status.trackDurationSeconds?.times(1_000L)

                if (resolvedTrack.ratingKey != currentTrack.ratingKey) {
                    reportStoppedTrackIfNeeded(
                        plexClient = plexClient,
                        track = currentTrack,
                        timeMillis = currentTrackTimeMillis,
                        durationMillis = currentTrackDurationMillis,
                        continuing = true,
                    )
                    scrobbleIfNeeded(
                        plexClient = plexClient,
                        scrobbledTrackKeys = scrobbledTrackKeys,
                        track = currentTrack,
                        timeMillis = currentTrackTimeMillis,
                        durationMillis = currentTrackDurationMillis,
                    )

                    currentTrack = resolvedTrack
                    currentTrackTimeMillis = 0L
                    currentTrackDurationMillis = durationMillis
                    lastReportedState = null
                    lastTimelineReportAt = 0L
                    stoppedPollCount = 0
                }

                if (positionMillis > 0L || playbackState != "stopped") {
                    currentTrackTimeMillis = positionMillis
                }
                if (durationMillis != null) {
                    currentTrackDurationMillis = durationMillis
                }

                miniPlayerState = latestMiniPlayerState.copy(
                    currentIndex = tracks.indexOfFirst { it.ratingKey == currentTrack.ratingKey }
                        .takeIf { it >= 0 } ?: latestMiniPlayerState.currentIndex,
                    isPaused = playbackState == "paused",
                    currentPositionMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                )

                val now = System.currentTimeMillis()
                val shouldReportTimeline = playbackState != lastReportedState ||
                    now - lastTimelineReportAt >= 10_000L

                if (shouldReportTimeline) {
                    reportTimelineSafely(
                        plexClient = plexClient,
                        track = currentTrack,
                        state = playbackState,
                        timeMillis = currentTrackTimeMillis,
                        durationMillis = currentTrackDurationMillis,
                        continuing = false,
                    )
                    lastReportedState = playbackState
                    lastTimelineReportAt = now
                }

                scrobbleIfNeeded(
                    plexClient = plexClient,
                    scrobbledTrackKeys = scrobbledTrackKeys,
                    track = currentTrack,
                    timeMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                )

                if (playbackState == "stopped") {
                    stoppedPollCount += 1
                    if (stoppedPollCount >= 3) {
                        reportStoppedTrackIfNeeded(
                            plexClient = plexClient,
                            track = currentTrack,
                            timeMillis = currentTrackTimeMillis,
                            durationMillis = currentTrackDurationMillis,
                            continuing = false,
                        )
                        scrobbleIfNeeded(
                            plexClient = plexClient,
                            scrobbledTrackKeys = scrobbledTrackKeys,
                            track = currentTrack,
                            timeMillis = currentTrackTimeMillis,
                            durationMillis = currentTrackDurationMillis,
                        )
                        break
                    }
                } else {
                    stoppedPollCount = 0
                }
            }
        }
    }

    private suspend fun reportTimelineSafely(
        plexClient: PlexClient,
        track: PlexTrackStream,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        runCatching {
            plexClient.reportTimeline(
                ratingKey = track.ratingKey,
                state = state,
                timeMillis = timeMillis,
                durationMillis = durationMillis,
                continuing = continuing,
            )
        }
    }

    private suspend fun reportStoppedTrackIfNeeded(
        plexClient: PlexClient,
        track: PlexTrackStream,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        reportTimelineSafely(
            plexClient = plexClient,
            track = track,
            state = "stopped",
            timeMillis = timeMillis,
            durationMillis = durationMillis,
            continuing = continuing,
        )
    }

    private suspend fun scrobbleIfNeeded(
        plexClient: PlexClient,
        scrobbledTrackKeys: MutableSet<String>,
        track: PlexTrackStream,
        timeMillis: Long,
        durationMillis: Long?,
    ) {
        if (track.ratingKey in scrobbledTrackKeys) return

        val effectiveDuration = durationMillis?.takeIf { it > 0L } ?: return
        val scrobbleThreshold = (effectiveDuration * 0.9f).toLong()
        if (timeMillis < scrobbleThreshold) return

        runCatching {
            plexClient.scrobble(track.ratingKey)
        }.onSuccess {
            scrobbledTrackKeys += track.ratingKey
        }
    }

    private fun resolveActiveTrack(
        tracks: List<PlexTrackStream>,
        latestMiniPlayerState: MiniPlayerState,
        status: SonosPlaybackStatus,
    ): PlexTrackStream? {
        val normalizedCurrentUri = status.currentTrackUri.orEmpty().substringBefore('?')
        if (normalizedCurrentUri.isNotBlank()) {
            tracks.firstOrNull { it.streamUrl.substringBefore('?') == normalizedCurrentUri }?.let { return it }
        }
        return tracks.getOrNull(latestMiniPlayerState.currentIndex)
    }

    private fun resolveNextPlaybackIndex(trackCount: Int, currentIndex: Int): Int? {
        if (trackCount <= 0) return null
        val safeCurrentIndex = currentIndex.coerceIn(0, trackCount - 1)
        return when (playbackMode) {
            PlaybackMode.Sequential -> (safeCurrentIndex + 1).takeIf { it < trackCount }
            PlaybackMode.RepeatAll -> if (trackCount == 1) 0 else (safeCurrentIndex + 1) % trackCount
            PlaybackMode.RepeatOne -> safeCurrentIndex
            PlaybackMode.Shuffle -> {
                if (trackCount == 1) {
                    0
                } else {
                    val randomIndex = Random.nextInt(trackCount - 1)
                    if (randomIndex >= safeCurrentIndex) randomIndex + 1 else randomIndex
                }
            }
        }
    }

    private fun SonosPlaybackStatus.toPlexPlaybackState(): String = when {
        transportState.equals("PLAYING", ignoreCase = true) -> "playing"
        transportState.equals("PAUSED_PLAYBACK", ignoreCase = true) -> "paused"
        transportState.equals("TRANSITIONING", ignoreCase = true) -> "buffering"
        else -> "stopped"
    }

    private fun resolveAlbumForTrack(track: PlexTrackStream, fallbackAlbum: PlexAlbum? = null): PlexAlbum {
        val matchedAlbum = track.albumRatingKey?.let { ratingKey ->
            allAlbums.firstOrNull { album -> album.ratingKey == ratingKey }
        }
        if (matchedAlbum != null) {
            return matchedAlbum
        }

        val fallback = fallbackAlbum ?: selectedAlbum
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
