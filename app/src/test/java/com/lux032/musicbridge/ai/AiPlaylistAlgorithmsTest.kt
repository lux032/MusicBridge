package com.lux032.musicbridge.ai

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.SonicCapableSource
import com.lux032.musicbridge.plex.SonicTrackMatch
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.parsePlexTrackBehavior
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
        favorite: Boolean = false,
        viewCount: Long? = null,
        lastViewedAt: Long? = null,
        skipCount: Long? = null,
    ) = PlexTrackStream(
        key, title, "url", "/part", null, if (favorite) 10f else null, artistName = artist, albumTitle = album,
        musicAnalysisVersion = if (analyzed) "1" else null,
        viewCount = viewCount,
        lastViewedAtEpochSeconds = lastViewedAt,
        skipCount = skipCount,
    )

    private fun intent(
        artists: List<String> = emptyList(),
        tracks: List<String> = emptyList(),
        genres: List<String> = emptyList(),
    ) = AiPlaylistIntent("x", "", artists, tracks, genres, null, null, null)

    @Test fun parsesBehaviorFieldsAndTreatsMissingValuesAsNull() {
        val parsed = parsePlexTrackBehavior("12", "1700000000", "3", " 1 ")
        assertEquals(12L, parsed.viewCount)
        assertEquals(1700000000L, parsed.lastViewedAtEpochSeconds)
        assertEquals(3L, parsed.skipCount)
        assertEquals("1", parsed.musicAnalysisVersion)

        val empty = parsePlexTrackBehavior("", null, "invalid", " ")
        assertNull(empty.viewCount)
        assertNull(empty.lastViewedAtEpochSeconds)
        assertNull(empty.skipCount)
        assertNull(empty.musicAnalysisVersion)
    }

    @Test fun recentPlaybackIncreasesBehaviorMultiplier() {
        val now = 2_000_000_000L
        val recent = track("recent", "Recent", viewCount = 20, lastViewedAt = now - 3600)
        val older = track("older", "Older", viewCount = 20, lastViewedAt = now - 10 * 86400L)
        assertTrue(
            HybridRecommender.calculateBehaviorMultiplier(recent, now) >
                HybridRecommender.calculateBehaviorMultiplier(older, now)
        )
    }

    @Test fun rediscoveryRequiresHighPlaysOldNonNullTimestamp() {
        val now = 2_000_000_000L
        val rediscovery = track("r", "Rediscovery", viewCount = 10, lastViewedAt = now - 31 * 86400L)
        val noTimestamp = track("n", "No timestamp", viewCount = 10)
        val lowPlays = track("l", "Low plays", viewCount = 9, lastViewedAt = now - 31 * 86400L)
        val rediscoveryMultiplier = HybridRecommender.calculateBehaviorMultiplier(rediscovery, now)
        assertTrue(rediscoveryMultiplier < HybridRecommender.calculateBehaviorMultiplier(noTimestamp, now))
        assertTrue(rediscoveryMultiplier < HybridRecommender.calculateBehaviorMultiplier(lowPlays, now))
    }

    @Test fun coldStartTrackHasNeutralBehaviorMultiplier() {
        assertEquals(1.0, HybridRecommender.calculateBehaviorMultiplier(track("x", "New"), 2_000_000_000L), 0.0)
    }

    @Test fun deduplicationKeepsBestDistanceAndMergesFreshestBehavior() {
        val cached = track("x", "Cached", viewCount = 2, lastViewedAt = 100, skipCount = 1)
        val fresh = track("x", "Fresh", favorite = true, viewCount = 5, lastViewedAt = 200, skipCount = 3)
        val merged = HybridRecommender.deduplicateByBestDistance(
            listOf(RankedTrack(cached, 1.0), RankedTrack(fresh, 2.0))
        ).single()
        assertEquals(1.0, merged.distance!!, 0.0)
        assertEquals("Cached", merged.track.title)
        assertEquals(5L, merged.track.viewCount)
        assertEquals(200L, merged.track.lastViewedAtEpochSeconds)
        assertEquals(3L, merged.track.skipCount)
        assertTrue(merged.track.userRating!! > 0f)
    }

    @Test fun fallbackCannotOvertakeSonicAfterBehaviorReranking() {
        val now = 2_000_000_000L
        val sonic = track("sonic", "Sonic", lastViewedAt = now - 3600, skipCount = 100)
        val fallback = track("fallback", "Fallback", favorite = true, viewCount = 1000, lastViewedAt = now - 100 * 86400L)
        val arranged = HybridRecommender.arrange(
            listOf(RankedTrack(sonic, 7.4), RankedTrack(fallback, 20.0)),
            limit = 2,
            nowEpochSeconds = now,
        )
        assertEquals(listOf("sonic", "fallback"), arranged.map { it.ratingKey })
    }

    @Test fun extremeSonicDistanceIsClampedBeforeReranking() = runBlocking {
        val seed = track("seed", "Seed")
        val sonicTrack = track("sonic", "Sonic", lastViewedAt = 2_000_000_000L - 3600, skipCount = 100)
        val fallback = track("fallback", "Fallback", favorite = true, viewCount = 1000, lastViewedAt = 1)
        val sonic = object : SonicCapableSource {
            override suspend fun fetchNearestTracks(ratingKey: String, limit: Int) =
                listOf(SonicTrackMatch("sonic", 15.0, sonicTrack))
            override suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String) = emptyList<PlexTrackStream>()
            override suspend fun createPlaylist(title: String, trackRatingKeys: List<String>) = PlexPlaylist("x", title, null, trackRatingKeys.size)
        }
        val result = HybridRecommender.recommend(
            intent = intent(tracks = listOf("seed")),
            library = listOf(seed, fallback),
            sonic = sonic,
            favorites = listOf(fallback),
            limit = 3,
            random = Random(1),
        )
        assertTrue(result.indexOfFirst { it.ratingKey == "sonic" } < result.indexOfFirst { it.ratingKey == "fallback" })
    }

    @Test fun behaviorMultiplierClampsAndCapsIndividualSignals() {
        val now = 2_000_000_000L
        val strongestBonus = track("bonus", "Bonus", favorite = true, viewCount = Long.MAX_VALUE, lastViewedAt = now - 100 * 86400L)
        assertEquals(0.6, HybridRecommender.calculateBehaviorMultiplier(strongestBonus, now), 0.0)

        val recent = track("recent", "Recent", lastViewedAt = now)
        assertEquals(1.45, HybridRecommender.calculateBehaviorMultiplier(recent, now), 0.000001)

        val skipFour = track("skip4", "Skip 4", skipCount = 4)
        val skipHundred = track("skip100", "Skip 100", skipCount = 100)
        assertEquals(1.20, HybridRecommender.calculateBehaviorMultiplier(skipFour, now), 0.000001)
        assertEquals(
            HybridRecommender.calculateBehaviorMultiplier(skipFour, now),
            HybridRecommender.calculateBehaviorMultiplier(skipHundred, now),
            0.000001,
        )

        val viewCapReached = track("views", "Views", viewCount = 10_000)
        val moreViews = track("more", "More", viewCount = Long.MAX_VALUE)
        assertEquals(
            HybridRecommender.calculateBehaviorMultiplier(viewCapReached, now),
            HybridRecommender.calculateBehaviorMultiplier(moreViews, now),
            0.000001,
        )
    }

    @Test fun fresherBehaviorCanClearFavoriteRating() {
        val staleFavorite = track("x", "Stale", favorite = true, lastViewedAt = 100)
        val freshUnfavorite = track("x", "Fresh", lastViewedAt = 200)
        assertNull(HybridRecommender.mergeBehavior(staleFavorite, freshUnfavorite).userRating)
    }

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

    @Test fun cjkBigramsMatchTrackAlbumAndArtistContext() {
        val library = listOf(
            track("1", "安静钢琴曲", "演奏者"),
            track("2", "别的歌", "钢琴少年"),
            track("3", "无关", "其他", album = "静钢之夜"),
            track("4", "Unrelated", "Nobody"),
        )
        val result = HybridRecommender.matchSeeds(intent(), library, prompt = "安静钢琴")
        assertEquals(listOf("1", "2", "3"), result.map { it.ratingKey })
    }

    @Test fun directHitsArePrioritizedBeforeContextHitsForSonicSeeds() = runBlocking {
        val context = track("context", "Midnight Jazz", "Context Artist")
        val direct = track("direct", "Exact Seed", "Direct Artist")
        val calls = mutableListOf<String>()
        val sonic = object : SonicCapableSource {
            override suspend fun fetchNearestTracks(ratingKey: String, limit: Int): List<SonicTrackMatch> {
                calls += ratingKey
                return emptyList()
            }
            override suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String) = emptyList<PlexTrackStream>()
            override suspend fun createPlaylist(title: String, trackRatingKeys: List<String>) = PlexPlaylist("x", title, null, trackRatingKeys.size)
        }
        HybridRecommender.recommend(
            intent = intent(tracks = listOf("exact seed"), genres = listOf("jazz")),
            library = listOf(context, direct),
            sonic = sonic,
            favorites = emptyList(),
            limit = 1,
        )
        assertEquals("direct", calls.first())
        assertEquals(listOf("direct", "context"), calls)
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

    @Test fun duplicateTrackKeepsMinimumDistance() {
        val duplicate = track("1", "Duplicate", "A")
        val deduplicated = HybridRecommender.deduplicateByBestDistance(
            listOf(RankedTrack(duplicate, .8), RankedTrack(duplicate, .1), RankedTrack(duplicate, null))
        )
        assertEquals(1, deduplicated.size)
        assertEquals(.1, deduplicated.single().distance!!, 0.0)
    }

    @Test fun nullSonicDistanceRanksBeforeFallback() = runBlocking {
        val seed = track("seed", "Seed", "A")
        val neighbor = track("neighbor", "Neighbor", "B")
        val fallback = track("fallback", "Fallback", "C")
        val sonic = object : SonicCapableSource {
            override suspend fun fetchNearestTracks(ratingKey: String, limit: Int) =
                listOf(SonicTrackMatch(neighbor.ratingKey, null, neighbor))
            override suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String) = emptyList<PlexTrackStream>()
            override suspend fun createPlaylist(title: String, trackRatingKeys: List<String>) = PlexPlaylist("x", title, null, trackRatingKeys.size)
        }
        val result = HybridRecommender.recommend(
            intent(tracks = listOf("seed")), listOf(seed, neighbor, fallback), sonic, listOf(fallback), limit = 3, random = Random(1)
        )
        assertTrue(result.indexOfFirst { it.ratingKey == "neighbor" } < result.indexOfFirst { it.ratingKey == "fallback" })
    }

    @Test fun sortsByDistanceDeduplicatesAndAvoidsConsecutiveArtist() {
        val a1 = track("1", "a1", "A"); val a2 = track("2", "a2", "A"); val b = track("3", "b", "B")
        val result = HybridRecommender.arrange(listOf(RankedTrack(a1, .1), RankedTrack(a1, .2), RankedTrack(a2, .2), RankedTrack(b, .3)))
        assertEquals(3, result.size)
        assertEquals(listOf("1", "3", "2"), result.map { it.ratingKey })
    }
}
