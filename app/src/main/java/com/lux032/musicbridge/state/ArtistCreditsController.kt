package com.lux032.musicbridge

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032CreditTrack
import com.lux032.musicbridge.music.Music032HttpException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The open artist's backstage tracks (032): one role filter, paged. */
internal data class ArtistCredits(
    val sourceId: String = "",
    val artistId: String = "",
    /** Null shows every role. */
    val role: String? = null,
    val tracks: List<Music032CreditTrack> = emptyList(),
    /** Tracks matching [role]. */
    val total: Int = 0,
    /** Tracks of every role, for the header when the artist has no releases of their own. */
    val allTotal: Int = 0,
    val roles: List<Pair<String, Int>> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
) {
    val hasMore: Boolean get() = loaded && tracks.size < total
}

/**
 * Kept outside the page composable so returning from an album keeps the loaded list (and with
 * it the saved scroll position). Reset whenever another artist opens.
 */
internal class ArtistCreditsController(
    private val scope: CoroutineScope,
    private val clientFor: (String) -> Music032Client?,
) {
    var state by mutableStateOf(ArtistCredits())
        private set
    private var job: Job? = null

    fun reset() {
        job?.cancel()
        state = ArtistCredits()
    }

    /** Opens [artistId]'s credits unless they are already shown or loading. */
    fun open(sourceId: String, artistId: String) {
        val current = state
        if (current.sourceId == sourceId && current.artistId == artistId && (current.loaded || current.isLoading)) return
        fetch(ArtistCredits(sourceId, artistId), offset = 0)
    }

    fun selectRole(role: String?) {
        val current = state
        if (current.artistId.isEmpty() || current.role == role) return
        fetch(current.copy(role = role, tracks = emptyList(), total = 0, loaded = false), offset = 0)
    }

    fun loadMore() {
        val current = state
        if (current.hasMore && !current.isLoading) fetch(current, offset = current.tracks.size)
    }

    fun retry() {
        val current = state
        if (current.artistId.isNotEmpty() && !current.isLoading) fetch(current, offset = current.tracks.size)
    }

    private fun fetch(base: ArtistCredits, offset: Int) {
        val client = clientFor(base.sourceId) ?: return reset()
        job?.cancel()
        state = base.copy(isLoading = true, error = null)
        job = scope.launch {
            try {
                val page = client.fetchArtistCredits(base.artistId, base.role, PAGE_SIZE, offset)
                state = base.copy(
                    tracks = (if (offset == 0) emptyList() else base.tracks) + page.items,
                    total = page.total,
                    allTotal = if (base.role == null) page.total else base.allTotal,
                    roles = page.roles,
                    isLoading = false,
                    loaded = true,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Music032HttpException) {
                // Servers without the credits endpoint (or an artist merged away meanwhile): no group.
                state = if (error.status == 404) base.copy(isLoading = false, loaded = true)
                else base.copy(isLoading = false, error = Strings.creditsLoadFailed)
            } catch (error: Exception) {
                Log.d(TAG, "Credits page failed for artist ${base.artistId}", error)
                state = base.copy(isLoading = false, error = Strings.creditsLoadFailed)
            }
        }
    }

    private companion object {
        const val TAG = "ArtistCredits"
        const val PAGE_SIZE = 30
    }
}
