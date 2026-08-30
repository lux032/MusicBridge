package com.lux032.musicbridge.ai

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.SonicCapableSource
import com.lux032.musicbridge.plex.SonicTrackMatch
import com.lux032.musicbridge.plex.PlexPlaylist
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class AiPlaylistAlgorithmsTest {
    private fun track(
        key: String,
        title: String,
        artist: String? = null,
        analyzed: Boolean = false,
        album: String? = null,
    ) = PlexTrackStream(
        key, title, "url", "/part", null, null, artistName = artist, albumTitle = album,
        musicAnalysisVersion = if (analyzed) "1" else null,
    )

    private fun intent(
        artists: List<String> = emptyList(),
        tracks: List<String> = emptyList(),
        genres: List<String> = emptyList(),
    ) = AiPlaylistIntent("x", "", artists, tracks, genres, null, null, null)

    @Test fun parsesStructuredPromptResponse() {
        val result = AiPromptParser.parse("""```json
          {"title":"Night","description":"calm","seedArtists":["A"],"seedTracks":["Song"],"genres":["jazz"],"mood":"quiet","bpmMin":60,"bpmMax":90}
        ```""")
        assertEquals("Night", result.title)
        assertEquals(listOf("A"), result.seedArtists)
        assertEquals(60, result.bpmMin)
    }

    @Test fun regexFallbackHandlesLenientJsonContent() {
        val result = AiPromptParser.parse("""{"title":"Night","seedTracks":["A"],}""")
        assertEquals("Night", result.title)
        assertEquals(listOf("A"), result.seedTracks)
    }

    @Test fun matchesSeedsByNormalizedTitleAndArtist() {
        val library = listOf(track("1", "My-Song!", "Someone"), track("2", "Other", "The Artist"))
        assertEquals(listOf("1", "2"), HybridRecommender.matchSeeds(intent(listOf("artist"), listOf("my song")), library).map { it.ratingKey })
    }

    @Test fun matchesGenreAndPromptKeywordsAcrossTrackAlbumAndArtist() {
        val library = listOf(
            track("1", "Midnight Jazz", "Someone"),
            track("2", "Other", "Synthwave Pilot"),
            track("3", "Else", "Else", album = "Rain Stories"),
            track("4", "Unrelated", "Nobody"),
        )
        val result = HybridRecommender.matchSeeds(
            intent(genres = listOf("jazz")),
            library,
            prompt = "rain synthwave",
        )
        assertEquals(listOf("1", "2", "3"), result.map { it.ratingKey })
    }

    @Test fun libraryArtistContextIsInjectedIntoLlmRequest() {
        val client = AiLlmClient(AiSettings(enabled = true, apiKey = "secret"))
        val userContent = client.buildUserMessage("late night", listOf("周杰伦", "宇多田光", "周杰伦", " "))
        assertTrue(userContent.contains("late night"))
        assertTrue(userContent.contains("周杰伦"))
        assertTrue(userContent.contains("宇多田光"))
        assertEquals(1, "周杰伦".toRegex().findAll(userContent).count())
    }

    @Test fun blankArtistDoesNotMatchEveryArtistSeed() {
        val library = listOf(track("1", "Instrumental", null), track("2", "Rock Song", "Rock"))
        assertEquals(listOf("2"), HybridRecommender.matchSeeds(intent(artists = listOf("rock")), library).map { it.ratingKey })
    }

    @Test fun punctuationOnlyTitleDoesNotMatchEveryTrackSeed() {
        val library = listOf(track("1", "!!!", "A"), track("2", "Real Song", "B"))
        assertEquals(listOf("2"), HybridRecommender.matchSeeds(intent(tracks = listOf("real song")), library).map { it.ratingKey })
    }

    @Test fun emptyLibraryReturnsEmptyRecommendation() = runBlocking {
        assertTrue(HybridRecommender.recommend(intent(), emptyList(), null, emptyList()).isEmpty())
    }

    @Test fun sonicIsCalledEvenWhenCachedSeedHasNoAnalysisVersion() = runBlocking {
        val seed = track("1", "Seed", "A", analyzed = false)
        val neighbor = track("2", "Neighbor", "B")
        val calls = mutableListOf<String>()
        val sonic = object : SonicCapableSource {
            override suspend fun fetchNearestTracks(ratingKey: String, limit: Int): List<SonicTrackMatch> {
                calls += ratingKey
                return listOf(SonicTrackMatch(neighbor.ratingKey, 0.1, neighbor))
            }
            override suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String) = emptyList<PlexTrackStream>()
            override suspend fun createPlaylist(title: String, trackRatingKeys: List<String>) = PlexPlaylist("x", title, null, trackRatingKeys.size)
        }
        val result = HybridRecommender.recommend(intent(tracks = listOf("seed")), listOf(seed, neighbor), sonic, emptyList())
        assertEquals(listOf("1"), calls)
        assertTrue(result.map { it.ratingKey }.containsAll(listOf("1", "2")))
    }

    @Test fun sonicFailureFallsBackInsteadOfFailingGeneration() = runBlocking {
        val seed = track("1", "Seed", "A", analyzed = true)
        val fallback = track("2", "Fallback", "B")
        val failingSonic = object : SonicCapableSource {
            override suspend fun fetchNearestTracks(ratingKey: String, limit: Int): List<SonicTrackMatch> = error("404")
            override suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String) = emptyList<PlexTrackStream>()
            override suspend fun createPlaylist(title: String, trackRatingKeys: List<String>) = PlexPlaylist("x", title, null, trackRatingKeys.size)
        }
        val result = HybridRecommender.recommend(intent(tracks = listOf("seed")), listOf(seed, fallback), failingSonic, listOf(fallback))
        assertEquals(setOf("1", "2"), result.map { it.ratingKey }.toSet())
    }

    @Test fun fallbackVariesWithRandomSeedInsteadOfPinningFavoritesFirst() = runBlocking {
        val library = (1..12).map { track(it.toString(), "Track $it", "Artist $it") }
        val favorites = library.take(8)
        val first = HybridRecommender.recommend(intent(), library, null, favorites, limit = 6, random = Random(1))
        val second = HybridRecommender.recommend(intent(), library, null, favorites, limit = 6, random = Random(2))
        assertNotEquals(first.map { it.ratingKey }, second.map { it.ratingKey })
        assertNotEquals(favorites.take(6).map { it.ratingKey }, first.map { it.ratingKey })
    }

    @Test fun fallbackRankingPreservesShuffledInputOrderInsteadOfAlphabetizing() {
        val z = track("z", "Zulu", "A"); val a = track("a", "Alpha", "B")
        assertEquals(listOf("z", "a"), HybridRecommender.arrange(HybridRecommender.rankFallback(listOf(z, a))).map { it.ratingKey })
    }

    @Test fun sortsByDistanceDeduplicatesAndAvoidsConsecutiveArtist() {
        val a1 = track("1", "a1", "A"); val a2 = track("2", "a2", "A"); val b = track("3", "b", "B")
        val result = HybridRecommender.arrange(listOf(RankedTrack(a1, .1), RankedTrack(a1, .2), RankedTrack(a2, .2), RankedTrack(b, .3)))
        assertEquals(3, result.size)
        assertEquals(listOf("1", "3", "2"), result.map { it.ratingKey })
    }
}
