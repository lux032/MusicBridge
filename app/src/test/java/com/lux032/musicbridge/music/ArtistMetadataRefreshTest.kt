package com.lux032.musicbridge.music

import org.junit.Assert.*
import org.junit.Test

class ArtistMetadataRefreshTest {
    @Test fun refreshPreservesOfflineTransportAndPlaybackContext() {
        val old = MusicTrack("1", "Song", "file:///music/song.flac", "original", 1000, 10f,
            sourceId = "server", artistName = "A, B", artists = listOf(ArtistRef("9", "A, B")),
            playbackContext = Music032PlaybackContext(5, 2))
        val fresh = old.copy(streamUrl = "https://server/stream", artistName = "A, B",
            artists = listOf(ArtistRef("10", "A"), ArtistRef("11", "B")), playbackContext = null)
        val result = old.withArtistMetadataFrom(fresh)
        assertEquals(fresh.artists, result.artists)
        assertEquals(old.streamUrl, result.streamUrl)
        assertEquals(old.playbackContext, result.playbackContext)
        assertEquals(old.userRating, result.userRating)
        assertSame(old, old.withArtistMetadataFrom(fresh.copy(sourceId = "another")))
    }
}
