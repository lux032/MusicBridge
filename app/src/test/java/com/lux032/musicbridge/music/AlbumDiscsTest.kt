package com.lux032.musicbridge.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDiscsTest {
    private val section = LibrarySection("032", "Music", "artist")

    private fun album(discCount: Int? = null) = MusicAlbum(
        ratingKey = "10",
        title = "Double CD",
        artistName = "Artist",
        year = 2024,
        thumbUrl = null,
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = section,
        sourceId = "src",
        discCount = discCount,
    )

    private fun track(
        id: String,
        title: String = "Track $id",
        discNumber: Int? = null,
        trackNumber: Int? = null,
        durationMillis: Long? = 180_000L,
    ) = MusicTrack(
        ratingKey = id,
        title = title,
        streamUrl = "https://example.org/$id",
        partKey = "/$id",
        durationMillis = durationMillis,
        userRating = null,
        albumRatingKey = "10",
        sourceId = "src",
        discNumber = discNumber,
        trackNumber = trackNumber,
    )

    @Test
    fun detectsMultiDiscFromActualTrackDiscNumbers() {
        // Single disc tagged as disc 1 or null -> false
        assertFalse(listOf(track("1", discNumber = null), track("2", discNumber = 1)).hasMultipleDiscs())
        // Single disc mis-tagged as disc 2 across every track -> false (matches 032player-rs 79e9b17)
        assertFalse(listOf(track("1", discNumber = 2), track("2", discNumber = 2)).hasMultipleDiscs())
        // Null normalized to 1 vs explicit disc 2 -> true
        assertTrue(listOf(track("1", discNumber = null), track("2", discNumber = 2)).hasMultipleDiscs())
        assertTrue(listOf(track("1", discNumber = 1), track("2", discNumber = 2)).hasMultipleDiscs())
    }

    @Test
    fun albumMultiDiscFallsBackToDiscCountOnlyWhenTracksEmpty() {
        assertTrue(isMultiDiscAlbum(album(discCount = 2), emptyList()))
        assertFalse(isMultiDiscAlbum(album(discCount = 1), emptyList()))
        assertFalse(isMultiDiscAlbum(album(discCount = null), emptyList()))
        // Even if discCount is 2, actual tracks on a single disc take precedence.
        assertFalse(isMultiDiscAlbum(album(discCount = 2), listOf(track("1", discNumber = 1), track("2", discNumber = 1))))
        // Even if discCount is null, actual tracks spanning 2 discs take precedence.
        assertTrue(isMultiDiscAlbum(album(discCount = null), listOf(track("1", discNumber = 1), track("2", discNumber = 2))))
    }

    @Test
    fun sortsAndGroupsTracksByDisc() {
        val raw = listOf(
            track("d2t2", title = "D2 T2", discNumber = 2, trackNumber = 2, durationMillis = 120_000L),
            track("d1t2", title = "D1 T2", discNumber = 1, trackNumber = 2, durationMillis = 100_000L),
            track("d2t1", title = "D2 T1", discNumber = 2, trackNumber = 1, durationMillis = 90_000L),
            track("d1t1", title = "D1 T1", discNumber = null, trackNumber = 1, durationMillis = 200_000L),
        )
        val sorted = raw.sortedInDiscOrder()
        assertEquals(listOf("d1t1", "d1t2", "d2t1", "d2t2"), sorted.map { it.ratingKey })

        val groups = sorted.groupByDisc()
        assertEquals(2, groups.size)
        assertEquals(1, groups[0].discNumber)
        assertEquals(listOf("d1t1", "d1t2"), groups[0].tracks.map { it.ratingKey })
        assertEquals(300_000L, groups[0].totalDurationMillis)
        assertEquals(2, groups[1].discNumber)
        assertEquals(listOf("d2t1", "d2t2"), groups[1].tracks.map { it.ratingKey })
        assertEquals(210_000L, groups[1].totalDurationMillis)
    }
}
