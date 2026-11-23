package com.lux032.musicbridge

import android.util.Log
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032HttpException
import com.lux032.musicbridge.music.Music032SimilarTrack
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates 032 Similar Track Radio and Queue Auto-Continue across both LocalDevice
 * and Sonos playback targets.
 *
 * All async fetches are guarded by [generation] and live queue identity checks so stale
 * responses from an old queue or cancelled session never mutate the active queue.
 */
internal class AppStateSimilarRadioCoordinator(
    private val state: AppState,
) {
    private val generation = AtomicLong(0L)
    private var fetchJob: Job? = null
    /** Recently discarded/refreshed keys so "换一批" avoids immediately repeating the same songs. */
    private val dismissedKeys = LinkedHashSet<String>()

    private fun nextGeneration(): Long {
        fetchJob?.cancel()
        fetchJob = null
        return generation.incrementAndGet()
    }

    private fun music032ClientFor(sourceId: String): Music032Client? =
        runCatching { state.clientFor(sourceId) as? Music032Client }.getOrNull()

    fun canStartSimilarRadio(track: MusicTrack): Boolean {
        if (track.ratingKey.toLongOrNull()?.let { it > 0L } != true) return false
        val client = music032ClientFor(track.sourceId) ?: return false
        val caps = client.capabilities() ?: return true
        return caps.apiRevision >= 2 && caps.hasFeature("similarTracks")
    }

    fun isCurrentQueueSupported(): Boolean {
        val player = state.miniPlayerState ?: return false
        val current = player.tracks.getOrNull(player.currentIndex) ?: player.tracks.firstOrNull() ?: return false
        return canStartSimilarRadio(current)
    }

    /** Called when a normal (non-radio) playback queue starts or is restored. */
    fun onManualQueueStarted(tracks: List<MusicTrack>, startIndex: Int) {
        nextGeneration()
        dismissedKeys.clear()
        val firstTrack = tracks.getOrNull(startIndex) ?: tracks.firstOrNull()
        val method = firstTrack?.let { music032ClientFor(it.sourceId)?.capabilities()?.similarityMethod }
        state.similarRadioState = state.similarRadioState.copy(
            manualQueueCount = tracks.size,
            radioSeed = null,
            lastAutoSeedTitle = null,
            similarReasons = emptyMap(),
            isLoading = false,
            errorKind = null,
            similarityMethod = method ?: state.similarRadioState.similarityMethod,
        )
        maybeTriggerAutoContinue()
    }

    /** Called when the queue is completely stopped, cleared, or server switches. */
    fun resetForEmptyQueue() {
        nextGeneration()
        dismissedKeys.clear()
        state.similarRadioState = SimilarRadioState(
            autoPlayEnabled = state.similarRadioState.autoPlayEnabled,
        )
    }

    /** Called when the user manually clears everything except the currently playing track. */
    fun onClearedToCurrentTrack() {
        nextGeneration()
        state.similarRadioState = state.similarRadioState.copy(
            manualQueueCount = 1,
            radioSeed = null,
            lastAutoSeedTitle = null,
            similarReasons = emptyMap(),
            isLoading = false,
            errorKind = null,
        )
        maybeTriggerAutoContinue()
    }

    fun onManualTracksInserted(insertIndex: Int, insertedCount: Int, newQueueSize: Int) {
        val current = state.similarRadioState
        val boundary = current.manualQueueCount.coerceIn(0, newQueueSize - insertedCount)
        val updatedBoundary = if (insertIndex <= boundary) boundary + insertedCount else boundary
        state.similarRadioState = current.copy(
            manualQueueCount = updatedBoundary.coerceIn(0, newQueueSize),
        )
    }

    fun onQueueTrackMoved(fromIndex: Int, toIndex: Int, queueSize: Int) {
        val current = state.similarRadioState
        state.similarRadioState = current.copy(
            manualQueueCount = remapManualQueueCountAfterMove(
                manualQueueCount = current.manualQueueCount,
                fromIndex = fromIndex,
                toIndex = toIndex,
                queueSize = queueSize,
            ),
        )
    }

    fun onQueueTrackRemoved(removeIndex: Int, removedTrack: MusicTrack, newQueueSize: Int) {
        val current = state.similarRadioState
        if (removeIndex >= current.manualQueueCount) {
            rememberDismissed(listOf(removedTrack))
        }
        state.similarRadioState = current.copy(
            manualQueueCount = remapManualQueueCountAfterRemove(
                manualQueueCount = current.manualQueueCount,
                removeIndex = removeIndex,
                newQueueSize = newQueueSize,
            ),
        )
        maybeTriggerAutoContinue()
    }

    fun onQueueTrackRestored(insertIndex: Int, wasManual: Boolean, newQueueSize: Int) {
        val current = state.similarRadioState
        val updated = if (wasManual && insertIndex <= current.manualQueueCount) {
            current.manualQueueCount + 1
        } else {
            current.manualQueueCount
        }
        state.similarRadioState = current.copy(manualQueueCount = updated.coerceIn(0, newQueueSize))
    }

    fun setAutoPlayEnabled(enabled: Boolean, announceInQueue: Boolean = false) {
        state.appPreferences.saveAutoPlaySimilarEnabled(enabled)
        val current = state.similarRadioState
        if (enabled) {
            state.similarRadioState = current.copy(
                autoPlayEnabled = true,
                errorKind = null,
            )
            if (announceInQueue) {
                state.playbackCoordinator.notifyQueuePublic(Strings.queueAutoEnabledNotice)
            } else {
                state.actionMessage = Strings.queueAutoEnabledNotice
            }
            maybeTriggerAutoContinue()
        } else {
            nextGeneration()
            val player = state.miniPlayerState
            val trimmedManualCount = if (player != null) {
                val keepThrough = maxOf(current.manualQueueCount - 1, player.currentIndex)
                    .coerceIn(0, player.tracks.lastIndex.coerceAtLeast(0))
                if (keepThrough < player.tracks.lastIndex) {
                    state.playbackCoordinator.replaceQueueUpcomingAfter(keepThrough, emptyList())
                }
                keepThrough + 1
            } else {
                0
            }
            state.similarRadioState = current.copy(
                autoPlayEnabled = false,
                manualQueueCount = trimmedManualCount,
                radioSeed = null,
                lastAutoSeedTitle = null,
                isLoading = false,
                errorKind = null,
            )
            if (announceInQueue) {
                state.playbackCoordinator.notifyQueuePublic(Strings.queueAutoDisabledNotice)
            } else {
                state.actionMessage = Strings.queueAutoDisabledNotice
            }
        }
    }

    /**
     * Starts Track Radio from the currently playing song WITHOUT interrupting playback.
     * Keeps `[0..currentIndex]` intact and replaces everything after `currentIndex`.
     */
    fun startRadioFromCurrentTrack() {
        val player = state.miniPlayerState ?: return
        val currentTrack = player.tracks.getOrNull(player.currentIndex) ?: return
        if (!canStartSimilarRadio(currentTrack)) {
            state.errorMessage = Strings.similarRadioNoMatches
            return
        }
        val gen = nextGeneration()
        dismissedKeys.clear()
        val seed = SimilarRadioSeed(currentTrack.sourceId, currentTrack.ratingKey, currentTrack.title)
        state.appPreferences.saveAutoPlaySimilarEnabled(true)
        state.similarRadioState = state.similarRadioState.copy(
            autoPlayEnabled = true,
            isLoading = true,
            errorKind = null,
        )

        fetchJob = state.scope.launch {
            val result = fetchSimilarBatch(
                seedTrack = currentTrack,
                existingQueue = player.tracks.take(player.currentIndex + 1),
                limit = RADIO_INITIAL_BATCH_SIZE,
            )
            if (gen != generation.get()) return@launch
            val livePlayer = state.miniPlayerState ?: return@launch
            val liveCurrent = livePlayer.tracks.getOrNull(livePlayer.currentIndex)
            if (liveCurrent == null ||
                liveCurrent.sourceId != currentTrack.sourceId ||
                liveCurrent.ratingKey != currentTrack.ratingKey
            ) {
                state.similarRadioState = state.similarRadioState.copy(isLoading = false)
                return@launch
            }
            when (result) {
                is FetchOutcome.Success -> {
                    if (result.items.isEmpty()) {
                        state.similarRadioState = state.similarRadioState.copy(
                            isLoading = false,
                            errorKind = SimilarRadioErrorKind.Empty,
                            similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                        )
                        state.actionMessage = Strings.similarRadioNoMatches
                        return@launch
                    }
                    if (state.playbackMode == PlaybackMode.RepeatOne || state.playbackMode == PlaybackMode.RepeatAll) {
                        state.playbackCoordinator.updatePlaybackMode(PlaybackMode.Sequential)
                    }
                    val newTracks = result.items.map(Music032SimilarTrack::track)
                    val keepThrough = livePlayer.currentIndex
                    state.playbackCoordinator.replaceQueueUpcomingAfter(keepThrough, newTracks)
                    state.similarRadioState = state.similarRadioState.copy(
                        autoPlayEnabled = true,
                        manualQueueCount = keepThrough + 1,
                        radioSeed = seed,
                        lastAutoSeedTitle = currentTrack.title,
                        similarReasons = mergeReasons(state.similarRadioState.similarReasons, result.items),
                        isLoading = false,
                        errorKind = null,
                        similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                    )
                    val notice = Strings.similarRadioStartedCurrent(currentTrack.title, newTracks.size)
                    state.actionMessage = notice
                    state.playbackCoordinator.notifyQueuePublic(notice)
                }
                is FetchOutcome.Error -> {
                    state.similarRadioState = state.similarRadioState.copy(
                        isLoading = false,
                        errorKind = result.kind,
                    )
                    val msg = if (result.kind == SimilarRadioErrorKind.Busy) {
                        Strings.similarRadioBusy
                    } else {
                        Strings.similarRadioFailed
                    }
                    state.errorMessage = msg
                    state.playbackCoordinator.notifyQueuePublic(msg, isError = true)
                }
            }
        }
    }

    /**
     * Starts Track Radio from any track in an album/playlist/artist/search list:
     * plays [track] immediately, then appends similar tracks seamlessly.
     */
    fun startRadioFromTrack(track: MusicTrack, fallbackAlbum: MusicAlbum? = null) {
        if (!canStartSimilarRadio(track)) {
            state.errorMessage = Strings.similarRadioNoMatches
            return
        }
        if (state.playbackMode == PlaybackMode.RepeatOne || state.playbackMode == PlaybackMode.RepeatAll) {
            state.playbackCoordinator.updatePlaybackMode(PlaybackMode.Sequential)
        }
        val gen = nextGeneration()
        dismissedKeys.clear()
        state.appPreferences.saveAutoPlaySimilarEnabled(true)

        val album = track.resolvePlaybackAlbum(
            albums = state.allAlbums,
            fallback = fallbackAlbum ?: MusicAlbum(
                ratingKey = track.albumRatingKey ?: track.ratingKey,
                title = track.albumTitle ?: track.title,
                artistName = track.artistName,
                year = null,
                thumbUrl = track.thumbUrl,
                userRating = null,
                addedAtEpochSeconds = null,
                lastViewedAtEpochSeconds = null,
                section = LibrarySection("032", state.connectionPreferences.displayName, "artist"),
                sourceId = track.sourceId,
            ),
        )
        // Start playback immediately with the seed track so audio begins with zero delay.
        state.playbackCoordinator.startSingleTrackPlayback(
            album = album,
            tracks = listOf(track),
            trackIndex = 0,
            initialPositionMillis = 0L,
            preserveRadioState = true,
        )
        val seed = SimilarRadioSeed(track.sourceId, track.ratingKey, track.title)
        val method = music032ClientFor(track.sourceId)?.capabilities()?.similarityMethod
        state.similarRadioState = SimilarRadioState(
            autoPlayEnabled = true,
            manualQueueCount = 1,
            radioSeed = seed,
            lastAutoSeedTitle = track.title,
            similarReasons = emptyMap(),
            isLoading = true,
            errorKind = null,
            similarityMethod = method,
        )

        fetchJob = state.scope.launch {
            val result = fetchSimilarBatch(
                seedTrack = track,
                existingQueue = listOf(track),
                limit = RADIO_INITIAL_BATCH_SIZE,
            )
            if (gen != generation.get()) return@launch
            val livePlayer = state.miniPlayerState ?: return@launch
            val first = livePlayer.tracks.firstOrNull()
            if (first == null || first.sourceId != track.sourceId || first.ratingKey != track.ratingKey) {
                state.similarRadioState = state.similarRadioState.copy(isLoading = false)
                return@launch
            }
            when (result) {
                is FetchOutcome.Success -> {
                    if (result.items.isEmpty()) {
                        state.similarRadioState = state.similarRadioState.copy(
                            isLoading = false,
                            errorKind = SimilarRadioErrorKind.Empty,
                            similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                        )
                        state.actionMessage = Strings.similarRadioNoMatches
                        return@launch
                    }
                    val newTracks = result.items.map(Music032SimilarTrack::track)
                    state.playbackCoordinator.appendTracksToQueue(newTracks)
                    state.similarRadioState = state.similarRadioState.copy(
                        similarReasons = mergeReasons(state.similarRadioState.similarReasons, result.items),
                        isLoading = false,
                        errorKind = null,
                        similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                    )
                    val notice = Strings.similarRadioStartedTrack(track.title, newTracks.size)
                    state.actionMessage = notice
                    state.playbackCoordinator.notifyQueuePublic(notice)
                }
                is FetchOutcome.Error -> {
                    state.similarRadioState = state.similarRadioState.copy(
                        isLoading = false,
                        errorKind = result.kind,
                    )
                    val msg = if (result.kind == SimilarRadioErrorKind.Busy) {
                        Strings.similarRadioBusy
                    } else {
                        Strings.similarRadioFailed
                    }
                    state.errorMessage = msg
                    state.playbackCoordinator.notifyQueuePublic(msg, isError = true)
                }
            }
        }
    }

    /** Replaces unplayed auto-similar tracks with a fresh batch ("换一批"). */
    fun refreshAutoSimilarBatch() {
        val player = state.miniPlayerState ?: return
        val radio = state.similarRadioState
        if (!radio.autoPlayEnabled) return
        val keepThrough = maxOf(radio.manualQueueCount - 1, player.currentIndex)
            .coerceIn(0, player.tracks.lastIndex.coerceAtLeast(0))
        val seedTrack = resolveSeedForRefresh(player, keepThrough) ?: return
        val unplayedAuto = if (keepThrough + 1 < player.tracks.size) {
            player.tracks.subList(keepThrough + 1, player.tracks.size)
        } else {
            emptyList()
        }
        rememberDismissed(unplayedAuto)
        val gen = nextGeneration()
        state.similarRadioState = radio.copy(isLoading = true, errorKind = null)

        fetchJob = state.scope.launch {
            val result = fetchSimilarBatch(
                seedTrack = seedTrack,
                existingQueue = player.tracks,
                limit = AUTO_CONTINUE_BATCH_SIZE,
            )
            if (gen != generation.get()) return@launch
            val livePlayer = state.miniPlayerState ?: return@launch
            val liveKeepThrough = maxOf(state.similarRadioState.manualQueueCount - 1, livePlayer.currentIndex)
                .coerceIn(0, livePlayer.tracks.lastIndex.coerceAtLeast(0))
            when (result) {
                is FetchOutcome.Success -> {
                    if (result.items.isEmpty()) {
                        state.similarRadioState = state.similarRadioState.copy(
                            isLoading = false,
                            errorKind = SimilarRadioErrorKind.Empty,
                            similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                        )
                        state.playbackCoordinator.notifyQueuePublic(Strings.similarRadioNoMatches)
                        return@launch
                    }
                    val newTracks = result.items.map(Music032SimilarTrack::track)
                    state.playbackCoordinator.replaceQueueUpcomingAfter(liveKeepThrough, newTracks)
                    state.similarRadioState = state.similarRadioState.copy(
                        lastAutoSeedTitle = seedTrack.title,
                        similarReasons = mergeReasons(state.similarRadioState.similarReasons, result.items),
                        isLoading = false,
                        errorKind = null,
                        similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                    )
                    state.playbackCoordinator.notifyQueuePublic(Strings.similarAutoRefreshed(newTracks.size))
                }
                is FetchOutcome.Error -> {
                    state.similarRadioState = state.similarRadioState.copy(
                        isLoading = false,
                        errorKind = result.kind,
                    )
                    val msg = if (result.kind == SimilarRadioErrorKind.Busy) {
                        Strings.similarRadioBusy
                    } else {
                        Strings.similarRadioFailed
                    }
                    state.playbackCoordinator.notifyQueuePublic(msg, isError = true)
                }
            }
        }
    }

    /** Manually triggers appending one more batch ("再加一批" / retry button). */
    fun loadMoreAutoSimilarTracks() {
        val player = state.miniPlayerState ?: return
        if (!state.similarRadioState.autoPlayEnabled || state.similarRadioState.isLoading) return
        triggerFetchAndAppend(
            player = player,
            playFirstAppendedIfEnded = false,
            announce = true,
        )
    }

    /**
     * Proactively prefetches and appends similar tracks when the queue approaches its end.
     * When [playFirstAppendedIfEnded] is true (e.g. queue reached natural `STATE_ENDED`
     * before a prefetch could finish), automatically starts playing the first appended track.
     */
    fun maybeTriggerAutoContinue(playFirstAppendedIfEnded: Boolean = false): Boolean {
        val player = state.miniPlayerState ?: return false
        val radio = state.similarRadioState
        val eligible = shouldTriggerSimilarAutoContinue(
            autoPlayEnabled = radio.autoPlayEnabled,
            playbackMode = state.playbackMode,
            sleepStopAfterCurrent = SleepTimerController.state.value.stopAfterCurrentTrack,
            tracksSize = player.tracks.size,
            currentIndex = player.currentIndex,
            isLoading = radio.isLoading,
            remainingThreshold = AUTO_CONTINUE_THRESHOLD,
        )
        if (!eligible) return false
        val seed = selectRollingSimilarSeed(player.tracks, player.currentIndex, ::canStartSimilarRadio)
            ?: return false
        triggerFetchAndAppend(
            player = player,
            seedOverride = seed,
            playFirstAppendedIfEnded = playFirstAppendedIfEnded,
            announce = false,
        )
        return true
    }

    private fun triggerFetchAndAppend(
        player: MiniPlayerState,
        seedOverride: MusicTrack? = null,
        playFirstAppendedIfEnded: Boolean,
        announce: Boolean,
    ) {
        val seed = seedOverride
            ?: selectRollingSimilarSeed(player.tracks, player.currentIndex, ::canStartSimilarRadio)
            ?: return
        val expectedSourceId = seed.sourceId
        val gen = nextGeneration()
        state.similarRadioState = state.similarRadioState.copy(
            isLoading = true,
            errorKind = null,
        )
        fetchJob = state.scope.launch {
            val result = fetchSimilarBatch(
                seedTrack = seed,
                existingQueue = player.tracks,
                limit = AUTO_CONTINUE_BATCH_SIZE,
            )
            if (gen != generation.get()) return@launch
            val livePlayer = state.miniPlayerState ?: return@launch
            val liveSourceId = livePlayer.tracks.firstOrNull { it.sourceId.isNotBlank() }?.sourceId
            if (liveSourceId != null && liveSourceId != expectedSourceId) {
                state.similarRadioState = state.similarRadioState.copy(isLoading = false)
                return@launch
            }
            when (result) {
                is FetchOutcome.Success -> {
                    if (result.items.isEmpty()) {
                        state.similarRadioState = state.similarRadioState.copy(
                            isLoading = false,
                            errorKind = SimilarRadioErrorKind.Empty,
                            similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                        )
                        if (announce) state.playbackCoordinator.notifyQueuePublic(Strings.similarRadioNoMatches)
                        if (playFirstAppendedIfEnded) finishEndedLocalPlaybackIfStillStopped()
                        return@launch
                    }
                    val newTracks = result.items.map(Music032SimilarTrack::track)
                    val firstNewIndex = livePlayer.tracks.size
                    state.playbackCoordinator.appendTracksToQueue(
                        tracks = newTracks,
                        playIndexAfterAppend = if (playFirstAppendedIfEnded) firstNewIndex else null,
                    )
                    state.similarRadioState = state.similarRadioState.copy(
                        lastAutoSeedTitle = seed.title,
                        similarReasons = mergeReasons(state.similarRadioState.similarReasons, result.items),
                        isLoading = false,
                        errorKind = null,
                        similarityMethod = result.method ?: state.similarRadioState.similarityMethod,
                    )
                    if (announce) {
                        state.playbackCoordinator.notifyQueuePublic(Strings.similarAutoAppended(newTracks.size))
                    }
                }
                is FetchOutcome.Error -> {
                    state.similarRadioState = state.similarRadioState.copy(
                        isLoading = false,
                        errorKind = result.kind,
                    )
                    if (announce) {
                        val msg = if (result.kind == SimilarRadioErrorKind.Busy) {
                            Strings.similarRadioBusy
                        } else {
                            Strings.similarRadioFailed
                        }
                        state.playbackCoordinator.notifyQueuePublic(msg, isError = true)
                    }
                    if (playFirstAppendedIfEnded) finishEndedLocalPlaybackIfStillStopped()
                }
            }
        }
    }

    private fun finishEndedLocalPlaybackIfStillStopped() {
        val current = state.miniPlayerState ?: return
        if (current.target != PlaybackTarget.LocalDevice) return
        if (!state.localPlaybackEngine.isEnded) return
        state.localPlaybackEngine.rewindToStartAndPause()
        state.miniPlayerState = current.copy(
            isPaused = true,
            isBuffering = false,
            currentPositionMillis = 0L,
        )
        state.actionMessage = Strings.playbackEnded(current.album.title)
    }

    private fun resolveSeedForRefresh(player: MiniPlayerState, keepThrough: Int): MusicTrack? {
        val radioSeed = state.similarRadioState.radioSeed
        if (radioSeed != null) {
            player.tracks.firstOrNull {
                it.sourceId == radioSeed.sourceId && it.ratingKey == radioSeed.ratingKey
            }?.takeIf(::canStartSimilarRadio)?.let { return it }
        }
        val slice = player.tracks.take(keepThrough + 1)
        return selectRollingSimilarSeed(slice, player.currentIndex.coerceAtMost(slice.lastIndex), ::canStartSimilarRadio)
    }

    private fun rememberDismissed(tracks: List<MusicTrack>) {
        tracks.forEach { track ->
            dismissedKeys.add(similarTrackIdentityKey(track))
        }
        while (dismissedKeys.size > MAX_DISMISSED_HISTORY) {
            val first = dismissedKeys.iterator().next()
            dismissedKeys.remove(first)
        }
    }

    private sealed interface FetchOutcome {
        data class Success(val items: List<Music032SimilarTrack>, val method: String?) : FetchOutcome
        data class Error(val kind: SimilarRadioErrorKind) : FetchOutcome
    }

    private suspend fun fetchSimilarBatch(
        seedTrack: MusicTrack,
        existingQueue: List<MusicTrack>,
        limit: Int,
    ): FetchOutcome {
        val client = music032ClientFor(seedTrack.sourceId)
            ?: return FetchOutcome.Error(SimilarRadioErrorKind.OfflineOrFailed)
        val excludedKeys = buildSet {
            existingQueue.forEach { add(similarTrackIdentityKey(it)) }
            addAll(dismissedKeys)
        }
        val excludeIds = excludedKeys.asSequence()
            .filter { it.startsWith("${seedTrack.sourceId}:") }
            .mapNotNull { it.substringAfter(':').toLongOrNull() }
            .filter { it > 0L }
            .toList()

        return try {
            val caps = runCatching { client.ensureCapabilities() }.getOrNull() ?: client.capabilities()
            if (caps != null && (caps.apiRevision < 2 || !caps.hasFeature("similarTracks"))) {
                return FetchOutcome.Success(emptyList(), caps.similarityMethod)
            }
            val raw = client.fetchSimilarTracks(
                trackId = seedTrack.ratingKey,
                limit = (limit * 2).coerceIn(12, 40),
                excludeIds = excludeIds,
                diverse = true,
            )
            val filtered = filterAndDiversifySimilarTracks(
                candidates = raw,
                excludedTrackKeys = excludedKeys,
                maxPerArtist = 2,
                limit = limit,
            )
            FetchOutcome.Success(filtered, caps?.similarityMethod)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Music032HttpException) {
            Log.w(LOG_TAG, "fetchSimilarTracks failed status=${error.status}", error)
            when (error.status) {
                503 -> FetchOutcome.Error(SimilarRadioErrorKind.Busy)
                404 -> FetchOutcome.Success(emptyList(), client.capabilities()?.similarityMethod)
                else -> FetchOutcome.Error(SimilarRadioErrorKind.OfflineOrFailed)
            }
        } catch (error: Exception) {
            Log.w(LOG_TAG, "fetchSimilarTracks network/parse failure", error)
            FetchOutcome.Error(SimilarRadioErrorKind.OfflineOrFailed)
        }
    }

    private fun mergeReasons(
        existing: Map<String, List<String>>,
        incoming: List<Music032SimilarTrack>,
    ): Map<String, List<String>> {
        if (incoming.isEmpty()) return existing
        val merged = LinkedHashMap<String, List<String>>(existing)
        incoming.forEach { item ->
            if (item.reasons.isNotEmpty()) {
                merged[similarTrackIdentityKey(item.track)] = item.reasons
            }
        }
        while (merged.size > MAX_CACHED_REASONS) {
            val oldest = merged.keys.iterator().next()
            merged.remove(oldest)
        }
        return merged
    }

    private companion object {
        const val LOG_TAG = "SimilarRadio"
        const val RADIO_INITIAL_BATCH_SIZE = 15
        const val AUTO_CONTINUE_BATCH_SIZE = 12
        const val AUTO_CONTINUE_THRESHOLD = 2
        const val MAX_DISMISSED_HISTORY = 120
        const val MAX_CACHED_REASONS = 300
    }
}
