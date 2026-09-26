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
import com.lux032.musicbridge.plex.SonicCapableSource
import com.lux032.musicbridge.ai.AiLlmClient
import com.lux032.musicbridge.ai.AiSettings
import com.lux032.musicbridge.ai.HybridRecommender
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.LocalPlaybackService
import com.lux032.musicbridge.local.NetworkQualityMonitor
import com.lux032.musicbridge.music.MediaTokenProbeResult
import com.lux032.musicbridge.music.Music032CapabilitiesStore
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.MusicSourceClient
import com.lux032.musicbridge.service.PlaybackForegroundService
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.storage.AlbumLocalStore
import com.lux032.musicbridge.storage.DownloadedAlbumGroup
import com.lux032.musicbridge.storage.OfflineDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private data class ClientFingerprint(
    val profile: MusicServerProfile,
    val opusTranscodeMode: OpusTranscodeMode,
    val sonosHiResTranscodeMode: SonosHiResTranscodeMode,
)

private data class CachedMusicSourceClient(
    val fingerprint: ClientFingerprint,
    val client: MusicSourceClient,
)

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
    var currentNetworkIsWifi by mutableStateOf(networkQualityMonitor.currentIsWifi())
        internal set
    internal val localPlaybackEngine = LocalPlaybackEngine(
        context = context,
        cacheMaxBytes = appPreferences.loadLocalCacheSize().bytes,
    ).also { LocalPlaybackEngine.activeEngine = it }

    var opusTranscodeMode by mutableStateOf(appPreferences.getOpusTranscodeMode())
    var sonosHiResTranscodeMode by mutableStateOf(appPreferences.getSonosHiResTranscodeMode())

    // Output selection is intentionally session-only: every new app process starts locally.
    var selectedPlaybackTarget by mutableStateOf<PlaybackTarget>(PlaybackTarget.LocalDevice)
        internal set

    /** The Sonos room currently targeted, if any — convenience for Sonos-only UI/logic. */
    val selectedSonosRoom: SonosRoom?
        get() = (selectedPlaybackTarget as? PlaybackTarget.Sonos)?.room

    var wifiStreamingLimit by mutableStateOf(appPreferences.loadLocalStreamingLimit(wifi = true))
        private set
    var cellularStreamingLimit by mutableStateOf(appPreferences.loadLocalStreamingLimit(wifi = false))
        private set
    var localOpusBitrate by mutableStateOf(appPreferences.loadLocalOpusBitrate())
        private set
    var wifiPrecacheCount by mutableStateOf(appPreferences.loadLocalPrecacheCount(wifi = true))
        private set
    var cellularPrecacheCount by mutableStateOf(appPreferences.loadLocalPrecacheCount(wifi = false))
        private set
    var localCacheSize by mutableStateOf(appPreferences.loadLocalCacheSize())
        private set
    var cachedOriginalTrackKeys by mutableStateOf<Set<String>>(emptySet())
        internal set

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
    val offlineDownloadManager = OfflineDownloadManager(context, albumLocalStore, scope)
    val downloadedTrackKeys = offlineDownloadManager.downloadedKeys
    val downloadedAlbums = offlineDownloadManager.downloadedAlbums
    val activeDownloadProgress = offlineDownloadManager.downloadProgress
    private val libraryCoordinator = AppStateLibraryCoordinator(this)
    private val playbackCoordinator = AppStatePlaybackCoordinator(this)
    private val musicSourceClients = java.util.concurrent.ConcurrentHashMap<String, CachedMusicSourceClient>()
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
    var artistAlbumsRevision by mutableIntStateOf(0)
    var playlists by mutableStateOf<List<PlexPlaylist>>(emptyList())
    var selectedPlaylist by mutableStateOf<PlexPlaylist?>(null)
    var playlistTrackResult by mutableStateOf<PlexPlaylistTracksResult?>(null)
    var isPlaylistSyncing by mutableStateOf(false)
        internal set
    var playlistSyncProgress by mutableStateOf(0 to 0)
        internal set
    var favoriteTracks by mutableStateOf<List<PlexTrackStream>>(emptyList())
    var aiSettings by mutableStateOf(appPreferences.loadAiSettings())
        private set
    var isAiGenerating by mutableStateOf(false)
        private set
    private var aiGenerationJob: Job? = null
    private var aiGenerationToken: Long = 0L
    var aiPlaylistDescription by mutableStateOf<String?>(null)
        internal set

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
        val selectedName = (selectedArtistName to artistAlbumsRevision).first
        selectedName?.let {
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

    fun resetLazyScrollPosition(section: AppSection) {
        savedLazyPositions.remove(section)
    }

    /** Direction of the most recent navigation — the section AnimatedContent reads this to
     *  pick its transition style (player morph / push / pop / tab fade). */
    var lastNavAction by mutableStateOf(NavAction.TabSwitch)
        private set

    fun navigateTo(section: AppSection) {
        if (navigationStack.lastOrNull() == section) return
        navigationStack.add(section)
        lastNavAction = NavAction.Push
    }

    fun openPlaybackDetail() {
        val existing = navigationStack.indexOf(AppSection.PlaybackDetail)
        when {
            existing == navigationStack.lastIndex -> return
            existing >= 0 -> {
                while (navigationStack.lastIndex > existing) {
                    navigationStack.removeAt(navigationStack.lastIndex)
                }
                lastNavAction = NavAction.Pop
            }
            else -> navigateTo(AppSection.PlaybackDetail)
        }
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

    internal fun client(preferences: MusicServerProfile = connectionPreferences): MusicSourceClient {
        val cacheKey = preferences.id.ifBlank { "active:${preferences.type}" }
        val fingerprint = ClientFingerprint(preferences, opusTranscodeMode, sonosHiResTranscodeMode)
        musicSourceClients[cacheKey]?.takeIf { it.fingerprint == fingerprint }?.let { return it.client }
        return when (preferences.type) {
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
                    sonosHiResTranscodeMode = sonosHiResTranscodeMode,
                )
            )
            MusicServerType.Music032 -> Music032Client(preferences)
        }.also { musicSourceClients[cacheKey] = CachedMusicSourceClient(fingerprint, it) }
    }

    fun updateOpusTranscodeMode(mode: OpusTranscodeMode) {
        opusTranscodeMode = mode
        musicSourceClients.clear()
        appPreferences.setOpusTranscodeMode(mode)
        actionMessage = when (mode) {
            OpusTranscodeMode.DIRECT_MP3 -> "已切换为 Opus 直连秒播 (MP3 320k)"
            OpusTranscodeMode.PROXY_FLAC -> "已切换为 Opus 无损中继 (FLAC 本地缓存)"
        }
    }

    fun updateSonosHiResTranscodeMode(mode: SonosHiResTranscodeMode) {
        sonosHiResTranscodeMode = mode
        musicSourceClients.clear()
        appPreferences.setSonosHiResTranscodeMode(mode)
        actionMessage = when (mode) {
            SonosHiResTranscodeMode.TRANSCODE_MP3 -> "已开启 Sonos Hi-Res MP3 320k 转码"
            SonosHiResTranscodeMode.DIRECT_ORIGINAL -> "已切换为 Sonos Hi-Res 原盘直投"
        }
    }

    fun selectPlaybackTarget(target: PlaybackTarget) {
        selectedPlaybackTarget = target
    }

    fun updateLocalStreamingLimit(wifi: Boolean, limit: LocalStreamingLimit) {
        if (wifi) wifiStreamingLimit = limit else cellularStreamingLimit = limit
        appPreferences.saveLocalStreamingLimit(wifi, limit)
        actionMessage = "已更新${if (wifi) "WiFi" else "蜂窝网络"}流媒体上限：${limit.label}"
    }

    fun updateLocalOpusBitrate(bitrate: LocalOpusBitrate) {
        localOpusBitrate = bitrate
        appPreferences.saveLocalOpusBitrate(bitrate)
        actionMessage = "本机转码已设为 Opus ${bitrate.label}"
    }

    fun updateLocalPrecacheCount(wifi: Boolean, count: LocalPrecacheCount) {
        if (wifi) wifiPrecacheCount = count else cellularPrecacheCount = count
        appPreferences.saveLocalPrecacheCount(wifi, count)
        actionMessage = "已更新${if (wifi) "WiFi" else "蜂窝网络"}预缓存：${count.label}"
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

    fun totalDownloadedBytes(): Long = albumLocalStore.totalDownloadedBytes()

    fun isTrackDownloaded(track: PlexTrackStream): Boolean =
        offlineDownloadManager.isTrackDownloaded(track.sourceId, track.ratingKey)

    fun downloadTrack(track: PlexTrackStream) {
        actionMessage = Strings.downloadStarted(track.title)
        offlineDownloadManager.enqueueDownload(track) { success ->
            actionMessage = if (success) Strings.downloadSuccess(track.title) else Strings.downloadFailed(track.title)
        }
    }

    fun deleteDownloadedTrack(track: PlexTrackStream) {
        offlineDownloadManager.deleteDownload(track.sourceId, track.ratingKey)
        actionMessage = Strings.deleteDownloadedSuccess(track.title)
    }

    fun clearAllDownloads() {
        offlineDownloadManager.clearAllDownloads()
        actionMessage = Strings.clearAllDownloadsSuccess
    }

    fun openDownloadedTracks() = libraryCoordinator.openDownloadedTracks()
    fun downloadAlbum(album: PlexAlbum, tracks: List<PlexTrackStream>) = libraryCoordinator.downloadAlbum(album, tracks)
    fun deleteDownloadedAlbum(album: PlexAlbum) = libraryCoordinator.deleteDownloadedAlbum(album)
    fun deleteDownloadedAlbum(group: DownloadedAlbumGroup) = libraryCoordinator.deleteDownloadedAlbum(group)
    fun playDownloadedAlbum(group: DownloadedAlbumGroup, startIndex: Int = 0) = libraryCoordinator.playDownloadedAlbum(group, startIndex)
    fun shuffleAllDownloadedTracks() = libraryCoordinator.shuffleAllDownloadedTracks()

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

    /** Policy captured when a local queue is built; Sonos never reads these settings. */
    internal fun currentLocalPlaybackPolicy(): LocalPlaybackPolicy {
        val wifi = currentNetworkIsWifi
        return LocalPlaybackPolicy(
            streamingLimit = if (wifi) wifiStreamingLimit else cellularStreamingLimit,
            opusBitrate = localOpusBitrate,
            precacheCount = if (wifi) wifiPrecacheCount else cellularPrecacheCount,
        )
    }

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
                MusicServerType.Music032 -> Strings.music032MissingMediaToken
            }
            return
        }
        if (normalized.type == MusicServerType.Music032 && normalized.mediaToken.isBlank()) {
            errorMessage = Strings.music032MissingMediaToken
            return
        }
        if (index >= 0 && serverProfiles[index].type == MusicServerType.Music032) {
            val previous = serverProfiles[index]
            Music032CapabilitiesStore.invalidate(Music032CapabilitiesStore.Key.of(previous.baseUrl, previous.token))
        }
        val mediaUrlChanged = normalized.type == MusicServerType.Music032 && index >= 0 &&
            (serverProfiles[index].baseUrl != normalized.baseUrl || serverProfiles[index].mediaToken != normalized.mediaToken)
        musicSourceClients.remove(normalized.id)
        if (index >= 0) serverProfiles[index] = normalized else serverProfiles.add(normalized)
        activateServer(normalized, refreshAfterSave || mediaUrlChanged)
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
            LocalPlaybackService.stop(context)
        }
        serverProfiles.removeAt(index)
        musicSourceClients.remove(serverId)
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
        probeActive032Profile(profile, probeProvidedToken = true)
        scope.launch {
            loadAlbumsFromLocalStore()
            loadArtists()
            loadPlaylists()
            if (refreshAfterSave && profile.hasCredentials()) refreshAlbums(showLoading = true)

        }
    }

    fun probeActive032Profile(profile: MusicServerProfile = connectionPreferences, probeProvidedToken: Boolean = false) {
        if (profile.type != MusicServerType.Music032 || !profile.hasCredentials()) return
        val client = runCatching { clientFor(profile.id) }.getOrNull() as? Music032Client ?: return
        scope.launch {
            launch {
                try { client.ensureCapabilities() } catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { /* Capability failure is silent. */ }
            }
            if (probeProvidedToken || profile.mediaToken.isBlank()) {
                launch {
                    val result = try { client.probeMediaToken() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { MediaTokenProbeResult.UNKNOWN }
                    if (result == MediaTokenProbeResult.INVALID && connectionPreferences.id == profile.id && errorMessage == null) {
                        errorMessage = Strings.music032InvalidMediaToken
                    }
                }
            }
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

    fun syncAllPlaylistsTracks() = libraryCoordinator.syncAllPlaylistsTracks()

    fun loadFavoriteTracks() = libraryCoordinator.loadFavoriteTracks()

    fun openFavoriteTracks() = libraryCoordinator.openFavoriteTracks()

    fun openPlaylistDetail(playlist: PlexPlaylist) = libraryCoordinator.openPlaylistDetail(playlist)

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) = libraryCoordinator.replaceAlbumInState(updatedAlbum)

    fun replaceTrackInState(updatedTrack: PlexTrackStream) = libraryCoordinator.replaceTrackInState(updatedTrack)

    fun toggleAlbumFavorite(album: PlexAlbum) = libraryCoordinator.toggleAlbumFavorite(album)

    fun toggleTrackFavorite(track: PlexTrackStream) = libraryCoordinator.toggleTrackFavorite(track)

    fun toggleArtistFavorite(artist: ArtistSummary) = libraryCoordinator.toggleArtistFavorite(artist)

    fun loadArtistTracks(artist: ArtistSummary) = libraryCoordinator.loadArtistTracks(artist)

    fun loadArtistAlbums(artist: ArtistSummary) = libraryCoordinator.loadArtistAlbums(artist)

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

    internal fun invalidateInFlightPlaybackRequests() {
        playbackCoordinator.invalidateInFlightPlaybackRequests()
        libraryCoordinator.cancelOfflineHydration()
    }

    internal val currentPlaybackRequestGeneration: Long
        get() = playbackCoordinator.currentPlaybackRequestGeneration

    fun refreshSonosRooms(announce: Boolean = true) = playbackCoordinator.refreshSonosRooms(announce)

    suspend fun loadVolume(room: SonosRoom) = playbackCoordinator.loadVolume(room)

    fun applyVolumeChange(room: SonosRoom, newValue: Float) = playbackCoordinator.applyVolumeChange(room, newValue)

    fun startPlaylistPlayback(
        playlistResult: PlexPlaylistTracksResult,
        shuffle: Boolean = false,
    ) = playbackCoordinator.startPlaylistPlayback(playlistResult, shuffle)

    fun updateAiSettings(settings: AiSettings) {
        aiSettings = settings.copy(
            baseUrl = settings.baseUrl.trim().trimEnd('/'),
            apiKey = settings.apiKey.trim(),
            model = settings.model.trim(),
        )
        appPreferences.saveAiSettings(aiSettings)
        actionMessage = Strings.saveAiSettings
    }

    fun cancelAiGeneration() {
        aiGenerationToken++
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        isAiGenerating = false
    }

    fun generateAiPlaylist(prompt: String) {
        if (connectionPreferences.type != MusicServerType.Plex) {
            errorMessage = Strings.aiPlexOnly
            return
        }
        val requestPrompt = prompt.trim()
        if (requestPrompt.isBlank()) return
        cancelAiGeneration()
        val token = ++aiGenerationToken
        isAiGenerating = true
        aiGenerationJob = scope.launch {
            runCatching {
                val source = clientFor(connectionPreferences.id)
                val library = withContext(Dispatchers.IO) {
                    val cached = allAlbums.flatMap { album ->
                        albumLocalStore.getAlbumTracks(album.sourceId, album.ratingKey).ifEmpty {
                            source.fetchAlbumTrackStreams(album).tracks
                        }
                    }
                    val recent = (source as? PlexClient)?.let { plex ->
                        withTimeoutOrNull(5_000L) {
                            runCatching { plex.fetchRecentlyPlayedTracks() }.getOrDefault(emptyList())
                        }.orEmpty()
                    }.orEmpty()
                    (cached + recent).groupBy { it.ratingKey }.values.map { matches ->
                        matches.drop(1).fold(matches.first()) { merged, track ->
                            HybridRecommender.mergeBehavior(merged, track)
                        }
                    }
                }
                val libraryArtists = library.mapNotNull { it.artistName?.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .shuffled()
                    .take(80)
                val intent = withContext(Dispatchers.IO) {
                    AiLlmClient(aiSettings).generateIntent(requestPrompt, libraryArtists)
                }
                val recommended = HybridRecommender.recommend(
                    intent, library, source as? SonicCapableSource, favoriteTracks, 30,
                    prompt = requestPrompt,
                )
                check(recommended.isNotEmpty()) { Strings.aiNoMatches }
                if (token != aiGenerationToken) return@launch
                val playlist = PlexPlaylist("ai:${UUID.randomUUID()}", intent.title, recommended.firstOrNull()?.thumbUrl, recommended.size, connectionPreferences.id)
                val result = PlexPlaylistTracksResult("", playlist, recommended)
                selectedPlaylist = playlist
                playlistTrackResult = result
                aiPlaylistDescription = intent.description
                navigateTo(AppSection.PlaylistDetail)
                startPlaylistPlayback(result)
            }.onFailure {
                if (token == aiGenerationToken && it !is kotlinx.coroutines.CancellationException) {
                    errorMessage = when (it.message) {
                        "AI_NOT_CONFIGURED" -> Strings.aiNotConfigured
                        "AI_RESPONSE_NOT_JSON" -> Strings.aiResponseInvalid
                        else -> it.message
                    }
                }
            }
            if (token == aiGenerationToken) isAiGenerating = false
        }
    }

    fun saveCurrentAiPlaylistToPlex() {
        val current = playlistTrackResult ?: return
        if (!current.playlist.ratingKey.startsWith("ai:")) return
        scope.launch {
            runCatching {
                (clientFor(current.playlist.sourceId) as? SonicCapableSource)
                    ?.createPlaylist(current.playlist.title, current.tracks.map { it.ratingKey })
                    ?: error(Strings.plexSaveUnsupported)
            }.onSuccess { saved ->
                actionMessage = Strings.savedToPlex
                loadPlaylists()
                selectedPlaylist = saved
                playlistTrackResult = current.copy(playlist = saved.copy(leafCount = current.tracks.size))
            }.onFailure { errorMessage = it.message }
        }
    }

    fun startSonicRadio(track: PlexTrackStream) {
        scope.launch {
            runCatching {
                val sonic = clientFor(track.sourceId) as? SonicCapableSource ?: error(Strings.sonicUnavailable)
                val matches = sonic.fetchNearestTracks(track.ratingKey, 30).map { it.track }
                check(matches.isNotEmpty()) { Strings.sonicNoNeighbors }
                val playlist = PlexPlaylist("ai:${UUID.randomUUID()}", Strings.sonicRadio, track.thumbUrl, matches.size + 1, track.sourceId)
                PlexPlaylistTracksResult("", playlist, listOf(track) + matches)
            }.onSuccess { result -> aiPlaylistDescription = null; playlistTrackResult = result; selectedPlaylist = result.playlist; navigateTo(AppSection.PlaylistDetail); startPlaylistPlayback(result) }
                .onFailure { errorMessage = it.message }
        }
    }

    fun togglePause(state: MiniPlayerState) = playbackCoordinator.togglePause(state)

    fun updatePlaybackMode(mode: PlaybackMode) = playbackCoordinator.updatePlaybackMode(mode)

    fun playQueueIndex(index: Int, positionMillis: Long = 0L, target: PlaybackTarget? = null) =
        playbackCoordinator.playQueueIndex(index, positionMillis, target)

    fun playPrevious() = playbackCoordinator.playPrevious()

    fun playNext() = playbackCoordinator.playNext()

    fun enqueueTrack(track: PlexTrackStream, placement: QueuePlacement) =
        playbackCoordinator.enqueueTracks(listOf(track), placement, track.title)

    fun enqueueAlbum(album: PlexAlbum, placement: QueuePlacement) =
        playbackCoordinator.enqueueAlbum(album, placement)

    fun playAlbum(album: PlexAlbum, startIndex: Int = 0) =
        playbackCoordinator.playAlbum(album, startIndex)

    fun switchPlaybackTarget(target: PlaybackTarget) = playbackCoordinator.switchPlaybackTarget(target)

    fun seekPlayback(state: MiniPlayerState, positionMillis: Long) =
        playbackCoordinator.seekPlayback(state, positionMillis)

    val sleepTimerState
        get() = SleepTimerController.state

    fun startSleepTimer(hours: Int, minutes: Int) =
        playbackCoordinator.startSleepTimer(hours, minutes)

    fun cancelSleepTimer() = playbackCoordinator.cancelSleepTimer()

    override fun onCleared() {
        LocalPlaybackService.stop(context)
        PlaybackForegroundService.stop(context)
        localPlaybackEngine.release()
        scope.cancel()
        super.onCleared()
    }
}
