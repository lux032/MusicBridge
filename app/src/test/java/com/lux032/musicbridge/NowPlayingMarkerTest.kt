package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NowPlayingMarkerTest {
    private val album = PlexAlbum(
        ratingKey = "album-a", title = "Album", artistName = null,
        year = null, thumbUrl = null, userRating = null,
        addedAtEpochSeconds = null, lastViewedAtEpochSeconds = null,
        section = PlexSection("1", "Music", "artist"), sourceId = "server-a",
    )

    private fun track(key: String, albumKey: String? = "album-a", source: String = "server-a") =
        PlexTrackStream(
            ratingKey = key, title = key, streamUrl = "", partKey = "",
            durationMillis = null, userRating = null, albumRatingKey = albumKey, sourceId = source,
        )

    private fun player(
        tracks: List<PlexTrackStream>,
        index: Int = 0,
        paused: Boolean = false,
        buffering: Boolean = false,
        playerAlbum: PlexAlbum = album,
    ) = MiniPlayerState(
        album = playerAlbum,
        tracks = tracks,
        currentIndex = index,
        target = PlaybackTarget.LocalDevice,
        isPaused = paused,
        isBuffering = buffering,
    )

    @Test fun noPlaybackGivesNoMarker() {
        assertNull((null as MiniPlayerState?).toNowPlayingMarker())
        assertNull(player(emptyList()).toNowPlayingMarker())
        assertNull(player(listOf(track("t1")), index = 3).toNowPlayingMarker())
    }

    @Test fun markerFollowsCurrentIndexAndPlayState() {
        val marker = player(listOf(track("t1"), track("t2")), index = 1, paused = true, buffering = true)
            .toNowPlayingMarker()!!
        assertEquals("t2", marker.ratingKey)
        assertFalse(marker.isPlaying)
        assertTrue(marker.isBuffering)
        assertTrue(marker.matches(track("t2")))
        assertFalse(marker.matches(track("t1")))
    }

    @Test fun trackMatchIsScopedToSource() {
        val marker = player(listOf(track("t1"))).toNowPlayingMarker()!!
        assertFalse(marker.matches(track("t1", source = "server-b")))
    }

    @Test fun albumMatchUsesTrackAlbumAndSource() {
        val marker = player(listOf(track("t1"))).toNowPlayingMarker()!!
        assertTrue(marker.matchesAlbum(album))
        assertFalse(marker.matchesAlbum(album.copy(ratingKey = "album-b")))
        assertFalse(marker.matchesAlbum(album.copy(sourceId = "server-b")))
    }

    @Test fun syntheticPlaylistAlbumNeverMarksARealAlbum() {
        // A 032 playlist id can equal an album id; a track without its own album must not
        // borrow the queue's synthetic album key.
        val playlistAlbum = album.copy(ratingKey = "album-a", title = "My playlist")
        val marker = player(listOf(track("t1", albumKey = null)), playerAlbum = playlistAlbum)
            .toNowPlayingMarker()!!
        assertNull(marker.albumRatingKey)
        assertFalse(marker.matchesAlbum(album))
    }

    @Test fun blankAlbumKeysNeverMatch() {
        val marker = player(listOf(track("t1", albumKey = ""))).toNowPlayingMarker()!!
        assertFalse(marker.matchesAlbum(album.copy(ratingKey = "")))
    }
}
