package com.lux032.musicbridge

import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.Music032SimilarTrack
import com.lux032.musicbridge.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimilarRadioLogicTest {

    private fun track(id: Long, artist: String = "Artist", artistId: String = "1", sourceId: String = "s1"): MusicTrack =
        MusicTrack(
            ratingKey = id.toString(),
            title = "Track $id",
            streamUrl = "https://example.com/api/v1/tracks/$id/stream",
            partKey = "/api/v1/tracks/$id/stream",
            durationMillis = 200_000L,
            userRating = null,
            artistName = artist,
            sourceId = sourceId,
            artists = listOf(ArtistRef(artistId, artist)),
        )

    private fun similar(id: Long, artist: String, artistId: String, score: Double = 0.8): Music032SimilarTrack =
        Music032SimilarTrack(
            track = track(id, artist = artist, artistId = artistId),
            distance = 1.0 - score,
            score = score,
            reasons = listOf("soundsSimilar"),
        )

    @Test
    fun shouldTriggerRespectsLoopAndSleepTimerPrecedence() {
        // Near end (1 remaining) in Sequential mode -> triggers.
        assertTrue(
            shouldTriggerSimilarAutoContinue(
                autoPlayEnabled = true,
                playbackMode = PlaybackMode.Sequential,
                sleepStopAfterCurrent = false,
                tracksSize = 5,
                currentIndex = 3,
                isLoading = false,
            ),
        )
        // Far from end (3 remaining) -> does not trigger yet.
        assertFalse(
            shouldTriggerSimilarAutoContinue(
                autoPlayEnabled = true,
                playbackMode = PlaybackMode.Sequential,
                sleepStopAfterCurrent = false,
                tracksSize = 5,
                currentIndex = 1,
                isLoading = false,
            ),
        )
        // RepeatOne & RepeatAll block auto-continue.
        assertFalse(
            shouldTriggerSimilarAutoContinue(
                autoPlayEnabled = true,
                playbackMode = PlaybackMode.RepeatOne,
                sleepStopAfterCurrent = false,
                tracksSize = 2,
                currentIndex = 1,
                isLoading = false,
            ),
        )
        assertFalse(
            shouldTriggerSimilarAutoContinue(
                autoPlayEnabled = true,
                playbackMode = PlaybackMode.RepeatAll,
                sleepStopAfterCurrent = false,
                tracksSize = 2,
                currentIndex = 1,
                isLoading = false,
            ),
        )
        // Sleep timer "stop after current track" blocks auto-continue.
        assertFalse(
            shouldTriggerSimilarAutoContinue(
                autoPlayEnabled = true,
                playbackMode = PlaybackMode.Sequential,
                sleepStopAfterCurrent = true,
                tracksSize = 2,
                currentIndex = 1,
                isLoading = false,
            ),
        )
    }

    @Test
    fun filterAndDiversifyDeduplicatesAndBackfillsInTwoPasses() {
        val candidates = listOf(
            similar(1, "A", "10", 0.95), // excluded
            similar(2, "A", "10", 0.90),
            similar(3, "A", "10", 0.85),
            similar(4, "A", "10", 0.80), // 3rd track by Artist A -> deferred
            similar(5, "B", "20", 0.70), // promoted ahead of track 4
        )
        val result = filterAndDiversifySimilarTracks(
            candidates = candidates,
            excludedTrackKeys = setOf("s1:1"),
            maxPerArtist = 2,
            limit = 4,
        )
        assertEquals(listOf("2", "3", "5", "4"), result.map { it.track.ratingKey })
    }

    @Test
    fun manualQueueBoundaryRemapsCleanlyAcrossMoveRemoveAndInsert() {
        // Queue: [M0, M1, A2, A3], manualQueueCount = 2
        // Dragging auto track A2 into manual slot 1 promotes it -> manualQueueCount = 3
        assertEquals(3, remapManualQueueCountAfterMove(manualQueueCount = 2, fromIndex = 2, toIndex = 1, queueSize = 4))
        // Dragging manual track M0 into auto slot 3 demotes it -> manualQueueCount = 1
        assertEquals(1, remapManualQueueCountAfterMove(manualQueueCount = 2, fromIndex = 0, toIndex = 3, queueSize = 4))
        // Reordering within manual stays 2
        assertEquals(2, remapManualQueueCountAfterMove(manualQueueCount = 2, fromIndex = 0, toIndex = 1, queueSize = 4))

        // Removing manual track decrements boundary; removing auto track preserves it
        assertEquals(1, remapManualQueueCountAfterRemove(manualQueueCount = 2, removeIndex = 0, newQueueSize = 3))
        assertEquals(2, remapManualQueueCountAfterRemove(manualQueueCount = 2, removeIndex = 2, newQueueSize = 3))

        // Adding to "End" inserts at manualQueueCount (before auto-similar tracks)
        assertEquals(2, manualQueueInsertIndex(QueuePlacement.End, currentIndex = 0, tracksSize = 6, manualQueueCount = 2))
        // Adding "Next" inserts right after currentIndex
        assertEquals(1, manualQueueInsertIndex(QueuePlacement.Next, currentIndex = 0, tracksSize = 6, manualQueueCount = 2))
    }

    @Test
    fun selectRollingSimilarSeedPicksTailEligibleTrackAndSkipsAccompaniment() {
        val queue = listOf(track(1), track(2), track(3, sourceId = "other"))
        val seed = selectRollingSimilarSeed(queue, currentIndex = 0) { it.sourceId == "s1" }
        assertEquals("2", seed?.ratingKey)

        // Single CD pattern: [Main song, Main song (instrumental)] -> must seed from Main song, not instrumental.
        val singleCd = listOf(
            track(10).copy(title = "リスポーン!!"),
            track(11).copy(title = "リスポーン!! (instrumental)"),
            track(12).copy(title = "リスポーン!! (Off Vocal)"),
        )
        val singleSeed = selectRollingSimilarSeed(singleCd, currentIndex = 0) { it.sourceId == "s1" }
        assertEquals("10", singleSeed?.ratingKey)

        // Word starting with "Inst" like "Instinct" must not be treated as accompaniment.
        assertFalse(isLikelyAccompanimentTitle("Hero (Instinct)"))
        assertTrue(isLikelyAccompanimentTitle("花になって (INSTRUMENTAL)"))
        assertTrue(isLikelyAccompanimentTitle("赤い罠(who loves it?) -Instrumental-"))
        assertTrue(isLikelyAccompanimentTitle("星屑（オフボーカル）"))
    }

    @Test
    fun primaryReasonLabelPrioritizesAcousticAndIgnoresInternalTags() {
        assertEquals(Strings.similarReasonSoundsSimilar, primarySimilarReasonLabel(listOf("sameArtist", "soundsSimilar")))
        assertEquals(Strings.similarReasonSameWork, primarySimilarReasonLabel(listOf("genre", "sameWork")))
        assertNull(primarySimilarReasonLabel(listOf("sameAlbum", "oftenSkipped")))
        assertNotNull(primarySimilarReasonLabel(listOf("era")))
    }
}
