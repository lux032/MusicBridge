package com.lux032.musicbridge

import android.app.Application
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.AlbumTracksResult
import com.lux032.musicbridge.music.MusicPlaylist
import com.lux032.musicbridge.music.PlaylistTracksResult
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.isFavorite
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.LocalPlaybackService
import com.lux032.musicbridge.local.NetworkQualityMonitor
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.MediaTokenProbeResult
import com.lux032.musicbridge.music.Music032CapabilitiesStore
import com.lux032.musicbridge.music.Music032AiPlaylistJson
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032HttpException
import com.lux032.musicbridge.music.Music032DailyMix
import com.lux032.musicbridge.music.Music032PlaybackReporter
import com.lux032.musicbridge.music.Music032ReporterApi
import com.lux032.musicbridge.music.MusicSourceClient
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterNotice
import com.lux032.musicbridge.music.ReporterPlayState
import com.lux032.musicbridge.service.PlaybackForegroundService
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.storage.AlbumLocalStore
import com.lux032.musicbridge.storage.DownloadedAlbumGroup
import com.lux032.musicbridge.storage.OfflineDownloadManager
import com.lux032.musicbridge.storage.PlaybackSessionStore
import com.lux032.musicbridge.storage.SavedPlaybackSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Cursor save cadence while playing; bounds what a process kill can lose. */
private const val PLAYBACK_POSITION_SAVE_INTERVAL_MILLIS = 5_000L

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
    internal val albumLocalStore = AlbumLocalStore(context)
    internal val sonosController = SonosController()
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

    /** Lyrics come from the 032 server (or the in-app lookup); see [TrackLyricsRepository]. */
    internal val lyricsRepository = TrackLyricsRepository { sourceId -> runCatching { clientFor(sourceId) }.getOrNull() }
    internal var lyricsTranslationSettings by mutableStateOf(
        appPreferences.loadLyricsTranslationSettings(Strings.defaultLyricsLanguage),
    )
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
    private val playlistCoordinator = AppStatePlaylistCoordinator(this)
    private val dailyMixCoordinator = AppStateDailyMixCoordinator(this)
    private val worksCoordinator = AppStateWorksCoordinator(this)
    var homeWorks by mutableStateOf(com.lux032.musicbridge.music.Music032WorkRotation())
    var homeWorksSourceId by mutableStateOf("")
    var isWorksLoading by mutableStateOf(false)
    var worksError by mutableStateOf<String?>(null)
    private val musicSourceClients = java.util.concurrent.ConcurrentHashMap<String, CachedMusicSourceClient>()
    private val music032Reporters = java.util.concurrent.ConcurrentHashMap<String, Music032PlaybackReporter>()

    /**
     * Process-level scope driving the 032 reporter actors. Deliberately NOT [scope]:
     * onCleared cancels [scope], and the bounded shutdown ends must still be sent
     * afterwards (fire-and-forget, deadline-bounded, cancellable transport).
     */
    private val playbackReporterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Sources whose reporter must not be recreated (deleted server / tombstoned edit). */
    private val tombstoned032SourceIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Per-source reporter generation: bumped on retire so a dying reporter's async
     *  preference writes (snapshot/resume-block) are dropped instead of clobbering the
     *  replacement reporter's fresh state (P2-B). */
    private val music032ReporterGenerations = java.util.concurrent.ConcurrentHashMap<String, Long>()
    internal val localPlaybackCoordinator = AppStateLocalPlaybackCoordinator(this)

    /**
     * Per-032-source playback session reporter (plan C, apiRevision 3). Reporters own
     * sessionId/seq/hasPlayed and run a non-blocking actor on [playbackReporterScope];
     * every exit path ends the open session with a bounded fire-and-forget end.
     */
    internal fun music032ReporterFor(sourceId: String): Music032ReporterApi? {
        if (sourceId in tombstoned032SourceIds) return null
        val client = runCatching { clientFor(sourceId) }.getOrNull() as? Music032Client ?: return null
        return music032Reporters.getOrPut(sourceId) {
            val generation = music032ReporterGenerations[sourceId] ?: 0L
            Music032PlaybackReporter(
                transport = client.playbackEventTransport,
                preferences = AppPreferencesMusic032ReporterPreferences(appPreferences, sourceId) {
                    (music032ReporterGenerations[sourceId] ?: 0L) == generation
                },
                scope = playbackReporterScope,
                logger = { message -> Log.w("Music032Reporter", message) },
                contextSupported = { client.capabilities()?.hasFeature("playbackContext") == true },
                notifier = { notice ->
                    scope.launch {
                        when (notice) {
                            is ReporterNotice.ResumeBlocked ->
                                actionMessage = "播放记录与服务端会话冲突，本次播放不再上报"
                            is ReporterNotice.StartRejected ->
                                actionMessage = "播放上报被服务器拒绝（HTTP ${notice.status}），本次播放不再上报"
                        }
                    }
                },
            )
        }
    }

    /**
     * Ends and drops the reporter of one source; [tombstone] prevents any recreation.
     * [forget] wipes the persisted resume snapshot SYNCHRONOUSLY (P2-N3): the dying
     * reporter's async cleanup is generation-guarded and may be dropped, so the clear
     * cannot be delegated to it. Only used when the source is gone for good or points at
     * a DIFFERENT library (a track id means nothing across libraries).
     */
    private fun retireMusic032Reporter(sourceId: String, tombstone: Boolean, forget: Boolean = true) {
        if (tombstone) tombstoned032SourceIds += sourceId
        music032ReporterGenerations.merge(sourceId, 1L, Long::plus)
        if (forget) {
            appPreferences.saveMusic032LastSession(sourceId, null)
            appPreferences.saveMusic032ResumeBlock(sourceId, null)
        }
        music032Reporters.remove(sourceId)?.requestShutdown(ReporterEndReason.Stopped, forget = forget)
    }

    private val music032ProfileChangeHandler = Music032ProfileChangeHandler(
        retireReporter = { sourceId, forget ->
            retireMusic032Reporter(sourceId, tombstone = false, forget = forget)
        },
        reporterFactory = { sourceId -> music032ReporterFor(sourceId) },
        blockResume = { sourceId, trackId -> appPreferences.saveMusic032ResumeBlock(sourceId, trackId) },
        notify = { message -> actionMessage = message },
    )

    private var nextNavigationEntryId = 1L
    val navigationEntries = mutableStateListOf(NavigationEntry(0L, AppSection.Home))
    val navigationStack: List<AppSection>
        get() = navigationEntries.map { it.section }
    val serverProfiles = mutableStateListOf<MusicServerProfile>().apply { addAll(initialProfiles) }
    var primarySection by mutableStateOf(AppSection.Home)
    var connectionPreferences by mutableStateOf(initialPreferences)

    var isLoading by mutableStateOf(false)
    var isSonosLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var actionMessage by mutableStateOf<String?>(null)
    var allAlbums by mutableStateOf<List<MusicAlbum>>(emptyList())
    var hasLoadedLocalAlbums by mutableStateOf(false)
    var lastAlbumSyncEpochMillis by mutableStateOf(appPreferences.loadLastAlbumSyncEpochMillis(initialPreferences.id))
    var albumSearchQuery by mutableStateOf("")
    var albumSearchResults by mutableStateOf<List<MusicAlbum>>(emptyList())
    var isAlbumSearchLoading by mutableStateOf(false)
    var selectedAlbum by mutableStateOf<MusicAlbum?>(null)
    var selectedArtistName by mutableStateOf<String?>(null)
    /** 032 artist id of the open artist page (sync model identity); null = name lookup. */
    var selectedArtistKey by mutableStateOf<String?>(null)
    /** True while an artist link is being resolved against the server (shows a loader). */
    var isResolvingArtist by mutableStateOf(false)
    /** Online artist page used when the local sync model has no releases for the id. */
    var remoteArtist by mutableStateOf<ArtistSummary?>(null)
    /** Multi-artist link awaiting the user's pick (e.g. "A, B" duet credits). */
    var artistChoices by mutableStateOf<List<ArtistRef>>(emptyList())
    var artistTracks by mutableStateOf<List<MusicTrack>>(emptyList())
    var isArtistTracksLoading by mutableStateOf(false)
    var artistTopTracks by mutableStateOf<List<MusicTrack>>(emptyList())
    var artistTopTracksArePopular by mutableStateOf(false)
    var artistTracksTotal by mutableStateOf(0)
    var artistTracksOffset by mutableStateOf(0)
    var artistTracksError by mutableStateOf<String?>(null)
    var artistTrackSort by mutableStateOf(appPreferences.loadArtistTrackSort())
    var artistTrackOrder by mutableStateOf(appPreferences.loadArtistTrackOrder())
    /** Artist list view mode and favourites filter persist across restarts; the search
     *  query only lives for the session (like the album search). */
    var artistPresentation by mutableStateOf(appPreferences.loadArtistPresentation())
        private set
    var artistFavoritesOnly by mutableStateOf(appPreferences.loadArtistFavoritesOnly())
        private set
    var artistSearchQuery by mutableStateOf("")
    var trackResult by mutableStateOf<AlbumTracksResult?>(null)
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
    var playlists by mutableStateOf<List<MusicPlaylist>>(emptyList())
    var selectedPlaylist by mutableStateOf<MusicPlaylist?>(null)
    var playlistTrackResult by mutableStateOf<PlaylistTracksResult?>(null)
    var isPlaylistSyncing by mutableStateOf(false)
        internal set
    var playlistSyncProgress by mutableStateOf(0 to 0)
        internal set
    var favoriteTracks by mutableStateOf<List<MusicTrack>>(emptyList())
    var isAiGenerating by mutableStateOf(false)
        private set
    private var aiGenerationJob: Job? = null
    private var aiGenerationToken: Long = 0L
    var aiPlaylistDescription by mutableStateOf<String?>(null)
        internal set
    /** Pending "add to playlist" picker; non-null shows the sheet. */
    var playlistAddRequest by mutableStateOf<PlaylistAddition?>(null)
        internal set
    /** Latest playlist write feedback for the global snackbar. */
    var playlistNotice by mutableStateOf<PlaylistNotice?>(null)
        internal set
    var isPlaylistMutating by mutableStateOf(false)
        internal set
    /** 032 daily mixes for the home banner. */
    var dailyMixes by mutableStateOf(DailyMixHomeState())
        internal set
    /** Daily mix shown in the playlist page; matched by [dailyMixPlaylistKey]. */
    var openDailyMix by mutableStateOf<DailyMixDetailState?>(null)
        internal set

    val activeSection: AppSection
        get() = navigationStack.lastOrNull() ?: AppSection.Home

    data class SavedScrollPosition(val index: Int = 0, val offset: Int = 0)

    private val savedScrollValues = mutableMapOf<AppSection, Int>()
    private val savedLazyPositions = mutableMapOf<AppSection, SavedScrollPosition>()

    // Derived collections are wrapped in derivedStateOf so the filter/sort runs only
    // when their snapshot inputs (allAlbums / recentPlayedAlbumKeys) actually change,
    // instead of re-sorting the whole library on every recomposition that reads them.
    val favoriteAlbums: List<MusicAlbum> by derivedStateOf {
        allAlbums
            .filter(MusicAlbum::isFavorite)
            .sortedWith(
                compareBy<MusicAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )
    }

    val recentPlayedAlbums: List<MusicAlbum> by derivedStateOf {
        allAlbums
            .filter { album ->
                album.ratingKey in recentPlayedAlbumKeys || (album.lastViewedAtEpochSeconds ?: 0L) > 0L
            }
            .sortedWith(
                compareBy<MusicAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )
    }

    /** Record the actual current album, not the album used to seed a mixed queue. */
    internal fun recordPlayedAlbum(album: MusicAlbum) {
        val keys = appPreferences.recordPlayedAlbum(album)
        // Playback may continue on another server while its library is not selected.
        if (album.sourceId == connectionPreferences.id) {
            recentPlayedAlbumKeys = keys
        }
    }

    val recentAddedAlbums: List<MusicAlbum> by derivedStateOf {
        allAlbums
            .sortedWith(
                compareByDescending<MusicAlbum> { it.addedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )
    }

    // Reads selectedArtistName + artists (snapshot state); re-queries the local store
    // only when either changes. A library sync rebuilds `artists`, which keeps the
    // cached album list in step with the underlying database.
    val selectedArtist: ArtistSummary? by derivedStateOf {
        val selectedName = (selectedArtistName to artistAlbumsRevision).first
        val selectedKey = selectedArtistKey
        if (selectedKey != null) {
            // 032: identity is the artist id. Local sync data wins when it has releases;
            // otherwise the server detail (fresh install, stale id after a merge) fills in.
            val local = libraryCoordinator.localArtistSummary(connectionPreferences.id, selectedKey, artists)
            return@derivedStateOf remoteArtist?.takeIf { it.ratingKey == selectedKey }
                ?: local?.takeIf { it.albums.isNotEmpty() }
                ?: local
        }
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
        navigationEntries.add(NavigationEntry(nextNavigationEntryId++, section))
        lastNavAction = NavAction.Push
    }

    internal fun pushWorkEntry(sourceId: String, workId: Long) {
        navigationEntries.add(NavigationEntry(nextNavigationEntryId++, AppSection.WorkDetail, sourceId, workId))
        lastNavAction = NavAction.Push
    }

    fun loadHomeWorks(force: Boolean = false) = worksCoordinator.loadHome(force = force)
    fun rotateHomeWorks() = worksCoordinator.loadHome(next = true)
    fun openWork(sourceId: String, workId: Long) = worksCoordinator.open(sourceId, workId)
    suspend fun fetchTrackWorks(track: MusicTrack) = worksCoordinator.trackWorks(track)
    suspend fun fetchWorkInfo(sourceId: String, workId: Long) = worksCoordinator.info(sourceId, workId)
    suspend fun fetchWork(sourceId: String, workId: Long) = worksCoordinator.detail(sourceId, workId)
    suspend fun fetchWorkSeries(sourceId: String, seriesId: Long) = worksCoordinator.series(sourceId, seriesId)
    fun playWork(sourceId: String, work: com.lux032.musicbridge.music.Music032Work, shuffle: Boolean = false, series: Boolean = false) =
        worksCoordinator.play(sourceId, work, shuffle, series)
    fun enqueueWorkTracks(tracks: List<MusicTrack>, title: String) =
        playbackCoordinator.enqueueTracks(tracks, QueuePlacement.End, title)

    fun openPlaybackDetail() {
        val existing = navigationStack.indexOf(AppSection.PlaybackDetail)
        when {
            existing == navigationStack.lastIndex -> return
            existing >= 0 -> {
                while (navigationEntries.lastIndex > existing) {
                    navigationEntries.removeAt(navigationEntries.lastIndex)
                }
                lastNavAction = NavAction.Pop
            }
            else -> navigateTo(AppSection.PlaybackDetail)
        }
    }

    fun replaceWith(section: AppSection) {
        navigationEntries.clear()
        navigationEntries.add(NavigationEntry(nextNavigationEntryId++, section))
        lastNavAction = NavAction.TabSwitch
    }

    fun switchPrimarySection(section: AppSection) {
        primarySection = section
        replaceWith(section)
    }

    fun navigateBack() {
        if (navigationEntries.size > 1) {
            navigationEntries.removeAt(navigationEntries.lastIndex)
            lastNavAction = NavAction.Pop
        }
    }

    internal fun client(preferences: MusicServerProfile = connectionPreferences): MusicSourceClient {
        val cacheKey = preferences.id.ifBlank { "active:${preferences.type}" }
        val fingerprint = ClientFingerprint(preferences, opusTranscodeMode, sonosHiResTranscodeMode)
        musicSourceClients[cacheKey]?.takeIf { it.fingerprint == fingerprint }?.let { return it.client }
        return when (preferences.type) {
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
            SonosHiResTranscodeMode.TRANSCODE_FLAC_48K -> "已开启 Sonos Hi-Res FLAC 24/48 转码"
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

    fun isTrackDownloaded(track: MusicTrack): Boolean =
        offlineDownloadManager.isTrackDownloaded(track.sourceId, track.ratingKey)

    fun downloadTrack(track: MusicTrack) {
        actionMessage = Strings.downloadStarted(track.title)
        offlineDownloadManager.enqueueDownload(track) { success ->
            actionMessage = if (success) Strings.downloadSuccess(track.title) else Strings.downloadFailed(track.title)
        }
    }

    fun deleteDownloadedTrack(track: MusicTrack) {
        offlineDownloadManager.deleteDownload(track.sourceId, track.ratingKey)
        actionMessage = Strings.deleteDownloadedSuccess(track.title)
    }

    fun clearAllDownloads() {
        offlineDownloadManager.clearAllDownloads()
        actionMessage = Strings.clearAllDownloadsSuccess
    }

    fun openDownloadedTracks() = libraryCoordinator.openDownloadedTracks()
    fun downloadAlbum(album: MusicAlbum, tracks: List<MusicTrack>) = libraryCoordinator.downloadAlbum(album, tracks)
    fun deleteDownloadedAlbum(album: MusicAlbum) = libraryCoordinator.deleteDownloadedAlbum(album)
    fun deleteDownloadedAlbum(group: DownloadedAlbumGroup) = libraryCoordinator.deleteDownloadedAlbum(group)
    fun playDownloadedAlbum(group: DownloadedAlbumGroup, startIndex: Int = 0) = libraryCoordinator.playDownloadedAlbum(group, startIndex)
    fun shuffleAllDownloadedTracks() = libraryCoordinator.shuffleAllDownloadedTracks()

    fun updateAlbumSortOption(option: AlbumSortOption) {
        albumSortOption = option
        appPreferences.saveAlbumSortOption(option)
    }

    internal fun updateLyricsTranslationSettings(settings: LyricsTranslationSettings) {
        lyricsTranslationSettings = settings
        appPreferences.saveLyricsTranslationSettings(settings)
    }

    fun updateAlbumFavoritesOnly(enabled: Boolean) {
        albumFavoritesOnly = enabled
        appPreferences.saveAlbumFavoritesOnly(enabled)
    }

    fun updateArtistPresentation(presentation: ArtistPresentation) {
        artistPresentation = presentation
        appPreferences.saveArtistPresentation(presentation)
    }

    fun updateArtistFavoritesOnly(enabled: Boolean) {
        artistFavoritesOnly = enabled
        appPreferences.saveArtistFavoritesOnly(enabled)
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
            errorMessage = Strings.music032MissingMediaToken
            return
        }
        if (normalized.type == MusicServerType.Music032 && normalized.mediaToken.isBlank()) {
            errorMessage = Strings.music032MissingMediaToken
            return
        }
        val previous032 = if (index >= 0) {
            serverProfiles[index].takeIf { it.type == MusicServerType.Music032 }
        } else {
            null
        }
        if (previous032 != null) {
            Music032CapabilitiesStore.invalidate(
                Music032CapabilitiesStore.Key.of(previous032.baseUrl, previous032.token),
            )
        }
        val mediaUrlChanged = normalized.type == MusicServerType.Music032 && index >= 0 &&
            (serverProfiles[index].baseUrl != normalized.baseUrl || serverProfiles[index].mediaToken != normalized.mediaToken)
        musicSourceClients.remove(normalized.id)
        if (index >= 0) serverProfiles[index] = normalized else serverProfiles.add(normalized)
        // P1-N1: connection-change handling runs STRICTLY AFTER the client cache entry
        // and the stored profile carry the NEW credentials, so a handover reporter is
        // built on the new token — never on the revoked old one.
        if (previous032 != null) {
            music032ProfileChangeHandler.onProfileSaved(previous032, normalized, miniPlayerState) {
                localPlaybackEngine.currentPositionMillis
            }
        }
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
        // Tombstone + retire unconditionally (P3): even without local playback a lingering
        // reporter with an open session must stop heartbeating the deleted server.
        retireMusic032Reporter(serverId, tombstone = true, forget = true)
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

    private var startupProbeDone = false

    fun probeStartup032Profile() {
        if (startupProbeDone) return
        startupProbeDone = true
        probeActive032Profile()
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
        selectedArtistKey = null
        remoteArtist = null
        artistChoices = emptyList()
        isResolvingArtist = false
        selectedPlaylist = null
        playlistAddRequest = null
        trackResult = null
        playlistTrackResult = null
        albumSearchResults = emptyList()
        hasLoadedLocalAlbums = false
        errorMessage = null
        dailyMixCoordinator.reset()
        worksCoordinator.reset()
    }

    fun openAlbumDetail(album: MusicAlbum) = libraryCoordinator.openAlbumDetail(album)

    fun openArtistAlbums(artist: ArtistSummary) = libraryCoordinator.openArtistAlbums(artist)

    fun openArtistAlbumsByName(artistName: String?) = libraryCoordinator.openArtistAlbumsByName(artistName)

    /** Artist link with server ids when available; several ids ask the user to pick one. */
    fun openArtistLink(artistName: String?, refs: List<ArtistRef>) = libraryCoordinator.openArtistLink(artistName, refs)

    fun openArtistRef(ref: ArtistRef) = libraryCoordinator.openArtistRef(ref)

    fun dismissArtistChoices() {
        artistChoices = emptyList()
    }

    fun shuffleArtist(artist: ArtistSummary) = libraryCoordinator.shuffleArtist(artist)

    fun loadPlaylists() = libraryCoordinator.loadPlaylists()

    fun syncAllPlaylistsTracks() = libraryCoordinator.syncAllPlaylistsTracks()

    fun loadFavoriteTracks() = libraryCoordinator.loadFavoriteTracks()

    fun openFavoriteTracks() = libraryCoordinator.openFavoriteTracks()

    fun openPlaylistDetail(playlist: MusicPlaylist) = libraryCoordinator.openPlaylistDetail(playlist)

    // --- Playlist editing (032 servers) ---------------------------------------------------

    /** True when the active server supports creating / editing playlists (032 only). */
    val canCreatePlaylists: Boolean
        get() = connectionPreferences.type == MusicServerType.Music032 && connectionPreferences.hasCredentials()

    fun canAddToPlaylist(sourceId: String): Boolean = playlistCoordinator.canAddFrom(sourceId)

    fun canEditPlaylist(playlist: MusicPlaylist): Boolean = playlistCoordinator.canEditPlaylist(playlist)

    fun requestAddToPlaylist(addition: PlaylistAddition) = playlistCoordinator.requestAdd(addition)

    fun dismissAddToPlaylist() {
        playlistAddRequest = null
    }

    suspend fun createPlaylist(
        name: String,
        description: String,
        addition: PlaylistAddition? = null,
    ): Result<MusicPlaylist> = playlistCoordinator.create(name, description, addition)

    fun openCreatedPlaylist(playlist: MusicPlaylist) = playlistCoordinator.openCreated(playlist)

    fun addToPlaylist(playlist: MusicPlaylist, addition: PlaylistAddition) = playlistCoordinator.addTo(playlist, addition)

    suspend fun updatePlaylist(playlist: MusicPlaylist, name: String, description: String): Result<MusicPlaylist> =
        playlistCoordinator.update(playlist, name, description)

    suspend fun deletePlaylist(playlist: MusicPlaylist): Result<Unit> = playlistCoordinator.delete(playlist)

    fun removeTrackFromOpenPlaylist(track: MusicTrack) = playlistCoordinator.removeTrack(track)

    /** Global snackbar (playlist writes, daily mix feedback). */
    internal fun notifyPlaylist(message: String, actionLabel: String? = null, isError: Boolean = false, onAction: (() -> Unit)? = null) =
        playlistCoordinator.notify(message, actionLabel, isError, onAction)

    // --- Daily mixes (032 servers) --------------------------------------------------------

    fun loadDailyMixes(force: Boolean = false) = dailyMixCoordinator.load(force)

    fun openDailyMixDetail(mix: Music032DailyMix) = dailyMixCoordinator.open(mix)

    fun playDailyMix(mix: Music032DailyMix, shuffle: Boolean = false) = dailyMixCoordinator.play(mix, shuffle)

    fun rateOpenDailyMix(value: Int, reasons: List<String> = emptyList()) = dailyMixCoordinator.rate(value, reasons)

    fun replaceOpenDailyMix() = dailyMixCoordinator.replace()

    fun saveOpenDailyMix() = dailyMixCoordinator.save()

    fun toggleRecommendationBlock(track: MusicTrack) = dailyMixCoordinator.toggleBlock(track)

    fun consumePlaylistNotice(id: Long) {
        if (playlistNotice?.id == id) playlistNotice = null
    }

    fun replaceAlbumInState(updatedAlbum: MusicAlbum) = libraryCoordinator.replaceAlbumInState(updatedAlbum)

    fun replaceTrackInState(updatedTrack: MusicTrack) = libraryCoordinator.replaceTrackInState(updatedTrack)

    fun toggleAlbumFavorite(album: MusicAlbum) = libraryCoordinator.toggleAlbumFavorite(album)

    fun toggleTrackFavorite(track: MusicTrack) = libraryCoordinator.toggleTrackFavorite(track)

    fun toggleArtistFavorite(artist: ArtistSummary) = libraryCoordinator.toggleArtistFavorite(artist)

    fun loadArtistTracks(artist: ArtistSummary) = libraryCoordinator.loadArtistTracks(artist)

    fun loadMoreArtistTracks() = libraryCoordinator.loadMoreArtistTracks()

    fun cancelArtistTracks() = libraryCoordinator.cancelArtistTracks()

    fun setArtistTrackSort(sort: com.lux032.musicbridge.music.ArtistTrackSort, order: String) {
        artistTrackSort = sort
        artistTrackOrder = order
        appPreferences.saveArtistTrackSort(sort, order)
        selectedArtist?.let { libraryCoordinator.loadArtistTracks(it, refreshDetail = false) }
    }

    fun loadArtistAlbums(artist: ArtistSummary) = libraryCoordinator.loadArtistAlbums(artist)

    suspend fun loadArtists() = libraryCoordinator.loadArtists()

    fun startSingleTrackPlayback(
        album: MusicAlbum,
        tracks: List<MusicTrack>,
        trackIndex: Int,
        initialPositionMillis: Long = 0L,
    ) = playbackCoordinator.startSingleTrackPlayback(album, tracks, trackIndex, initialPositionMillis)

    fun startAlbumPlayback(
        albumTrackResult: AlbumTracksResult,
        startIndex: Int = 0,
        initialPositionMillis: Long = 0L,
    ) = playbackCoordinator.startAlbumPlayback(albumTrackResult, startIndex, initialPositionMillis)

    internal fun invalidateInFlightPlaybackRequests() {
        playbackCoordinator.invalidateInFlightPlaybackRequests()
        libraryCoordinator.cancelOfflineHydration()
    }

    /** True while a session restored after process death has not been loaded into any
     *  renderer yet (see AppStatePlaybackCoordinator.startRestoredSession). */
    internal val isRestoredSessionPending: Boolean
        get() = playbackCoordinator.isRestoredSessionPending

    internal val currentPlaybackRequestGeneration: Long
        get() = playbackCoordinator.currentPlaybackRequestGeneration

    fun refreshSonosRooms(announce: Boolean = true) = playbackCoordinator.refreshSonosRooms(announce)

    suspend fun loadVolume(room: SonosRoom) = playbackCoordinator.loadVolume(room)

    fun applyVolumeChange(room: SonosRoom, newValue: Float) = playbackCoordinator.applyVolumeChange(room, newValue)

    fun startPlaylistPlayback(
        playlistResult: PlaylistTracksResult,
        shuffle: Boolean = false,
    ) = playbackCoordinator.startPlaylistPlayback(playlistResult, shuffle)

    fun cancelAiGeneration() {
        aiGenerationToken++
        aiGenerationJob?.cancel()
        aiGenerationJob = null
        isAiGenerating = false
    }

    /**
     * AI playlist from one sentence, generated by the active 032 server
     * (`POST /api/v1/ai/playlists`): the server's model reads the prompt, the server
     * picks the tracks with its daily-mix selection. Played at once, not saved.
     */
    fun generateAiPlaylist(prompt: String) {
        val requestPrompt = prompt.trim()
        if (requestPrompt.isBlank()) return
        cancelAiGeneration()
        val token = ++aiGenerationToken
        isAiGenerating = true
        val sourceId = connectionPreferences.id
        aiGenerationJob = scope.launch {
            runCatching {
                val client = clientFor(sourceId) as? Music032Client ?: error(Strings.aiPlaylistUnavailable)
                val caps = runCatching { client.ensureCapabilities() }.getOrNull()
                check(caps?.hasFeature(Music032AiPlaylistJson.FEATURE) == true) { Strings.aiPlaylistUnavailable }
                client.generateAiPlaylist(requestPrompt).also { check(it.tracks.isNotEmpty()) { Strings.aiNoMatches } }
            }.onSuccess { generated ->
                if (token != aiGenerationToken) return@onSuccess
                val playlist = MusicPlaylist(
                    "ai:${UUID.randomUUID()}",
                    generated.title.ifBlank { Strings.aiPlaylist },
                    generated.tracks.firstOrNull()?.thumbUrl,
                    generated.tracks.size,
                    sourceId,
                )
                val result = PlaylistTracksResult("", playlist, generated.tracks)
                selectedPlaylist = playlist
                playlistTrackResult = result
                aiPlaylistDescription = listOf(
                    generated.description,
                    generated.conditions.takeIf { it.isNotEmpty() }?.let(Strings::aiPlaylistConditions).orEmpty(),
                ).filter(String::isNotBlank).joinToString("\n")
                navigateTo(AppSection.PlaylistDetail)
                startPlaylistPlayback(result)
            }.onFailure {
                if (token == aiGenerationToken && it !is kotlinx.coroutines.CancellationException) {
                    errorMessage = (it as? Music032HttpException)?.serverMessage ?: it.message
                }
            }
            if (token == aiGenerationToken) isAiGenerating = false
        }
    }

    /** Saves the open AI playlist as an ordinary server playlist. */
    fun saveCurrentAiPlaylist() {
        val current = playlistTrackResult ?: return
        if (!current.playlist.ratingKey.startsWith("ai:") || current.tracks.isEmpty()) return
        scope.launch {
            createPlaylist(
                current.playlist.title,
                aiPlaylistDescription.orEmpty().substringBefore('\n'),
                PlaylistAddition.Tracks(current.tracks),
            ).onFailure { errorMessage = it.message }
        }
    }

    fun togglePause(state: MiniPlayerState) = playbackCoordinator.togglePause(state)

    fun updatePlaybackMode(mode: PlaybackMode) = playbackCoordinator.updatePlaybackMode(mode)

    fun playQueueIndex(index: Int, positionMillis: Long = 0L, target: PlaybackTarget? = null) =
        playbackCoordinator.playQueueIndex(index, positionMillis, target)

    fun playPrevious() = playbackCoordinator.playPrevious()

    fun playNext() = playbackCoordinator.playNext()

    fun enqueueTrack(track: MusicTrack, placement: QueuePlacement) =
        playbackCoordinator.enqueueTracks(listOf(track), placement, track.title)

    fun enqueueAlbum(album: MusicAlbum, placement: QueuePlacement) =
        playbackCoordinator.enqueueAlbum(album, placement)

    fun playAlbum(album: MusicAlbum, startIndex: Int = 0) =
        playbackCoordinator.playAlbum(album, startIndex)

    fun switchPlaybackTarget(target: PlaybackTarget) = playbackCoordinator.switchPlaybackTarget(target)

    fun seekPlayback(state: MiniPlayerState, positionMillis: Long) =
        playbackCoordinator.seekPlayback(state, positionMillis)

    val sleepTimerState
        get() = SleepTimerController.state

    fun startSleepTimer(hours: Int, minutes: Int) =
        playbackCoordinator.startSleepTimer(hours, minutes)

    fun cancelSleepTimer() = playbackCoordinator.cancelSleepTimer()

    // --- Playback session persistence ----------------------------------------------------
    // The last session survives process death: on a cold start the mini player comes back
    // paused at the saved track/position, and the first transport command loads it (see
    // AppStatePlaybackCoordinator.startRestoredSession).

    private val playbackSessionStore = PlaybackSessionStore(context)
    /** Single-threaded so queue and cursor writes land in order. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val playbackSessionIo = Dispatchers.IO.limitedParallelism(1)
    private var persistedQueueId = 0L
    private var persistedTracks: List<MusicTrack>? = null
    private var persistedAlbum: MusicAlbum? = null

    init {
        // Library caches of servers whose type is no longer supported (Plex).
        appPreferences.retiredServerIds.takeIf { it.isNotEmpty() }?.let { retired ->
            scope.launch(Dispatchers.IO) { retired.forEach(albumLocalStore::deleteServerData) }
        }
        scope.launch {
            val saved = withContext(playbackSessionIo) { playbackSessionStore.load() }
            // Only if the user has not started something in the meantime.
            if (saved != null && miniPlayerState == null) restorePlaybackSession(saved)
            persistPlaybackSession()
        }
    }

    private suspend fun restorePlaybackSession(saved: SavedPlaybackSession) {
        val track = saved.tracks[saved.currentIndex]
        val sourceId = track.sourceId.ifBlank { saved.album.sourceId }
        if (serverProfiles.none { it.id == sourceId }) {
            // The server it belonged to was deleted: the session can never play again.
            withContext(playbackSessionIo) { playbackSessionStore.clear() }
            return
        }
        // Restore the queue and progress, not the output device: every cold start uses
        // this device, even when the saved session was playing on Sonos.
        val target = PlaybackTarget.LocalDevice
        playbackMode = saved.playbackMode
        selectPlaybackTarget(target)
        miniPlayerState = MiniPlayerState(
            album = saved.album,
            tracks = saved.tracks,
            currentIndex = saved.currentIndex,
            target = target,
            isPaused = true,
            isBuffering = false,
            currentPositionMillis = saved.positionMillis,
            durationMillis = saved.durationMillis ?: track.durationMillis,
        )
        playbackCoordinator.markRestoredSession(saved.tracks)
        // Already on disk: the observer must not rewrite it under a new id.
        persistedQueueId = saved.queueId
        persistedTracks = saved.tracks
        persistedAlbum = saved.album
        Log.i("PlaybackSession", "restored ${saved.tracks.size} tracks at index ${saved.currentIndex}, ${saved.positionMillis}ms")
    }

    /**
     * Mirrors the player into the store. The queue is rewritten only when it changes; the
     * cursor is written at once on track / pause / output / mode changes and otherwise at
     * most every [PLAYBACK_POSITION_SAVE_INTERVAL_MILLIS] while playing, so a kill loses at
     * most a few seconds. A null player (sleep timer, server switch) keeps the last saved
     * session, which is what a later cold start should offer to resume.
     */
    private suspend fun persistPlaybackSession() {
        var lastCursorKey: List<Any?>? = null
        var lastSavedPositionMillis = 0L
        snapshotFlow { miniPlayerState to playbackMode }.collect { (player, mode) ->
            player ?: return@collect
            if (player.tracks !== persistedTracks || player.album !== persistedAlbum) {
                val queueId = maxOf(persistedQueueId + 1, System.currentTimeMillis())
                persistedQueueId = queueId
                persistedTracks = player.tracks
                persistedAlbum = player.album
                val album = player.album
                val tracks = player.tracks
                withContext(playbackSessionIo) { playbackSessionStore.saveQueue(queueId, album, tracks) }
                lastCursorKey = null
            }
            val cursorKey = listOf(persistedQueueId, player.currentIndex, player.sonosRoom?.coordinatorUuid, player.isPaused, mode)
            val positionDelta = kotlin.math.abs(player.currentPositionMillis - lastSavedPositionMillis)
            val positionDue = if (player.isPaused) positionDelta > 0L else positionDelta >= PLAYBACK_POSITION_SAVE_INTERVAL_MILLIS
            if (cursorKey != lastCursorKey || positionDue) {
                lastCursorKey = cursorKey
                lastSavedPositionMillis = player.currentPositionMillis
                val queueId = persistedQueueId
                withContext(playbackSessionIo) {
                    playbackSessionStore.saveCursor(
                        queueId = queueId,
                        currentIndex = player.currentIndex,
                        positionMillis = player.currentPositionMillis,
                        durationMillis = player.durationMillis,
                        sonosRoom = player.sonosRoom,
                        playbackMode = mode,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        // Last cursor flush (SharedPreferences.apply is safe on the main thread).
        miniPlayerState?.takeIf { it.tracks === persistedTracks }?.let { player ->
            playbackSessionStore.saveCursor(
                queueId = persistedQueueId,
                currentIndex = player.currentIndex,
                positionMillis = player.currentPositionMillis,
                durationMillis = player.durationMillis,
                sonosRoom = player.sonosRoom,
                playbackMode = playbackMode,
            )
        }
        // Fire-and-forget bounded ends on the process-level reporter scope: no runBlocking
        // on the main thread (ANR/interlock risk), and delivery does not depend on the
        // scope this method is about to cancel. The transport is genuinely cancellable and
        // shutdown sends carry their own 1.5s deadline; a force kill or a lost end is
        // backstopped by the server-side 90s/10min lease. The screen turning off never
        // reaches here, so background playback keeps heartbeating.
        music032Reporters.values.forEach { reporter ->
            reporter.requestShutdown(ReporterEndReason.ClientClosed)
        }
        LocalPlaybackService.stop(context)
        PlaybackForegroundService.stop(context)
        localPlaybackEngine.release()
        scope.cancel()
        super.onCleared()
    }
}
