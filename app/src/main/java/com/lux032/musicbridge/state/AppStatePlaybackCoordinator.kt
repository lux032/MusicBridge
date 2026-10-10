package com.lux032.musicbridge

import android.util.Log
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032Json
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.music.Music032Warmup
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterPlayState
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
import com.lux032.musicbridge.sonos.SonosQueueItem
import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

internal fun shouldApplyPlexSonosTranscode(isPlexClient: Boolean?, streamUrl: String): Boolean =
    isPlexClient ?: streamUrl.contains("X-Plex-Token", ignoreCase = true)

/**
 * Honest 032 session state for Sonos-pushed playback. Sonos has no "suppressed" notion;
 * STOPPED is honestly not playing, so it maps to paused (the session stays alive on the
 * paused lease while the app decides whether to advance).
 */
internal fun sonosReporterPlayState(playbackState: String): ReporterPlayState = when (playbackState) {
    "playing" -> ReporterPlayState.Playing
    "buffering" -> ReporterPlayState.Buffering
    else -> ReporterPlayState.Paused
}

/**
 * Repeat-one on Sonos: the speaker (or the sequential push loop) restarts the SAME track,
 * so the position wraps from near the end back to ~0 without a track change. That wrap is
 * a genuinely new play. The boundary is deliberately strict — a seek-back from mid-track
 * must not look like a loop. (Boundary constants not yet verified against hardware; see
 * implementation notes.)
 */
internal fun isSameTrackRepeatWrap(
    previousPositionMillis: Long,
    newPositionMillis: Long,
    durationMillis: Long?,
    playbackState: String,
): Boolean {
    val duration = durationMillis ?: return false
    if (duration <= 20_000L) return false
    if (playbackState == "stopped") return false
    return previousPositionMillis >= duration - 10_000L && newPositionMillis <= 10_000L
}

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

    /** The speaker-side queue loaded by [startQueuePlaybackOnSonos]; null in push mode. */
    @Volatile private var sonosQueue: SonosQueueSession? = null

    private fun sonosQueueFor(room: SonosRoom?, tracks: List<PlexTrackStream>): SonosQueueSession? =
        sonosQueue?.takeIf { room != null && it.roomUuid == room.coordinatorUuid && it.mirrors(tracks) }

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

    // --- Restored session (cold start after process death) -------------------------------
    // The mini player is put back paused, but no renderer holds the queue yet: the local
    // engine is empty and a Sonos room is not driven by us. Until the first command loads
    // it, transport commands must start playback instead of talking to that empty renderer.
    // Identity-checked against the live queue, so ANY fresh playback start (which always
    // builds a new tracks list) retires the restored state without extra bookkeeping.
    private var restoredQueue: List<PlexTrackStream>? = null

    /** Mirrors ExoPlayer's default maxSeekToPreviousPosition used by the local engine. */
    private val restartThresholdMillis = 3_000L

    val isRestoredSessionPending: Boolean
        get() = restoredQueue != null && state.miniPlayerState?.tracks === restoredQueue

    fun markRestoredSession(tracks: List<PlexTrackStream>) {
        restoredQueue = tracks
    }

    /** Loads the restored queue into [target] and starts it at [index]/[positionMillis]. */
    private fun startRestoredSession(index: Int, positionMillis: Long, target: PlaybackTarget) {
        val playerState = state.miniPlayerState ?: return
        restoredQueue = null
        val safeIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        Log.i(LOG_TAG, "start restored session index=$safeIndex positionMs=$positionMillis target=$target")
        when (target) {
            PlaybackTarget.LocalDevice -> state.localPlaybackCoordinator.startLocalPlayback(
                album = playerState.album,
                tracks = playerState.tracks,
                startIndex = safeIndex,
                initialPositionMillis = positionMillis,
            )
            is PlaybackTarget.Sonos -> {
                // Prefer the freshly discovered room: the saved one may carry a stale IP.
                val room = state.sonosRooms.firstOrNull { it.coordinatorUuid == target.room.coordinatorUuid }
                    ?: target.room
                state.selectPlaybackTarget(PlaybackTarget.Sonos(room))
                playSonosQueueIndex(
                    playerState.copy(target = PlaybackTarget.Sonos(room)),
                    safeIndex,
                    positionMillis,
                    room,
                    continueChain = false,
                )
            }
        }
    }

    init {
        SleepTimerController.onExpired = { room ->
            activeSonosPlan = null
            sonosQueue = null
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
        continueChain: Boolean = false,
    ) {
        val targetTrack = tracks.getOrNull(trackIndex) ?: return
        if (canUseSonosQueue(tracks, ::is032Source)) {
            startQueuePlaybackOnSonos(album, tracks, trackIndex, room, initialPositionMillis, continueChain)
            return
        }
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
                        continueChain = continueChain,
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
        continueChain: Boolean = false,
    ) {
        if (canUseSonosQueue(albumTrackResult.tracks, ::is032Source)) {
            startQueuePlaybackOnSonos(albumTrackResult.album, albumTrackResult.tracks, startIndex, room,
                initialPositionMillis, continueChain)
            return
        }
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
                                continueChain = continueChain,
                            )
                        }
                        state.miniPlayerState = state.miniPlayerState?.let { current -> current.copy(
                            album = track.resolvePlaybackAlbum(state.allAlbums, current.album),
                            currentIndex = index,
                            isPaused = false,
                            currentPositionMillis = startPositionMillis,
                            durationMillis = track.durationMillis,
                            target = PlaybackTarget.Sonos(room),
                        ) }
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

    // --- Sonos device queue ---------------------------------------------------------------

    /**
     * Loads [tracks] into the speaker's own queue and starts [startIndex]. Sonos then plays
     * the list by itself; the reporting loop only follows its position. The first track is
     * prepared (hydrated / FLAC warmed) before the push, the rest warm in the background.
     */
    private fun startQueuePlaybackOnSonos(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        startIndex: Int,
        room: SonosRoom,
        initialPositionMillis: Long,
        continueChain: Boolean,
    ) {
        val startTrack = tracks.getOrNull(startIndex) ?: return
        transcodeRestartSeek = null
        val requestGeneration = nextPlaybackRequestGeneration()
        val displayAlbum = resolveAlbumForTrack(startTrack, album)
        val offset = initialPositionMillis.coerceAtLeast(0L)
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
            currentIndex = startIndex,
            target = PlaybackTarget.Sonos(room),
            isPaused = false,
            currentPositionMillis = offset,
            durationMillis = startTrack.durationMillis,
        )
        PlaybackForegroundService.start(
            context = state.context,
            title = startTrack.title,
            subtitle = "正在推送到 ${room.roomName}",
            isPaused = false,
            artworkUrl = startTrack.thumbUrl,
        )
        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.albumPlaybackJob = state.scope.launch {
            runCatching {
                val prepared = prepareSonosTrack(startTrack) { isCurrentPlaybackRequest(requestGeneration) }
                ensureCurrentPlaybackRequest(requestGeneration)
                val startPlan = prepared.plan ?: error(Strings.music032SonosFallbackUnavailable)
                val startUrl = if (startPlan.kind == SonosPlanKind.LIVE_MP3)
                    Music032TranscodeUrls.withOffsetMs(startPlan.url, offset, prepared.track.durationMillis)
                else startPlan.url
                val entries = tracks.mapIndexed { index, track ->
                    if (index == startIndex) {
                        SonosQueueEntry(track.sourceId, track.ratingKey, startPlan.kind, startPlan.url, startPlan.mime)
                    } else {
                        sonosQueueEntryFor(hydrate032Track(track))
                    }
                }
                val items = entries.mapIndexed { index, entry ->
                    sonosQueueItem(tracks[index], if (index == startIndex) entry.copy(url = startUrl) else entry, displayAlbum)
                }
                state.plexTranscodeProxy.cancelPendingDownload()
                state.sonosController.replaceQueue(
                    room = room,
                    items = items,
                    startIndex = startIndex,
                    positionSeconds = if (shouldSonosSeekAfterPush(startPlan.kind, offset)) (offset / 1_000L).toInt() else 0,
                )
                ensureCurrentPlaybackRequest(requestGeneration)
                sonosQueue = SonosQueueSession(room.coordinatorUuid, entries)
                record032Plan(prepared.track, startPlan, startUrl)
                current032Fallback = startPlan.fellBackFromFlac
                set032RestartSeek(prepared.track, if (startPlan.kind == SonosPlanKind.LIVE_MP3) offset else 0L)
                runCatching { state.sonosController.setPlayMode(room, sonosPlayModeFor(state.playbackMode)) }
                entries
            }.onSuccess { entries ->
                if (isCurrentPlaybackRequest(requestGeneration)) {
                    state.isPlaybackCommandLoading = false
                    startPlaybackReporting(
                        room,
                        tracks,
                        startIndex,
                        offset,
                        autoAdvanceWhenStopped = false,
                        continueChain = continueChain,
                    )
                    state.actionMessage = sonosPushMessage(
                        if (tracks.size > 1) "已将 ${tracks.size} 首加入 ${room.roomName} 的播放队列"
                        else "已将 ${startTrack.title} 推送到 ${room.roomName}",
                        current032Fallback,
                    )
                    warmSonosQueue(entries, startIndex)
                }
            }.onFailure { error ->
                if (error !is CancellationException && isCurrentPlaybackRequest(requestGeneration)) {
                    state.errorMessage = error.message ?: "未知错误"
                    state.isPlaybackCommandLoading = false
                }
            }
        }
    }

    /** Plans one queue entry without blocking (no FLAC warm-up; see [warmSonosQueue]). */
    private fun sonosQueueEntryFor(track: PlexTrackStream): SonosQueueEntry {
        val caps = (runCatching { state.clientFor(track.sourceId) }.getOrNull() as? Music032Client)?.capabilities()
        val original = SonosPlan(track.streamUrl, Music032Json.original032MimeType(track.sourceAudioCodec, null), SonosPlanKind.ORIGINAL)
        val plan = plan032(track, state.opusTranscodeMode, state.sonosHiResTranscodeMode, caps).getOrDefault(original)
        return SonosQueueEntry(track.sourceId, track.ratingKey, plan.kind, plan.url, plan.mime ?: track.playbackMimeType)
    }

    private fun sonosQueueItem(track: PlexTrackStream, entry: SonosQueueEntry, album: PlexAlbum?): SonosQueueItem =
        SonosQueueItem(
            url = entry.url,
            title = track.title,
            albumTitle = track.albumTitle ?: album?.title,
            artist = track.artistName,
            albumArtUrl = track.thumbUrl,
            mimeType = entry.mime,
            durationMillis = track.durationMillis,
        )

    /**
     * Builds the 48 kHz FLAC cache of the queued hi-res tracks in play order, so the speaker
     * finds them ready later; stops as soon as the queue is replaced. Without the app the
     * server still builds a missing entry on the speaker's first request.
     */
    private fun warmSonosQueue(entries: List<SonosQueueEntry>, startIndex: Int) {
        val pending = (1..entries.size).map { entries[(startIndex + it) % entries.size] }
            .filter { it.kind == SonosPlanKind.STATIC_FLAC }
            .distinctBy { it.url }
        if (pending.isEmpty()) return
        val isCurrent = { sonosQueue?.entries?.any { e -> e.url == pending.first().url } == true }
        state.scope.launch(Dispatchers.IO) {
            for (entry in pending) {
                if (!isCurrent()) break
                try {
                    Music032Warmup.prepare(entry.url, budgetMillis = 300_000L, isCurrent = isCurrent)
                } catch (cancel: CancellationException) {
                    break
                } catch (_: Exception) { /* Best effort: the server builds it on demand. */ }
            }
        }
    }

    /** Queue-panel tap / next / previous / resume inside a loaded speaker queue. */
    private fun jumpWithinSonosQueue(
        session: SonosQueueSession,
        playerState: MiniPlayerState,
        index: Int,
        positionMillis: Long,
        room: SonosRoom,
    ) {
        val track = playerState.tracks[index]
        val entry = session.entries[index]
        val position = positionMillis.coerceAtLeast(0L)
        val commandGeneration = playbackToggleGeneration.incrementAndGet()
        activeSonosPlan = ActiveSonosPlan(entry.sourceId, entry.ratingKey, entry.kind, entry.url, entry.mime)
        transcodeRestartSeek = null
        state.miniPlayerState = playerState.copy(
            album = resolveAlbumForTrack(track, playerState.album),
            currentIndex = index,
            isPaused = false,
            currentPositionMillis = position,
            durationMillis = track.durationMillis,
        )
        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.scope.launch {
            runCatching {
                if (entry.kind == SonosPlanKind.LIVE_MP3) {
                    // A live MP3 cannot seek: re-open the entry at the offset (or from 0).
                    val url = Music032TranscodeUrls.withOffsetMs(entry.url, position, track.durationMillis)
                    state.sonosController.replaceQueueItem(room, index, sonosQueueItem(track, entry.copy(url = url), playerState.album))
                    set032RestartSeek(track, position)
                    state.sonosController.playQueueIndex(room, index)
                } else {
                    state.sonosController.playQueueIndex(room, index, (position / 1_000L).toInt())
                }
            }.onSuccess {
                if (commandGeneration == playbackToggleGeneration.get()) {
                    state.actionMessage = sonosPushMessage("正在播放：${track.title}", false)
                }
            }.onFailure { error ->
                if (error !is CancellationException && commandGeneration == playbackToggleGeneration.get()) {
                    state.errorMessage = error.message ?: "未知错误"
                }
            }
            if (commandGeneration == playbackToggleGeneration.get()) state.isPlaybackCommandLoading = false
            if (state.playbackReportingJob?.isActive != true && sonosQueue === session) {
                startPlaybackReporting(room, playerState.tracks, index, position,
                    autoAdvanceWhenStopped = false, continueChain = true)
            }
        }
    }

    /**
     * Re-points the current track at [url] (live transcode resume / seek). In queue mode the
     * queue entry is swapped in place so Sonos keeps advancing through the rest of the queue.
     */
    private suspend fun pushSonosUrl(room: SonosRoom, track: PlexTrackStream, url: String, albumTitle: String, mime: String?) {
        val player = state.miniPlayerState
        val session = player?.let { sonosQueueFor(room, it.tracks) }
        val index = player?.currentIndex ?: -1
        val entry = session?.entries?.getOrNull(index)
        if (entry != null && entry.ratingKey == track.ratingKey && entry.sourceId == track.sourceId) {
            state.sonosController.replaceQueueItem(room, index, SonosQueueItem(url, track.title, albumTitle,
                track.artistName, track.thumbUrl, mime, track.durationMillis))
            state.sonosController.playQueueIndex(room, index)
        } else {
            state.sonosController.playTrack(room, url, track.title, albumTitle, mime)
        }
    }

    // --- Adopting the speaker's queue -------------------------------------------------------

    private class AdoptedSonosQueue(
        val tracks: List<PlexTrackStream>,
        val reusedKnown: Boolean,
        val refs: List<AdoptedQueueRef>,
        val index: Int,
        val status: SonosPlaybackStatus,
        val playMode: PlaybackMode?,
    )

    /**
     * Selecting a room while nothing renders here (idle, or the paused session restored
     * after a cold start) first looks at the speaker: when it is already playing a 032
     * queue — pushed earlier by this app or by the web panel — the player adopts it (track,
     * position, pause state, play mode) instead of later re-pushing the stale saved session
     * over it. Anything else on the speaker is left untouched and the selection stays as is.
     */
    private fun adoptSonosQueueIfPlaying(room: SonosRoom) {
        val snapshot = state.miniPlayerState
        val generation = playbackRequestGeneration.get()
        val stillIdle = {
            playbackRequestGeneration.get() == generation && state.miniPlayerState === snapshot &&
                (state.selectedPlaybackTarget as? PlaybackTarget.Sonos)?.room?.coordinatorUuid == room.coordinatorUuid
        }
        state.scope.launch {
            val adopted = try {
                loadAdoptableSonosQueue(room, snapshot?.tracks.orEmpty())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(LOG_TAG, "sonos queue adopt probe failed: ${error.message}")
                null
            } ?: return@launch
            if (!stillIdle()) return@launch
            applyAdoptedSonosQueue(room, adopted, snapshot)
        }
    }

    private suspend fun loadAdoptableSonosQueue(room: SonosRoom, known: List<PlexTrackStream>): AdoptedSonosQueue? {
        val controller = state.sonosController
        val status = controller.getPlaybackStatus(room)
        if (status.transportState !in ADOPTABLE_TRANSPORT_STATES) return null
        if (controller.getMediaUri(room)?.startsWith("x-rincon-queue:") != true) return null
        val servers = state.serverProfiles.filter { it.type == MusicServerType.Music032 }.map { profile ->
            Music032ServerRef(profile.id, profile.baseUrl, setOf(profile.mediaToken.trim(), profile.token.trim()).filter { it.isNotEmpty() }.toSet())
        }
        val refs = resolveAdoptableQueue(controller.browseQueue(room, SONOS_QUEUE_MAX_TRACKS + 1), servers) ?: return null
        val index = resolveSonosQueueIndex(refs.map { it.entry() }, status) ?: return null
        val reuse = reusableTracks(refs, known)
        val tracks = if (reuse) known else fetchQueueTracks(refs) ?: return null
        val playMode = runCatching { playbackModeFromSonos(controller.getPlayMode(room)) }.getOrNull()
        return AdoptedSonosQueue(tracks, reuse, refs, index, status, playMode)
    }

    /** Loads the tracks of a foreign queue (e.g. pushed by the web panel); null if any is gone. */
    private suspend fun fetchQueueTracks(refs: List<AdoptedQueueRef>): List<PlexTrackStream>? = coroutineScope {
        val permits = Semaphore(ADOPT_FETCH_CONCURRENCY)
        val unique = refs.map { it.sourceId to it.ratingKey }.distinct()
        val fetched = unique.map { key ->
            async {
                permits.withPermit {
                    key to state.clientFor(key.first).fetchTrackStream(key.second)
                }
            }
        }.awaitAll().toMap()
        refs.map { fetched[it.sourceId to it.ratingKey] ?: return@coroutineScope null }
    }

    private fun applyAdoptedSonosQueue(room: SonosRoom, adopted: AdoptedSonosQueue, snapshot: MiniPlayerState?) {
        val track = adopted.tracks[adopted.index]
        val ref = adopted.refs[adopted.index]
        val position = ((adopted.status.relTimeSeconds ?: 0) * 1_000L + ref.offsetMs)
            .let { p -> track.durationMillis?.let(p::coerceAtMost) ?: p }
        Log.i(LOG_TAG, "adopt sonos queue room=${room.roomName} size=${adopted.tracks.size} index=${adopted.index} " +
            "positionMs=$position reused=${adopted.reusedKnown}")
        restoredQueue = null
        nextPlaybackRequestGeneration()
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        sonosQueue = SonosQueueSession(room.coordinatorUuid, adopted.refs.map { it.entry() })
        activeSonosPlan = ActiveSonosPlan(ref.sourceId, ref.ratingKey, ref.kind, ref.url, ref.mime)
        if (ref.kind == SonosPlanKind.LIVE_MP3) set032RestartSeek(track, ref.offsetMs)
        adopted.playMode?.let { state.playbackMode = it }
        state.selectPlaybackTarget(PlaybackTarget.Sonos(room))
        state.volumeSyncKey += 1
        syncSleepTimerRoom(room)
        state.miniPlayerState = MiniPlayerState(
            album = resolveAlbumForTrack(track, snapshot?.album?.takeIf { adopted.reusedKnown }),
            tracks = adopted.tracks,
            currentIndex = adopted.index,
            target = PlaybackTarget.Sonos(room),
            isPaused = adopted.status.transportState != "PLAYING" && adopted.status.transportState != "TRANSITIONING",
            currentPositionMillis = position,
            durationMillis = track.durationMillis,
        )
        startPlaybackReporting(room, adopted.tracks, adopted.index, position,
            autoAdvanceWhenStopped = false, continueChain = true)
        state.errorMessage = null
        state.actionMessage = "已同步 ${room.roomName} 正在播放的队列"
    }

    /** Mirrors a "play next" / "add to queue" into the speaker queue. */
    private fun enqueueIntoSonosQueue(
        room: SonosRoom,
        session: SonosQueueSession,
        insertIndex: Int,
        tracks: List<PlexTrackStream>,
        previousSize: Int,
    ) {
        if (!canUseSonosQueue(tracks, ::is032Source)) {
            // Non-032 tracks cannot live in the speaker queue; it keeps playing on its own.
            sonosQueue = null
            return
        }
        val added = tracks.map(::sonosQueueEntryFor)
        val updated = session.copy(entries = session.entries.toMutableList().apply { addAll(insertIndex, added) })
        sonosQueue = updated
        state.scope.launch {
            runCatching {
                state.sonosController.insertIntoQueue(
                    room,
                    added.mapIndexed { i, entry -> sonosQueueItem(tracks[i], entry, null) },
                    if (insertIndex >= previousSize) -1 else insertIndex,
                )
            }.onSuccess {
                warmSonosQueue(updated.entries, (insertIndex - 1).coerceAtLeast(0))
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                if (sonosQueue === updated) sonosQueue = null
                state.errorMessage = error.message ?: "无法加入 Sonos 队列"
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
        if (canUseSonosQueue(tracks, ::is032Source)) {
            startQueuePlaybackOnSonos(fakeAlbum, tracks, 0, room, 0L, continueChain = false)
            return
        }
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
                            )                        }
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
        if (isRestoredSessionPending) {
            // A restored session is always paused, so the toggle can only mean "play".
            startRestoredSession(playerState.currentIndex, playerState.currentPositionMillis, playerState.target)
            return
        }
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
                            pushSonosUrl(room, currentTrack, url, currentTrack.albumTitle ?: latest.album.title, active032.mime)
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
                    // STOPPED re-push of the same listen: continue the chain (P2-A).
                    playQueueIndex(latest.currentIndex, latest.currentPositionMillis, PlaybackTarget.Sonos(room), continueChain = true)
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
                            playQueueIndex(latest.currentIndex, latest.currentPositionMillis, PlaybackTarget.Sonos(room), continueChain = true)
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
        state.miniPlayerState?.let { player ->
            val room = player.sonosRoom
            if (room != null && sonosQueueFor(room, player.tracks) != null) {
                state.scope.launch { runCatching { state.sonosController.setPlayMode(room, sonosPlayModeFor(mode)) } }
            }
        }
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
        if (isRestoredSessionPending) {
            // Same rule as the engine: past the first few seconds, "previous" restarts.
            val index = if (playerState.currentPositionMillis > restartThresholdMillis) {
                playerState.currentIndex
            } else {
                (playerState.currentIndex - 1).coerceAtLeast(0)
            }
            startRestoredSession(index, 0L, playerState.target)
            return
        }
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
        if (isRestoredSessionPending) {
            if (playerState.currentIndex < playerState.tracks.lastIndex) {
                startRestoredSession(playerState.currentIndex + 1, 0L, playerState.target)
            }
            return
        }
        if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.playLocalNext()
            return
        }
        val newIndex = (playerState.currentIndex + 1).coerceAtMost(playerState.tracks.lastIndex)
        if (newIndex != playerState.currentIndex) playQueueIndex(newIndex, 0L, playerState.target)
    }

    fun playQueueIndex(index: Int, positionMillis: Long, target: PlaybackTarget?, continueChain: Boolean = false) {
        val playerState = state.miniPlayerState ?: return
        if (isRestoredSessionPending) {
            startRestoredSession(index, positionMillis, target ?: playerState.target)
            return
        }
        val targetIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        when (val resolvedTarget = target ?: playerState.target) {
            PlaybackTarget.LocalDevice -> {
                activeSonosPlan = null
                pending032Plan = null
                sonosQueue = null
                if (playerState.target == PlaybackTarget.LocalDevice) {
                    // Same renderer: seek inside the live ExoPlayer queue.
                    state.localPlaybackCoordinator.playLocalIndex(targetIndex, positionMillis)
                } else {
                    // Renderer switch (Sonos -> this device): stop the speaker and rebuild
                    // the local queue at the same position. continueChain lets the 032
                    // reporter resume the open session instead of counting a second play.
                    playerState.sonosRoom?.let { room ->
                        state.scope.launch { runCatching { state.sonosController.stop(room) } }
                    }
                    state.localPlaybackCoordinator.startLocalPlayback(
                        album = playerState.album,
                        tracks = playerState.tracks,
                        startIndex = targetIndex,
                        initialPositionMillis = positionMillis,
                        continueChain = true,
                    )
                }
            }
            is PlaybackTarget.Sonos -> {
                if (playerState.target == PlaybackTarget.LocalDevice) {
                    state.localPlaybackCoordinator.stopLocalPlayback()
                }
                // continueChain: explicit param for genuine renderer switches / STOPPED
                // re-pushes; a queue-panel tap (even on the current item) is a fresh play.
                playSonosQueueIndex(
                    playerState,
                    targetIndex,
                    positionMillis,
                    resolvedTarget.room,
                    continueChain = continueChain || playerState.target == PlaybackTarget.LocalDevice,
                )
            }
        }
    }

    private fun playSonosQueueIndex(
        playerState: MiniPlayerState,
        targetIndex: Int,
        positionMillis: Long,
        targetRoom: SonosRoom,
        continueChain: Boolean,
    ) {
        val outgoing = playerState.tracks.getOrNull(playerState.currentIndex)
        val target = playerState.tracks.getOrNull(targetIndex)
        val sameTrack = outgoing != null && target != null &&
            outgoing.ratingKey == target.ratingKey && outgoing.sourceId == target.sourceId
        // User-initiated track change on Sonos: close the outgoing 032 session as skipped
        // (the server only counts it when the position sits below its skip threshold). An
        // auto-advance has already ended its session completed, so this is a no-op there.
        outgoing
            ?.takeIf {
                playerState.target is PlaybackTarget.Sonos && !sameTrack && is032Source(it)
            }
            ?.let {
                state.music032ReporterFor(it.sourceId)?.requestEnd(ReporterEndReason.Skipped)
            }
        // Queue mode: the speaker already holds the list — just jump inside it.
        val queueSession = sonosQueueFor(targetRoom, playerState.tracks)
            ?.takeIf { playerState.sonosRoom?.coordinatorUuid == targetRoom.coordinatorUuid }
        if (queueSession != null && target != null) {
            jumpWithinSonosQueue(queueSession, playerState, targetIndex, positionMillis, targetRoom)
            return
        }
        val currentTrackResult = state.trackResult?.takeIf { result ->
            result.album.ratingKey == playerState.album.ratingKey &&
                result.album.sourceId == playerState.album.sourceId &&
                result.tracks.map { it.sourceId to it.ratingKey } ==
                playerState.tracks.map { it.sourceId to it.ratingKey }
        }
        if (currentTrackResult != null) {
            startAlbumPlaybackOnSonos(currentTrackResult, targetRoom, targetIndex, positionMillis, continueChain)
        } else {
            startSingleTrackPlaybackOnSonos(
                album = resolveAlbumForTrack(playerState.tracks[targetIndex], playerState.album),
                tracks = playerState.tracks,
                trackIndex = targetIndex,
                room = targetRoom,
                initialPositionMillis = positionMillis,
                continueChain = continueChain,
            )
        }
    }

    /** Moves the current playback (or just the selection, when idle) to another output. */
    fun switchPlaybackTarget(target: PlaybackTarget) {
        val restored = state.miniPlayerState?.takeIf { isRestoredSessionPending }
        if (restored != null) {
            // Nothing is rendering yet: just retarget the paused session; play starts there.
            state.selectPlaybackTarget(target)
            state.miniPlayerState = restored.copy(target = target)
            state.volumeSyncKey += 1
            if (target is PlaybackTarget.Sonos) adoptSonosQueueIfPlaying(target.room)
            return
        }
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
            if (target is PlaybackTarget.Sonos) adoptSonosQueueIfPlaying(target.room)
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
        playQueueIndex(playerState.currentIndex, playerState.currentPositionMillis, target, continueChain = true)
        state.actionMessage = when (target) {
            PlaybackTarget.LocalDevice -> "正在切换到 ${Strings.localPlayback}"
            is PlaybackTarget.Sonos -> "正在切换到 ${target.room.roomName}"
        }
    }

    fun seekPlayback(playerState: MiniPlayerState, positionMillis: Long) {
        if (isRestoredSessionPending) {
            // Only move the cursor; the next play starts from here.
            val duration = playerState.durationMillis?.takeIf { it > 0L } ?: Long.MAX_VALUE
            state.miniPlayerState = state.miniPlayerState?.copy(
                currentPositionMillis = positionMillis.coerceIn(0L, duration),
            )
            return
        }
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
                    pushSonosUrl(
                        room = room,
                        track = effectiveTrack,
                        url = seekUrl,
                        albumTitle = effectiveTrack.albumTitle ?: playerState.album.title,
                        mime = if (active032 != null) active032.mime
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
                    if (is032Source(targetTrack)) {
                        state.music032ReporterFor(targetTrack.sourceId)?.requestSeek(
                            targetMillis,
                            durationMillis,
                        )
                    }
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

        // A speaker-side 032 queue cannot hold Plex tracks (proxy/transcode sessions need the
        // phone): refuse instead of silently dropping them at the end of the speaker queue.
        if (!isRestoredSessionPending && sonosQueueFor(playerState.sonosRoom, playerState.tracks) != null &&
            !canUseSonosQueue(tracks, ::is032Source)) {
            state.actionMessage = null
            state.errorMessage = "Sonos 正在播放 032 队列，Plex 曲目无法加入，请单独播放"
            return
        }
        val insertIndex = when (placement) {
            QueuePlacement.Next -> (playerState.currentIndex + 1).coerceAtMost(playerState.tracks.size)
            QueuePlacement.End -> playerState.tracks.size
        }
        val updatedTracks = playerState.tracks.toMutableList().apply {
            addAll(insertIndex, tracks)
        }
        val restoredPending = isRestoredSessionPending
        state.miniPlayerState = playerState.copy(tracks = updatedTracks)
        if (restoredPending) {
            // The empty engine must not receive inserts (indices would misalign); the
            // whole queue is loaded on the first play. Keep the session marked restored.
            restoredQueue = updatedTracks
        } else if (playerState.target == PlaybackTarget.LocalDevice) {
            state.localPlaybackCoordinator.insertIntoLocalQueue(insertIndex, tracks)
        } else {
            val room = playerState.sonosRoom
            sonosQueueFor(room, playerState.tracks)?.let { session ->
                enqueueIntoSonosQueue(room!!, session, insertIndex, tracks, playerState.tracks.size)
            }
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
        continueChain: Boolean = false,
    ) {
        val normalizedInitialIndex = initialTrackIndex.coerceIn(0, tracks.lastIndex)
        state.playbackReportingJob?.cancel()
        state.playbackReportingJob = state.scope.launch {
            val thisJob = coroutineContext.job
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
            // 032 session owned by this reporting loop; the finally block may only end
            // exactly this session (a takeover chain installs a new id first).
            var owned032SessionId: String? = null
            var owned032SourceId: String? = null
            try {
            val scrobbledTrackKeys = mutableSetOf<String>()
            var currentTrack = initialTrack
            var currentTrackTimeMillis = initialPositionMillis.coerceAtLeast(0L)
            var currentTrackDurationMillis = currentTrack.durationMillis
            var lastReportedState: String? = null
            var lastTimelineReportAt = 0L
            var stoppedPollCount = 0
            var hasObservedPlayback = initialPositionMillis > 0L

            if (is032Source(currentTrack)) {
                val reporter = state.music032ReporterFor(currentTrack.sourceId)
                val trackId = currentTrack.ratingKey.toLongOrNull()
                if (reporter != null && trackId != null) {
                    // The push has not produced sound yet: the honest initial state is
                    // buffering. Only a genuine renderer switch (or a same-track re-push)
                    // takes the chain over; an explicit push is a fresh play (P1-2).
                    if (continueChain) {
                        reporter.requestTakeover(
                            trackId = trackId,
                            positionMillis = currentTrackTimeMillis,
                            durationMillis = currentTrackDurationMillis ?: 0L,
                            initialState = ReporterPlayState.Buffering,
                        )
                    } else {
                        reporter.requestStart(
                            trackId = trackId,
                            positionMillis = currentTrackTimeMillis,
                            durationMillis = currentTrackDurationMillis ?: 0L,
                            initialState = ReporterPlayState.Buffering,
                        )
                    }
                    owned032SourceId = currentTrack.sourceId
                }
            } else {
                reportTimelineSafely(
                    track = currentTrack,
                    stateName = "playing",
                    timeMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                    continuing = false,
                )
                lastReportedState = "playing"
                lastTimelineReportAt = System.currentTimeMillis()
            }

            while (true) {
                delay(1_000)

                val latestMiniPlayerState = state.miniPlayerState ?: break
                if (latestMiniPlayerState.sonosRoom?.coordinatorUuid != room.coordinatorUuid) break

                val currentQueue = latestMiniPlayerState.tracks
                val status = runCatching { state.sonosController.getPlaybackStatus(room) }.getOrNull() ?: continue
                // Queue mode: Sonos advances on its own; map its queue position back.
                val queueSession = sonosQueueFor(room, currentQueue)
                val queueIndex = queueSession?.let { resolveSonosQueueIndex(it.entries, status) }
                val resolvedTrack = queueIndex?.let(currentQueue::getOrNull)
                    ?: resolveActiveTrack(currentQueue, latestMiniPlayerState, status) ?: continue
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
                    queueSession?.entries?.getOrNull(queueIndex ?: -1)?.let { entry ->
                        activeSonosPlan = ActiveSonosPlan(entry.sourceId, entry.ratingKey, entry.kind, entry.url, entry.mime)
                    }
                    // A track change observed by this loop is an auto-advance or an
                    // external controller switch: end the old 032 session with the real
                    // last observed position — completed only near the end, otherwise
                    // replaced, never a fabricated full-duration completion (P3).
                    if (is032Source(currentTrack)) {
                        val nearEnd = currentTrackDurationMillis?.let { duration ->
                            currentTrackTimeMillis >= (duration - 3_000L).coerceAtLeast(0L)
                        } ?: false
                        state.music032ReporterFor(currentTrack.sourceId)?.requestEnd(
                            if (nearEnd) ReporterEndReason.Completed else ReporterEndReason.Replaced,
                            finalPositionMillis = currentTrackTimeMillis,
                        )
                        owned032SessionId = null
                        owned032SourceId = null
                    } else {
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
                    }

                    currentTrack = resolvedTrack
                    currentTrackTimeMillis = 0L
                    currentTrackDurationMillis = durationMillis
                    lastReportedState = null
                    lastTimelineReportAt = 0L
                    stoppedPollCount = 0
                    hasObservedPlayback = false
                    if (is032Source(currentTrack)) {
                        val reporter = state.music032ReporterFor(currentTrack.sourceId)
                        val trackId = currentTrack.ratingKey.toLongOrNull()
                        if (reporter != null && trackId != null) {
                            // Auto-advance inside the queue: an explicit new play.
                            reporter.requestStart(
                                trackId = trackId,
                                positionMillis = 0L,
                                durationMillis = currentTrackDurationMillis ?: 0L,
                                initialState = sonosReporterPlayState(playbackState),
                            )
                            owned032SourceId = currentTrack.sourceId
                        }
                    }
                }

                // Sonos resets REL_TIME to zero when the transport reaches STOPPED. For an
                // Opus restart seek, adding the source offset to that zero would resurrect
                // the last seek position and overwrite the real end-of-track progress.
                val stoppedAtResetPosition = playbackState == "stopped" && rawPositionMillis == 0L

                // Repeat-one on Sonos restarts the SAME track: the position wraps from
                // near the end back to ~0 without a track change. That wrap is a genuinely
                // new play — the completed session closes and a fresh one starts.
                if (is032Source(currentTrack) && !stoppedAtResetPosition &&
                    isSameTrackRepeatWrap(currentTrackTimeMillis, positionMillis, currentTrackDurationMillis, playbackState)
                ) {
                    val reporter = state.music032ReporterFor(currentTrack.sourceId)
                    val trackId = currentTrack.ratingKey.toLongOrNull()
                    if (reporter != null && trackId != null) {
                        // The completed play ends with the real observed final position.
                        reporter.requestEnd(
                            ReporterEndReason.Completed,
                            finalPositionMillis = currentTrackTimeMillis,
                        )
                        // A loop restart is a genuinely new play (fresh chain).
                        reporter.requestStart(
                            trackId = trackId,
                            positionMillis = positionMillis,
                            durationMillis = currentTrackDurationMillis ?: 0L,
                            initialState = sonosReporterPlayState(playbackState),
                        )
                        owned032SourceId = currentTrack.sourceId
                    }
                }

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
                    album = currentTrack.resolvePlaybackAlbum(state.allAlbums, latestMiniPlayerState.album),
                    currentIndex = queueIndex ?: currentQueue.indexOfFirst { it.ratingKey == currentTrack.ratingKey && it.sourceId == currentTrack.sourceId }
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

                if (is032Source(currentTrack)) {
                    val reporter = state.music032ReporterFor(currentTrack.sourceId)
                    val trackId = currentTrack.ratingKey.toLongOrNull()
                    if (reporter != null && trackId != null) {
                        // While stopped, the trackFinished logic owns the lifecycle: never
                        // re-open a session for a track that just ended.
                        if (playbackState != "stopped" && reporter.openSessionTrackId() != trackId) {
                            reporter.requestFallbackStart(
                                trackId = trackId,
                                positionMillis = currentTrackTimeMillis,
                                durationMillis = currentTrackDurationMillis ?: 0L,
                                initialState = sonosReporterPlayState(playbackState),
                            )
                        }
                        // Non-blocking: the reporter actor merges the observation.
                        reporter.observe(
                            sonosReporterPlayState(playbackState),
                            currentTrackTimeMillis,
                            currentTrackDurationMillis ?: 0L,
                        )
                        reporter.ownedSessionId()?.let { sessionId ->
                            owned032SessionId = sessionId
                            owned032SourceId = currentTrack.sourceId
                        }
                    }
                } else {
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
                }

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
                        if (is032Source(currentTrack)) {
                            // trackFinished reached the end of the track: completed with
                            // the real final position. A push that never produced sound
                            // is an error, never a skip.
                            state.music032ReporterFor(currentTrack.sourceId)?.requestEnd(
                                if (playbackNeverStarted) ReporterEndReason.Error else ReporterEndReason.Completed,
                                finalPositionMillis = currentTrackTimeMillis,
                            )
                            owned032SessionId = null
                            owned032SourceId = null
                        } else {
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
                        }
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
                // P1-1: service teardown runs synchronously and only while this job is
                // still the active reporting job (a cancelled old job must never stop the
                // new playback's notification or wipe its action callback); the bounded
                // 032 end is fire-and-forget and never gates the teardown on network.
                val endingSessionId = owned032SessionId
                val endingSourceId = owned032SourceId
                ReportingJobCleanup(
                    isActiveJob = { state.playbackReportingJob === thisJob },
                    stopPlaybackService = {
                        PlaybackForegroundService.onPlaybackAction = null
                        PlaybackForegroundService.stop(state.context)
                    },
                    requestSessionEnd = {
                        if (endingSessionId != null && endingSourceId != null) {
                            state.music032ReporterFor(endingSourceId)?.requestEnd(
                                ReporterEndReason.Stopped,
                                expectedSessionId = endingSessionId,
                            )
                        }
                    },
                ).onJobFinished()
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
        sonosQueue = null
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
        const val ADOPT_FETCH_CONCURRENCY = 8
        val ADOPTABLE_TRANSPORT_STATES = setOf("PLAYING", "PAUSED_PLAYBACK", "TRANSITIONING")
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
            year = track.albumYearFrom(fallback),
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
