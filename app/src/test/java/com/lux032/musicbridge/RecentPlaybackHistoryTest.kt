package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class RecentPlaybackHistoryTest {
    private val preferences = AppPreferences(RuntimeEnvironment.getApplication())
    private val first = MusicAlbum(
        ratingKey = "album-a", title = "Album A", artistName = null,
        year = null, thumbUrl = null, userRating = null,
        addedAtEpochSeconds = null, lastViewedAtEpochSeconds = null,
        section = LibrarySection("1", "Music", "artist"), sourceId = "server-a",
    )
    private val second = first.copy(ratingKey = "album-b", title = "Album B")

    private fun play(album: MusicAlbum, fallback: MusicAlbum = first): List<String> {
        val track = MusicTrack(
            ratingKey = "track-${album.ratingKey}", title = "Track", streamUrl = "", partKey = "",
            durationMillis = null, userRating = null,
            albumRatingKey = album.ratingKey, sourceId = album.sourceId,
        )
        return preferences.recordPlayedAlbum(track.resolvePlaybackAlbum(listOf(first, second, album), fallback))
    }

    @Test fun mixedQueueRecordsEveryPlayedAlbumAndMovesRevisitedAlbumFirst() {
        assertEquals(listOf("album-a"), play(first))
        assertEquals(listOf("album-b", "album-a"), play(second))
        assertEquals(listOf("album-a", "album-b"), play(first, second))
    }

    @Test fun startingAtSecondAlbumDoesNotRecordQueueSeedAlbum() {
        assertEquals(listOf("album-b"), play(second))
    }

    @Test fun repeatedObservationsDoNotDuplicateAlbums() {
        play(first)
        play(second)
        repeat(5) { assertEquals(listOf("album-b", "album-a"), play(second)) }
    }

    @Test fun sameAlbumKeysOnDifferentServersKeepIndependentHistories() {
        play(first)
        play(second.copy(sourceId = "server-b"))
        assertEquals(listOf("album-a"), preferences.loadRecentPlayedAlbumKeys("server-a"))
        assertEquals(listOf("album-b"), preferences.loadRecentPlayedAlbumKeys("server-b"))
    }
}
