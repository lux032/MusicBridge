package com.lux032.musicbridge.model

import com.lux032.musicbridge.albumYearFrom
import com.lux032.musicbridge.resolvePlaybackAlbum
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackAlbumMetadataTest {
    private val firstAlbum = PlexAlbum(
        ratingKey = "album-a", title = "First album", artistName = null,
        year = 1999, thumbUrl = null, userRating = null,
        addedAtEpochSeconds = null, lastViewedAtEpochSeconds = null,
        section = PlexSection("1", "Music", "artist"), sourceId = "server-a",
    )
    private fun track(albumKey: String?, source: String = "server-a") = PlexTrackStream(
        ratingKey = "track", title = "Track", streamUrl = "", partKey = "",
        durationMillis = null, userRating = null, albumRatingKey = albumKey, sourceId = source,
    )

    @Test fun switchingTracksUsesCachedCurrentAlbumInsteadOfFirstAlbum() {
        val secondAlbum = firstAlbum.copy(ratingKey = "album-b", year = 2024)
        val cachedAlbums = listOf(firstAlbum, secondAlbum)
        val resolved = track("album-b").resolvePlaybackAlbum(cachedAlbums, firstAlbum)
        assertEquals(secondAlbum, resolved)
        assertEquals(2024, track("album-b").albumYearFrom(resolved))
        assertEquals(firstAlbum, track("album-a").resolvePlaybackAlbum(cachedAlbums, resolved))
    }

    @Test fun cachedAlbumLookupIsScopedToMusicSource() {
        val otherServerAlbum = firstAlbum.copy(sourceId = "server-b", year = 2020)
        assertEquals(otherServerAlbum, track("album-a", "server-b").resolvePlaybackAlbum(
            listOf(firstAlbum, otherServerAlbum), firstAlbum,
        ))
        assertNull(track("album-b").resolvePlaybackAlbum(listOf(firstAlbum), firstAlbum).year)
    }

    @Test fun sameAlbumKeepsYear() {
        assertEquals(1999, track("album-a").albumYearFrom(firstAlbum))
    }

    @Test fun mixedPlaylistDoesNotReuseFirstAlbumYear() {
        assertNull(track("album-b").albumYearFrom(firstAlbum))
        assertEquals(2024, track("album-b").albumYearFrom(
            firstAlbum.copy(ratingKey = "album-b", year = 2024),
        ))
    }

    @Test fun sameKeyOnDifferentServerDoesNotReuseYear() {
        assertNull(track("album-a", "server-b").albumYearFrom(firstAlbum))
    }

    @Test fun unknownAlbumDoesNotReuseYear() {
        assertNull(track(null).albumYearFrom(firstAlbum))
        assertNull(track("").albumYearFrom(firstAlbum))
        assertNull(track("album-a").albumYearFrom(null))
        assertNull(track("album-a").albumYearFrom(firstAlbum.copy(year = null)))
    }
}
