package com.lux032.musicbridge.local

import com.lux032.musicbridge.PlaybackMode
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.storage.SavedPlaybackSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackColdStartTest {

    @Test
    fun canColdStartAcceptsConfiguredServerOrOfflineAvailability() {
        val session = sampleSession(sourceId = "srv-1")
        assertTrue(
            canColdStartSavedSession(
                saved = session,
                configuredServerIds = setOf("srv-1"),
                hasAnyOfflineTrack = false,
            )
        )
        assertTrue(
            canColdStartSavedSession(
                saved = session,
                configuredServerIds = setOf("other-server"),
                hasAnyOfflineTrack = true,
            )
        )
        assertFalse(
            canColdStartSavedSession(
                saved = session,
                configuredServerIds = setOf("other-server"),
                hasAnyOfflineTrack = false,
            )
        )
    }

    @Test
    fun canColdStartRejectsEmptyOrOutOfRangeSessions() {
        val empty = sampleSession(sourceId = "srv-1").copy(tracks = emptyList())
        assertFalse(
            canColdStartSavedSession(
                saved = empty,
                configuredServerIds = setOf("srv-1"),
                hasAnyOfflineTrack = true,
            )
        )
        val badIndex = sampleSession(sourceId = "srv-1").copy(currentIndex = 5)
        assertFalse(
            canColdStartSavedSession(
                saved = badIndex,
                configuredServerIds = setOf("srv-1"),
                hasAnyOfflineTrack = true,
            )
        )
    }

    private fun sampleSession(sourceId: String): SavedPlaybackSession {
        val album = MusicAlbum(
            ratingKey = "alb-1",
            title = "Album",
            artistName = "Artist",
            year = 2024,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = LibrarySection("1", "Music", "artist"),
            sourceId = sourceId,
        )
        val track = MusicTrack(
            ratingKey = "trk-1",
            title = "Song",
            streamUrl = "https://example.com/stream/1",
            partKey = "/parts/1.flac",
            durationMillis = 180_000L,
            userRating = null,
            albumRatingKey = "alb-1",
            albumTitle = "Album",
            artistName = "Artist",
            sourceId = sourceId,
        )
        return SavedPlaybackSession(
            queueId = 42L,
            album = album,
            tracks = listOf(track),
            currentIndex = 0,
            positionMillis = 12_000L,
            durationMillis = 180_000L,
            sonosRoom = null,
            playbackMode = PlaybackMode.Sequential,
        )
    }
}
