package com.lux032.musicbridge

import android.util.Log
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.MusicSourceClient
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.TrackCredit
import kotlinx.coroutines.CancellationException

/**
 * Backstage credits of a track (032 only), fetched on demand and shared by the More sheet and
 * the lyrics page. A failed lookup is an empty list: credits are optional decoration.
 */
internal class TrackCreditsRepository(private val clientFor: (String) -> MusicSourceClient?) {
    private val cache = object : LinkedHashMap<Pair<String, String>, List<TrackCredit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<String, String>, List<TrackCredit>>) =
            size > CACHE_SIZE
    }

    suspend fun load(track: MusicTrack): List<TrackCredit> {
        val client = clientFor(track.sourceId) as? Music032Client ?: return emptyList()
        val key = track.sourceId to track.ratingKey
        synchronized(cache) { cache[key] }?.let { return it }
        return try {
            client.fetchTrackCredits(track.ratingKey).also { synchronized(cache) { cache[key] = it } }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.d(TAG, "Credits unavailable for ${track.ratingKey}", error)
            emptyList()
        }
    }

    private companion object {
        const val TAG = "TrackCredits"
        const val CACHE_SIZE = 32
    }
}
