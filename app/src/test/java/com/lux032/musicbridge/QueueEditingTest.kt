package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueEditingTest {

    private fun track(id: String, durationMillis: Long? = 180_000L, sourceId: String = "s1") = MusicTrack(
        ratingKey = id,
        title = "Track $id",
        streamUrl = "http://example/$id",
        partKey = "/part/$id",
        durationMillis = durationMillis,
        userRating = null,
        sourceId = sourceId,
    )

    @Test
    fun remapIndexAfterMove_tracksCurrentAcrossAllDirections() {
        // Moving the current item itself follows it to the destination.
        assertEquals(4, remapIndexAfterMove(currentIndex = 1, fromIndex = 1, toIndex = 4))
        assertEquals(0, remapIndexAfterMove(currentIndex = 3, fromIndex = 3, toIndex = 0))

        // Moving an earlier item past current shifts current left by 1.
        assertEquals(1, remapIndexAfterMove(currentIndex = 2, fromIndex = 0, toIndex = 2))
        assertEquals(1, remapIndexAfterMove(currentIndex = 2, fromIndex = 0, toIndex = 4))

        // Moving a later item before or onto current shifts current right by 1.
        assertEquals(3, remapIndexAfterMove(currentIndex = 2, fromIndex = 4, toIndex = 2))
        assertEquals(3, remapIndexAfterMove(currentIndex = 2, fromIndex = 4, toIndex = 0))

        // Moves wholly before or wholly after current leave current untouched.
        assertEquals(2, remapIndexAfterMove(currentIndex = 2, fromIndex = 0, toIndex = 1))
        assertEquals(2, remapIndexAfterMove(currentIndex = 2, fromIndex = 3, toIndex = 4))
        assertEquals(2, remapIndexAfterMove(currentIndex = 2, fromIndex = 2, toIndex = 2))
    }

    @Test
    fun moveTrackInQueueState_reordersTracksAndKeepsPlayingTrackPinned() {
        val initial = listOf(track("1"), track("2"), track("3"), track("4"))
        // Currently playing track "3" (index 2); move track "1" (index 0) to the end (index 3).
        val moved = moveTrackInQueueState(initial, currentIndex = 2, fromIndex = 0, toIndex = 3)!!
        assertEquals(listOf("2", "3", "4", "1"), moved.tracks.map { it.ratingKey })
        assertEquals(1, moved.currentIndex)
        assertEquals("3", moved.tracks[moved.currentIndex].ratingKey)

        // Move currently playing track "3" (index 1) to index 0.
        val movedCurrent = moveTrackInQueueState(moved.tracks, moved.currentIndex, fromIndex = 1, toIndex = 0)!!
        assertEquals(listOf("3", "2", "4", "1"), movedCurrent.tracks.map { it.ratingKey })
        assertEquals(0, movedCurrent.currentIndex)
        assertEquals("3", movedCurrent.tracks[movedCurrent.currentIndex].ratingKey)

        // Invalid or no-op moves return null.
        assertNull(moveTrackInQueueState(initial, 1, 1, 1))
        assertNull(moveTrackInQueueState(initial, 1, -1, 2))
        assertNull(moveTrackInQueueState(initial, 1, 0, 9))
    }

    @Test
    fun removeTrackFromQueueState_handlesNonCurrentCurrentLastAndSoleTrack() {
        val initial = listOf(track("1"), track("2"), track("3"))

        // Remove before current (index 0 while current is 1 -> current becomes 0, still track "2").
        val removeBefore = removeTrackFromQueueState(initial, currentIndex = 1, removeIndex = 0)!!
        assertEquals(listOf("2", "3"), removeBefore.tracks.map { it.ratingKey })
        assertEquals(0, removeBefore.currentIndex)
        assertFalse(removeBefore.removedWasCurrent)

        // Remove after current (index 2 while current is 1 -> current stays 1).
        val removeAfter = removeTrackFromQueueState(initial, currentIndex = 1, removeIndex = 2)!!
        assertEquals(listOf("1", "2"), removeAfter.tracks.map { it.ratingKey })
        assertEquals(1, removeAfter.currentIndex)
        assertFalse(removeAfter.removedWasCurrent)

        // Remove current in middle (index 1 -> advances to track "3" now at index 1).
        val removeCurrentMiddle = removeTrackFromQueueState(initial, currentIndex = 1, removeIndex = 1)!!
        assertEquals(listOf("1", "3"), removeCurrentMiddle.tracks.map { it.ratingKey })
        assertEquals(1, removeCurrentMiddle.currentIndex)
        assertEquals("3", removeCurrentMiddle.tracks[removeCurrentMiddle.currentIndex].ratingKey)
        assertTrue(removeCurrentMiddle.removedWasCurrent)

        // Remove current at end (index 2 -> falls back to previous track "2" at index 1).
        val removeCurrentLast = removeTrackFromQueueState(initial, currentIndex = 2, removeIndex = 2)!!
        assertEquals(listOf("1", "2"), removeCurrentLast.tracks.map { it.ratingKey })
        assertEquals(1, removeCurrentLast.currentIndex)
        assertEquals("2", removeCurrentLast.tracks[removeCurrentLast.currentIndex].ratingKey)
        assertTrue(removeCurrentLast.removedWasCurrent)

        // Remove sole track -> empty list and currentIndex -1.
        val removeSole = removeTrackFromQueueState(listOf(track("1")), currentIndex = 0, removeIndex = 0)!!
        assertTrue(removeSole.tracks.isEmpty())
        assertEquals(-1, removeSole.currentIndex)
        assertTrue(removeSole.removedWasCurrent)
    }

    @Test
    fun clearUpcomingKeepCurrentState_keepsOnlyActiveTrack() {
        val initial = listOf(track("1"), track("2"), track("3"), track("4"))
        val kept = clearUpcomingKeepCurrentState(initial, currentIndex = 2)!!
        assertEquals(listOf("3"), kept.map { it.ratingKey })

        // Single-track queue returns null (nothing else to clear).
        assertNull(clearUpcomingKeepCurrentState(listOf(track("1")), currentIndex = 0))
    }

    @Test
    fun sonosInsertBeforeForMove_matchesUpnpPreRemovalIndexing() {
        // Moving downwards (to > from): InsertBefore is 1-based position before removal (to + 2).
        assertEquals(3, sonosInsertBeforeForMove(fromIndex = 0, toIndex = 1))
        assertEquals(5, sonosInsertBeforeForMove(fromIndex = 0, toIndex = 3))
        assertEquals(4, sonosInsertBeforeForMove(fromIndex = 1, toIndex = 2))

        // Moving upwards (to < from): InsertBefore is 1-based destination (to + 1).
        assertEquals(1, sonosInsertBeforeForMove(fromIndex = 1, toIndex = 0))
        assertEquals(1, sonosInsertBeforeForMove(fromIndex = 3, toIndex = 0))
        assertEquals(2, sonosInsertBeforeForMove(fromIndex = 3, toIndex = 1))
    }

    @Test
    fun remainingQueueDurationMillis_sumsCurrentRemainingPlusUpcoming() {
        val tracks = listOf(
            track("1", 100_000L),
            track("2", 200_000L),
            track("3", 300_000L),
        )
        assertEquals(450_000L, remainingQueueDurationMillis(tracks, currentIndex = 1, currentPositionMillis = 50_000L))
        // Clamps negative or over-duration current positions cleanly.
        assertEquals(300_000L, remainingQueueDurationMillis(tracks, currentIndex = 1, currentPositionMillis = 999_000L))
        // Unknown durations return null.
        assertNull(remainingQueueDurationMillis(listOf(track("1", null)), currentIndex = 0, currentPositionMillis = 0L))
    }
}
