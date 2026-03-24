package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexClient
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.service.PlaybackForegroundService
import com.lux032.musicbridge.sonos.SonosDiscovery
import com.lux032.musicbridge.sonos.SonosPlaybackStatus
import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

internal class AppStatePlaybackCoordinator(
    private val state: AppState,
) {
    init {
        PlaybackForegroundService.onSleepTimerExpired = {
            state.albumPlaybackJob?.cancel()
            state.playbackReportingJob?.cancel()
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
        room: SonosRoom,
        initialPositionMillis: Long,
    ) {
        val targetTrack = tracks.getOrNull(trackIndex) ?: return
        val displayAlbum = resolveAlbumForTrack(targetTrack, album)
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        state.appPreferences.markAlbumPlayed(displayAlbum.ratingKey)
        state.recentPlayedAlbumKeys = state.appPreferences.loadRecentPlayedAlbumKeys()
        state.selectedSonosRoom = room
        state.volumeSyncKey += 1
        syncSleepTimerRoom(room)
        state.miniPlayerState = MiniPlayerState(
            album = displayAlbum,
            tracks = tracks,
            currentIndex = trackIndex,
            room = room,
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
                state.sonosController.playTrack(
                    room = room,
                    trackUrl = targetTrack.streamUrl,
                    title = targetTrack.title,
                    albumTitle = targetTrack.albumTitle ?: displayAlbum.title,
                )
                if (initialPositionMillis > 0L) {
                    state.sonosController.seek(room, (initialPositionMillis / 1_000L).toInt())
                }
            }.onSuccess {
                startPlaybackReporting(room, tracks, trackIndex, initialPositionMillis)
                state.actionMessage = "已将 ${targetTrack.title} 推送到 ${room.roomName}"
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isPlaybackCommandLoading = false
        }
    }

    fun startAlbumPlayback(
        albumTrackResult: PlexAlbumTracksResult,
        room: SonosRoom,
        startIndex: Int,
        initialPositionMillis: Long,
    ) {
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        state.appPreferences.markAlbumPlayed(albumTrackResult.album.ratingKey)
        state.recentPlayedAlbumKeys = state.appPreferences.loadRecentPlayedAlbumKeys()
        state.selectedSonosRoom = room
        state.volumeSyncKey += 1
        syncSleepTimerRoom(room)
        state.miniPlayerState = MiniPlayerState(
            album = albumTrackResult.album,
            tracks = albumTrackResult.tracks,
            currentIndex = startIndex,
            room = room,
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
                playAlbumSequentially(
                    sonosController = state.sonosController,
                    room = room,
                    trackResult = albumTrackResult,
                    startIndex = startIndex,
                    initialPositionMillis = initialPositionMillis,
                    getNextIndex = { currentIndex ->
                        resolveNextPlaybackIndex(albumTrackResult.tracks.size, currentIndex)
                    },
                    onTrackChanged = { index, track, startPositionMillis ->
                        if (!didStartFirstTrack) {
                            didStartFirstTrack = true
                            state.isPlaybackCommandLoading = false
                            startPlaybackReporting(room, albumTrackResult.tracks, index, startPositionMillis)
                        }
                        state.miniPlayerState = state.miniPlayerState?.copy(
                            currentIndex = index,
                            isPaused = false,
                            currentPositionMillis = startPositionMillis,
                            durationMillis = track.durationMillis,
                            room = room,
                        )
                        state.actionMessage = "正在连续播放：${track.title}"
                    },
                )
            }.onSuccess {
                state.actionMessage = "专辑播放结束：${albumTrackResult.album.title}"
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun refreshSonosRooms() {
        state.isSonosLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.sonosDiscoveryAttempted = true
        state.scope.launch {
            runCatching {
                SonosDiscovery(state.context).discoverRooms()
            }.onSuccess { rooms ->
                state.sonosRooms = rooms
                state.selectedSonosRoom = state.selectedSonosRoom
                    ?.let { current -> rooms.firstOrNull { room -> room.coordinatorUuid == current.coordinatorUuid } }
                    ?: rooms.firstOrNull()
                state.actionMessage = if (rooms.isEmpty()) {
                    "当前未发现 Sonos 房间"
                } else {
                    "已同步 ${rooms.size} 个 Sonos 房间"
                }
                state.volumeSyncKey += 1
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
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
        room: SonosRoom,
        shuffle: Boolean,
    ) {
        if (playlistResult.tracks.isEmpty()) return
        state.albumPlaybackJob?.cancel()
        state.playbackReportingJob?.cancel()
        state.selectedSonosRoom = room
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
        )
        val initialAlbum = tracks.firstOrNull()?.let { resolveAlbumForTrack(it, fakeAlbum) } ?: fakeAlbum
        state.miniPlayerState = MiniPlayerState(
            album = initialAlbum,
            tracks = tracks,
            currentIndex = 0,
            room = room,
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
                    getNextIndex = { currentIndex ->
                        when (state.playbackMode) {
                            PlaybackMode.Sequential -> if (currentIndex < tracks.lastIndex) currentIndex + 1 else null
                            PlaybackMode.RepeatAll -> (currentIndex + 1) % tracks.size
                            PlaybackMode.RepeatOne -> currentIndex
                            PlaybackMode.Shuffle -> Random.nextInt(tracks.size)
                        }
                    },
                    onTrackChanged = { index, track, positionMillis ->
                        if (!didStartFirstTrack) {
                            didStartFirstTrack = true
                            state.isPlaybackCommandLoading = false
                            startPlaybackReporting(room, tracks, index, positionMillis)
                        }
                        state.miniPlayerState = state.miniPlayerState?.copy(
                            album = resolveAlbumForTrack(track, fakeAlbum),
                            currentIndex = index,
                            currentPositionMillis = positionMillis,
                            durationMillis = track.durationMillis,
                        )
                    },
                )
            }.onFailure {
                state.errorMessage = it.message ?: "播放失败"
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun togglePause(playerState: MiniPlayerState) {
        state.isPlaybackCommandLoading = true
        state.scope.launch {
            runCatching {
                if (playerState.isPaused) {
                    state.sonosController.resume(playerState.room)
                } else {
                    state.sonosController.pause(playerState.room)
                }
            }.onSuccess {
                state.miniPlayerState = state.miniPlayerState?.copy(isPaused = !playerState.isPaused)
                state.actionMessage = if (playerState.isPaused) "已继续播放" else "已暂停播放"
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isPlaybackCommandLoading = false
        }
    }

    fun selectSonosRoom(room: SonosRoom) {
        state.selectedSonosRoom = room
        state.actionMessage = "已选择 Sonos 房间: ${room.roomName}"
        state.volumeSyncKey += 1
    }

    fun updatePlaybackMode(mode: PlaybackMode) {
        state.playbackMode = mode
        state.actionMessage = "播放模式已切换为：${mode.label}"
    }

    fun playQueueIndex(index: Int, positionMillis: Long, room: SonosRoom?) {
        val playerState = state.miniPlayerState ?: return
        val targetRoom = room ?: playerState.room
        val targetIndex = index.coerceIn(0, playerState.tracks.lastIndex)
        val currentTrackResult = state.trackResult?.takeIf { it.album.ratingKey == playerState.album.ratingKey }
        if (currentTrackResult != null) {
            startAlbumPlayback(currentTrackResult, targetRoom, targetIndex, positionMillis)
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
        val playerState = state.miniPlayerState ?: run {
            selectSonosRoom(room)
            return
        }
        if (room.coordinatorUuid == playerState.room.coordinatorUuid) {
            selectSonosRoom(room)
            return
        }
        playQueueIndex(playerState.currentIndex, playerState.currentPositionMillis, room)
        state.actionMessage = "正在切换到 ${room.roomName}"
    }

    fun seekPlayback(playerState: MiniPlayerState, positionMillis: Long) {
        val durationMillis = playerState.durationMillis?.takeIf { it > 0L } ?: return
        val targetMillis = positionMillis.coerceIn(0L, durationMillis)
        state.miniPlayerState = state.miniPlayerState?.takeIf { current ->
            current.room.coordinatorUuid == playerState.room.coordinatorUuid
        }?.copy(currentPositionMillis = targetMillis)

        state.isPlaybackCommandLoading = true
        state.scope.launch {
            runCatching {
                state.sonosController.seek(playerState.room, (targetMillis / 1_000L).toInt())
            }.onSuccess {
                state.actionMessage = "已跳转到 ${formatDuration(targetMillis)}"
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isPlaybackCommandLoading = false
        }
    }

    fun startSleepTimer(hours: Int, minutes: Int, room: SonosRoom) {
        val safeHours = hours.coerceIn(0, 23)
        val safeMinutes = minutes.coerceIn(0, 59)
        val totalMinutes = (safeHours * 60) + safeMinutes
        if (totalMinutes <= 0) {
            state.errorMessage = Strings.sleepTimerInvalidDuration
            return
        }
        val durationMillis = totalMinutes * 60_000L
        PlaybackForegroundService.setSleepTimer(
            context = state.context,
            room = room,
            durationMillis = durationMillis,
        )
        state.errorMessage = null
        state.actionMessage = "${Strings.sleepTimer} ${Strings.sleepTimerRemaining(formatSleepTimer(durationMillis))}"
    }

    fun cancelSleepTimer() {
        PlaybackForegroundService.cancelSleepTimer(state.context)
        state.errorMessage = null
        state.actionMessage = "${Strings.sleepTimer} ${Strings.sleepTimerOff}"
    }

    private fun startPlaybackReporting(
        room: SonosRoom,
        tracks: List<PlexTrackStream>,
        initialTrackIndex: Int,
        initialPositionMillis: Long = 0L,
    ) {
        val normalizedInitialIndex = initialTrackIndex.coerceIn(0, tracks.lastIndex)
        state.playbackReportingJob?.cancel()
        state.playbackReportingJob = state.scope.launch {
            val initialTrack = tracks.getOrNull(normalizedInitialIndex) ?: return@launch
            PlaybackForegroundService.onPlaybackAction = { action ->
                val playerState = state.miniPlayerState
                if (playerState != null) {
                    when (action) {
                        PlaybackForegroundService.PlaybackAction.Previous -> {
                            if (playerState.currentIndex > 0) playQueueIndex(playerState.currentIndex - 1, 0L, playerState.room)
                        }
                        PlaybackForegroundService.PlaybackAction.TogglePause -> togglePause(playerState)
                        PlaybackForegroundService.PlaybackAction.Next -> {
                            if (playerState.currentIndex < playerState.tracks.lastIndex) playQueueIndex(playerState.currentIndex + 1, 0L, playerState.room)
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
            val plexClient = state.client()
            val scrobbledTrackKeys = mutableSetOf<String>()
            var currentTrack = initialTrack
            var currentTrackTimeMillis = initialPositionMillis.coerceAtLeast(0L)
            var currentTrackDurationMillis = currentTrack.durationMillis
            var lastReportedState: String? = null
            var lastTimelineReportAt = 0L
            var stoppedPollCount = 0

            reportTimelineSafely(
                plexClient = plexClient,
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
                if (latestMiniPlayerState.room.coordinatorUuid != room.coordinatorUuid) break

                val status = runCatching { state.sonosController.getPlaybackStatus(room) }.getOrNull() ?: continue
                val resolvedTrack = resolveActiveTrack(tracks, latestMiniPlayerState, status) ?: continue
                val playbackState = status.toPlexPlaybackState()
                val positionMillis = ((status.relTimeSeconds ?: 0) * 1_000L).coerceAtLeast(0L)
                val durationMillis = resolvedTrack.durationMillis ?: status.trackDurationSeconds?.times(1_000L)

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

                state.miniPlayerState = latestMiniPlayerState.copy(
                    currentIndex = tracks.indexOfFirst { it.ratingKey == currentTrack.ratingKey }
                        .takeIf { it >= 0 } ?: latestMiniPlayerState.currentIndex,
                    isPaused = playbackState == "paused",
                    currentPositionMillis = currentTrackTimeMillis,
                    durationMillis = currentTrackDurationMillis,
                )

                PlaybackForegroundService.updateIfRunning(
                    title = currentTrack.title,
                    subtitle = currentTrack.artistName ?: room.roomName,
                    isPaused = playbackState == "paused",
                    artworkUrl = currentTrack.thumbUrl,
                )

                val now = System.currentTimeMillis()
                val shouldReportTimeline = playbackState != lastReportedState ||
                    now - lastTimelineReportAt >= 10_000L

                if (shouldReportTimeline) {
                    reportTimelineSafely(
                        plexClient = plexClient,
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
                        // Try to advance to next track instead of stopping
                        val currentIndex = tracks.indexOfFirst { it.ratingKey == currentTrack.ratingKey }
                            .takeIf { it >= 0 } ?: break
                        val nextIndex = resolveNextPlaybackIndex(tracks.size, currentIndex)
                        if (nextIndex == null || nextIndex == currentIndex) break
                        val nextTrack = tracks.getOrNull(nextIndex) ?: break
                        state.scope.launch {
                            playQueueIndex(nextIndex, 0L, room)
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

    private suspend fun reportTimelineSafely(
        plexClient: PlexClient,
        track: PlexTrackStream,
        stateName: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean,
    ) {
        runCatching {
            plexClient.reportTimeline(
                ratingKey = track.ratingKey,
                state = stateName,
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
            stateName = "stopped",
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

    private fun syncSleepTimerRoom(room: SonosRoom) {
        val timerState = PlaybackForegroundService.sleepTimerState.value
        if (!timerState.isActive) return
        PlaybackForegroundService.setSleepTimer(
            context = state.context,
            room = room,
            durationMillis = timerState.remainingMillis,
        )
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
            state.allAlbums.firstOrNull { album -> album.ratingKey == ratingKey }
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
