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
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.AlbumTracksResult
import com.lux032.musicbridge.music.PlaylistTracksResult
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.playbackUrlIdentity
import com.lux032.musicbridge.music.isFavorite
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


internal fun AppStatePlaybackCoordinator.startPlaybackReporting(
    room: SonosRoom,
    tracks: List<MusicTrack>,
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
                    PlaybackForegroundService.PlaybackAction.Previous -> playPrevious()
                    PlaybackForegroundService.PlaybackAction.TogglePause -> togglePause(playerState)
                    PlaybackForegroundService.PlaybackAction.Next -> {
                        if (playerState.currentIndex < playerState.tracks.lastIndex) playQueueIndex(playerState.currentIndex + 1, 0L, playerState.target)
                    }
                    is PlaybackForegroundService.PlaybackAction.Seek ->
                        seekPlayback(playerState, action.positionMillis)
                    is PlaybackForegroundService.PlaybackAction.SetVolume ->
                        playerState.sonosRoom?.let { applyVolumeChange(it, action.volume.toFloat(), announce = false) }
                    PlaybackForegroundService.PlaybackAction.ToggleFavorite ->
                        playerState.tracks.getOrNull(playerState.currentIndex)?.let(state::toggleTrackFavorite)
                }
            }
        }
        PlaybackForegroundService.start(
            context = state.context,
            title = initialTrack.title,
            subtitle = "正在推送到 ${room.roomName}",
            artworkUrl = initialTrack.thumbUrl ?: state.miniPlayerState?.album?.thumbUrl,
        )
        // 032 session owned by this reporting loop; the finally block may only end
        // exactly this session (a takeover chain installs a new id first).
        var owned032SessionId: String? = null
        var owned032SourceId: String? = null
        try {
        var currentTrack = initialTrack
        var currentTrackTimeMillis = initialPositionMillis.coerceAtLeast(0L)
        var currentTrackDurationMillis = currentTrack.durationMillis
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
                        context = currentTrack.playbackContext,
                    )
                } else {
                    reporter.requestStart(
                        trackId = trackId,
                        positionMillis = currentTrackTimeMillis,
                        durationMillis = currentTrackDurationMillis ?: 0L,
                        initialState = ReporterPlayState.Buffering,
                        context = currentTrack.playbackContext,
                    )
                }
                owned032SourceId = currentTrack.sourceId
            }
        }

        // 系统媒体卡把音量键交给 Sonos：先读一次房间音量，之后每 10 秒同步一次，
        // 这样在 Sonos App 或音箱上改过的音量也能反映到系统音量条上。
        var volumePollCountdown = 0
        while (true) {
            delay(1_000)

            val latestMiniPlayerState = state.miniPlayerState ?: break
            if (latestMiniPlayerState.sonosRoom?.coordinatorUuid != room.coordinatorUuid) break
            if (volumePollCountdown-- <= 0) {
                volumePollCountdown = AppStatePlaybackCoordinator.SESSION_VOLUME_POLL_TICKS
                if (state.volumeChangeJob?.isActive != true) {
                    runCatching { state.sonosController.getVolume(room) }
                        .onSuccess(PlaybackForegroundService::updateVolumeIfRunning)
                }
            }

            val currentQueue = latestMiniPlayerState.tracks
            if (isSonosQueueMutationInFlight) continue
            val status = runCatching { state.sonosController.getPlaybackStatus(room) }.getOrNull() ?: continue
            if (isSonosQueueMutationInFlight) continue
            // Queue mode: Sonos advances on its own; map its queue position back.
            val queueSession = sonosQueueFor(room, currentQueue)
            val queueIndex = queueSession?.let { resolveSonosQueueIndex(it.entries, status) }
            val resolvedTrack = queueIndex?.let(currentQueue::getOrNull)
                ?: resolveActiveTrack(currentQueue, latestMiniPlayerState, status) ?: continue
            val playbackState = status.toReportedPlaybackState()
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
                } else if (restartAgeMillis < AppStatePlaybackCoordinator.TRANSCODE_RESTART_STATE_GRACE_MILLIS) {
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
                    AppStatePlaybackCoordinator.LOG_TAG,
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
                    activeSonosPlan = AppStatePlaybackCoordinator.ActiveSonosPlan(entry.sourceId, entry.ratingKey, entry.kind, entry.url, entry.mime)
                }
                // A track change observed by this loop is an auto-advance or an
                // external controller switch: end the old 032 session with the real
                // last observed position — completed only near the end, otherwise
                // replaced, never a fabricated full-duration completion (P3).
                val nearEndForSleep = currentTrackDurationMillis?.let { duration ->
                    currentTrackTimeMillis >= (duration - 5_000L).coerceAtLeast(0L)
                } ?: false
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
                }
                if (hasObservedPlayback && nearEndForSleep && SleepTimerController.triggerIfStopAfterCurrentTrack()) {
                    break
                }

                currentTrack = resolvedTrack
                currentTrackTimeMillis = 0L
                currentTrackDurationMillis = durationMillis
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
                            context = currentTrack.playbackContext,
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
            if (!stoppedAtResetPosition &&
                isSameTrackRepeatWrap(currentTrackTimeMillis, positionMillis, currentTrackDurationMillis, playbackState)
            ) {
                val reporter = if (is032Source(currentTrack)) state.music032ReporterFor(currentTrack.sourceId) else null
                val trackId = currentTrack.ratingKey.toLongOrNull()
                if (reporter != null && trackId != null) {
                    // The completed play ends with the real observed final position.
                    reporter.requestEnd(
                        ReporterEndReason.Completed,
                        finalPositionMillis = currentTrackTimeMillis,
                    )
                    owned032SessionId = null
                    owned032SourceId = null
                }
                if (SleepTimerController.triggerIfStopAfterCurrentTrack()) {
                    break
                }
                if (reporter != null && trackId != null) {
                    // A loop restart is a genuinely new play (fresh chain).
                    reporter.requestStart(
                        trackId = trackId,
                        positionMillis = positionMillis,
                        durationMillis = currentTrackDurationMillis ?: 0L,
                        initialState = sonosReporterPlayState(playbackState),
                        context = currentTrack.playbackContext,
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
            val displayAlbum = currentTrack.resolvePlaybackAlbum(state.allAlbums, latestMiniPlayerState.album)
            if (playbackState == "playing") state.recordPlayedAlbum(displayAlbum)
            val updatedIndex = queueIndex ?: currentQueue.indexOfFirst { it.ratingKey == currentTrack.ratingKey && it.sourceId == currentTrack.sourceId }
                .takeIf { it >= 0 } ?: latestMiniPlayerState.currentIndex
            state.miniPlayerState = latestMiniPlayerState.copy(
                album = displayAlbum,
                currentIndex = updatedIndex,
                isPaused = effectivePaused,
                currentPositionMillis = currentTrackTimeMillis,
                durationMillis = currentTrackDurationMillis,
            )
            if (updatedIndex != latestMiniPlayerState.currentIndex) {
                state.similarRadioCoordinator.maybeTriggerAutoContinue()
            }

            // 收藏状态以队列里的曲目为准：toggleTrackFavorite 只更新 miniPlayerState。
            val queueTrack = state.miniPlayerState?.let { it.tracks.getOrNull(it.currentIndex) }
                ?.takeIf { it.ratingKey == currentTrack.ratingKey && it.sourceId == currentTrack.sourceId }
            PlaybackForegroundService.updateIfRunning(
                PlaybackForegroundService.PlaybackUpdate(
                    title = currentTrack.title,
                    // 系统媒体卡不显示远程设备名，把房间写进副标题（声音去哪儿要一眼可见）。
                    subtitle = listOfNotNull(currentTrack.artistName, room.roomName).joinToString(" · "),
                    isPaused = effectivePaused,
                    artworkUrl = currentTrack.thumbUrl ?: displayAlbum.thumbUrl,
                    positionMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                    isFavorite = (queueTrack ?: currentTrack).isFavorite,
                )
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
                            context = currentTrack.playbackContext,
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
                    }
                    if (trackFinished && SleepTimerController.triggerIfStopAfterCurrentTrack()) {
                        break
                    }
                    if (!autoAdvanceWhenStopped) continue

                    // Standalone queues do not have a sequential playback owner.
                    val currentIndex = currentQueue.indexOfFirst { it.ratingKey == currentTrack.ratingKey && it.sourceId == currentTrack.sourceId }
                        .takeIf { it >= 0 } ?: break
                    val nextIndex = resolveNextPlaybackIndex(currentQueue.size, currentIndex)
                    if (nextIndex == null) {
                        if (state.similarRadioCoordinator.maybeTriggerAutoContinue(playFirstAppendedIfEnded = true)) {
                            break
                        }
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

internal fun AppStatePlaybackCoordinator.resetFinishedSonosQueue(room: SonosRoom) {
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


private fun resolveActiveTrack(
    tracks: List<MusicTrack>,
    latestMiniPlayerState: MiniPlayerState,
    status: SonosPlaybackStatus,
): MusicTrack? {
    val normalizedCurrentUri = playbackUrlIdentity(status.currentTrackUri.orEmpty())
    if (normalizedCurrentUri.isNotBlank()) {
        tracks.firstOrNull { playbackUrlIdentity(it.streamUrl) == normalizedCurrentUri }?.let { return it }
    }
    return tracks.getOrNull(latestMiniPlayerState.currentIndex)
}


private fun SonosPlaybackStatus.toReportedPlaybackState(): String = when {
    transportState.equals("PLAYING", ignoreCase = true) -> "playing"
    transportState.equals("PAUSED_PLAYBACK", ignoreCase = true) -> "paused"
    transportState.equals("TRANSITIONING", ignoreCase = true) -> "buffering"
    else -> "stopped"
}

