package com.lux032.musicbridge

import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.LocalQueueItem
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.withLocalStreamQuality
import com.lux032.musicbridge.service.PlaybackForegroundService
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Drives on-device playback through [LocalPlaybackEngine]. Mirrors the Sonos side of
 * [AppStatePlaybackCoordinator]: the engine owns rendering, this coordinator owns queue
 * state, Plex timeline reporting, scrobbling, and the foreground notification.
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
                        currentPositionMillis = 0L,
                        durationMillis = track.durationMillis,
                    )
                    state.actionMessage = "正在播放：${track.title}"
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    if (current.isPaused == !isPlaying) return@launch
                    state.miniPlayerState = current.copy(isPaused = !isPlaying)
                    val track = current.tracks.getOrNull(current.currentIndex) ?: return@launch
                    PlaybackForegroundService.updateIfRunning(
                        title = track.title,
                        subtitle = Strings.localPlayback,
                        isPaused = !isPlaying,
                        artworkUrl = track.thumbUrl,
                    )
                }
            }

            override fun onQueueEnded() {
                state.scope.launch {
                    val current = state.miniPlayerState ?: return@launch
                    if (current.target != PlaybackTarget.LocalDevice) return@launch
                    state.miniPlayerState = current.copy(isPaused = true)
                    state.actionMessage = "播放结束：${current.album.title}"
                }
            }

            override fun onPlayerError(message: String) {
                state.scope.launch {
                    if (state.miniPlayerState?.target != PlaybackTarget.LocalDevice) return@launch
                    state.errorMessage = message
                }
            }
        }
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
        state.appPreferences.markAlbumPlayed(album.sourceId, album.ratingKey)
        state.recentPlayedAlbumKeys = state.appPreferences.loadRecentPlayedAlbumKeys(album.sourceId)
        state.miniPlayerState = MiniPlayerState(
            album = album,
            tracks = tracks,
            currentIndex = safeIndex,
            target = PlaybackTarget.LocalDevice,
            isPaused = false,
            currentPositionMillis = initialPositionMillis.coerceAtLeast(0L),
            durationMillis = tracks.getOrNull(safeIndex)?.durationMillis,
        )
        state.errorMessage = null
        state.isPlaybackCommandLoading = false
        rebuildEngineQueue(tracks, safeIndex, initialPositionMillis)
        val initialTrack = tracks[safeIndex]
        PlaybackForegroundService.start(
            context = state.context,
            title = initialTrack.title,
            subtitle = Strings.localPlayback,
            isPaused = false,
            artworkUrl = initialTrack.thumbUrl,
        )
        state.actionMessage = "正在本机播放：${initialTrack.title}"
        startReporting()
    }

    /** Rebuilds the engine queue from the current display queue, applying the
     *  network-appropriate stream quality to every URL. */
    private fun rebuildEngineQueue(
        tracks: List<PlexTrackStream>,
        startIndex: Int,
        startPositionMillis: Long,
    ) {
        val quality = state.currentLocalStreamQuality()
        val engine = state.localPlaybackEngine
        engine.setPlaybackMode(state.playbackMode)
        engine.setQueue(
            tracks.map { it.toLocalQueueItem(quality) },
            startIndex,
            startPositionMillis,
        )
    }

    private fun PlexTrackStream.toLocalQueueItem(quality: LocalStreamQuality): LocalQueueItem {
        val effective = withLocalStreamQuality(quality, state.plexClientIdentifier)
        return LocalQueueItem(
            url = effective.streamUrl,
            title = effective.title,
            artist = effective.artistName,
            artworkUrl = effective.thumbUrl,
        )
    }

    /** Inserts tracks into the live engine queue so its indices stay aligned with
     *  [MiniPlayerState.tracks] after an enqueue. */
    fun insertIntoLocalQueue(insertIndex: Int, tracks: List<PlexTrackStream>) {
        val quality = state.currentLocalStreamQuality()
        state.localPlaybackEngine.insertQueueItems(
            insertIndex,
            tracks.map { it.toLocalQueueItem(quality) },
        )
    }

    fun toggleLocalPause(playerState: MiniPlayerState) {
        val engine = state.localPlaybackEngine
        if (engine.isPlaying) {
            engine.pause()
            state.miniPlayerState = state.miniPlayerState?.copy(isPaused = true)
            state.actionMessage = "已暂停播放"
        } else {
            // ExoPlayer ignores play() once the queue ran to STATE_ENDED — restart the
            // current track instead of leaving the button dead.
            if (engine.isEnded) {
                engine.playAt(playerState.currentIndex, 0L)
            } else {
                engine.resume()
            }
            state.miniPlayerState = state.miniPlayerState?.copy(isPaused = false)
            state.actionMessage = "已继续播放"
        }
    }

    fun seekLocal(playerState: MiniPlayerState, positionMillis: Long) {
        val durationMillis = playerState.durationMillis?.takeIf { it > 0L } ?: Long.MAX_VALUE
        val targetMillis = positionMillis.coerceIn(0L, durationMillis)
        state.localPlaybackEngine.seekTo(targetMillis)
        state.miniPlayerState = state.miniPlayerState
            ?.takeIf { it.target == PlaybackTarget.LocalDevice }
            ?.copy(currentPositionMillis = targetMillis)
        state.actionMessage = "已跳转到 ${formatDuration(targetMillis)}"
    }

    fun playLocalIndex(index: Int, positionMillis: Long) {
        val playerState = state.miniPlayerState ?: return
        val safeIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        state.localPlaybackEngine.playAt(safeIndex, positionMillis)
        val track = playerState.tracks.getOrNull(safeIndex) ?: return
        state.miniPlayerState = playerState.copy(
            currentIndex = safeIndex,
            isPaused = false,
            currentPositionMillis = positionMillis.coerceAtLeast(0L),
            durationMillis = track.durationMillis,
        )
    }

    fun stopLocalPlayback() {
        state.localPlaybackEngine.stopPlayback()
    }

    fun applyPlaybackMode(mode: PlaybackMode) {
        state.localPlaybackEngine.setPlaybackMode(mode)
    }

    private fun startReporting() {
        state.playbackReportingJob?.cancel()
        state.playbackReportingJob = state.scope.launch {
            PlaybackForegroundService.onPlaybackAction = { action ->
                val playerState = state.miniPlayerState
                if (playerState != null && playerState.target == PlaybackTarget.LocalDevice) {
                    when (action) {
                        PlaybackForegroundService.PlaybackAction.Previous -> {
                            if (playerState.currentIndex > 0) playLocalIndex(playerState.currentIndex - 1, 0L)
                        }
                        PlaybackForegroundService.PlaybackAction.TogglePause -> toggleLocalPause(playerState)
                        PlaybackForegroundService.PlaybackAction.Next -> {
                            if (playerState.currentIndex < playerState.tracks.lastIndex) {
                                playLocalIndex(playerState.currentIndex + 1, 0L)
                            }
                        }
                    }
                }
            }
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
                    val isPaused = !engine.isPlaying
                    val playbackState = when {
                        engine.isEnded -> "stopped"
                        isPaused -> "paused"
                        else -> "playing"
                    }

                    state.miniPlayerState = current.copy(
                        currentIndex = index,
                        isPaused = isPaused && !engine.isEnded,
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

                    PlaybackForegroundService.updateIfRunning(
                        title = track.title,
                        subtitle = Strings.localPlayback,
                        isPaused = isPaused,
                        artworkUrl = track.thumbUrl,
                    )

                    val now = System.currentTimeMillis()
                    if (playbackState != lastReportedState || now - lastTimelineReportAt >= 10_000L) {
                        reportTimelineSafelyLocal(track, playbackState, positionMillis, durationMillis, continuing = false)
                        lastReportedState = playbackState
                        lastTimelineReportAt = now
                    }
                    scrobbleIfNeededLocal(scrobbledTrackKeys, track, positionMillis, durationMillis)
                }
            } finally {
                PlaybackForegroundService.onPlaybackAction = null
                PlaybackForegroundService.stop(state.context)
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
        }
    }
}
