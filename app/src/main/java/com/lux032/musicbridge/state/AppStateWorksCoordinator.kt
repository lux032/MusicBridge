package com.lux032.musicbridge

import com.lux032.musicbridge.music.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Read-only work browsing; never fabricates editable playlists or changes the active source. */
internal class AppStateWorksCoordinator(private val state: AppState) {
    private var homeJob: Job? = null
    private var loadedAt = 0L
    private var generation = 0L

    fun reset() {
        generation++
        homeJob?.cancel()
        loadedAt = 0L
        state.homeWorks = Music032WorkRotation()
        state.homeWorksSourceId = ""
        state.isWorksLoading = false
        state.worksError = null
    }

    private fun client(sourceId: String) = state.clientFor(sourceId) as? Music032Client

    suspend fun trackWorks(track: MusicTrack): List<Music032TrackWork> {
        val client = client(track.sourceId) ?: return emptyList()
        val caps = client.ensureCapabilities() ?: return emptyList()
        if (!caps.hasFeature("trackWorks")) return emptyList()
        return client.fetchTrackWorks(track.ratingKey)
    }

    suspend fun info(sourceId: String, id: Long) = requireNotNull(client(sourceId)).fetchWorkInfo(id)
    suspend fun detail(sourceId: String, id: Long) = requireNotNull(client(sourceId)).fetchWork(id)
    suspend fun series(sourceId: String, id: Long): Music032WorkSeries? {
        val client = client(sourceId) ?: return null
        if (client.ensureCapabilities()?.hasFeature("series") != true) return null
        return client.fetchWorkSeries(id)
    }

    fun loadHome(next: Boolean = false, force: Boolean = false) {
        val profile = state.connectionPreferences
        if (profile.type != MusicServerType.Music032 || !profile.hasCredentials()) {
            reset()
            return
        }
        if (state.homeWorksSourceId != profile.id) reset()
        if (homeJob?.isActive == true) return
        if (!next && !force && System.currentTimeMillis() - loadedAt < 600_000) return
        val client = client(profile.id) ?: return
        val ticket = generation
        state.homeWorksSourceId = profile.id
        state.isWorksLoading = true
        state.worksError = null
        homeJob = state.scope.launch {
            try {
                if (client.ensureCapabilities()?.hasFeature("home") != true) return@launch
                val old = state.homeWorks
                val result = if (next) client.fetchHomeWorks(old.batch + 1, old.generation) else client.fetchHomeWorks()
                if (generation != ticket || state.connectionPreferences.id != profile.id) return@launch
                state.homeWorks = result
                loadedAt = System.currentTimeMillis()
                if (result.renewed) state.notifyPlaylist(Strings.workRotationRenewed)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (generation == ticket) state.worksError = Strings.workLoadFailed(error.message)
            } finally {
                if (generation == ticket) state.isWorksLoading = false
            }
        }
    }

    fun open(sourceId: String, id: Long) {
        if (id <= 0) return
        state.pushWorkEntry(sourceId, id)
    }

    fun play(sourceId: String, work: Music032Work, shuffle: Boolean = false, series: Boolean = false) {
        val ticket = generation
        state.scope.launch {
            try {
                val client = client(sourceId) ?: return@launch
                val tracks = if (series) {
                    if (client.ensureCapabilities()?.hasFeature("seriesTracks") != true) {
                        state.notifyPlaylist(Strings.workSeriesUnavailable, isError = true)
                        return@launch
                    }
                    client.fetchSeriesTracks(work.seriesId ?: return@launch)
                } else client.fetchWorkTracks(work.id).uniqueStreams()
                if (generation != ticket) return@launch
                if (tracks.isEmpty()) {
                    state.notifyPlaylist(Strings.workNoTracks)
                    return@launch
                }
                val title = if (series) work.seriesTitle else work.title
                state.startPlaylistPlayback(PlaylistTracksResult("", MusicPlaylist(
                    ratingKey = "${if (series) "series" else "work"}:${if (series) work.seriesId else work.id}",
                    title = title, thumbUrl = work.artworkUrl, leafCount = tracks.size, sourceId = sourceId,
                ), tracks), shuffle)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                state.notifyPlaylist(Strings.workLoadFailed(error.message), isError = true)
            }
        }
    }
}
