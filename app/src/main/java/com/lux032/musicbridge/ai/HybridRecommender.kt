package com.lux032.musicbridge.ai

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.SonicCapableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

private val NORM_REGEX = Regex("[^\\p{L}\\p{N}]+")
private val CJK_RUN_REGEX = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]+")
private val LATIN_WORD_REGEX = Regex("[a-z0-9]+")
private const val MAX_SONIC_SEEDS = 5
private const val UNKNOWN_SONIC_DISTANCE = 5.0
private const val FALLBACK_DISTANCE_BASE = 10.0
private const val FALLBACK_DISTANCE_STEP = 0.001

internal data class SeedMatches(
    val directHits: List<PlexTrackStream>,
    val contextHits: List<PlexTrackStream>,
) {
    val all: List<PlexTrackStream>
        get() = (directHits + contextHits).distinctBy { it.ratingKey }
}

object HybridRecommender {
    private fun String.norm() = lowercase().replace(NORM_REGEX, " ").trim()

    private val commonStopWords = setOf(
        "the", "and", "for", "with", "this", "that", "from", "into", "your", "some",
        "song", "songs", "music", "playlist", "medley",
    )

    private fun contextTerms(values: List<String>): List<String> = values.flatMap { value ->
        val normalized = value.norm()
        val latinTerms = LATIN_WORD_REGEX.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 3 && it !in commonStopWords }
        val cjkTerms = CJK_RUN_REGEX.findAll(normalized).flatMap { match ->
            val run = match.value
            when {
                run.length < 2 -> emptySequence()
                else -> (0 until run.length - 1).asSequence().map { index -> run.substring(index, index + 2) }
            }
        }
        (latinTerms + cjkTerms).toList()
    }.distinct()

    internal fun matchSeedGroups(
        intent: AiPlaylistIntent,
        library: List<PlexTrackStream>,
        prompt: String = "",
    ): SeedMatches {
        val trackTerms = intent.seedTracks.map { it.norm() }.filter { it.isNotBlank() }
        val artistTerms = intent.seedArtists.map { it.norm() }.filter { it.isNotBlank() }
        val terms = contextTerms(intent.genres + prompt)
        val directHits = mutableListOf<PlexTrackStream>()
        val contextHits = mutableListOf<PlexTrackStream>()

        library.forEach { track ->
            val title = track.title.norm()
            val album = track.albumTitle.orEmpty().norm()
            val artist = track.artistName.orEmpty().norm()
            val directTrackMatch = trackTerms.any { term ->
                title.isNotBlank() && (title.contains(term) || (title.length >= 3 && term.contains(title)))
            }
            val directArtistMatch = artistTerms.any { term ->
                artist.isNotBlank() && (artist.contains(term) || (artist.length >= 3 && term.contains(artist)))
            }
            if (directTrackMatch || directArtistMatch) {
                directHits += track
            } else if (terms.any { term ->
                title.contains(term) || album.contains(term) || artist.contains(term)
            }) {
                contextHits += track
            }
        }
        return SeedMatches(
            directHits = directHits.distinctBy { it.ratingKey },
            contextHits = contextHits.distinctBy { it.ratingKey },
        )
    }

    fun matchSeeds(
        intent: AiPlaylistIntent,
        library: List<PlexTrackStream>,
        prompt: String = "",
    ): List<PlexTrackStream> = matchSeedGroups(intent, library, prompt).all

    internal fun deduplicateByBestDistance(candidates: List<RankedTrack>): List<RankedTrack> =
        candidates.groupBy { it.track.ratingKey }.values.map { matches ->
            matches.minWithOrNull(compareBy<RankedTrack> { it.distance ?: Double.MAX_VALUE })!!
        }

    fun arrange(candidates: List<RankedTrack>, limit: Int = 30): List<PlexTrackStream> {
        val remaining = deduplicateByBestDistance(candidates)
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
        val matches = matchSeedGroups(intent, library, prompt)
        val seeds = (matches.directHits + matches.contextHits).distinctBy { it.ratingKey }
        val ranked = mutableListOf<RankedTrack>()
        seeds.take(MAX_SONIC_SEEDS).forEach { seed ->
            ranked += RankedTrack(seed, 0.0)
            if (sonic != null) {
                runCatching { sonic.fetchNearestTracks(seed.ratingKey, limit) }
                    .getOrDefault(emptyList())
                    .forEach { ranked += RankedTrack(it.track, it.distance ?: UNKNOWN_SONIC_DISTANCE) }
            }
        }
        if (deduplicateByBestDistance(ranked).size < limit) {
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
