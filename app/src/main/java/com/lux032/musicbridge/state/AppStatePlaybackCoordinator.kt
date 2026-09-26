package com.lux032.musicbridge

import android.util.Log
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032Json
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.music.Music032Warmup
import com.lux032.musicbridge.music.WarmupResult
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.originalCacheKey
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.PlexClient
import com.lux032.musicbridge.plex.playbackUrlIdentity
import com.lux032.musicbridge.plex.withPlexTranscodeOffsetSeconds
import com.lux032.musicbridge.plex.withOpusTranscodeMode
import com.lux032.musicbridge.plex.withSonosHiResTranscodeMode
import com.lux032.musicbridge.service.PlaybackForegroundService
import com.lux032.musicbridge.sonos.SonosDiscovery
import com.lux032.musicbridge.sonos.SonosPlaybackStatus
import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

internal fun shouldApplyPlexSonosTranscode(isPlexClient: Boolean?, streamUrl: String): Boolean =
    isPlexClient ?: streamUrl.contains("X-Plex-Token", ignoreCase = true)

internal fun preparePlexSonosTranscode(
    track: PlexTrackStream,
    isPlexSource: Boolean,
    opusMode: OpusTranscodeMode,
    hiResMode: SonosHiResTranscodeMode,
    clientId: String,
): PlexTrackStream = if (isPlexSource) {
    track.withOpusTranscodeMode(opusMode, clientId).withSonosHiResTranscodeMode(hiResMode.forPlex(), clientId)
} else track

internal fun configuredPlexDirectMp3(
    track: PlexTrackStream,
    isPlexSource: Boolean,
    opusMode: OpusTranscodeMode,
    hiResMode: SonosHiResTranscodeMode,
): Boolean = isPlexSource && (
    (track.sourceAudioCodec.equals("opus", ignoreCase = true) && opusMode == OpusTranscodeMode.DIRECT_MP3) ||
        (track.sourceAudioCodec?.lowercase() in LOSSLESS_AUDIO_CODECS &&
            (track.sourceAudioSampleRate ?: 0) > SONOS_MAX_SAMPLE_RATE_HZ &&
            hiResMode.forPlex() == SonosHiResTranscodeMode.TRANSCODE_MP3)
    )

internal class AppStatePlaybackCoordinator(
    private val state: AppState,
) {
    private val playbackRequestGeneration = AtomicLong(0L)
    private val seekRequestGeneration = AtomicLong(0L)
    private val playbackToggleGeneration = AtomicLong(0L)
    private val playbackToggleMutex = Mutex()
    private val hydrationCache = Music032HydrationCache(state.scope)

    private var pending032Plan: Pair<PlexTrackStream, SonosPlan>? = null
    private var pending032Offset = 0L
    @Volatile private var activeSonosPlan: ActiveSonosPlan? = null
    private data class ActiveSonosPlan(val sourceId: String, val ratingKey: String, val kind: SonosPlanKind,
                                       val url: String, val mime: String?)
    private data class PreparedSonosTrack(val track: PlexTrackStream, val plan: SonosPlan?)
    private var pending032Fallback = false
    private var current032Fallback = false

    private fun is032Source(track: PlexTrackStream): Boolean =
        runCatching { state.clientFor(track.sourceId) is Music032Client }.getOrNull()
            ?: Music032TranscodeUrls.isOriginalStream(track.streamUrl)

    private fun activePlanFor(track: PlexTrackStream): ActiveSonosPlan? = activeSonosPlan?.takeIf {
        it.sourceId == track.sourceId && it.ratingKey == track.ratingKey &&
            state.miniPlayerState?.let { player ->
                player.tracks.getOrNull(player.currentIndex)?.let { current ->
                    current.sourceId == track.sourceId && current.ratingKey == track.ratingKey
                }
            } == true
    }

    private fun set032RestartSeek(track: PlexTrackStream, offset: Long) {
        val clamped = Music032TranscodeUrls.clampedOffsetMs(offset, track.durationMillis)
        transcodeRestartSeek = if (clamped > 0) TranscodeRestartSeek(track.sourceId, track.ratingKey,
            clamped, seekRequestGeneration.incrementAndGet(), true, System.currentTimeMillis()) else null
    }

    private fun record032Plan(track: PlexTrackStream, plan: SonosPlan, url: String) {
        activeSonosPlan = ActiveSonosPlan(track.sourceId, track.ratingKey, plan.kind, url, plan.mime)
    }

    @Volatile
    private var transcodeRestartSeek: TranscodeRestartSeek? = null

    init {
        SleepTimerController.onExpired = { room ->
            activeSonosPlan = null
            pending032Plan = null
            playbackRequestGeneration.incrementAndGet()
            state.albumPlaybackJob?.cancel()
            state.playbackReportingJob?.cancel()
            state.localPlaybackCoordinator.stopLocalPlayback()
            if (room != null) {
                state.scope.launch {
                    runCatching { state.sonosController.pause(room) }
                    runCatching { state.sonosController.stop(room) }
                }
            }
            // Tears down the Sonos-side notification if that service was running.
            PlaybackForegroundService.stop(state.context)
            state.scope.launch {
                state.isPlaybackCommandLoading = false
                state.errorMessage = null
                state.miniPlayerState = null
                state.actionMessage = Strings.sleepTimerFinished
            }
        }
    }

    fun startSingleTrackPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        trackIndex: Int,
        initialPositionMillis: Long,
    ) {
        when (val target = state.selectedPlaybackTarget) {
            PlaybackTarget.LocalDevice -> {
                val targetTrack = tracks.getOrNull(trackIndex) ?: return
                state.localPlaybackCoordinator.startLocalPlayback(
                    album = resolveAlbumForTrack(targetTrack, album),
                    tracks = tracks,
                    startIndex = trackIndex,
                    initialPositionMillis = initialPositionMillis,
                )
            }
            is PlaybackTarget.Sonos -> startSingleTrackPlaybackOnSonos(
                album = album,
                tracks = tracks,
                trackIndex = trackIndex,
                room = target.room,
                initialPositionMillis = initialPositionMillis,
            )
        }
    }

    private fun startSingleTrackPlaybackOnSonos(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        trackIndex: Int,
        room: SonosRoom,
        initialPositionMillis: Long,
    ) {
        val targetTrack = tracks.getOrNull(trackIndex) ?: return
        val resumesLiveTranscodeDirectMp3 = initialPositionMillis > 0L && !is032Source(targetTrack) && targetTrack.isConfiguredDirectMp3()
        transcodeRestartSeek = if (resumesLiveTranscodeDirectMp3) {
            TranscodeRestartSeek(
                sourceId = targetTrack.sourceId,
                ratingKey = targetTrack.ratingKey,
                offsetMillis = initialPositionMillis.coerceAtLeast(0L),
                generation = seekRequestGeneration.incrementAndGet(),
                pending = true,
                startedAtMillis = System.currentTimeMillis(),
            )
        } else {
            null
        }
        val requestGeneration = nextPlaybackRequestGeneration()
        val displayAlbum = resolveAlbumForTrack(targetTrack, album)
        stopLocalPlaybackForSonosHandover()
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        state.appPreferences.markAlbumPlayed(displayAlbum.sourceId, displayAlbum.ratingKey)
        state.recentPlayedAlbumKeys = state.appPreferences.loadRecentPlayedAlbumKeys(displayAlbum.sourceId)
        state.selectPlaybackTarget(PlaybackTarget.Sonos(room))
        state.volumeSyncKey += 1
        syncSleepTimerRoom(room)
        state.miniPlayerState = MiniPlayerState(
            album = displayAlbum,
            tracks = tracks,
            currentIndex = trackIndex,
            target = PlaybackTarget.Sonos(room),
            isPaused = false,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = targetTrack.durationMillis,
        )
        PlaybackForegroundService.start(
            context = state.context,
            title = targetTrack.title,
            subtitle = "正在推送到 ${room.roomName}",
            isPaused = false,
            artworkUrl = targetTrack.thumbUrl,
        )
        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                val prepared = prepareSonosTrack(targetTrack) { isCurrentPlaybackRequest(requestGeneration) }
                if (prepared.plan != null) ensureCurrentPlaybackRequest(requestGeneration)
                val effectiveTrack = prepared.track
                val isOpus = effectiveTrack.sourceAudioCodec.equals("opus", ignoreCase = true)
                val isLiveTranscodeDirectMp3 = effectiveTrack.streamUrl.contains("/:/transcode/universal/start.mp3", ignoreCase = true)
                if (isOpus && isPlexSource(effectiveTrack) && state.opusTranscodeMode == OpusTranscodeMode.PROXY_FLAC) {
                    state.actionMessage = "正在准备 FLAC 缓存：${effectiveTrack.title}"
                }
                val playbackUrl = if (prepared.plan != null) {
                    state.plexTranscodeProxy.cancelPendingDownload()
                    if (prepared.plan.kind == SonosPlanKind.LIVE_MP3)
                        Music032TranscodeUrls.withOffsetMs(prepared.plan.url, initialPositionMillis, effectiveTrack.durationMillis)
                    else prepared.plan.url
                } else if (isLiveTranscodeDirectMp3) {
                    state.plexTranscodeProxy.cancelPendingDownload()
                    effectiveTrack.streamUrl.withPlexTranscodeOffsetSeconds(
                        (initialPositionMillis.coerceAtLeast(0L) / 1_000L).toInt(),
                    )
                } else {
                    state.plexTranscodeProxy.preparePlaybackUrl(effectiveTrack)
                }
                ensureCurrentPlaybackRequest(requestGeneration)
                if (prepared.plan != null) set032RestartSeek(effectiveTrack,
                    if (prepared.plan.kind == SonosPlanKind.LIVE_MP3) initialPositionMillis else 0L)
                state.sonosController.playTrack(
                    room = room,
                    trackUrl = playbackUrl,
                    title = effectiveTrack.title,
                    albumTitle = effectiveTrack.albumTitle ?: displayAlbum.title,
                    mimeType = if (prepared.plan != null) prepared.plan.mime else effectiveTrack.playbackMimeType,
                )
                if (prepared.plan != null && isCurrentPlaybackRequest(requestGeneration)) {
                    record032Plan(effectiveTrack, prepared.plan, playbackUrl)
                    current032Fallback = prepared.plan.fellBackFromFlac
                }
                if (if (prepared.plan != null) shouldSonosSeekAfterPush(prepared.plan.kind, initialPositionMillis)
                    else initialPositionMillis > 0L && !isLiveTranscodeDirectMp3) {
                    state.sonosController.seek(room, (initialPositionMillis / 1_000L).toInt())
                }
            }.onSuccess {
                if (isCurrentPlaybackRequest(requestGeneration)) {
                    startPlaybackReporting(
                        room,
                        tracks,
                        trackIndex,
                        initialPositionMillis,
                        autoAdvanceWhenStopped = true,
                    )
                    state.actionMessage = sonosPushMessage("已将 ${targetTrack.title} 推送到 ${room.roomName}",
                        is032Source(targetTrack) && current032Fallback)
                }
            }.onFailure { error ->
                if (error !is CancellationException && isCurrentPlaybackRequest(requestGeneration)) {
                    state.errorMessage = error.message ?: "未知错误"
                }
            }
            if (isCurrentPlaybackRequest(requestGeneration)) state.isPlaybackCommandLoading = false
        }
    }

    fun startAlbumPlayback(
        albumTrackResult: PlexAlbumTracksResult,
        startIndex: Int,
        initialPositionMillis: Long,
    ) {
        when (val target = state.selectedPlaybackTarget) {
            PlaybackTarget.LocalDevice -> {
                state.localPlaybackCoordinator.startLocalPlayback(
                    album = albumTrackResult.album,
                    tracks = albumTrackResult.tracks,
                    startIndex = startIndex,
                    initialPositionMillis = initialPositionMillis,
                )
            }
            is PlaybackTarget.Sonos -> startAlbumPlaybackOnSonos(
                albumTrackResult = albumTrackResult,
                room = target.room,
                startIndex = startIndex,
                initialPositionMillis = initialPositionMillis,
            )
        }
    }

    private fun startAlbumPlaybackOnSonos(
        albumTrackResult: PlexAlbumTracksResult,
        room: SonosRoom,
        startIndex: Int,
        initialPositionMillis: Long,
    ) {
        val initialTrack = albumTrackResult.tracks.getOrNull(startIndex)
        val resumesLiveTranscodeDirectMp3 = initialPositionMillis > 0L && initialTrack != null && !is032Source(initialTrack) && initialTrack.isConfiguredDirectMp3()
        transcodeRestartSeek = if (resumesLiveTranscodeDirectMp3) {
            TranscodeRestartSeek(
                sourceId = initialTrack.sourceId,
                ratingKey = initialTrack.ratingKey,
                offsetMillis = initialPositionMillis.coerceAtLeast(0L),
                generation = seekRequestGeneration.incrementAndGet(),
                pending = true,
                startedAtMillis = System.currentTimeMillis(),
            )
        } else {
            null
        }
        val requestGeneration = nextPlaybackRequestGeneration()
        stopLocalPlaybackForSonosHandover()
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        state.appPreferences.markAlbumPlayed(albumTrackResult.album.sourceId, albumTrackResult.album.ratingKey)
        state.recentPlayedAlbumKeys = state.appPreferences.loadRecentPlayedAlbumKeys(albumTrackResult.album.sourceId)
        state.selectPlaybackTarget(PlaybackTarget.Sonos(room))
        state.volumeSyncKey += 1
        syncSleepTimerRoom(room)
        state.miniPlayerState = MiniPlayerState(
            album = albumTrackResult.album,
            tracks = albumTrackResult.tracks,
            currentIndex = startIndex,
            target = PlaybackTarget.Sonos(room),
            isPaused = false,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = albumTrackResult.tracks.getOrNull(startIndex)?.durationMillis,
        )
        albumTrackResult.tracks.getOrNull(startIndex)?.let { initialTrack ->
            PlaybackForegroundService.start(
                context = state.context,
                title = initialTrack.title,
                subtitle = "正在推送到 ${room.roomName}",
                isPaused = false,
                artworkUrl = initialTrack.thumbUrl,
            )
        }
        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.albumPlaybackJob = state.scope.launch {
            runCatching<Unit> {
                var didStartFirstTrack = false
                var isPreparingFirstTrack = true
                playAlbumSequentially(
                    sonosController = state.sonosController,
                    room = room,
                    trackResult = albumTrackResult,
                    startIndex = startIndex,
                    initialPositionMillis = initialPositionMillis,
                    preparePlayback = { track ->
                        val prepared = prepareSonosTrack(track) { isCurrentPlaybackRequest(requestGeneration) }
                        if (prepared.plan != null) ensureCurrentPlaybackRequest(requestGeneration)
                        val effectiveTrack = prepared.track
                        val isOpus = effectiveTrack.sourceAudioCodec.equals("opus", ignoreCase = true)
                        val playbackOffsetMillis = if (isPreparingFirstTrack) {
                            initialPositionMillis.coerceAtLeast(0L)
                        } else {
                            0L
                        }
                        isPreparingFirstTrack = false
                        if (isOpus && isPlexSource(effectiveTrack) && state.opusTranscodeMode == OpusTranscodeMode.PROXY_FLAC) {
                            state.actionMessage = "正在准备 FLAC 缓存：${effectiveTrack.title}"
                        }
                        val isLiveTranscodeDirectMp3 = effectiveTrack.streamUrl.contains("/:/transcode/universal/start.mp3", ignoreCase = true)
                        val playbackUrl = if (prepared.plan != null) {
                            ensureCurrentPlaybackRequest(requestGeneration)
                            state.plexTranscodeProxy.cancelPendingDownload()
                            if (prepared.plan.kind == SonosPlanKind.LIVE_MP3)
                                Music032TranscodeUrls.withOffsetMs(prepared.plan.url, playbackOffsetMillis, effectiveTrack.durationMillis)
                            else prepared.plan.url
                        } else if (isLiveTranscodeDirectMp3) {
                            ensureCurrentPlaybackRequest(requestGeneration)
                            state.plexTranscodeProxy.cancelPendingDownload()
                            effectiveTrack.streamUrl.withPlexTranscodeOffsetSeconds(
                                (playbackOffsetMillis / 1_000L).toInt(),
                            )
                        } else {
                            state.plexTranscodeProxy.preparePlaybackUrl(effectiveTrack).also {
                                ensureCurrentPlaybackRequest(requestGeneration)
                            }
                        }
                        if (prepared.plan != null) {
                            ensureCurrentPlaybackRequest(requestGeneration)
                            pending032Plan = effectiveTrack to prepared.plan
                            pending032Offset = playbackOffsetMillis
                            pending032Fallback = prepared.plan.fellBackFromFlac
                        }
                        SonosPlayback(playbackUrl, if (prepared.plan != null) prepared.plan.mime else effectiveTrack.playbackMimeType,
                            prepared.plan?.let { it.kind == SonosPlanKind.LIVE_MP3 && playbackOffsetMillis > 0L }
                                ?: playbackUrl.contains("/:/transcode/universal/start", ignoreCase = true))
                    },
                    onTrackPushed = { track, playback ->
                        if (isCurrentPlaybackRequest(requestGeneration)) {
                            val plan = pending032Plan?.takeIf { it.first.sourceId == track.sourceId && it.first.ratingKey == track.ratingKey }
                            if (plan != null) {
                                record032Plan(plan.first, plan.second, playback.url)
                                current032Fallback = pending032Fallback
                                set032RestartSeek(plan.first, if (plan.second.kind == SonosPlanKind.LIVE_MP3) pending032Offset else 0L)
                                pending032Plan = null
                            }
                        }
                    },
                    getTracks = { state.miniPlayerState?.tracks ?: albumTrackResult.tracks },
                    getNextIndex = { currentIndex ->
                        resolveNextPlaybackIndex(
                            state.miniPlayerState?.tracks?.size ?: albumTrackResult.tracks.size,
                            currentIndex,
                        )
                    },
                    onTrackChanged = { index, track, startPositionMillis ->
                        transcodeRestartSeek = transcodeRestartSeek?.takeIf {
                            it.sourceId == track.sourceId && it.ratingKey == track.ratingKey
                        }
                        activeSonosPlan = activeSonosPlan?.takeIf {
                            it.sourceId == track.sourceId && it.ratingKey == track.ratingKey
                        }
                        if (!didStartFirstTrack) {
                            didStartFirstTrack = true
                            state.isPlaybackCommandLoading = false
                            startPlaybackReporting(
                                room,
                                albumTrackResult.tracks,
                                index,
                                startPositionMillis,
                                autoAdvanceWhenStopped = false,
                            )
                        }
                        state.miniPlayerState = state.miniPlayerState?.copy(
                            currentIndex = index,
                            isPaused = false,
                            currentPositionMillis = startPositionMillis,
                            durationMillis = track.durationMillis,
                            target = PlaybackTarget.Sonos(room),
                        )
                        state.actionMessage = sonosPushMessage("正在连续播放：${track.title}",
                            is032Source(track) && current032Fallback)
                        val queue = state.miniPlayerState?.tracks ?: albumTrackResult.tracks
                        val candidate = next032WarmupCandidate(queue, index,
                            state.playbackMode == PlaybackMode.Sequential, state.opusTranscodeMode,
                            state.sonosHiResTranscodeMode) { next ->
                            (runCatching { state.clientFor(next.sourceId) }.getOrNull() as? Music032Client)?.capabilities()
                        }?.takeIf(::is032Source)
                        if (candidate != null) launch {
                            try {
                                val nextPlan = plan032(candidate, state.opusTranscodeMode,
                                    state.sonosHiResTranscodeMode,
                                    (runCatching { state.clientFor(candidate.sourceId) }.getOrNull() as? Music032Client)?.capabilities()).getOrNull()
                                if (nextPlan?.needsWarmup == true) Music032Warmup.prepare(nextPlan.url)
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { /* Best-effort prewarm. */ }
                        }
                    },
                )
            }.onSuccess {
                if (isCurrentPlaybackRequest(requestGeneration)) {
                    state.playbackReportingJob?.cancel()
                    resetFinishedSonosQueue(room)
                    state.actionMessage = "专辑播放结束：${albumTrackResult.album.title}"
                }
            }.onFailure { error ->
                if (error is CancellationException && isCurrentPlaybackRequest(requestGeneration) &&
                    state.miniPlayerState?.tracks?.getOrNull(state.miniPlayerState?.currentIndex ?: -1)?.let(::is032Source) == true) {
                    state.isPlaybackCommandLoading = false
                }
                if (error !is CancellationException && isCurrentPlaybackRequest(requestGeneration)) {
                    state.errorMessage = error.message ?: "未知错误"
                    state.isPlaybackCommandLoading = false
                }
            }
        }
    }

    fun refreshSonosRooms(announce: Boolean = true) {
        state.isSonosLoading = true
        if (announce) {
            state.errorMessage = null
            state.actionMessage = null
        }
        state.sonosDiscoveryAttempted = true
        state.scope.launch {
            runCatching {
                SonosDiscovery(state.context).discoverRooms()
            }.onSuccess { rooms ->
                state.sonosRooms = rooms
                // Refresh the selected room from the fresh discovery (IPs may have changed);
                // never auto-select a speaker — the phone is the default output.
                (state.selectedPlaybackTarget as? PlaybackTarget.Sonos)?.room?.let { current ->
                    rooms.firstOrNull { room -> room.coordinatorUuid == current.coordinatorUuid }
                        ?.let { state.selectPlaybackTarget(PlaybackTarget.Sonos(it)) }
                }
                if (announce) {
                    state.actionMessage = if (rooms.isEmpty()) {
                        "当前未发现 Sonos 房间"
                    } else {
                        "已同步 ${rooms.size} 个 Sonos 房间"
                    }
                }
                state.volumeSyncKey += 1
            }.onFailure {
                if (announce) {
                    state.errorMessage = it.message ?: "未知错误"
                }
            }
            state.isSonosLoading = false
        }
    }

    suspend fun loadVolume(room: SonosRoom) {
        state.isVolumeLoading = true
        state.hasLoadedSonosVolume = false
        state.errorMessage = null
        runCatching {
            state.sonosController.getVolume(room)
        }.onSuccess {
            state.sonosVolume = it.toFloat()
            state.hasLoadedSonosVolume = true
        }.onFailure {
            state.errorMessage = it.message ?: "未知错误"
        }
        state.isVolumeLoading = false
    }

    fun applyVolumeChange(room: SonosRoom, newValue: Float) {
        state.sonosVolume = newValue
        state.hasLoadedSonosVolume = true
        state.errorMessage = null
        state.volumeChangeJob?.cancel()
        state.volumeChangeJob = state.scope.launch {
            delay(180)
            val targetVolume = state.sonosVolume.toInt().coerceIn(0, 100)
            state.isVolumeChanging = true
            runCatching {
                state.sonosController.setVolume(room, targetVolume)
            }.onSuccess {
                state.actionMessage = "已将 ${room.roomName} 音量设置为 $targetVolume"
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
                state.volumeSyncKey += 1
            }
            state.isVolumeChanging = false
        }
    }

    fun startPlaylistPlayback(
        playlistResult: PlexPlaylistTracksResult,
        shuffle: Boolean,
    ) {
        if (playlistResult.tracks.isEmpty()) return
        val requestedTracks = if (shuffle) playlistResult.tracks.shuffled() else playlistResult.tracks
        if (state.selectedPlaybackTarget == PlaybackTarget.LocalDevice && !state.networkQualityMonitor.currentIsConnected()) {
            state.scope.launch {
                val localTracks = withContext(Dispatchers.IO) {
                    requestedTracks.filter { track ->
                        state.albumLocalStore.isTrackAvailableLocally(track.sourceId, track.ratingKey) ||
                            LocalAudioCache.isFullyCached(originalCacheKey(track.sourceId, track.ratingKey, track.partKey))
                    }
                }
                when {
                    localTracks.isEmpty() -> state.errorMessage = "当前离线且歌单曲目均未在本地缓存"
                    localTracks.size < requestedTracks.size -> {
                        state.actionMessage = "离线模式：已为你播放本地可用的 ${localTracks.size}/${requestedTracks.size} 首歌曲"
                        startLocalPlaylistTracks(playlistResult, localTracks)
                    }
                    else -> startLocalPlaylistTracks(playlistResult, requestedTracks)
                }
            }
            return
        }
        val tracks = requestedTracks
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
            sourceId = playlistResult.playlist.sourceId.ifBlank { tracks.firstOrNull()?.sourceId.orEmpty() },
        )
        when (val target = state.selectedPlaybackTarget) {
            PlaybackTarget.LocalDevice -> {
                val initialAlbum = tracks.firstOrNull()?.let { resolveAlbumForTrack(it, fakeAlbum) } ?: fakeAlbum
                state.localPlaybackCoordinator.startLocalPlayback(
                    album = initialAlbum,
                    tracks = tracks,
                    startIndex = 0,
                    initialPositionMillis = 0L,
                )
            }
            is PlaybackTarget.Sonos -> startPlaylistPlaybackOnSonos(
                playlistResult = playlistResult,
                room = target.room,
                shuffle = shuffle,
            )
        }
    }

    private fun startLocalPlaylistTracks(
        playlistResult: PlexPlaylistTracksResult,
        tracks: List<PlexTrackStream>,
    ) {
        if (tracks.isEmpty() || state.selectedPlaybackTarget != PlaybackTarget.LocalDevice) return
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
            sourceId = playlistResult.playlist.sourceId.ifBlank { tracks.firstOrNull()?.sourceId.orEmpty() },
        )
        val initialAlbum = tracks.firstOrNull()?.let { resolveAlbumForTrack(it, fakeAlbum) } ?: fakeAlbum
        state.localPlaybackCoordinator.startLocalPlayback(
            album = initialAlbum,
            tracks = tracks,
            startIndex = 0,
            initialPositionMillis = 0L,
        )
    }

    private fun startPlaylistPlaybackOnSonos(
        playlistResult: PlexPlaylistTracksResult,
        room: SonosRoom,
        shuffle: Boolean,
    ) {
        if (playlistResult.tracks.isEmpty()) return
        transcodeRestartSeek = null
        val requestGeneration = nextPlaybackRequestGeneration()
        stopLocalPlaybackForSonosHandover()
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        state.selectPlaybackTarget(PlaybackTarget.Sonos(room))
        state.volumeSyncKey += 1
        syncSleepTimerRoom(room)
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
            sourceId = playlistResult.playlist.sourceId.ifBlank { tracks.firstOrNull()?.sourceId.orEmpty() },
        )
        val initialAlbum = tracks.firstOrNull()?.let { resolveAlbumForTrack(it, fakeAlbum) } ?: fakeAlbum
        state.miniPlayerState = MiniPlayerState(
            album = initialAlbum,
            tracks = tracks,
            currentIndex = 0,
            target = PlaybackTarget.Sonos(room),
            isPaused = false,
        )
        tracks.firstOrNull()?.let { initialTrack ->
            PlaybackForegroundService.start(
                context = state.context,
                title = initialTrack.title,
                subtitle = "正在推送到 ${room.roomName}",
                isPaused = false,
                artworkUrl = initialTrack.thumbUrl,
            )
        }
        state.isPlaybackCommandLoading = true
        state.albumPlaybackJob = state.scope.launch {
            runCatching {
                var didStartFirstTrack = false
                playAlbumSequentially(
                    sonosController = state.sonosController,
                    room = room,
                    trackResult = PlexAlbumTracksResult("", fakeAlbum, tracks),
                    startIndex = 0,
                    initialPositionMillis = 0L,
                    preparePlayback = { track ->
                        val prepared = prepareSonosTrack(track) { isCurrentPlaybackRequest(requestGeneration) }
                        if (prepared.plan != null) ensureCurrentPlaybackRequest(requestGeneration)
                        val effectiveTrack = prepared.track
                        val isOpus = effectiveTrack.sourceAudioCodec.equals("opus", ignoreCase = true)
                        if (isOpus && isPlexSource(effectiveTrack) && state.opusTranscodeMode == OpusTranscodeMode.PROXY_FLAC) {
                            state.actionMessage = "正在准备 FLAC 缓存：${effectiveTrack.title}"
                        }
                        val isLiveTranscodeDirectMp3 = effectiveTrack.streamUrl.contains("/:/transcode/universal/start.mp3", ignoreCase = true)
                        val playbackUrl = if (prepared.plan != null) {
                            ensureCurrentPlaybackRequest(requestGeneration)
                            state.plexTranscodeProxy.cancelPendingDownload()
                            if (prepared.plan.kind == SonosPlanKind.LIVE_MP3)
                                Music032TranscodeUrls.withOffsetMs(prepared.plan.url, 0L, effectiveTrack.durationMillis)
                            else prepared.plan.url
                        } else if (isLiveTranscodeDirectMp3) {
                            ensureCurrentPlaybackRequest(requestGeneration)
                            state.plexTranscodeProxy.cancelPendingDownload()
                            effectiveTrack.streamUrl
                        } else {
                            state.plexTranscodeProxy.preparePlaybackUrl(effectiveTrack).also {
                                ensureCurrentPlaybackRequest(requestGeneration)
                            }
                        }
                        if (prepared.plan != null) {
                            ensureCurrentPlaybackRequest(requestGeneration)
                            pending032Plan = effectiveTrack to prepared.plan
                            pending032Offset = 0L
                            pending032Fallback = prepared.plan.fellBackFromFlac
                        }
                        SonosPlayback(playbackUrl, if (prepared.plan != null) prepared.plan.mime else effectiveTrack.playbackMimeType,
                            if (prepared.plan != null) false
                            else playbackUrl.contains("/:/transcode/universal/start", ignoreCase = true))
                    },
                    onTrackPushed = { track, playback ->
                        if (isCurrentPlaybackRequest(requestGeneration)) {
                            val plan = pending032Plan?.takeIf { it.first.sourceId == track.sourceId && it.first.ratingKey == track.ratingKey }
                            if (plan != null) {
                                record032Plan(plan.first, plan.second, playback.url)
                                current032Fallback = pending032Fallback
                                set032RestartSeek(plan.first, if (plan.second.kind == SonosPlanKind.LIVE_MP3) pending032Offset else 0L)
                                pending032Plan = null
                            }
                        }
                    },
                    getTracks = { state.miniPlayerState?.tracks ?: tracks },
                    getNextIndex = { currentIndex ->
                        val queueSize = state.miniPlayerState?.tracks?.size ?: tracks.size
                        when (state.playbackMode) {
                            PlaybackMode.Sequential -> if (currentIndex < queueSize - 1) currentIndex + 1 else null
                            PlaybackMode.RepeatAll -> (currentIndex + 1) % queueSize
                            PlaybackMode.RepeatOne -> currentIndex
                            PlaybackMode.Shuffle -> Random.nextInt(queueSize)
                        }
                    },
                    onTrackChanged = { index, track, positionMillis ->
                        transcodeRestartSeek = null
                        activeSonosPlan = activeSonosPlan?.takeIf {
                            it.sourceId == track.sourceId && it.ratingKey == track.ratingKey
                        }
                        if (!didStartFirstTrack) {
                            didStartFirstTrack = true
                            state.isPlaybackCommandLoading = false
                            startPlaybackReporting(
                                room,
                                tracks,
                                index,
                                positionMillis,
                                autoAdvanceWhenStopped = false,
                            )
                        }
                        state.miniPlayerState = state.miniPlayerState?.copy(
                            album = resolveAlbumForTrack(track, fakeAlbum),
                            currentIndex = index,
                            currentPositionMillis = positionMillis,
                            durationMillis = track.durationMillis,
                        )
                        playlistTrackMessage(track.title, is032Source(track), current032Fallback)?.let {
                            state.actionMessage = it
                        }
                        val candidate = next032WarmupCandidate(state.miniPlayerState?.tracks ?: tracks, index,
                            state.playbackMode == PlaybackMode.Sequential, state.opusTranscodeMode,
                            state.sonosHiResTranscodeMode) { next ->
                            (runCatching { state.clientFor(next.sourceId) }.getOrNull() as? Music032Client)?.capabilities()
                        }?.takeIf(::is032Source)
                        if (candidate != null) launch {
                            try {
                                val nextPlan = plan032(candidate, state.opusTranscodeMode,
                                    state.sonosHiResTranscodeMode,
                                    (runCatching { state.clientFor(candidate.sourceId) }.getOrNull() as? Music032Client)?.capabilities()).getOrNull()
                                if (nextPlan?.needsWarmup == true) Music032Warmup.prepare(nextPlan.url)
                            } catch (cancel: CancellationException) { throw cancel }
                            catch (_: Exception) { /* Best-effort prewarm. */ }
                        }
                    },
                )
            }.onSuccess {
                if (isCurrentPlaybackRequest(requestGeneration)) {
                    state.playbackReportingJob?.cancel()
                    resetFinishedSonosQueue(room)
                    state.actionMessage = "播放列表播放结束：${playlistResult.playlist.title}"
                }
            }.onFailure { error ->
                if (error is CancellationException && isCurrentPlaybackRequest(requestGeneration) &&
                    state.miniPlayerState?.tracks?.getOrNull(state.miniPlayerState?.currentIndex ?: -1)?.let(::is032Source) == true) {
                    state.isPlaybackCommandLoading = false
                }
                if (error !is CancellationException && isCurrentPlaybackRequest(requestGeneration)) {
                    state.errorMessage = error.message ?: "播放失败"
                    state.isPlaybackCommandLoading = false
                }
            }
        }
    }

    fun togglePause(playerState: MiniPlayerState) {
        if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.toggleLocalPause(playerState)
            return
        }
        val commandGeneration = playbackToggleGeneration.incrementAndGet()
        state.isPlaybackCommandLoading = true
        state.scope.launch {
            var restartedPlayback = false
            playbackToggleMutex.withLock {
                if (commandGeneration < playbackToggleGeneration.get()) return@withLock

                val latest = state.miniPlayerState ?: playerState
                val room = latest.sonosRoom ?: return@withLock
                val initialStatus = runCatching {
                    state.sonosController.getPlaybackStatus(room)
                }.getOrNull()
                val transportState = initialStatus?.transportState.orEmpty()
                val isActuallyPlaying = transportState.equals("PLAYING", ignoreCase = true)
                val isActuallyStopped = transportState.equals("STOPPED", ignoreCase = true) ||
                    transportState.equals("NO_MEDIA_PRESENT", ignoreCase = true)
                val shouldResume = if (transportState.isBlank()) latest.isPaused else !isActuallyPlaying
                val currentTrack = latest.tracks.getOrNull(latest.currentIndex)
                val resumesLiveTranscodeDirectMp3 = shouldResume && currentTrack != null &&
                    (activePlanFor(currentTrack)?.let { it.kind == SonosPlanKind.LIVE_MP3 }
                        ?: currentTrack.isConfiguredDirectMp3())
                Log.i(
                    LOG_TAG,
                    "toggle transport=$transportState shouldResume=$shouldResume " +
                        "positionMs=${latest.currentPositionMillis}",
                )

                // Pausing a Plex live MP3 response can close its HTTP stream even while
                // Sonos still briefly reports PAUSED_PLAYBACK. A plain Play is therefore
                // inherently racy: always create a fresh offset stream for this format.
                if (resumesLiveTranscodeDirectMp3 || (shouldResume && isActuallyStopped)) {
                    val active032 = currentTrack?.let(::activePlanFor)
                    if (active032?.kind == SonosPlanKind.LIVE_MP3) {
                        val position = Music032TranscodeUrls.clampedOffsetMs(latest.currentPositionMillis,
                            latest.durationMillis ?: currentTrack.durationMillis)
                        val url = Music032TranscodeUrls.withOffsetMs(active032.url, position,
                            latest.durationMillis ?: currentTrack.durationMillis)
                        val seekGeneration = seekRequestGeneration.incrementAndGet()
                        transcodeRestartSeek = TranscodeRestartSeek(currentTrack.sourceId, currentTrack.ratingKey,
                            position, seekGeneration, true, System.currentTimeMillis())
                        if (commandGeneration != playbackToggleGeneration.get() || activePlanFor(currentTrack) != active032) {
                            if (transcodeRestartSeek?.generation == seekGeneration) transcodeRestartSeek = null
                            return@withLock
                        }
                        val outcome = runCatching {
                            state.plexTranscodeProxy.cancelPendingDownload()
                            state.sonosController.playTrack(room, url, currentTrack.title,
                                currentTrack.albumTitle ?: latest.album.title, active032.mime)
                        }
                        if (commandGeneration == playbackToggleGeneration.get() && activePlanFor(currentTrack) == active032) {
                            outcome.onSuccess {
                                activeSonosPlan = active032.copy(url = url)
                                state.miniPlayerState = state.miniPlayerState?.copy(isPaused = false)
                                state.actionMessage = "已继续播放"
                            }.onFailure {
                                transcodeRestartSeek = null
                                state.errorMessage = it.message ?: "未知错误"
                            }
                            state.isPlaybackCommandLoading = false
                        }
                        return@withLock
                    }
                    restartedPlayback = true
                    Log.i(LOG_TAG, "restart transport at ${latest.currentPositionMillis}ms")
                    playQueueIndex(latest.currentIndex, latest.currentPositionMillis, PlaybackTarget.Sonos(room))
                    return@withLock
                }

                val commandResult = runCatching {
                    if (shouldResume) state.sonosController.resume(room)
                    else state.sonosController.pause(room)
                }

                if (commandGeneration == playbackToggleGeneration.get()) {
                    commandResult.onSuccess {
                        state.miniPlayerState = state.miniPlayerState?.copy(isPaused = !shouldResume)
                        state.actionMessage = if (shouldResume) "已继续播放" else "已暂停播放"
                    }.onFailure { error ->
                        val actualStatus = runCatching {
                            state.sonosController.getPlaybackStatus(room)
                        }.getOrNull()
                        val actualState = actualStatus?.transportState.orEmpty()
                        val reachedTarget = if (shouldResume) {
                            actualState.equals("PLAYING", ignoreCase = true)
                        } else {
                            actualState.equals("PAUSED_PLAYBACK", ignoreCase = true) ||
                                actualState.equals("STOPPED", ignoreCase = true)
                        }
                        if (reachedTarget) {
                            state.miniPlayerState = state.miniPlayerState?.copy(isPaused = !shouldResume)
                        } else if (shouldResume && actualState.equals("STOPPED", ignoreCase = true)) {
                            restartedPlayback = true
                            playQueueIndex(latest.currentIndex, latest.currentPositionMillis, PlaybackTarget.Sonos(room))
                        } else {
                            state.errorMessage = error.message ?: "未知错误"
                        }
                    }
                }
            }
            if (!restartedPlayback && commandGeneration == playbackToggleGeneration.get()) {
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun updatePlaybackMode(mode: PlaybackMode) {
        state.playbackMode = mode
        if (state.miniPlayerState?.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.applyPlaybackMode(mode)
        }
        state.actionMessage = "播放模式已切换为：${mode.label}"
    }

    /**
     * Previous-button entry point. Local playback restarts the current track first and
     * only steps back on a quick second press (standard music-app behavior, mirrored by
     * the media notification). Sonos keeps a plain skip — the speaker owns its timeline.
     */
    fun playPrevious() {
        val playerState = state.miniPlayerState ?: return
        if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.playLocalPrevious(playerState)
            return
        }
        val newIndex = (playerState.currentIndex - 1).coerceAtLeast(0)
        if (newIndex != playerState.currentIndex) {
            playQueueIndex(newIndex, 0L, playerState.target)
        }
    }

    fun playNext() {
        val playerState = state.miniPlayerState ?: return
        if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.playLocalNext()
            return
        }
        val newIndex = (playerState.currentIndex + 1).coerceAtMost(playerState.tracks.lastIndex)
        if (newIndex != playerState.currentIndex) playQueueIndex(newIndex, 0L, playerState.target)
    }

    fun playQueueIndex(index: Int, positionMillis: Long, target: PlaybackTarget?) {
        val playerState = state.miniPlayerState ?: return
        val targetIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        when (val resolvedTarget = target ?: playerState.target) {
            PlaybackTarget.LocalDevice -> {
                activeSonosPlan = null
                pending032Plan = null
                if (playerState.target == PlaybackTarget.LocalDevice) {
                    // Same renderer: seek inside the live ExoPlayer queue.
                    state.localPlaybackCoordinator.playLocalIndex(targetIndex, positionMillis)
                } else {
                    // Renderer switch (Sonos -> this device): stop the speaker and rebuild
                    // the local queue at the same position.
                    playerState.sonosRoom?.let { room ->
                        state.scope.launch { runCatching { state.sonosController.stop(room) } }
                    }
                    state.localPlaybackCoordinator.startLocalPlayback(
                        album = playerState.album,
                        tracks = playerState.tracks,
                        startIndex = targetIndex,
                        initialPositionMillis = positionMillis,
                    )
                }
            }
            is PlaybackTarget.Sonos -> {
                if (playerState.target == PlaybackTarget.LocalDevice) {
                    state.localPlaybackCoordinator.stopLocalPlayback()
                }
                playSonosQueueIndex(playerState, targetIndex, positionMillis, resolvedTarget.room)
            }
        }
    }

    private fun playSonosQueueIndex(
        playerState: MiniPlayerState,
        targetIndex: Int,
        positionMillis: Long,
        targetRoom: SonosRoom,
    ) {
        val currentTrackResult = state.trackResult?.takeIf { result ->
            result.album.ratingKey == playerState.album.ratingKey &&
                result.album.sourceId == playerState.album.sourceId &&
                result.tracks.map { it.sourceId to it.ratingKey } ==
                playerState.tracks.map { it.sourceId to it.ratingKey }
        }
        if (currentTrackResult != null) {
            startAlbumPlaybackOnSonos(currentTrackResult, targetRoom, targetIndex, positionMillis)
        } else {
            startSingleTrackPlaybackOnSonos(
                album = resolveAlbumForTrack(playerState.tracks[targetIndex], playerState.album),
                tracks = playerState.tracks,
                trackIndex = targetIndex,
                room = targetRoom,
                initialPositionMillis = positionMillis,
            )
        }
    }

    /** Moves the current playback (or just the selection, when idle) to another output. */
    fun switchPlaybackTarget(target: PlaybackTarget) {
        if (target == PlaybackTarget.LocalDevice) {
            activeSonosPlan = null
            pending032Plan = null
        }
        val playerState = state.miniPlayerState ?: run {
            state.selectPlaybackTarget(target)
            state.actionMessage = when (target) {
                PlaybackTarget.LocalDevice -> "已切换为 ${Strings.localPlayback}"
                is PlaybackTarget.Sonos -> "已选择 Sonos 房间: ${target.room.roomName}"
            }
            state.volumeSyncKey += 1
            return
        }
        val sameTarget = when (target) {
            PlaybackTarget.LocalDevice -> playerState.target == PlaybackTarget.LocalDevice
            is PlaybackTarget.Sonos ->
                playerState.sonosRoom?.coordinatorUuid == target.room.coordinatorUuid
        }
        if (sameTarget) {
            state.selectPlaybackTarget(target)
            state.volumeSyncKey += 1
            return
        }
        playQueueIndex(playerState.currentIndex, playerState.currentPositionMillis, target)
        state.actionMessage = when (target) {
            PlaybackTarget.LocalDevice -> "正在切换到 ${Strings.localPlayback}"
            is PlaybackTarget.Sonos -> "正在切换到 ${target.room.roomName}"
        }
    }

    fun seekPlayback(playerState: MiniPlayerState, positionMillis: Long) {
        if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.seekLocal(playerState, positionMillis)
            return
        }
        val room = playerState.sonosRoom ?: return
        val durationMillis = playerState.durationMillis?.takeIf { it > 0L } ?: return
        val targetTrack = playerState.tracks.getOrNull(playerState.currentIndex) ?: return
        val active032 = activePlanFor(targetTrack)
        val targetMillis = if (active032?.kind == SonosPlanKind.LIVE_MP3 ||
            (active032 == null && is032Source(targetTrack) && targetTrack.isConfiguredDirectMp3()))
            Music032TranscodeUrls.clampedOffsetMs(positionMillis, durationMillis)
        else positionMillis.coerceIn(0L, durationMillis)
        val isLiveTranscodeDirectMp3 = if (active032 != null) active032.kind == SonosPlanKind.LIVE_MP3 else targetTrack.isConfiguredDirectMp3()
        val seekGeneration = seekRequestGeneration.incrementAndGet()
        if (isLiveTranscodeDirectMp3) {
            transcodeRestartSeek = TranscodeRestartSeek(
                sourceId = targetTrack.sourceId,
                ratingKey = targetTrack.ratingKey,
                offsetMillis = targetMillis,
                generation = seekGeneration,
                pending = true,
                startedAtMillis = System.currentTimeMillis(),
            )
        }
        state.miniPlayerState = state.miniPlayerState?.takeIf { current ->
            current.sonosRoom?.coordinatorUuid == room.coordinatorUuid
        }?.copy(currentPositionMillis = targetMillis)

        state.isPlaybackCommandLoading = true
        state.scope.launch {
            runCatching {
                if (isLiveTranscodeDirectMp3) {
                    val prepared = if (active032 == null) prepareSonosTrack(targetTrack) {
                        seekRequestGeneration.get() == seekGeneration &&
                            state.miniPlayerState?.sonosRoom?.coordinatorUuid == room.coordinatorUuid &&
                            state.miniPlayerState?.tracks?.getOrNull(state.miniPlayerState?.currentIndex ?: -1)?.let {
                                it.sourceId == targetTrack.sourceId && it.ratingKey == targetTrack.ratingKey
                            } == true
                    } else null
                    val effectiveTrack = prepared?.track ?: targetTrack
                    val offsetSeconds = (targetMillis / 1_000L).toInt()
                    val actual032Kind = prepared?.plan?.kind
                    if (active032 == null && prepared?.plan != null &&
                        !shouldRestart032Seek(isLiveTranscodeDirectMp3, actual032Kind ?: SonosPlanKind.ORIGINAL)) {
                        ensureCurrent032Seek(targetTrack, room, seekGeneration)
                        transcodeRestartSeek = null
                        state.sonosController.seek(room, (targetMillis / 1_000L).toInt())
                        return@runCatching
                    }
                    val seekUrl = when {
                        active032 != null -> Music032TranscodeUrls.withOffsetMs(active032.url, targetMillis, durationMillis)
                        prepared?.plan?.kind == SonosPlanKind.LIVE_MP3 ->
                            Music032TranscodeUrls.withOffsetMs(prepared.plan.url, targetMillis, durationMillis)
                        else -> effectiveTrack.streamUrl.withPlexTranscodeOffsetSeconds(offsetSeconds)
                    }
                    Log.i(LOG_TAG, "opus seek restart targetMs=$targetMillis generation=$seekGeneration")
                    if (active032 != null || prepared?.plan != null) ensureCurrent032Seek(targetTrack, room, seekGeneration)
                    state.plexTranscodeProxy.cancelPendingDownload()
                    state.sonosController.playTrack(
                        room = room,
                        trackUrl = seekUrl,
                        title = effectiveTrack.title,
                        albumTitle = effectiveTrack.albumTitle ?: playerState.album.title,
                        mimeType = if (active032 != null) active032.mime
                            else if (prepared?.plan != null) prepared.plan.mime else effectiveTrack.playbackMimeType,
                    )
                    if (seekRequestGeneration.get() == seekGeneration) {
                        if (active032 != null && activePlanFor(targetTrack) == active032) activeSonosPlan = active032.copy(url = seekUrl)
                        else if (prepared?.plan != null && prepared.plan.kind == SonosPlanKind.LIVE_MP3 &&
                            state.miniPlayerState?.tracks?.getOrNull(state.miniPlayerState?.currentIndex ?: -1)?.let {
                                it.sourceId == targetTrack.sourceId && it.ratingKey == targetTrack.ratingKey
                            } == true) record032Plan(effectiveTrack, prepared.plan, seekUrl)
                    }
                    if (playerState.isPaused) state.sonosController.pause(room)
                } else {
                    transcodeRestartSeek = null
                    Log.i(LOG_TAG, "sonos seek targetMs=$targetMillis generation=$seekGeneration")
                    state.sonosController.seek(room, (targetMillis / 1_000L).toInt())
                }
            }.onSuccess {
                if (seekRequestGeneration.get() == seekGeneration) {
                    state.actionMessage = "已跳转到 ${formatDuration(targetMillis)}"
                }
            }.onFailure { error ->
                if (error !is CancellationException && seekRequestGeneration.get() == seekGeneration) {
                    transcodeRestartSeek = null
                    Log.w(LOG_TAG, "seek failed targetMs=$targetMillis", error)
                    state.errorMessage = error.message ?: "未知错误"
                }
            }
            if (seekRequestGeneration.get() == seekGeneration) {
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun enqueueTracks(
        tracks: List<PlexTrackStream>,
        placement: QueuePlacement,
        displayTitle: String,
    ) {
        if (tracks.isEmpty()) return
        val playerState = state.miniPlayerState
        if (playerState == null) {
            state.actionMessage = null
            state.errorMessage = Strings.startPlaybackBeforeQueueing
            return
        }

        val insertIndex = when (placement) {
            QueuePlacement.Next -> (playerState.currentIndex + 1).coerceAtMost(playerState.tracks.size)
            QueuePlacement.End -> playerState.tracks.size
        }
        val updatedTracks = playerState.tracks.toMutableList().apply {
            addAll(insertIndex, tracks)
        }
        state.miniPlayerState = playerState.copy(tracks = updatedTracks)
        if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.insertIntoLocalQueue(insertIndex, tracks)
        }
        state.errorMessage = null
        state.actionMessage = when (placement) {
            QueuePlacement.Next -> Strings.queueAddedNext(displayTitle)
            QueuePlacement.End -> Strings.queueAddedEnd(displayTitle)
        }
    }

    fun playAlbum(album: PlexAlbum, startIndex: Int = 0) {
        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.scope.launch {
            try {
                val cachedTracks = withContext(Dispatchers.IO) {
                    state.albumLocalStore.getAlbumTracks(album.sourceId, album.ratingKey)
                }
                if (cachedTracks.isNotEmpty()) {
                    val result = PlexAlbumTracksResult("", album, cachedTracks)
                    state.selectedAlbum = album
                    state.trackResult = result
                    startAlbumPlayback(result, startIndex, 0L)
                    state.isPlaybackCommandLoading = false
                    // Background silent refresh
                    runCatching {
                        state.clientFor(album.sourceId).fetchAlbumTrackStreams(album)
                    }.onSuccess { fresh ->
                        withContext(Dispatchers.IO) {
                            state.albumLocalStore.saveAlbumTracks(album.sourceId, album.ratingKey, fresh.tracks)
                        }
                    }
                    return@launch
                }

                val freshResult = runCatching {
                    state.clientFor(album.sourceId).fetchAlbumTrackStreams(album)
                }.getOrThrow()

                if (freshResult.tracks.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        state.albumLocalStore.saveAlbumTracks(album.sourceId, album.ratingKey, freshResult.tracks)
                    }
                    state.selectedAlbum = album
                    state.trackResult = freshResult
                    startAlbumPlayback(freshResult, startIndex, 0L)
                } else {
                    state.errorMessage = "该专辑没有可播放的曲目"
                }
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    state.errorMessage = error.message ?: "播放专辑失败"
                }
            } finally {
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun enqueueAlbum(album: PlexAlbum, placement: QueuePlacement) {
        if (state.miniPlayerState == null) {
            state.actionMessage = null
            state.errorMessage = Strings.startPlaybackBeforeQueueing
            return
        }
        state.scope.launch {
            runCatching {
                state.clientFor(album.sourceId).fetchAlbumTrackStreams(album).tracks
            }.onSuccess { tracks ->
                enqueueTracks(tracks, placement, album.title)
            }.onFailure {
                state.errorMessage = it.message ?: "无法读取专辑曲目"
            }
        }
    }

    fun startSleepTimer(hours: Int, minutes: Int) {
        val safeHours = hours.coerceIn(0, 23)
        val safeMinutes = minutes.coerceIn(0, 59)
        val totalMinutes = (safeHours * 60) + safeMinutes
        if (totalMinutes <= 0) {
            state.errorMessage = Strings.sleepTimerInvalidDuration
            return
        }
        val durationMillis = totalMinutes * 60_000L
        // The timer only attaches a Sonos room when one is actually involved: during local
        // playback the stale selected room must not be paused when the timer elapses.
        val room = state.miniPlayerState?.sonosRoom
            ?: state.selectedSonosRoom.takeIf { state.miniPlayerState == null }
        SleepTimerController.start(
            scope = state.scope,
            durationMillis = durationMillis,
            room = room,
        )
        state.errorMessage = null
        state.actionMessage = "${Strings.sleepTimer} ${Strings.sleepTimerRemaining(formatSleepTimer(durationMillis))}"
    }

    fun cancelSleepTimer() {
        SleepTimerController.cancel()
        state.errorMessage = null
        state.actionMessage = "${Strings.sleepTimer} ${Strings.sleepTimerOff}"
    }

    private fun startPlaybackReporting(
        room: SonosRoom,
        tracks: List<PlexTrackStream>,
        initialTrackIndex: Int,
        initialPositionMillis: Long = 0L,
        autoAdvanceWhenStopped: Boolean,
    ) {
        val normalizedInitialIndex = initialTrackIndex.coerceIn(0, tracks.lastIndex)
        state.playbackReportingJob?.cancel()
        state.playbackReportingJob = state.scope.launch {
            val initialTrack = tracks.getOrNull(normalizedInitialIndex) ?: return@launch
            PlaybackForegroundService.onPlaybackAction = { action ->
                val playerState = state.miniPlayerState
                if (playerState != null && playerState.sonosRoom != null) {
                    when (action) {
                        PlaybackForegroundService.PlaybackAction.Previous -> {
                            if (playerState.currentIndex > 0) playQueueIndex(playerState.currentIndex - 1, 0L, playerState.target)
                        }
                        PlaybackForegroundService.PlaybackAction.TogglePause -> togglePause(playerState)
                        PlaybackForegroundService.PlaybackAction.Next -> {
                            if (playerState.currentIndex < playerState.tracks.lastIndex) playQueueIndex(playerState.currentIndex + 1, 0L, playerState.target)
                        }
                    }
                }
            }
            PlaybackForegroundService.start(
                context = state.context,
                title = initialTrack.title,
                subtitle = "正在推送到 ${room.roomName}",
                artworkUrl = initialTrack.thumbUrl,
            )
            try {
            val scrobbledTrackKeys = mutableSetOf<String>()
            var currentTrack = initialTrack
            var currentTrackTimeMillis = initialPositionMillis.coerceAtLeast(0L)
            var currentTrackDurationMillis = currentTrack.durationMillis
            var lastReportedState: String? = null
            var lastTimelineReportAt = 0L
            var stoppedPollCount = 0
            var hasObservedPlayback = initialPositionMillis > 0L

            reportTimelineSafely(
                track = currentTrack,
                stateName = "playing",
                timeMillis = currentTrackTimeMillis,
                durationMillis = currentTrackDurationMillis,
                continuing = false,
            )
            lastReportedState = "playing"
            lastTimelineReportAt = System.currentTimeMillis()

            while (true) {
                delay(1_000)

                val latestMiniPlayerState = state.miniPlayerState ?: break
                if (latestMiniPlayerState.sonosRoom?.coordinatorUuid != room.coordinatorUuid) break

                val currentQueue = latestMiniPlayerState.tracks
                val status = runCatching { state.sonosController.getPlaybackStatus(room) }.getOrNull() ?: continue
                val resolvedTrack = resolveActiveTrack(currentQueue, latestMiniPlayerState, status) ?: continue
                val playbackState = status.toPlexPlaybackState()
                val rawPositionMillis = ((status.relTimeSeconds ?: 0) * 1_000L).coerceAtLeast(0L)
                val restartSeek = transcodeRestartSeek?.takeIf {
                    it.sourceId == resolvedTrack.sourceId && it.ratingKey == resolvedTrack.ratingKey
                }
                if (restartSeek?.pending == true) {
                    val restartAgeMillis = System.currentTimeMillis() - restartSeek.startedAtMillis
                    if (playbackState == "playing") {
                        if (transcodeRestartSeek?.generation == restartSeek.generation) {
                            transcodeRestartSeek = restartSeek.copy(pending = false)
                        }
                    } else if (restartAgeMillis < TRANSCODE_RESTART_STATE_GRACE_MILLIS) {
                        // SetAVTransportURI briefly exposes STOPPED/the previous transport.
                        // Keep the optimistic button and absolute slider position until the
                        // replacement stream actually reports PLAYING.
                        continue
                    } else if (transcodeRestartSeek?.generation == restartSeek.generation) {
                        transcodeRestartSeek = restartSeek.copy(pending = false)
                    }
                }
                val positionMillis = (rawPositionMillis + (restartSeek?.offsetMillis ?: 0L))
                    .coerceAtLeast(0L)
                    .let { position -> resolvedTrack.durationMillis?.let(position::coerceAtMost) ?: position }
                if (restartSeek != null && !restartSeek.hasLoggedReadback) {
                    Log.i(
                        LOG_TAG,
                        "opus seek readback rawMs=$rawPositionMillis absoluteMs=$positionMillis " +
                            "offsetMs=${restartSeek.offsetMillis} generation=${restartSeek.generation}",
                    )
                    if (transcodeRestartSeek?.generation == restartSeek.generation) {
                        transcodeRestartSeek = restartSeek.copy(hasLoggedReadback = true)
                    }
                }
                val durationMillis = resolvedTrack.durationMillis ?: status.trackDurationSeconds?.times(1_000L)

                if (resolvedTrack.ratingKey != currentTrack.ratingKey || resolvedTrack.sourceId != currentTrack.sourceId) {
                    transcodeRestartSeek = null
                    activeSonosPlan = activeSonosPlan?.takeIf {
                        retainSonosPlanForTrack(it.sourceId, it.ratingKey, resolvedTrack)
                    }
                    reportStoppedTrackIfNeeded(
                        track = currentTrack,
                        timeMillis = currentTrackTimeMillis,
                        durationMillis = currentTrackDurationMillis,
                        continuing = true,
                    )
                    scrobbleIfNeeded(
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
                    hasObservedPlayback = false
                }

                // Sonos resets REL_TIME to zero when the transport reaches STOPPED. For an
                // Opus restart seek, adding the source offset to that zero would resurrect
                // the last seek position and overwrite the real end-of-track progress.
                val stoppedAtResetPosition = playbackState == "stopped" && rawPositionMillis == 0L
                if (!stoppedAtResetPosition) {
                    currentTrackTimeMillis = positionMillis
                }
                if (durationMillis != null) {
                    currentTrackDurationMillis = durationMillis
                }
                if (playbackState != "stopped" || !stoppedAtResetPosition) {
                    hasObservedPlayback = true
                }

                // STOPPED is not playing. Showing a pause icon in that state makes the next
                // tap send Pause again (UPnP 701) instead of recovering playback.
                val effectivePaused = playbackState == "paused" || playbackState == "stopped"
                state.miniPlayerState = latestMiniPlayerState.copy(
                    currentIndex = currentQueue.indexOfFirst { it.ratingKey == currentTrack.ratingKey && it.sourceId == currentTrack.sourceId }
                        .takeIf { it >= 0 } ?: latestMiniPlayerState.currentIndex,
                    isPaused = effectivePaused,
                    currentPositionMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                )

                PlaybackForegroundService.updateIfRunning(
                    title = currentTrack.title,
                    subtitle = currentTrack.artistName ?: room.roomName,
                    isPaused = effectivePaused,
                    artworkUrl = currentTrack.thumbUrl,
                )

                val now = System.currentTimeMillis()
                val shouldReportTimeline = playbackState != lastReportedState ||
                    now - lastTimelineReportAt >= 10_000L

                if (shouldReportTimeline) {
                    reportTimelineSafely(
                        track = currentTrack,
                        stateName = playbackState,
                        timeMillis = currentTrackTimeMillis,
                        durationMillis = currentTrackDurationMillis,
                        continuing = false,
                    )
                    lastReportedState = playbackState
                    lastTimelineReportAt = now
                }

                scrobbleIfNeeded(
                    scrobbledTrackKeys = scrobbledTrackKeys,
                    track = currentTrack,
                    timeMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                )

                if (playbackState == "stopped") {
                    stoppedPollCount += 1
                    val nearTrackEnd = currentTrackDurationMillis?.let { duration ->
                        currentTrackTimeMillis >= (duration - 3_000L).coerceAtLeast(0L)
                    } ?: false
                    val trackFinished = stoppedPollCount == 3 && hasObservedPlayback && nearTrackEnd
                    // Sonos never started playing (e.g. the stream URL was rejected):
                    // surface the failure instead of polling forever.
                    val playbackNeverStarted = stoppedPollCount == 10 && !hasObservedPlayback
                    if (trackFinished || playbackNeverStarted) {
                        if (playbackNeverStarted) {
                            state.errorMessage = "Sonos 未能开始播放：${currentTrack.title}"
                        }
                        reportStoppedTrackIfNeeded(
                            track = currentTrack,
                            timeMillis = currentTrackTimeMillis,
                            durationMillis = currentTrackDurationMillis,
                            continuing = false,
                        )
                        scrobbleIfNeeded(
                            scrobbledTrackKeys = scrobbledTrackKeys,
                            track = currentTrack,
                            timeMillis = currentTrackTimeMillis,
                            durationMillis = currentTrackDurationMillis,
                        )
                        if (!autoAdvanceWhenStopped) continue

                        // Standalone queues do not have a sequential playback owner.
                        val currentIndex = currentQueue.indexOfFirst { it.ratingKey == currentTrack.ratingKey && it.sourceId == currentTrack.sourceId }
                            .takeIf { it >= 0 } ?: break
                        val nextIndex = resolveNextPlaybackIndex(currentQueue.size, currentIndex)
                        if (nextIndex == null) {
                            resetFinishedSonosQueue(room)
                            break
                        }
                        if (nextIndex == currentIndex) break
                        currentQueue.getOrNull(nextIndex) ?: break
                        state.scope.launch {
                            playQueueIndex(nextIndex, 0L, PlaybackTarget.Sonos(room))
                        }
                        break
                    }
                } else {
                    stoppedPollCount = 0
                }
            }
            } finally {
                PlaybackForegroundService.onPlaybackAction = null
                PlaybackForegroundService.stop(state.context)
            }
        }
    }

    private fun resetFinishedSonosQueue(room: SonosRoom) {
        activeSonosPlan = null
        pending032Plan = null
        current032Fallback = false
        val current = state.miniPlayerState ?: return
        if (current.sonosRoom?.coordinatorUuid != room.coordinatorUuid) return

        transcodeRestartSeek = null
        state.isPlaybackCommandLoading = false
        state.miniPlayerState = current.copy(
            isPaused = true,
            isBuffering = false,
            currentPositionMillis = 0L,
        )
    }

    private fun nextPlaybackRequestGeneration(): Long {
        activeSonosPlan = null
        pending032Plan = null
        current032Fallback = false
        playbackToggleGeneration.incrementAndGet()
        return playbackRequestGeneration.incrementAndGet()
    }

    /**
     * Retires every in-flight Sonos playback request. Callers that take playback over from
     * another coordinator use this so the cancelled Sonos network calls cannot land on
     * [AppState.errorMessage] after the handover.
     */
    fun invalidateInFlightPlaybackRequests() {
        nextPlaybackRequestGeneration()
    }

    /**
     * Snapshot of the live playback request generation. Coordinators that hand work to a
     * network round trip capture this and re-check it before acting on the result.
     */
    val currentPlaybackRequestGeneration: Long
        get() = playbackRequestGeneration.get()

    /**
     * Stops the on-device engine before a Sonos push. Both engines render independently,
     * so leaving the local player running would double up the audio.
     */
    private fun stopLocalPlaybackForSonosHandover() {
        val isLocalActive = state.miniPlayerState?.target == PlaybackTarget.LocalDevice ||
            state.localPlaybackEngine.isPlayRequested
        if (!isLocalActive) return
        state.localPlaybackCoordinator.stopLocalPlayback()
    }

    private fun isCurrentPlaybackRequest(generation: Long): Boolean =
        playbackRequestGeneration.get() == generation

    private fun ensureCurrentPlaybackRequest(generation: Long) {
        if (!isCurrentPlaybackRequest(generation)) {
            throw CancellationException("播放请求已被新的操作取代。")
        }
    }

    private fun ensureCurrent032Seek(track: PlexTrackStream, room: SonosRoom, generation: Long) {
        if (seekRequestGeneration.get() != generation ||
            state.miniPlayerState?.sonosRoom?.coordinatorUuid != room.coordinatorUuid ||
            state.miniPlayerState?.tracks?.getOrNull(state.miniPlayerState?.currentIndex ?: -1)?.let {
                it.sourceId == track.sourceId && it.ratingKey == track.ratingKey
            } != true) throw CancellationException("播放请求已被新的操作取代。")
    }

    private fun isPlexSource(track: PlexTrackStream): Boolean = shouldApplyPlexSonosTranscode(
        runCatching { state.clientFor(track.sourceId) is PlexClient }.getOrNull(), track.streamUrl,
    )

    private fun PlexTrackStream.isConfiguredDirectMp3(): Boolean = if (is032Source(this)) {
        predictSonosKind(this, state.opusTranscodeMode, state.sonosHiResTranscodeMode,
            (runCatching { state.clientFor(sourceId) }.getOrNull() as? Music032Client)?.capabilities()) == SonosPlanKind.LIVE_MP3
    } else configuredPlexDirectMp3(this, isPlexSource(this), state.opusTranscodeMode, state.sonosHiResTranscodeMode.forPlex())

    private suspend fun hydrate032Track(track: PlexTrackStream): PlexTrackStream =
        hydrationCache.hydrate(track) { state.clientFor(track.sourceId).fetchTrackStream(track.ratingKey) }

    private suspend fun prepareSonosTrack(
        track: PlexTrackStream,
        isCurrent: () -> Boolean = { true },
    ): PreparedSonosTrack {
        val is032 = is032Source(track)
        val codec = track.sourceAudioCodec?.lowercase()
        val hydrated = if (is032) hydrate032Track(track) else if (codec in LOSSLESS_AUDIO_CODECS && track.sourceAudioSampleRate == null) {
            runCatching {
                state.clientFor(track.sourceId).fetchTrackStream(track.ratingKey)
            }.getOrNull()?.let { fresh ->
                track.copy(
                    streamUrl = fresh.streamUrl,
                    sourceAudioCodec = fresh.sourceAudioCodec ?: track.sourceAudioCodec,
                    sourceAudioBitrateKbps = fresh.sourceAudioBitrateKbps ?: track.sourceAudioBitrateKbps,
                    sourceAudioSampleRate = fresh.sourceAudioSampleRate,
                    playbackMimeType = fresh.playbackMimeType,
                )
            } ?: track
        } else {
            track
        }
        if (hydrated.sourceAudioSampleRate != track.sourceAudioSampleRate ||
            (is032 && hydrated.sourceAudioCodec != track.sourceAudioCodec)) {
            state.miniPlayerState = state.miniPlayerState?.let { player ->
                player.copy(
                    tracks = player.tracks.map { queued ->
                        if (queued.sourceId == track.sourceId && queued.ratingKey == track.ratingKey) hydrated else queued
                    },
                )
            }
        }
        if (is032) {
            val client = runCatching { state.clientFor(track.sourceId) }.getOrNull() as? Music032Client
            val caps = client?.let {
                val deferred = state.scope.async(Dispatchers.IO) { it.ensureCapabilities() }
                withTimeoutOrNull(3_000L) {
                    try { deferred.await() } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                }
            }
            var plan = plan032(hydrated, state.opusTranscodeMode,
                state.sonosHiResTranscodeMode, caps).getOrThrow()
            if (plan.needsWarmup) {
                if (Music032Warmup.prepare(plan.url, isCurrent = isCurrent,
                    onCacheMiss = { state.actionMessage = "${Strings.music032FlacPreparing}：${hydrated.title}" }) != WarmupResult.READY) {
                    Music032Warmup.ensureCurrent(isCurrent)
                    plan = fallback032Mp3(hydrated, caps,
                        SonosPlan(hydrated.streamUrl, Music032Json.original032MimeType(hydrated.sourceAudioCodec, null), SonosPlanKind.ORIGINAL))
                        .getOrElse { error(Strings.music032SonosFallbackUnavailable) }
                    if (plan.kind != SonosPlanKind.LIVE_MP3) error(Strings.music032SonosFallbackUnavailable)
                    Music032Warmup.ensureCurrent(isCurrent)
                    plan = plan.copy(fellBackFromFlac = true)
                    state.actionMessage = Strings.music032FlacFallback
                }
            }
            Music032Warmup.ensureCurrent(isCurrent)
            return PreparedSonosTrack(hydrated, plan)
        }
        return PreparedSonosTrack(preparePlexSonosTranscode(hydrated, isPlexSource(hydrated),
            state.opusTranscodeMode, state.sonosHiResTranscodeMode, state.plexClientIdentifier), null)
    }

    private data class TranscodeRestartSeek(
        val sourceId: String,
        val ratingKey: String,
        val offsetMillis: Long,
        val generation: Long,
        val pending: Boolean,
        val startedAtMillis: Long,
        val hasLoggedReadback: Boolean = false,
    )

    private companion object {
        const val LOG_TAG = "MBPlayback"
        const val TRANSCODE_RESTART_STATE_GRACE_MILLIS = 4_000L
    }

    private suspend fun reportTimelineSafely(
        track: PlexTrackStream,
        stateName: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        runCatching {
            state.clientFor(track.sourceId).reportTimeline(
                ratingKey = track.ratingKey,
                state = stateName,
                timeMillis = timeMillis,
                durationMillis = durationMillis,
                continuing = continuing,
            )
        }
    }

    private suspend fun reportStoppedTrackIfNeeded(
        track: PlexTrackStream,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        reportTimelineSafely(
            track = track,
            stateName = "stopped",
            timeMillis = timeMillis,
            durationMillis = durationMillis,
            continuing = continuing,
        )
    }

    private suspend fun scrobbleIfNeeded(
        scrobbledTrackKeys: MutableSet<String>,
        track: PlexTrackStream,
        timeMillis: Long,
        durationMillis: Long?,
    ) {
        val playbackKey = "${track.sourceId}:${track.ratingKey}"
        if (playbackKey in scrobbledTrackKeys) return

        val effectiveDuration = durationMillis?.takeIf { it > 0L } ?: return
        val scrobbleThreshold = (effectiveDuration * 0.9f).toLong()
        if (timeMillis < scrobbleThreshold) return

        runCatching {
            state.clientFor(track.sourceId).scrobble(track.ratingKey, timeMillis, durationMillis)
        }.onSuccess {
            scrobbledTrackKeys += playbackKey
            recordTrackPlaybackSafely(track)
        }
    }

    private suspend fun recordTrackPlaybackSafely(track: PlexTrackStream) {
        withContext(Dispatchers.IO) {
            runCatching {
                state.albumLocalStore.recordTrackPlayback(track.sourceId, track.ratingKey)
            }
        }
    }

    private fun resolveActiveTrack(
        tracks: List<PlexTrackStream>,
        latestMiniPlayerState: MiniPlayerState,
        status: SonosPlaybackStatus,
    ): PlexTrackStream? {
        val normalizedCurrentUri = playbackUrlIdentity(status.currentTrackUri.orEmpty())
        if (normalizedCurrentUri.isNotBlank()) {
            tracks.firstOrNull { playbackUrlIdentity(it.streamUrl) == normalizedCurrentUri }?.let { return it }
        }
        return tracks.getOrNull(latestMiniPlayerState.currentIndex)
    }

    private fun syncSleepTimerRoom(room: SonosRoom) {
        if (!SleepTimerController.state.value.isActive) return
        SleepTimerController.attachRoom(room)
    }

    private fun resolveNextPlaybackIndex(trackCount: Int, currentIndex: Int): Int? {
        if (trackCount <= 0) return null
        val safeCurrentIndex = currentIndex.coerceIn(0, trackCount - 1)
        return when (state.playbackMode) {
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
}

private fun formatSleepTimer(durationMillis: Long): String {
    val totalMinutes = (durationMillis / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        "%d:%02d".format(hours, minutes)
    } else {
        "%d".format(minutes.coerceAtLeast(1L))
    }
}
