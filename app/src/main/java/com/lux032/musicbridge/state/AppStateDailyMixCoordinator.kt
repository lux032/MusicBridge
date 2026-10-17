package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032DailyMix
import com.lux032.musicbridge.music.Music032DailyMixDetail
import com.lux032.musicbridge.music.Music032DailyMixStatus
import com.lux032.musicbridge.music.Music032HttpException
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Home banner data. [status] null means the server has no daily mixes (hide the banner). */
data class DailyMixHomeState(
    val sourceId: String = "",
    val status: Music032DailyMixStatus? = null,
    val day: String = "",
    val items: List<Music032DailyMix> = emptyList(),
    val replacementsLeft: Int = 0,
) {
    val isGenerating: Boolean get() = status == Music032DailyMixStatus.Generating && items.isEmpty()
    val isVisible: Boolean get() = items.isNotEmpty() || isGenerating
}

/** The daily mix shown in the playlist detail page (its key is [dailyMixPlaylistKey]). */
data class DailyMixDetailState(
    val sourceId: String,
    val mix: Music032DailyMix,
    val exploreTrackIds: Set<String>,
    val blockedTrackIds: Set<String>,
    val replacementsLeft: Int,
    val isBusy: Boolean = false,
)

internal fun dailyMixPlaylistKey(id: Long) = "mix:$id"

/** Synthetic playlist so the mix reuses the playlist detail page and playback paths. */
internal fun dailyMixPlaylistResult(sourceId: String, detail: Music032DailyMixDetail): PlexPlaylistTracksResult {
    val mix = detail.mix
    val playlist = PlexPlaylist(
        ratingKey = dailyMixPlaylistKey(mix.id),
        title = mix.title,
        thumbUrl = mix.coverUrls.firstOrNull() ?: detail.tracks.firstOrNull()?.thumbUrl,
        leafCount = detail.tracks.size,
        sourceId = sourceId,
        description = mix.description,
    )
    return PlexPlaylistTracksResult("", playlist, detail.tracks)
}

/** Snackbar copy for a rating change (server design §6). */
internal fun dailyMixRatingMessage(value: Int, reasons: List<String>): String = when {
    value > 0 -> Strings.dailyMixLikedNotice
    value < 0 && "theme" in reasons -> Strings.dailyMixThemeBlockedNotice
    value < 0 -> Strings.dailyMixDislikedNotice
    else -> Strings.dailyMixRatingCleared
}

internal fun dailyMixErrorMessage(error: Throwable): String = when {
    error is Music032HttpException && error.code == "replacement_limit" -> Strings.dailyMixReplaceLimit
    error is Music032HttpException && error.code == "not_active" -> Strings.dailyMixNotActive
    error is Music032HttpException && error.code == "no_theme" -> Strings.dailyMixNoTheme
    error is Music032HttpException && error.status == 404 -> Strings.dailyMixNotActive
    error is java.io.IOException -> Strings.playlistNetworkError
    else -> Strings.dailyMixFailed(error.message)
}

/**
 * 032 daily mixes: loads today's set for the home banner (polling while the server generates),
 * opens a mix in the playlist page, and sends ratings, replacements, saves and track blocks.
 */
internal class AppStateDailyMixCoordinator(
    private val state: AppState,
) {
    private var loadJob: Job? = null
    private var lastLoadedAtMillis = 0L
    private val details = mutableMapOf<Long, Music032DailyMixDetail>()

    private fun activeClient(): Pair<String, Music032Client>? {
        val profile = state.connectionPreferences
        if (profile.type != MusicServerType.Music032 || !profile.hasCredentials()) return null
        val client = runCatching { state.clientFor(profile.id) }.getOrNull() as? Music032Client ?: return null
        return profile.id to client
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        lastLoadedAtMillis = 0L
        details.clear()
        state.dailyMixes = DailyMixHomeState()
        state.openDailyMix = null
    }

    /** Loads today's mixes; cheap to call on every home visit (throttled unless [force]). */
    fun load(force: Boolean = false) {
        val (sourceId, client) = activeClient() ?: run {
            if (state.dailyMixes.status != null) reset()
            return
        }
        val current = state.dailyMixes
        if (current.sourceId != sourceId) {
            reset()
        } else if (!force) {
            if (loadJob?.isActive == true) return
            val fresh = System.currentTimeMillis() - lastLoadedAtMillis < REFRESH_INTERVAL_MILLIS
            if (fresh && current.status != Music032DailyMixStatus.Generating) return
        }
        loadJob?.cancel()
        loadJob = state.scope.launch {
            val capabilities = runCatching { client.ensureCapabilities() }.getOrNull()
            if (capabilities != null && !capabilities.hasFeature("dailyMixes")) {
                if (state.connectionPreferences.id == sourceId) state.dailyMixes = DailyMixHomeState(sourceId)
                return@launch
            }
            var polls = 0
            while (isActive) {
                val result = try {
                    client.fetchDailyMixes()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    // 404: the server has no daily mixes. Other failures keep what is shown.
                    if (error is Music032HttpException && error.status == 404 && state.connectionPreferences.id == sourceId) {
                        state.dailyMixes = DailyMixHomeState(sourceId)
                    }
                    return@launch
                }
                if (state.connectionPreferences.id != sourceId) return@launch
                state.dailyMixes = DailyMixHomeState(sourceId, result.status, result.day, result.items, result.replacementsLeft)
                lastLoadedAtMillis = System.currentTimeMillis()
                if (result.status != Music032DailyMixStatus.Generating || ++polls > MAX_GENERATING_POLLS) break
                delay(GENERATING_POLL_MILLIS)
            }
        }
    }

    private suspend fun detail(client: Music032Client, id: Long, refresh: Boolean = false): Music032DailyMixDetail {
        if (!refresh) details[id]?.let { return it }
        return client.fetchDailyMix(id).also { details[id] = it }
    }

    fun open(mix: Music032DailyMix) {
        val (sourceId, client) = activeClient() ?: return
        state.scope.launch {
            state.isLoading = true
            try {
                show(sourceId, detail(client, mix.id, refresh = true), navigate = true)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.errorMessage = dailyMixErrorMessage(error)
            } finally {
                state.isLoading = false
            }
        }
    }

    fun play(mix: Music032DailyMix, shuffle: Boolean = false) {
        val (sourceId, client) = activeClient() ?: return
        state.scope.launch {
            try {
                val result = dailyMixPlaylistResult(sourceId, detail(client, mix.id))
                if (result.tracks.isEmpty()) return@launch
                state.startPlaylistPlayback(result, shuffle)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.notifyPlaylist(dailyMixErrorMessage(error), isError = true)
            }
        }
    }

    private fun show(sourceId: String, detail: Music032DailyMixDetail, navigate: Boolean) {
        val result = dailyMixPlaylistResult(sourceId, detail)
        state.aiPlaylistDescription = null
        state.openDailyMix = DailyMixDetailState(
            sourceId = sourceId,
            mix = detail.mix,
            exploreTrackIds = detail.exploreTrackIds,
            blockedTrackIds = detail.blockedTrackIds,
            replacementsLeft = state.dailyMixes.replacementsLeft,
        )
        state.selectedPlaylist = result.playlist
        state.playlistTrackResult = result
        if (navigate) state.navigateTo(AppSection.PlaylistDetail)
    }

    private fun updateMix(updated: Music032DailyMix) {
        val home = state.dailyMixes
        state.dailyMixes = home.copy(items = home.items.map { if (it.id == updated.id) updated else it })
        state.openDailyMix = state.openDailyMix?.let { if (it.mix.id == updated.id) it.copy(mix = updated) else it }
        details[updated.id]?.let { details[updated.id] = it.copy(mix = updated) }
    }

    /** Runs [block] for the open mix with the busy flag set; failures go to the snackbar. */
    private fun withOpenMix(block: suspend (DailyMixDetailState, Music032Client) -> Unit) {
        val open = state.openDailyMix ?: return
        if (open.isBusy) return
        val (sourceId, client) = activeClient() ?: return
        if (sourceId != open.sourceId) return
        state.openDailyMix = open.copy(isBusy = true)
        state.scope.launch {
            try {
                block(open, client)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.notifyPlaylist(dailyMixErrorMessage(error), isError = true)
            } finally {
                state.openDailyMix = state.openDailyMix?.copy(isBusy = false)
            }
        }
    }

    fun rate(value: Int, reasons: List<String> = emptyList()) = withOpenMix { open, client ->
        val updated = client.rateDailyMix(open.mix.id, value, reasons)
        updateMix(updated)
        state.notifyPlaylist(dailyMixRatingMessage(value, updated.ratingReasons))
    }

    fun replace() = withOpenMix { open, client ->
        val next = client.regenerateDailyMix(open.mix.id)
        val home = state.dailyMixes
        val left = (home.replacementsLeft - 1).coerceAtLeast(0)
        state.dailyMixes = home.copy(
            items = home.items.map { if (it.id == open.mix.id) next else it },
            replacementsLeft = left,
        )
        details.remove(open.mix.id)
        val detail = detail(client, next.id, refresh = true)
        if (state.openDailyMix?.mix?.id == open.mix.id) show(open.sourceId, detail, navigate = false)
        state.openDailyMix = state.openDailyMix?.copy(replacementsLeft = left)
        state.notifyPlaylist(Strings.dailyMixReplaced(next.title))
        load(force = true)
    }

    fun save() = withOpenMix { open, client ->
        val playlist = client.saveDailyMix(open.mix.id)
        updateMix(open.mix.copy(savedPlaylistId = playlist.ratingKey.toLongOrNull()))
        state.loadPlaylists()
        state.notifyPlaylist(Strings.dailyMixSaved(playlist.title), Strings.viewPlaylist) {
            state.openPlaylistDetail(playlist)
        }
    }

    fun toggleBlock(track: PlexTrackStream) {
        val open = state.openDailyMix ?: return
        setBlock(track, track.ratingKey !in open.blockedTrackIds)
    }

    private fun setBlock(track: PlexTrackStream, block: Boolean) {
        val open = state.openDailyMix ?: return
        val (sourceId, client) = activeClient() ?: return
        if (sourceId != open.sourceId) return
        state.scope.launch {
            try {
                client.setRecommendationBlock(track.ratingKey, block)
                state.openDailyMix = state.openDailyMix?.let {
                    it.copy(blockedTrackIds = if (block) it.blockedTrackIds + track.ratingKey else it.blockedTrackIds - track.ratingKey)
                }
                details[open.mix.id]?.let { cached ->
                    details[open.mix.id] = cached.copy(
                        blockedTrackIds = if (block) cached.blockedTrackIds + track.ratingKey else cached.blockedTrackIds - track.ratingKey,
                    )
                }
                state.notifyPlaylist(
                    if (block) Strings.recommendationBlocked(track.title) else Strings.recommendationRestored(track.title),
                    Strings.undo,
                ) { setBlock(track, !block) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.notifyPlaylist(dailyMixErrorMessage(error), isError = true)
            }
        }
    }

    private companion object {
        const val REFRESH_INTERVAL_MILLIS = 10 * 60 * 1000L
        const val GENERATING_POLL_MILLIS = 5_000L
        const val MAX_GENERATING_POLLS = 24
    }
}
