package com.lux032.musicbridge

import androidx.media3.common.Player
import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.LocalPlaybackService
import com.lux032.musicbridge.local.LocalQueueItem
import com.lux032.musicbridge.local.isLiveTranscodeUrl
import com.lux032.musicbridge.local.originalCacheKey
import com.lux032.musicbridge.local.shouldUseCachedOriginal
import com.lux032.musicbridge.local.transcodedCacheKey
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterPlayState
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.willTranscodeLocally
import com.lux032.musicbridge.plex.withLocalPlaybackPolicy
import com.lux032.musicbridge.service.PlaybackForegroundService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long a local-playback handover waits for a cancelled Sonos push to unwind before it
 * silences the speaker anyway. The SOAP call is a blocking socket read, so the join has to
 * be bounded or an unreachable speaker would stall the stop command indefinitely.
 */
private const val SONOS_HANDOVER_JOIN_TIMEOUT_MILLIS = 2_000L

internal fun networkReevaluationIndices(
    queueSize: Int,
    currentIndex: Int,
    playbackMode: PlaybackMode,
): List<Int> {
    if (queueSize <= 0) return emptyList()
    // Every non-current item is potentially reachable after a later mode change. Shuffle
    // and RepeatAll already make indices before current directly reachable.
    return when (playbackMode) {
        PlaybackMode.Sequential,
        PlaybackMode.RepeatOne,
        PlaybackMode.RepeatAll,
        PlaybackMode.Shuffle -> (0 until queueSize).filter { it != currentIndex }
    }
}

/**
 * End reason a 032 session gets when the local engine moves to another media item.
 * AUTO and REPEAT both mean the previous track played to its end (REPEAT is the
 * repeat-one loop: the completed play closes and a genuinely new play starts, so a loop
 * counts again). SEEK is a user-initiated jump — the server only counts it as a skip when
 * the final position sits below its skip threshold.
 */
internal fun reporterEndReasonForTransition(reason: Int): ReporterEndReason = when (reason) {
    Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> ReporterEndReason.Skipped
    else -> ReporterEndReason.Completed
}

/**
 * Honest playback state for 032 session reporting. `isPlaying` already requires READY +
 * playWhenReady + no playback suppression, so an audio-focus loss is reported as
 * buffering (not audible) instead of pretending to play.
 */
internal fun localReporterPlayState(
    isPlaying: Boolean,
    isPlayRequested: Boolean,
    isBuffering: Boolean,
): ReporterPlayState = when {
    isPlaying -> ReporterPlayState.Playing
    !isPlayRequested -> ReporterPlayState.Paused
    // Play requested but buffering or suppressed: honestly not playing.
    else -> ReporterPlayState.Buffering
}

/**
 * Drives on-device playback through [LocalPlaybackEngine]. Mirrors the Sonos side of
 * [AppStatePlaybackCoordinator]: the engine owns rendering, this coordinator owns queue
 * state, Plex timeline reporting, and scrobbling. The system media notification is
 * published by [LocalPlaybackService] via a MediaSession bound to the same player, so
 * notification/lockscreen/headset controls drive the player directly and flow back here
 * through the engine's listener callbacks.
 */
internal class AppStateLocalPlaybackCoordinator(
    private val state: AppState,
) {
    private fun is032Track(track: PlexTrackStream): Boolean =
        runCatching { state.clientFor(track.sourceId) is Music032Client }.getOrNull()
            ?: Music032TranscodeUrls.isOriginalStream(track.streamUrl)

    /** All 032 session routing for the local target (see Local032SessionDriver docs). */
    private val sessionDriver = Local032SessionDriver(
        reporterFor = { sourceId -> state.music032ReporterFor(sourceId) },
        is032Track = ::is032Track,
    )

    init {
        state.localPlaybackEngine.listener = object : LocalPlaybackEngine.Listener {
            override fun onTrackChanged(index: Int) {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    if (index == current.currentIndex) return@launch
                    val track = current.tracks.getOrNull(index) ?: return@launch
                    state.miniPlayerState = current.copy(
                        album = track.resolvePlaybackAlbum(state.allAlbums, current.album),
                        currentIndex = index,
                        isBuffering = state.localPlaybackEngine.isBuffering,
                        currentPositionMillis = state.localPlaybackEngine.currentPositionMillis,
                        durationMillis = track.durationMillis,
                    )
                    state.actionMessage = "正在播放：${track.title}"
                }
            }

            override fun onTrackTransition(index: Int, reason: Int) {
                val current = state.miniPlayerState ?: return
                if (current.target != PlaybackTarget.LocalDevice) return
                val track = current.tracks.getOrNull(index) ?: return
                val engine = state.localPlaybackEngine
                state.recordPlayedAlbum(track.resolvePlaybackAlbum(state.allAlbums, current.album))
                sessionDriver.onEngineTransition(
                    newTrack = track,
                    reason = reason,
                    positionMillis = engine.currentPositionMillis.coerceAtLeast(0L),
                    durationMillis = engine.durationMillis ?: track.durationMillis ?: 0L,
                    initialState = localReporterPlayState(engine.isPlaying, engine.isPlayRequested, engine.isBuffering),
                )
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                push032ObservationNow()
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    val engine = state.localPlaybackEngine
                    val paused = !engine.isPlayRequested
                    if (current.isPaused == paused && current.isBuffering == engine.isBuffering) return@launch
                    state.miniPlayerState = current.copy(
                        isPaused = paused,
                        isBuffering = engine.isBuffering,
                    )
                }
            }

            override fun onBufferingChanged(isBuffering: Boolean) {
                push032ObservationNow()
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    val paused = !state.localPlaybackEngine.isPlayRequested
                    if (current.isPaused == paused && current.isBuffering == isBuffering) return@launch
                    state.miniPlayerState = current.copy(
                        isPaused = paused,
                        isBuffering = isBuffering,
                    )
                }
            }

            override fun onQueueEnded(failed: Boolean) {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    // The final track never produces a media-item transition, so its 032
                    // session is closed here: completed on a natural STATE_ENDED, error
                    // (with the real last observed position) when it failed (P1-3).
                    sessionDriver.onQueueEnded(
                        track = current.tracks.getOrNull(state.localPlaybackEngine.currentIndex),
                        failed = failed,
                    )
                    state.localPlaybackEngine.rewindToStartAndPause()
                    state.miniPlayerState = current.copy(
                        isPaused = true,
                        isBuffering = false,
                        currentPositionMillis = 0L,
                    )
                    state.actionMessage = "播放结束：${current.album.title}"
                }
            }

            override fun onSeeked() {
                val current = state.miniPlayerState ?: return
                if (current.target != PlaybackTarget.LocalDevice) return
                val engine = state.localPlaybackEngine
                val track = current.tracks.getOrNull(engine.currentIndex) ?: return
                sessionDriver.onSeek(
                    track = track,
                    positionMillis = engine.currentPositionMillis.coerceAtLeast(0L),
                    durationMillis = engine.durationMillis ?: track.durationMillis ?: 0L,
                )
            }

            override fun onTrackSkipped(index: Int) {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    val track = current.tracks.getOrNull(index) ?: return@launch
                    // The engine gave up on this item: close its 032 session as error; the
                    // following transition opens the next track's session.
                    sessionDriver.onTrackFailed(track)
                    state.actionMessage = "《${track.title}》播放异常，已跳至下一首"
                }
            }

            override fun onCachedOriginalFallback(cacheKey: String) {
                state.scope.launch {
                    state.cachedOriginalTrackKeys = state.cachedOriginalTrackKeys - cacheKey
                }
            }

            override fun onAudioFormat(index: Int, sampleRate: Int?, bitDepth: Int?, isTranscoded: Boolean) {
                if (isTranscoded) return
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice || index !in current.tracks.indices) return@launch
                    val existing = current.tracks[index]
                    val updated = existing.copy(
                        sourceAudioSampleRate = existing.sourceAudioSampleRate ?: sampleRate,
                        bitDepth = existing.bitDepth ?: bitDepth,
                    )
                    if (updated == existing) return@launch
                    val tracks = current.tracks.toMutableList().also { it[index] = updated }
                    state.miniPlayerState = current.copy(tracks = tracks)
                }
            }

            override fun onPlayerError(message: String) {
                state.scope.launch {
                    if (state.miniPlayerState?.target != PlaybackTarget.LocalDevice) return@launch
                    state.miniPlayerState
                        ?.tracks?.getOrNull(state.localPlaybackEngine.currentIndex)
                        ?.let(sessionDriver::onTrackFailed)
                    state.miniPlayerState = state.miniPlayerState?.copy(isBuffering = false)
                    state.errorMessage = message
                }
            }
        }
        state.localPlaybackEngine.setNetworkIsWifi(state.currentNetworkIsWifi)
        state.scope.launch {
            var previousIsWifi = state.networkQualityMonitor.currentIsWifi()
            state.networkQualityMonitor.isWifi.collect { isWifi ->
                if (isWifi == previousIsWifi) return@collect
                val switchedToCellular = previousIsWifi && !isWifi
                previousIsWifi = isWifi
                state.currentNetworkIsWifi = isWifi
                state.localPlaybackEngine.setNetworkIsWifi(isWifi)
                if (switchedToCellular) LocalAudioCache.cancelPrecache()
                reEvaluateQueueForNetworkChange()
            }
        }
    }

    private suspend fun reEvaluateQueueForNetworkChange() {
        val current = state.miniPlayerState ?: return
        if (current.target != PlaybackTarget.LocalDevice) return
        val currentIndex = state.localPlaybackEngine.currentIndex
        val indices = networkReevaluationIndices(current.tracks.size, currentIndex, state.playbackMode)
        if (indices.isEmpty()) return
        val policy = state.currentLocalPlaybackPolicy()
        val replacements = withContext(Dispatchers.IO) {
            indices.associateWith { index -> current.tracks[index].toLocalQueueItem(policy) }
        }
        if (state.miniPlayerState?.tracks !== current.tracks || state.localPlaybackEngine.currentIndex != currentIndex) return
        val preservedKeys = state.cachedOriginalTrackKeys.toMutableSet()
        indices.forEach { index ->
            val track = current.tracks[index]
            preservedKeys.remove(originalCacheKey(track.sourceId, track.ratingKey, track.partKey))
        }
        preservedKeys += replacements.values.filter(LocalQueueItem::isCachedOriginal).map(LocalQueueItem::cacheKey)
        state.cachedOriginalTrackKeys = preservedKeys
        state.localPlaybackEngine.replaceQueueItems(replacements, policy.precacheCount.count)
    }

    private fun resolveTrackWithLocalDownload(track: PlexTrackStream): PlexTrackStream {
        val downloaded = state.albumLocalStore.getDownloadedTrack(track.sourceId, track.ratingKey)
        if (downloaded != null && java.io.File(downloaded.localAudioPath).exists()) {
            val effectiveRating = track.userRating ?: downloaded.userRating
            return downloaded.toTrackStream().copy(userRating = effectiveRating)
        }
        return track
    }

    /** Pushes the honest engine observation into the 032 reporter (non-blocking). */
    private fun push032ObservationNow() {
        val current = state.miniPlayerState ?: return
        if (current.target != PlaybackTarget.LocalDevice) return
        val engine = state.localPlaybackEngine
        val track = current.tracks.getOrNull(engine.currentIndex) ?: return
        if (!is032Track(track)) return
        state.music032ReporterFor(track.sourceId)?.observe(
            localReporterPlayState(engine.isPlaying, engine.isPlayRequested, engine.isBuffering),
            engine.currentPositionMillis.coerceAtLeast(0L),
            engine.durationMillis ?: track.durationMillis ?: 0L,
        )
    }

    fun startLocalPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        startIndex: Int,
        initialPositionMillis: Long,
        continueChain: Boolean = false,
    ) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        // Only release a room this app is actually driving; a merely selected room may be
        // playing something the user started from another controller.
        // A restored (not yet loaded) session only REMEMBERS its room; we are not driving it.
        val handedOverSonosRoom = if (state.isRestoredSessionPending) null
        else sonosRoomToReleaseForLocalPlayback(state.miniPlayerState)
        val sonosPushJob = state.albumPlaybackJob
        // Retire in-flight Sonos requests first: their cancelled network calls must not
        // surface as errors once the local engine owns playback.
        state.invalidateInFlightPlaybackRequests()
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        if (handedOverSonosRoom != null) {
            // Sonos renders one pushed track at a time, so cancelling our push loop leaves
            // the speaker playing the current track and it would overlap the phone output.
            PlaybackForegroundService.stop(state.context)
            state.scope.launch {
                // Let the cancelled push unwind first: a playTrack() that returns after our
                // stop would make the speaker start over. Bounded, because the SOAP call is
                // a blocking socket read that cancellation cannot interrupt.
                withTimeoutOrNull(SONOS_HANDOVER_JOIN_TIMEOUT_MILLIS) {
                    runCatching { sonosPushJob?.cancelAndJoin() }
                }
                runCatching { state.sonosController.pause(handedOverSonosRoom) }
                runCatching { state.sonosController.stop(handedOverSonosRoom) }
            }
        }
        // Keep the active session aligned with the local engine after an output switch.
        state.selectPlaybackTarget(PlaybackTarget.LocalDevice)
        // Resolve downloaded tracks to local mirror stream items so that offline artwork & lyrics
        // are properly bound to MiniPlayerState even when offline.
        val resolvedTracks = tracks.map(::resolveTrackWithLocalDownload)

        val displayAlbum = resolvedTracks[safeIndex].resolvePlaybackAlbum(state.allAlbums, album)
        state.recordPlayedAlbum(displayAlbum)
        state.miniPlayerState = MiniPlayerState(
            album = displayAlbum,
            tracks = resolvedTracks,
            currentIndex = safeIndex,
            target = PlaybackTarget.LocalDevice,
            isPaused = false,
            isBuffering = true,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = resolvedTracks.getOrNull(safeIndex)?.durationMillis,
        )
        state.errorMessage = null
        state.isPlaybackCommandLoading = false
        state.scope.launch {
            rebuildEngineQueue(resolvedTracks, safeIndex, initialPositionMillis)
        }
        // A running sleep timer may still point at a Sonos room; local playback now owns it.
        SleepTimerController.attachRoom(null)
        // The Sonos reporting job's cleanup is job-identity guarded (P1-1), so the old
        // job will not stop its foreground notification when we take over: do it here.
        PlaybackForegroundService.stop(state.context)
        LocalPlaybackService.start(state.context)
        val initialTrack = resolvedTracks[safeIndex]
        state.actionMessage = "正在本机播放：${initialTrack.title}"
        // Explicit starts open a fresh chain; only a renderer switch (Sonos -> this
        // device) takes the open session over.
        sessionDriver.onExplicitStart(
            track = initialTrack,
            positionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = initialTrack.durationMillis ?: 0L,
            continueChain = continueChain,
        )
        startReporting()
    }


    /** Rebuilds the engine queue from the current display queue, applying the
     *  network-appropriate stream quality to every URL. */
    private suspend fun rebuildEngineQueue(
        tracks: List<PlexTrackStream>,
        startIndex: Int,
        startPositionMillis: Long,
    ) {
        val policy = state.currentLocalPlaybackPolicy()
        val items = withContext(Dispatchers.IO) { tracks.map { it.toLocalQueueItem(policy) } }
        if (state.miniPlayerState?.tracks !== tracks) return
        val engine = state.localPlaybackEngine
        engine.setPlaybackMode(state.playbackMode)
        updateCachedOriginalKeys(items)
        engine.setQueue(
            items,
            startIndex,
            startPositionMillis,
            policy.precacheCount.count,
        )
    }

    private fun PlexTrackStream.toLocalQueueItem(policy: LocalPlaybackPolicy): LocalQueueItem {
        // Tier 1: Check offline downloaded original file
        val downloaded = state.albumLocalStore.getDownloadedTrack(sourceId, ratingKey)
        if (downloaded != null && java.io.File(downloaded.localAudioPath).exists()) {
            val offlineTrack = downloaded.toTrackStream()
            val origKey = originalCacheKey(sourceId, ratingKey, partKey)
            return LocalQueueItem(
                url = offlineTrack.streamUrl,
                cacheKey = origKey,
                isTranscoded = false,
                title = offlineTrack.title,
                artist = offlineTrack.artistName,
                artworkUrl = offlineTrack.thumbUrl,
                durationMillis = offlineTrack.durationMillis,
                isCachedOriginal = true, // marks as offline/cached original, bypasses transcode & precache
            )
        }

        // Tier 2: Check SimpleCache
        val origKey = originalCacheKey(sourceId, ratingKey, partKey)
        val needsTranscode = willTranscodeLocally(policy)
        if (shouldUseCachedOriginal(needsTranscode, LocalAudioCache.isFullyCached(origKey))) {
            val direct = withLocalPlaybackPolicy(policy.copy(streamingLimit = LocalStreamingLimit.MAXIMUM), state.plexClientIdentifier)
            return direct.toQueueItem(origKey, isTranscoded = false).copy(
                isCachedOriginal = true,
                fallbackItem = toTranscodedLocalQueueItem(policy),
            )
        }

        // Tier 3: Remote Network stream
        val effective = withLocalPlaybackPolicy(policy, state.plexClientIdentifier)
        // Plex universal transcode 或 032 transcode.ogg 都是实时转码流。
        val isTranscoded = effective.streamUrl.isLiveTranscodeUrl()
        val key = if (isTranscoded) {
            transcodedCacheKey(sourceId, ratingKey, partKey, policy.opusBitrate.kbps)
        } else {
            origKey
        }
        return effective.toQueueItem(key, isTranscoded)
    }


    private fun PlexTrackStream.toTranscodedLocalQueueItem(policy: LocalPlaybackPolicy): LocalQueueItem {
        val effective = withLocalPlaybackPolicy(policy, state.plexClientIdentifier)
        return effective.toQueueItem(
            transcodedCacheKey(sourceId, ratingKey, partKey, policy.opusBitrate.kbps),
            isTranscoded = true,
        )
    }

    private fun PlexTrackStream.toQueueItem(cacheKey: String, isTranscoded: Boolean) = LocalQueueItem(
        url = streamUrl,
        cacheKey = cacheKey,
        isTranscoded = isTranscoded,
        title = title,
        artist = artistName,
        artworkUrl = thumbUrl,
        durationMillis = durationMillis,
    )

    private fun updateCachedOriginalKeys(items: List<LocalQueueItem>) {
        state.cachedOriginalTrackKeys = items.filter(LocalQueueItem::isCachedOriginal).mapTo(mutableSetOf()) { it.cacheKey }
    }

    /** Inserts tracks into the live engine queue so its indices stay aligned with
     *  [MiniPlayerState.tracks] after an enqueue. */
    fun insertIntoLocalQueue(insertIndex: Int, tracks: List<PlexTrackStream>) {
        val policy = state.currentLocalPlaybackPolicy()
        val resolvedTracks = tracks.map(::resolveTrackWithLocalDownload)
        state.scope.launch {
            val items = withContext(Dispatchers.IO) { resolvedTracks.map { it.toLocalQueueItem(policy) } }
            state.cachedOriginalTrackKeys = state.cachedOriginalTrackKeys + items.filter(LocalQueueItem::isCachedOriginal).map { it.cacheKey }
            state.localPlaybackEngine.insertQueueItems(insertIndex, items)
        }
    }


    fun toggleLocalPause(playerState: MiniPlayerState) {
        val engine = state.localPlaybackEngine
        if (engine.isPlaying) {
            engine.pause()
            state.miniPlayerState = state.miniPlayerState?.copy(isPaused = true, isBuffering = false)
            state.actionMessage = "已暂停播放"
        } else {
            // ExoPlayer ignores play() once the queue ran to STATE_ENDED — restart the
            // current track instead of leaving the button dead.
            if (engine.isEnded) {
                engine.playAt(playerState.currentIndex, 0L)
            } else {
                engine.resume()
            }
            state.miniPlayerState = state.miniPlayerState?.copy(
                isPaused = false,
                isBuffering = engine.isBuffering,
            )
            state.actionMessage = "已继续播放"
        }
    }

    fun seekLocal(playerState: MiniPlayerState, positionMillis: Long) {
        val durationMillis = playerState.durationMillis?.takeIf { it > 0L } ?: Long.MAX_VALUE
        val targetMillis = positionMillis.coerceIn(0L, durationMillis)
        val effectiveTargetMillis = state.localPlaybackEngine.seekTo(targetMillis)
        state.miniPlayerState = state.miniPlayerState
            ?.takeIf { it.target == PlaybackTarget.LocalDevice }
            ?.copy(currentPositionMillis = effectiveTargetMillis)
        playerState.tracks.getOrNull(playerState.currentIndex)
            ?.let { track ->
                sessionDriver.onSeek(
                    track = track,
                    positionMillis = effectiveTargetMillis,
                    durationMillis = playerState.durationMillis ?: track.durationMillis ?: 0L,
                )
            }
        state.actionMessage = "已跳转到 ${formatDuration(effectiveTargetMillis)}"
    }

    /**
     * Standard previous-button behavior: restarts the current track when it has been
     * playing for a while, otherwise jumps to the previous queue item (the engine's
     * seekToPrevious decides, and reports the resulting index synchronously).
     */
    fun playLocalPrevious(playerState: MiniPlayerState) {
        val engine = state.localPlaybackEngine
        engine.seekToPrevious()
        val newIndex = engine.currentIndex
        val track = playerState.tracks.getOrNull(newIndex) ?: return
        state.miniPlayerState = playerState.copy(
            album = track.resolvePlaybackAlbum(state.allAlbums, playerState.album),
            currentIndex = newIndex,
            isPaused = !engine.isPlayRequested,
            isBuffering = state.localPlaybackEngine.isBuffering,
            currentPositionMillis = state.localPlaybackEngine.currentPositionMillis,
            durationMillis = track.durationMillis,
        )
    }

    fun playLocalNext() {
        state.localPlaybackEngine.seekToNext()
    }

    fun playLocalIndex(index: Int, positionMillis: Long) {
        val playerState = state.miniPlayerState ?: return
        val safeIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        // Re-selecting the CURRENT item is an explicit new play from 0 on a fresh chain
        // (P2-A); plain pause/resume never goes through here and keeps its chain.
        if (safeIndex == playerState.currentIndex) {
            playerState.tracks.getOrNull(safeIndex)?.let { track ->
                sessionDriver.onExplicitReplaySameTrack(
                    track = track,
                    durationMillis = state.localPlaybackEngine.durationMillis ?: track.durationMillis ?: 0L,
                )
            }
        }
        state.localPlaybackEngine.playAt(safeIndex, positionMillis)
        val track = playerState.tracks.getOrNull(safeIndex) ?: return
        state.miniPlayerState = playerState.copy(
            album = track.resolvePlaybackAlbum(state.allAlbums, playerState.album),
            currentIndex = safeIndex,
            isPaused = false,
            isBuffering = state.localPlaybackEngine.isBuffering,
            currentPositionMillis = positionMillis.coerceAtLeast(0L),
            durationMillis = track.durationMillis,
        )
    }

    fun stopLocalPlayback() {
        state.localPlaybackEngine.stopPlayback()
        LocalPlaybackService.stop(state.context)
    }

    fun applyPlaybackMode(mode: PlaybackMode) {
        state.localPlaybackEngine.setPlaybackMode(mode)
    }

    private fun startReporting() {
        state.playbackReportingJob?.cancel()
        state.playbackReportingJob = state.scope.launch {
            val thisJob = coroutineContext.job
            // P3: the session this job owns is captured into a JOB-LOCAL variable during
            // the loop's life; the finally never reads shared driver state.
            var ownedSession: Pair<String, String>? = null
            try {
                val scrobbledTrackKeys = mutableSetOf<String>()
                var reportedTrack: PlexTrackStream? = null
                var lastReportedState: String? = null
                var lastTimelineReportAt = 0L

                while (isActive) {
                    delay(1_000)
                    val current = state.miniPlayerState ?: break
                    if (current.target != PlaybackTarget.LocalDevice) break
                    val engine = state.localPlaybackEngine
                    val index = engine.currentIndex.coerceIn(0, current.tracks.lastIndex)
                    val track = current.tracks.getOrNull(index) ?: break
                    val positionMillis = engine.currentPositionMillis
                    val durationMillis = engine.durationMillis ?: track.durationMillis
                    val isPaused = !engine.isPlayRequested
                    val isBuffering = engine.isBuffering
                    val playbackState = when {
                        engine.isEnded || engine.isIdleAfterError -> "stopped"
                        isPaused -> "paused"
                        isBuffering -> "buffering"
                        else -> "playing"
                    }
                    engine.recordDiagnosticHeartbeat(playbackState)

                    state.miniPlayerState = current.copy(
                        album = track.resolvePlaybackAlbum(state.allAlbums, current.album),
                        currentIndex = index,
                        isPaused = isPaused && !engine.isEnded,
                        isBuffering = isBuffering,
                        currentPositionMillis = positionMillis,
                        durationMillis = durationMillis,
                    )

                    val trackIs032 = is032Track(track)
                    if (reportedTrack != null &&
                        (reportedTrack!!.ratingKey != track.ratingKey || reportedTrack!!.sourceId != track.sourceId)
                    ) {
                        val finished = reportedTrack!!
                        // 032 transitions are closed by the engine transition callback with
                        // the old session's own position; the legacy path stays Plex-only.
                        if (!is032Track(finished)) {
                            reportTimelineSafelyLocal(finished, "stopped", positionMillis = 0L, finished.durationMillis, continuing = true)
                            scrobbleIfNeededLocal(scrobbledTrackKeys, finished, positionMillis, finished.durationMillis)
                        }
                        lastReportedState = null
                        lastTimelineReportAt = 0L
                    }
                    reportedTrack = track

                    if (trackIs032) {
                        // Non-blocking: the reporter actor merges the observation and
                        // repairs a missing session itself (no network on this loop).
                        sessionDriver.onPoll(
                            track = track,
                            enginePlaybackState = playbackState,
                            reporterState = localReporterPlayState(
                                engine.isPlaying, engine.isPlayRequested, engine.isBuffering,
                            ),
                            positionMillis = positionMillis.coerceAtLeast(0L),
                            durationMillis = durationMillis ?: 0L,
                        )
                        sessionDriver.captureOwnedSession()?.let { ownedSession = it }
                    } else {
                        val now = System.currentTimeMillis()
                        if (playbackState != lastReportedState || now - lastTimelineReportAt >= 10_000L) {
                            reportTimelineSafelyLocal(track, playbackState, positionMillis, durationMillis, continuing = false)
                            lastReportedState = playbackState
                            lastTimelineReportAt = now
                        }
                        scrobbleIfNeededLocal(scrobbledTrackKeys, track, positionMillis, durationMillis)
                    }
                }
            } finally {
                // P1-1: service teardown runs synchronously and only while this job is
                // still the active reporting job (a cancelled old job never stops the new
                // playback's service). P3: an inactive job sends no end — the superseding
                // play's own start ends or chains the old session. The bounded end is
                // fire-and-forget and never gates the teardown on network.
                val owned = ownedSession
                ReportingJobCleanup(
                    isActiveJob = { state.playbackReportingJob === thisJob },
                    stopPlaybackService = { LocalPlaybackService.stop(state.context) },
                    requestSessionEnd = {
                        owned?.let { (sourceId, sessionId) ->
                            state.music032ReporterFor(sourceId)?.requestEnd(
                                ReporterEndReason.Stopped,
                                expectedSessionId = sessionId,
                            )
                        }
                    },
                ).onJobFinished()
            }
        }
    }

    private suspend fun reportTimelineSafelyLocal(
        track: PlexTrackStream,
        stateName: String,
        positionMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        runCatching {
            state.clientFor(track.sourceId).reportTimeline(
                ratingKey = track.ratingKey,
                state = stateName,
                timeMillis = positionMillis.coerceAtLeast(0L),
                durationMillis = durationMillis,
                continuing = continuing,
            )
        }
    }

    private suspend fun scrobbleIfNeededLocal(
        scrobbledTrackKeys: MutableSet<String>,
        track: PlexTrackStream,
        positionMillis: Long,
        durationMillis: Long?,
    ) {
        val playbackKey = "${track.sourceId}:${track.ratingKey}"
        if (playbackKey in scrobbledTrackKeys) return
        val effectiveDuration = durationMillis?.takeIf { it > 0L } ?: return
        if (positionMillis < (effectiveDuration * 0.9f).toLong()) return
        runCatching {
            state.clientFor(track.sourceId).scrobble(track.ratingKey, positionMillis, durationMillis)
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
}
