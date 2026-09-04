package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlaybackRoutingTest {

    private val room = SonosRoom(
        roomName = "Living Room",
        coordinatorUuid = "uuid",
        coordinatorBaseUrl = "http://192.168.1.20:1400",
        memberCount = 1,
        rawCoordinatorName = "Living Room",
        memberBaseUrls = listOf("http://192.168.1.20:1400"),
    )

    private fun track(ratingKey: String) = PlexTrackStream(
        ratingKey = ratingKey,
        title = "Track $ratingKey",
        streamUrl = "http://server/library/parts/$ratingKey/file.flac",
        partKey = "/part/$ratingKey",
        durationMillis = 180_000L,
        userRating = null,
        sourceId = "src1",
    )

    @Test fun offlineListOnLocalTargetPlaysDownloadedOriginals() {
        assertEquals(
            OfflinePlaybackRoute.LocalOriginal,
            offlinePlaybackRoute(PlaybackTarget.LocalDevice),
        )
    }

    @Test fun offlineListDuringSonosCastKeepsCasting() {
        assertEquals(
            OfflinePlaybackRoute.SonosHydrated,
            offlinePlaybackRoute(PlaybackTarget.Sonos(room)),
        )
    }

    @Test fun hydratedStartIndexFollowsTheTappedTrack() {
        val hydrated = listOf(track("a"), track("b"), track("c"))
        assertEquals(2, resolveHydratedStartIndex(hydrated, "c"))
    }

    @Test fun hydratedStartIndexSurvivesDroppedTracks() {
        // The offline group held a, b, c but the server no longer exposes b.
        val hydrated = listOf(track("a"), track("c"))
        assertEquals(1, resolveHydratedStartIndex(hydrated, "c"))
    }

    @Test fun hydratedStartIndexFallsBackToQueueHead() {
        val hydrated = listOf(track("a"), track("b"))
        assertEquals(0, resolveHydratedStartIndex(hydrated, "missing"))
        assertEquals(0, resolveHydratedStartIndex(hydrated, null))
        assertEquals(0, resolveHydratedStartIndex(hydrated, ""))
    }

    @Test fun hydrationStaysCurrentWhenNothingChanged() {
        assertTrue(
            isOfflineHydrationStillCurrent(
                requestedTarget = PlaybackTarget.Sonos(room),
                currentTarget = PlaybackTarget.Sonos(room),
                requestedGeneration = 7L,
                currentGeneration = 7L,
            ),
        )
    }

    @Test fun hydrationIsStaleAfterTheUserSwitchesOutput() {
        assertFalse(
            isOfflineHydrationStillCurrent(
                requestedTarget = PlaybackTarget.Sonos(room),
                currentTarget = PlaybackTarget.LocalDevice,
                requestedGeneration = 7L,
                currentGeneration = 7L,
            ),
        )
    }

    @Test fun hydrationIsStaleAfterAnotherRoomTakesOver() {
        val otherRoom = room.copy(roomName = "Kitchen", coordinatorUuid = "uuid-2")
        assertFalse(
            isOfflineHydrationStillCurrent(
                requestedTarget = PlaybackTarget.Sonos(room),
                currentTarget = PlaybackTarget.Sonos(otherRoom),
                requestedGeneration = 7L,
                currentGeneration = 7L,
            ),
        )
    }

    @Test fun hydrationIsStaleAfterAnotherPlaybackRequestStarts() {
        // Same room, but a newer request already owns the queue.
        assertFalse(
            isOfflineHydrationStillCurrent(
                requestedTarget = PlaybackTarget.Sonos(room),
                currentTarget = PlaybackTarget.Sonos(room),
                requestedGeneration = 7L,
                currentGeneration = 8L,
            ),
        )
    }

    @Test fun localHandoverReleasesTheRoomThisAppIsDriving() {
        assertEquals(room, sonosRoomToReleaseForLocalPlayback(miniPlayerState(PlaybackTarget.Sonos(room))))
    }

    @Test fun localHandoverLeavesASelectedButUndrivenRoomAlone() {
        // Nothing is playing through this app, so no stop command may be sent: the room
        // may be rendering audio started from another controller.
        assertNull(sonosRoomToReleaseForLocalPlayback(null))
    }

    @Test fun localHandoverHasNoRoomToReleaseDuringLocalPlayback() {
        assertNull(sonosRoomToReleaseForLocalPlayback(miniPlayerState(PlaybackTarget.LocalDevice)))
    }

    private fun miniPlayerState(target: PlaybackTarget) = MiniPlayerState(
        album = PlexAlbum(
            ratingKey = "album1",
            title = "Album",
            artistName = "Artist",
            year = null,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("", "", ""),
            sourceId = "src1",
        ),
        tracks = listOf(track("a")),
        currentIndex = 0,
        target = target,
        isPaused = false,
    )
}
