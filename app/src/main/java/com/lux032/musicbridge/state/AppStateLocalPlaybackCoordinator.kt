package com.lux032.musicbridge

import com.lux032.musicbridge.local.LocalAudioCache
import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.LocalPlaybackService
import com.lux032.musicbridge.local.LocalQueueItem
import com.lux032.musicbridge.local.originalCacheKey
import com.lux032.musicbridge.local.shouldUseCachedOriginal
import com.lux032.musicbridge.local.transcodedCacheKey
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.willTranscodeLocally
import com.lux032.musicbridge.plex.withLocalPlaybackPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    init {
        state.localPlaybackEngine.listener = object : LocalPlaybackEngine.Listener {
            override fun onTrackChanged(index: Int) {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    if (index == current.currentIndex) return@launch
                    val track = current.tracks.getOrNull(index) ?: return@launch
                    state.miniPlayerState = current.copy(
                        currentIndex = index,
                        isBuffering = state.localPlaybackEngine.isBuffering,
                        currentPositionMillis = state.localPlaybackEngine.currentPositionMillis,
                        durationMillis = track.durationMillis,
                    )
                    state.actionMessage = "正在播放：${track.title}"
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
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

            override fun onQueueEnded() {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    state.localPlaybackEngine.rewindToStartAndPause()
                    state.miniPlayerState = current.copy(
                        isPaused = true,
                        isBuffering = false,
                        currentPositionMillis = 0L,
                    )
                    state.actionMessage = "播放结束：${current.album.title}"
                }
            }

            override fun onTrackSkipped(index: Int) {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    val track = current.tracks.getOrNull(index) ?: return@launch
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

    fun startLocalPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        startIndex: Int,
        initialPositionMillis: Long,
    ) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        // Keep the active session aligned with the local engine after an output switch.
        state.selectPlaybackTarget(PlaybackTarget.LocalDevice)
        state.appPreferences.markAlbumPlayed(album.sourceId, album.ratingKey)
        state.recentPlayedAlbumKeys = state.appPreferences.loadRecentPlayedAlbumKeys(album.sourceId)
        state.miniPlayerState = MiniPlayerState(
            album = album,
            tracks = tracks,
            currentIndex = safeIndex,
            target = PlaybackTarget.LocalDevice,
            isPaused = false,
            isBuffering = true,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = tracks.getOrNull(safeIndex)?.durationMillis,
        )
        state.errorMessage = null
        state.isPlaybackCommandLoading = false
        state.scope.launch {
            rebuildEngineQueue(tracks, safeIndex, initialPositionMillis)
        }
        // A running sleep timer may still point at a Sonos room; local playback now owns it.
        SleepTimerController.attachRoom(null)
        LocalPlaybackService.start(state.context)
        val initialTrack = tracks[safeIndex]
        state.actionMessage = "正在本机播放：${initialTrack.title}"
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
        val origKey = originalCacheKey(sourceId, ratingKey, partKey)
        val needsTranscode = willTranscodeLocally(policy)
        if (shouldUseCachedOriginal(needsTranscode, LocalAudioCache.isFullyCached(origKey))) {
            val direct = withLocalPlaybackPolicy(policy.copy(streamingLimit = LocalStreamingLimit.MAXIMUM), state.plexClientIdentifier)
            return direct.toQueueItem(origKey, isTranscoded = false).copy(
                isCachedOriginal = true,
                fallbackItem = toTranscodedLocalQueueItem(policy),
            )
        }
        val effective = withLocalPlaybackPolicy(policy, state.plexClientIdentifier)
        val isTranscoded = effective.streamUrl.contains("/:/transcode/universal/start", ignoreCase = true)
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
        state.scope.launch {
            val items = withContext(Dispatchers.IO) { tracks.map { it.toLocalQueueItem(policy) } }
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
            currentIndex = newIndex,
            isPaused = false,
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
        state.localPlaybackEngine.playAt(safeIndex, positionMillis)
        val track = playerState.tracks.getOrNull(safeIndex) ?: return
        state.miniPlayerState = playerState.copy(
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
                        currentIndex = index,
                        isPaused = isPaused && !engine.isEnded,
                        isBuffering = isBuffering,
                        currentPositionMillis = positionMillis,
                        durationMillis = durationMillis,
                    )

                    if (reportedTrack != null &&
                        (reportedTrack!!.ratingKey != track.ratingKey || reportedTrack!!.sourceId != track.sourceId)
                    ) {
                        val finished = reportedTrack!!
                        reportTimelineSafelyLocal(finished, "stopped", positionMillis = 0L, finished.durationMillis, continuing = true)
                        scrobbleIfNeededLocal(scrobbledTrackKeys, finished, positionMillis, finished.durationMillis)
                        lastReportedState = null
                        lastTimelineReportAt = 0L
                    }
                    reportedTrack = track

                    val now = System.currentTimeMillis()
                    if (playbackState != lastReportedState || now - lastTimelineReportAt >= 10_000L) {
                        reportTimelineSafelyLocal(track, playbackState, positionMillis, durationMillis, continuing = false)
                        lastReportedState = playbackState
                        lastTimelineReportAt = now
                    }
                    scrobbleIfNeededLocal(scrobbledTrackKeys, track, positionMillis, durationMillis)
                }
            } finally {
                LocalPlaybackService.stop(state.context)
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
