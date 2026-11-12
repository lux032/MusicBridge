package com.lux032.musicbridge

import android.util.Log
import com.lux032.musicbridge.music.LyricsTranslationSettings
import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032LyricsCandidate
import com.lux032.musicbridge.music.Music032TrackLyrics
import com.lux032.musicbridge.music.MusicSourceClient
import com.lux032.musicbridge.lyrics.ParsedLyrics
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.lyrics.loadLyricsFromUrl
import com.lux032.musicbridge.lyrics.loadTrackLyrics
import kotlinx.coroutines.CancellationException

/** Lyrics of one track plus what the source lets the user do with them. */
internal data class TrackLyrics(
    val lyrics: ParsedLyrics,
    /** Non-null when the 032 server resolved the lyrics (source, manual pick, translation alignment). */
    val server: Music032TrackLyrics? = null,
    /** The server can search / bind / clear online lyrics for this track. */
    val canSearch: Boolean = false,
    /** The server has AI translation enabled. */
    val serverTranslation: Boolean = false,
) {
    /** AI translation, or NetEase's own Simplified Chinese translation (needs no server AI). */
    fun canTranslate(targetLanguage: String): Boolean =
        lyrics.lines.isNotEmpty() && server != null && (
            serverTranslation ||
                (server.source == Music032TrackLyrics.SOURCE_NETEASE && LyricsTranslationSettings.isSimplifiedChinese(targetLanguage))
            )

    companion object {
        val Empty = TrackLyrics(ParsedLyrics(emptyList(), isSynced = false))
    }
}

/**
 * Where a track's lyrics come from:
 * - 032 servers with `features.lyricsOnline`: the server resolves everything (local, cache,
 *   NetEase / LRCLIB, manual picks, translation). A downloaded copy is used when offline.
 * - Older 032 servers: server / downloaded lyrics, then the in-app NetEase / LRCLIB lookup.
 */
internal class TrackLyricsRepository(private val clientFor: (String) -> MusicSourceClient?) {
    private fun music032(track: MusicTrack): Music032Client? = clientFor(track.sourceId) as? Music032Client

    suspend fun load(track: MusicTrack): TrackLyrics {
        val client = music032(track)
        val caps = client?.let { runCatching { it.ensureCapabilities() }.getOrNull() }
        if (client == null || caps?.hasFeature(FEATURE_ONLINE) != true) {
            // Server unreachable while offline still falls back to downloaded lyrics here.
            return TrackLyrics(loadTrackLyrics(track))
        }
        val serverTranslation = caps.hasFeature(FEATURE_TRANSLATION)
        return try {
            val server = client.fetchLyrics(track.ratingKey)
            TrackLyrics(
                lyrics = server?.lyrics ?: ParsedLyrics(emptyList(), isSynced = false),
                server = server,
                canSearch = true,
                serverTranslation = serverTranslation,
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.d(TAG, "Server lyrics unavailable for ${track.ratingKey}", error)
            TrackLyrics(downloadedLyrics(track), canSearch = false)
        }
    }

    suspend fun translate(track: MusicTrack, targetLanguage: String): List<String> =
        requireClient(track).translateLyrics(track.ratingKey, targetLanguage)

    suspend fun search(track: MusicTrack, name: String, artist: String): List<Music032LyricsCandidate> =
        requireClient(track).searchLyrics(name, artist, track.durationMillis)

    suspend fun bind(track: MusicTrack, candidate: Music032LyricsCandidate) {
        requireClient(track).bindLyrics(track.ratingKey, candidate)
    }

    suspend fun clear(track: MusicTrack) = requireClient(track).clearLyrics(track.ratingKey)

    private fun requireClient(track: MusicTrack): Music032Client =
        music032(track) ?: error("Lyrics actions need a 032 Music Server")

    private suspend fun downloadedLyrics(track: MusicTrack): ParsedLyrics {
        val url = track.lyricUrl?.takeIf { it.startsWith("file:") } ?: return ParsedLyrics(emptyList(), isSynced = false)
        return runCatching { loadLyricsFromUrl(url) }.getOrDefault(ParsedLyrics(emptyList(), isSynced = false))
    }

    private companion object {
        const val TAG = "TrackLyrics"
        const val FEATURE_ONLINE = "lyricsOnline"
        const val FEATURE_TRANSLATION = "lyricsTranslation"
    }
}
