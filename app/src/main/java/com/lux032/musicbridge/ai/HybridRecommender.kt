package com.lux032.musicbridge.ai

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.SonicCapableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val NORM_REGEX = Regex("[^\\p{L}\\p{N}]+")
private const val MAX_SONIC_SEEDS = 5
private const val FALLBACK_DISTANCE_BASE = 10.0
private const val FALLBACK_DISTANCE_STEP = 0.001

object HybridRecommender {
    private fun String.norm() = lowercase().replace(NORM_REGEX, " ").trim()

    private val COMMON_STOP_WORDS = setOf("the", "and", "for", "with", "this", "that", "from", "into", "your", "some", "song", "songs", "music")

    fun matchSeeds(
        intent: AiPlaylistIntent,
        library: List<PlexTrackStream>,
        prompt: String = "",
    ): List<PlexTrackStream> {
        val trackTerms = intent.seedTracks.map { it.norm() }.filter { it.isNotBlank() }
        val artistTerms = intent.seedArtists.map { it.norm() }.filter { it.isNotBlank() }
        val genreTerms = intent.genres.flatMap { it.norm().split(' ') }.filter { it.length >= 2 }
        val promptTerms = prompt.norm().split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 && it !in COMMON_STOP_WORDS }
        val contextTerms = (genreTerms + promptTerms).distinct()

        val directHits = mutableListOf<PlexTrackStream>()
        val contextHits = mutableListOf<PlexTrackStream>()

        library.forEach { track ->
            val title = track.title.norm()
            val album = track.albumTitle.orEmpty().norm()
            val artist = track.artistName.orEmpty().norm()

            val isDirectHit = trackTerms.any { term -> title.isNotBlank() && (title.contains(term) || term.contains(title)) } ||
                artistTerms.any { term -> artist.isNotBlank() && (artist.contains(term) || term.contains(artist)) }
            if (isDirectHit) {
                directHits += track
            } else if (contextTerms.isNotEmpty() && contextTerms.any { term ->
                (title.isNotBlank() && title.contains(term)) ||
                    (album.isNotBlank() && album.contains(term)) ||
                    (artist.isNotBlank() && artist.contains(term))
            }) {
                contextHits += track
            }
        }
        return (directHits + contextHits).distinctBy { it.ratingKey }
    }

    fun arrange(candidates: List<RankedTrack>, limit: Int = 30): List<PlexTrackStream> {
        val remaining = candidates.distinctBy { it.track.ratingKey }
            .sortedWith(compareBy<RankedTrack> { it.distance ?: Double.MAX_VALUE }.thenBy { it.track.title.lowercase() })
            .toMutableList()
        val result = mutableListOf<PlexTrackStream>()
        val artistCounts = mutableMapOf<String, Int>()
        while (remaining.isNotEmpty() && result.size < limit) {
            val pickIndex = remaining.indexOfFirst { ranked ->
                val artist = ranked.track.artistName.orEmpty().lowercase()
                val previousArtist = result.lastOrNull()?.artistName.orEmpty().lowercase()
                artist != previousArtist && artistCounts.getOrDefault(artist, 0) < 3
            }.takeIf { it >= 0 } ?: 0
            val track = remaining.removeAt(pickIndex).track
            result += track
            val artist = track.artistName.orEmpty().lowercase()
            artistCounts[artist] = artistCounts.getOrDefault(artist, 0) + 1
        }
        return result
    }

    internal fun rankFallback(tracks: List<PlexTrackStream>): List<RankedTrack> =
        tracks.mapIndexed { index, track ->
            RankedTrack(track, FALLBACK_DISTANCE_BASE + index * FALLBACK_DISTANCE_STEP)
        }

    suspend fun recommend(
        intent: AiPlaylistIntent,
        library: List<PlexTrackStream>,
        sonic: SonicCapableSource?,
        favorites: List<PlexTrackStream>,
        limit: Int = 30,
        prompt: String = "",
        random: Random = Random.Default,
    ): List<PlexTrackStream> = withContext(Dispatchers.Default) {
        if (library.isEmpty()) return@withContext emptyList()
        val seeds = matchSeeds(intent, library, prompt)
        val ranked = mutableListOf<RankedTrack>()
        seeds.take(MAX_SONIC_SEEDS).forEach { seed ->
            ranked += RankedTrack(seed, 0.0)
            if (sonic != null) {
                runCatching { sonic.fetchNearestTracks(seed.ratingKey, limit) }
                    .getOrDefault(emptyList())
                    .forEach { ranked += RankedTrack(it.track, it.distance) }
            }
        }
        if (ranked.size < limit) {
            val related = library.filter { candidate ->
                seeds.any { seed ->
                    val sameArtist = !seed.artistName.isNullOrBlank() && seed.artistName == candidate.artistName
                    val sameAlbum = !seed.albumRatingKey.isNullOrBlank() && seed.albumRatingKey == candidate.albumRatingKey
                    sameArtist || sameAlbum
                }
            }
            val fallback = (related.shuffled(random) + favorites.shuffled(random) + library.shuffled(random))
                .distinctBy { it.ratingKey }
            ranked += rankFallback(fallback)
        }
        arrange(ranked, limit)
    }
}
