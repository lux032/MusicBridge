package com.lux032.musicbridge

import android.app.Application
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexAuthConfig
import com.lux032.musicbridge.plex.PlexClient
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.PlexTranscodeProxy
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.NetworkQualityMonitor
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.MusicSourceClient
import com.lux032.musicbridge.service.PlaybackForegroundService
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.storage.AlbumLocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

@Stable
class AppState(
    application: Application,
) : AndroidViewModel(application) {
    internal val context = application.applicationContext
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val appPreferences = AppPreferences(context)
    internal val plexClientIdentifier = appPreferences.loadOrCreatePlexClientIdentifier()
    internal val albumLocalStore = AlbumLocalStore(context)
    internal val sonosController = SonosController()
    internal val plexTranscodeProxy = PlexTranscodeProxy.getInstance(context)
    internal val networkQualityMonitor = NetworkQualityMonitor(context)
    internal val localPlaybackEngine = LocalPlaybackEngine(
        context = context,
        cacheMaxBytes = appPreferences.loadLocalCacheSize().bytes,
    )

    var opusTranscodeMode by mutableStateOf(appPreferences.getOpusTranscodeMode())

    var selectedPlaybackTarget by mutableStateOf(appPreferences.loadPlaybackTarget())
        private set

    /** The Sonos room currently targeted, if any — convenience for Sonos-only UI/logic. */
    val selectedSonosRoom: SonosRoom?
        get() = (selectedPlaybackTarget as? PlaybackTarget.Sonos)?.room

    var wifiStreamQuality by mutableStateOf(appPreferences.loadLocalStreamQuality(wifi = true))
        private set
    var cellularStreamQuality by mutableStateOf(appPreferences.loadLocalStreamQuality(wifi = false))
        private set
    var localCacheSize by mutableStateOf(appPreferences.loadLocalCacheSize())
        private set

    /** All-albums grid presentation, persisted so the user's choices survive navigation
     *  and app restarts. */
    var albumSortOption by mutableStateOf(appPreferences.loadAlbumSortOption())
        private set
    var albumFavoritesOnly by mutableStateOf(appPreferences.loadAlbumFavoritesOnly())
        private set
    var albumDecadeFilter by mutableStateOf(appPreferences.loadAlbumDecadeFilter())
        private set

    private val initialProfiles = appPreferences.loadServerProfiles()
    private val initialActiveServerId = appPreferences.loadActiveServerId(initialProfiles)
    private val initialPreferences = initialProfiles.firstOrNull { it.id == initialActiveServerId } ?: MusicServerProfile()
    private val libraryCoordinator = AppStateLibraryCoordinator(this)
    private val playbackCoordinator = AppStatePlaybackCoordinator(this)
    internal val localPlaybackCoordinator = AppStateLocalPlaybackCoordinator(this)

    val navigationStack = mutableStateListOf(AppSection.Home)
    val serverProfiles = mutableStateListOf<MusicServerProfile>().apply { addAll(initialProfiles) }
    var primarySection by mutableStateOf(AppSection.Home)
    var connectionPreferences by mutableStateOf(initialPreferences)

    var isLoading by mutableStateOf(false)
    var isSonosLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var actionMessage by mutableStateOf<String?>(null)
    var allAlbums by mutableStateOf<List<PlexAlbum>>(emptyList())
    var hasLoadedLocalAlbums by mutableStateOf(false)
    var lastAlbumSyncEpochMillis by mutableStateOf(appPreferences.loadLastAlbumSyncEpochMillis(initialPreferences.id))
    var albumSearchQuery by mutableStateOf("")
    var albumSearchResults by mutableStateOf<List<PlexAlbum>>(emptyList())
    var isAlbumSearchLoading by mutableStateOf(false)
    var selectedAlbum by mutableStateOf<PlexAlbum?>(null)
    var selectedArtistName by mutableStateOf<String?>(null)
    var artistTracks by mutableStateOf<List<PlexTrackStream>>(emptyList())
    var isArtistTracksLoading by mutableStateOf(false)
    var artistPresentation by mutableStateOf(ArtistPresentation.Covers)
    var trackResult by mutableStateOf<PlexAlbumTracksResult?>(null)
    var sonosRooms by mutableStateOf<List<SonosRoom>>(emptyList())
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
    var recentPlayedAlbumKeys by mutableStateOf(appPreferences.loadRecentPlayedAlbumKeys(initialPreferences.id))
    var artists by mutableStateOf<List<ArtistSummary>>(emptyList())
    var playlists by mutableStateOf<List<PlexPlaylist>>(emptyList())
    var selectedPlaylist by mutableStateOf<PlexPlaylist?>(null)
    var playlistTrackResult by mutableStateOf<PlexPlaylistTracksResult?>(null)
    var favoriteTracks by mutableStateOf<List<PlexTrackStream>>(emptyList())

    val activeSection: AppSection
        get() = navigationStack.lastOrNull() ?: AppSection.Home

    data class SavedScrollPosition(val index: Int = 0, val offset: Int = 0)

    private val savedScrollValues = mutableMapOf<AppSection, Int>()
    private val savedLazyPositions = mutableMapOf<AppSection, SavedScrollPosition>()

    // Derived collections are wrapped in derivedStateOf so the filter/sort runs only
    // when their snapshot inputs (allAlbums / recentPlayedAlbumKeys) actually change,
    // instead of re-sorting the whole library on every recomposition that reads them.
    val favoriteAlbums: List<PlexAlbum> by derivedStateOf {
        allAlbums
            .filter(PlexAlbum::isFavorite)
            .sortedWith(
                compareBy<PlexAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )
    }

    val recentPlayedAlbums: List<PlexAlbum> by derivedStateOf {
        allAlbums
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
    }

    val recentAddedAlbums: List<PlexAlbum> by derivedStateOf {
        allAlbums
            .sortedWith(
                compareByDescending<PlexAlbum> { it.addedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )
    }

    // Reads selectedArtistName + artists (snapshot state); re-queries the local store
    // only when either changes. A library sync rebuilds `artists`, which keeps the
    // cached album list in step with the underlying database.
    val selectedArtist: ArtistSummary? by derivedStateOf {
        selectedArtistName?.let { selectedName ->
            val albums = albumLocalStore.getAlbumsByArtist(connectionPreferences.id, selectedName)
            val summary = artists.firstOrNull { it.name == selectedName }
            summary?.let {
                ArtistSummary(
                    name = it.name,
                    coverUrl = it.coverUrl,
                    albumCount = it.albumCount,
                    albums = albums,
                    ratingKey = it.ratingKey,
                    sourceId = it.sourceId,
                    userRating = it.userRating,
                )
            }
        }
    }

    fun saveScrollValue(section: AppSection, value: Int) {
        savedScrollValues[section] = value
    }

    fun getSavedScrollValue(section: AppSection): Int = savedScrollValues[section] ?: 0

    fun saveLazyScrollPosition(section: AppSection, index: Int, offset: Int) {
        savedLazyPositions[section] = SavedScrollPosition(index, offset)
    }

    fun getSavedLazyScrollPosition(section: AppSection): SavedScrollPosition =
        savedLazyPositions[section] ?: SavedScrollPosition()

    /** Direction of the most recent navigation — the section AnimatedContent reads this to
     *  pick its transition style (player morph / push / pop / tab fade). */
    var lastNavAction by mutableStateOf(NavAction.TabSwitch)
        private set

    fun navigateTo(section: AppSection) {
        if (navigationStack.lastOrNull() == section) return
        navigationStack.add(section)
        lastNavAction = NavAction.Push
    }

    fun replaceWith(section: AppSection) {
        navigationStack.clear()
        navigationStack.add(section)
        lastNavAction = NavAction.TabSwitch
    }

    fun switchPrimarySection(section: AppSection) {
        primarySection = section
        replaceWith(section)
    }

    fun navigateBack() {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.lastIndex)
            lastNavAction = NavAction.Pop
        }
    }

    internal fun client(preferences: MusicServerProfile = connectionPreferences): MusicSourceClient =
        when (preferences.type) {
            MusicServerType.Plex -> PlexClient(
                PlexAuthConfig(
                    username = preferences.username,
                    password = preferences.password,
                    preferredServer = preferences.server,
                    baseUrl = preferences.baseUrl,
                    token = preferences.token,
                    sourceId = preferences.id,
                    clientIdentifier = plexClientIdentifier,
                    opusTranscodeMode = opusTranscodeMode,
                )
            )
            MusicServerType.Music032 -> Music032Client(preferences)
        }

    fun updateOpusTranscodeMode(mode: OpusTranscodeMode) {
        opusTranscodeMode = mode
        appPreferences.setOpusTranscodeMode(mode)
        actionMessage = when (mode) {
            OpusTranscodeMode.DIRECT_MP3 -> "已切换为 Opus 直连秒播 (MP3 320k)"
            OpusTranscodeMode.PROXY_FLAC -> "已切换为 Opus 无损中继 (FLAC 本地缓存)"
        }
    }

    fun selectPlaybackTarget(target: PlaybackTarget) {
        selectedPlaybackTarget = target
        appPreferences.savePlaybackTarget(target)
    }

    fun updateLocalStreamQuality(wifi: Boolean, quality: LocalStreamQuality) {
        if (wifi) wifiStreamQuality = quality else cellularStreamQuality = quality
        appPreferences.saveLocalStreamQuality(wifi, quality)
        actionMessage = "已更新${if (wifi) "WiFi" else "蜂窝网络"}音质：${quality.label}"
    }

    fun updateLocalCacheSize(size: LocalCacheSize) {
        localCacheSize = size
        appPreferences.saveLocalCacheSize(size)
        LocalAudioCache.setMaxBytes(size.bytes)
        actionMessage = "本机缓存上限已设为 ${size.megabytesLabel}"
    }

    fun clearLocalAudioCache() {
        LocalAudioCache.clear()
        actionMessage = "已清空本机缓存"
    }

    fun localCacheUsedBytes(): Long = LocalAudioCache.usedBytes()

    fun updateAlbumSortOption(option: AlbumSortOption) {
        albumSortOption = option
        appPreferences.saveAlbumSortOption(option)
    }

    fun updateAlbumFavoritesOnly(enabled: Boolean) {
        albumFavoritesOnly = enabled
        appPreferences.saveAlbumFavoritesOnly(enabled)
    }

    fun updateAlbumDecadeFilter(filter: AlbumDecadeFilter) {
        albumDecadeFilter = filter
        appPreferences.saveAlbumDecadeFilter(filter)
    }

    /** Quality applied to new local playback URLs, based on the current network. */
    internal fun currentLocalStreamQuality(): LocalStreamQuality =
        if (networkQualityMonitor.currentIsWifi()) wifiStreamQuality else cellularStreamQuality

    internal fun clientFor(sourceId: String): MusicSourceClient =
        client(
            serverProfiles.firstOrNull { it.id == sourceId }
                ?: connectionPreferences.takeIf { sourceId.isBlank() }
                ?: error("对应的音乐服务器连接已被移除。")
        )

    suspend fun loadAlbumsFromLocalStore() = libraryCoordinator.loadAlbumsFromLocalStore()

    suspend fun searchAlbums(query: String) = libraryCoordinator.searchAlbums(query)

    suspend fun refreshAlbums(showLoading: Boolean = true) = libraryCoordinator.refreshAlbums(showLoading)

    fun saveServerProfile(profile: MusicServerProfile, refreshAfterSave: Boolean) {
        val normalized = profile.copy(
            id = profile.id.ifBlank { UUID.randomUUID().toString() },
        ).normalizedForStorage()
        val index = serverProfiles.indexOfFirst { it.id == normalized.id }
        if (!normalized.hasCredentials()) {
            errorMessage = when (normalized.type) {
                MusicServerType.Plex -> "请填写 Plex Token，或填写 Plex 用户名和密码。"
                MusicServerType.Music032 -> "请填写 032 Music Server 地址和 API Token。"
            }
            return
        }
        if (index >= 0) serverProfiles[index] = normalized else serverProfiles.add(normalized)
        activateServer(normalized, refreshAfterSave)
        actionMessage = "已保存服务器：${normalized.displayName}"
    }

    fun switchServer(serverId: String) {
        val profile = serverProfiles.firstOrNull { it.id == serverId } ?: return
        if (profile.id == connectionPreferences.id) return
        activateServer(profile, refreshAfterSave = true)
    }

    fun deleteServer(serverId: String) {
        val index = serverProfiles.indexOfFirst { it.id == serverId }
        if (index < 0) return
        val wasActive = connectionPreferences.id == serverId
        if (miniPlayerState?.tracks?.any { it.sourceId == serverId } == true) {
            albumPlaybackJob?.cancel()
            playbackReportingJob?.cancel()
            miniPlayerState = null
            localPlaybackEngine.stopPlayback()
            PlaybackForegroundService.stop(context)
        }
        serverProfiles.removeAt(index)
        albumLocalStore.deleteServerData(serverId)
        appPreferences.clearSourceData(serverId)
        if (wasActive) {
            val next = serverProfiles.firstOrNull()
            if (next != null && next.id.isNotBlank()) {
                activateServer(next, refreshAfterSave = false)
            } else {
                connectionPreferences = MusicServerProfile()
                clearLibraryForServerChange()
                appPreferences.saveServerProfiles(serverProfiles, null)
            }
        } else {
            appPreferences.saveServerProfiles(serverProfiles, connectionPreferences.id)
        }
        actionMessage = "服务器已移除"
    }

    private fun activateServer(profile: MusicServerProfile, refreshAfterSave: Boolean) {
        connectionPreferences = profile
        appPreferences.saveServerProfiles(serverProfiles, profile.id)
        clearLibraryForServerChange()
        recentPlayedAlbumKeys = appPreferences.loadRecentPlayedAlbumKeys(profile.id)
        lastAlbumSyncEpochMillis = appPreferences.loadLastAlbumSyncEpochMillis(profile.id)
        scope.launch {
            loadAlbumsFromLocalStore()
            loadArtists()
            loadPlaylists()
            if (refreshAfterSave && profile.hasCredentials()) refreshAlbums(showLoading = true)
        }
    }

    private fun clearLibraryForServerChange() {
        allAlbums = emptyList()
        artists = emptyList()
        playlists = emptyList()
        favoriteTracks = emptyList()
        selectedAlbum = null
        selectedArtistName = null
        selectedPlaylist = null
        trackResult = null
        playlistTrackResult = null
        albumSearchResults = emptyList()
        hasLoadedLocalAlbums = false
        errorMessage = null
    }

    fun openAlbumDetail(album: PlexAlbum) = libraryCoordinator.openAlbumDetail(album)

    fun openArtistAlbums(artist: ArtistSummary) = libraryCoordinator.openArtistAlbums(artist)

    fun openArtistAlbumsByName(artistName: String?) = libraryCoordinator.openArtistAlbumsByName(artistName)

    fun shuffleArtist(artist: ArtistSummary) = libraryCoordinator.shuffleArtist(artist)

    fun loadPlaylists() = libraryCoordinator.loadPlaylists()

    fun loadFavoriteTracks() = libraryCoordinator.loadFavoriteTracks()

    fun openFavoriteTracks() = libraryCoordinator.openFavoriteTracks()

    fun openPlaylistDetail(playlist: PlexPlaylist) = libraryCoordinator.openPlaylistDetail(playlist)

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) = libraryCoordinator.replaceAlbumInState(updatedAlbum)

    fun replaceTrackInState(updatedTrack: PlexTrackStream) = libraryCoordinator.replaceTrackInState(updatedTrack)

    fun toggleAlbumFavorite(album: PlexAlbum) = libraryCoordinator.toggleAlbumFavorite(album)

    fun toggleTrackFavorite(track: PlexTrackStream) = libraryCoordinator.toggleTrackFavorite(track)

    fun toggleArtistFavorite(artist: ArtistSummary) = libraryCoordinator.toggleArtistFavorite(artist)

    fun loadArtistTracks(artist: ArtistSummary) = libraryCoordinator.loadArtistTracks(artist)

    suspend fun loadArtists() = libraryCoordinator.loadArtists()

    fun startSingleTrackPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        trackIndex: Int,
        initialPositionMillis: Long = 0L,
    ) = playbackCoordinator.startSingleTrackPlayback(album, tracks, trackIndex, initialPositionMillis)

    fun startAlbumPlayback(
        albumTrackResult: PlexAlbumTracksResult,
        startIndex: Int = 0,
        initialPositionMillis: Long = 0L,
    ) = playbackCoordinator.startAlbumPlayback(albumTrackResult, startIndex, initialPositionMillis)

    fun refreshSonosRooms(announce: Boolean = true) = playbackCoordinator.refreshSonosRooms(announce)

    suspend fun loadVolume(room: SonosRoom) = playbackCoordinator.loadVolume(room)

    fun applyVolumeChange(room: SonosRoom, newValue: Float) = playbackCoordinator.applyVolumeChange(room, newValue)

    fun startPlaylistPlayback(
        playlistResult: PlexPlaylistTracksResult,
        shuffle: Boolean = false,
    ) = playbackCoordinator.startPlaylistPlayback(playlistResult, shuffle)

    fun togglePause(state: MiniPlayerState) = playbackCoordinator.togglePause(state)

    fun updatePlaybackMode(mode: PlaybackMode) = playbackCoordinator.updatePlaybackMode(mode)

    fun playQueueIndex(index: Int, positionMillis: Long = 0L, target: PlaybackTarget? = null) =
        playbackCoordinator.playQueueIndex(index, positionMillis, target)

    fun enqueueTrack(track: PlexTrackStream, placement: QueuePlacement) =
        playbackCoordinator.enqueueTracks(listOf(track), placement, track.title)

    fun enqueueAlbum(album: PlexAlbum, placement: QueuePlacement) =
        playbackCoordinator.enqueueAlbum(album, placement)

    fun switchPlaybackTarget(target: PlaybackTarget) = playbackCoordinator.switchPlaybackTarget(target)

    fun seekPlayback(state: MiniPlayerState, positionMillis: Long) =
        playbackCoordinator.seekPlayback(state, positionMillis)

    val sleepTimerState
        get() = PlaybackForegroundService.sleepTimerState

    fun startSleepTimer(hours: Int, minutes: Int) =
        playbackCoordinator.startSleepTimer(hours, minutes)

    fun cancelSleepTimer() = playbackCoordinator.cancelSleepTimer()

    override fun onCleared() {
        localPlaybackEngine.release()
        scope.cancel()
        super.onCleared()
    }
}
