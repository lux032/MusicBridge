package com.lux032.musicbridge.storage

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class OfflineAlbumGroupTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun testBuildAlbumGroupsAggregation() {
        val trackA1 = DownloadedAudioTrack(
            sourceId = "src1",
            ratingKey = "track1",
            partKey = "/part1",
            title = "Alpha",
            artistName = "Artist 1",
            albumTitle = "Album A",
            albumRatingKey = "albumA",
            durationMillis = 180000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 900,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/local/path/track1.flac",
            localThumbPath = "/local/path/coverA.jpg",
            localLyricPath = null,
            fileSize = 20_000_000L,
            downloadedAtEpochSeconds = 1000L,
        )

        val trackA2 = DownloadedAudioTrack(
            sourceId = "src1",
            ratingKey = "track2",
            partKey = "/part2",
            title = "Beta",
            artistName = "Artist 1",
            albumTitle = "Album A",
            albumRatingKey = "albumA",
            durationMillis = 200000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 900,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/local/path/track2.flac",
            localThumbPath = "/local/path/coverA.jpg",
            localLyricPath = null,
            fileSize = 30_000_000L,
            downloadedAtEpochSeconds = 2000L,
        )

        val trackB1 = DownloadedAudioTrack(
            sourceId = "src1",
            ratingKey = "track3",
            partKey = "/part3",
            title = "Gamma",
            artistName = "Artist 2",
            albumTitle = "Album B",
            albumRatingKey = "albumB",
            durationMillis = 150000L,
            sourceAudioCodec = "mp3",
            sourceAudioBitrateKbps = 320,
            sourceAudioSampleRate = 44100,
            bitDepth = null,
            localAudioPath = "/local/path/track3.mp3",
            localThumbPath = "/local/path/coverB.jpg",
            localLyricPath = null,
            fileSize = 10_000_000L,
            downloadedAtEpochSeconds = 3000L,
        )

        val groups = buildAlbumGroups(listOf(trackA1, trackA2, trackB1))
        assertEquals(2, groups.size)

        // Album B has latest downloaded time 3000L, so it should be first
        assertEquals("albumB", groups[0].albumRatingKey)
        assertEquals("Album B", groups[0].albumTitle)
        assertEquals("Artist 2", groups[0].artistName)
        assertEquals(1, groups[0].tracks.size)
        assertEquals(10_000_000L, groups[0].totalSizeBytes)
        assertEquals(3000L, groups[0].latestDownloadedAt)

        // Album A has latest downloaded time 2000L
        assertEquals("albumA", groups[1].albumRatingKey)
        assertEquals("Album A", groups[1].albumTitle)
        assertEquals("Artist 1", groups[1].artistName)
        assertEquals(2, groups[1].tracks.size)
        assertEquals(50_000_000L, groups[1].totalSizeBytes)
        assertEquals(2000L, groups[1].latestDownloadedAt)
        assertEquals("Alpha", groups[1].tracks[0].title)
        assertEquals("Beta", groups[1].tracks[1].title)
    }

    @Test
    fun testBuildAlbumGroupsFallbackWhenRatingKeyMissing() {
        val trackWithoutRatingKey = DownloadedAudioTrack(
            sourceId = "src1",
            ratingKey = "track99",
            partKey = "/part99",
            title = "Special Song",
            artistName = "Artist X",
            albumTitle = "Standalone Collection",
            albumRatingKey = null,
            durationMillis = 120000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 800,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/local/path/track99.flac",
            localThumbPath = null,
            localLyricPath = null,
            fileSize = 15_000_000L,
            downloadedAtEpochSeconds = 5000L,
        )

        val groups = buildAlbumGroups(listOf(trackWithoutRatingKey))
        assertEquals(1, groups.size)
        assertEquals("Standalone Collection", groups[0].albumRatingKey)
        assertEquals("Standalone Collection", groups[0].albumTitle)
    }

    @Test
    fun testDeleteDownloadedAlbumTransaction() {
        val store = AlbumLocalStore(context)
        store.clearAllDownloadedTracks()

        val trackA1 = DownloadedAudioTrack(
            sourceId = "src1",
            ratingKey = "t1",
            partKey = "/p1",
            title = "Track 1",
            artistName = "Artist 1",
            albumTitle = "Album 1",
            albumRatingKey = "alb1",
            durationMillis = 1000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = null,
            sourceAudioSampleRate = null,
            bitDepth = null,
            localAudioPath = "/audio1.flac",
            localThumbPath = null,
            localLyricPath = null,
            fileSize = 1000L,
            downloadedAtEpochSeconds = 100L,
        )
        val trackA2 = trackA1.copy(ratingKey = "t2", partKey = "/p2", title = "Track 2", fileSize = 2000L)
        val trackB1 = trackA1.copy(ratingKey = "t3", partKey = "/p3", albumRatingKey = "alb2", albumTitle = "Album 2", fileSize = 5000L)

        store.insertDownloadedTrack(trackA1)
        store.insertDownloadedTrack(trackA2)
        store.insertDownloadedTrack(trackB1)

        assertEquals(8000L, store.totalDownloadedBytes())

        val deletedTracks = store.deleteDownloadedAlbum("src1", "alb1")
        assertEquals(2, deletedTracks.size)
        assertTrue(deletedTracks.any { it.ratingKey == "t1" })
        assertTrue(deletedTracks.any { it.ratingKey == "t2" })

        // Only track B1 remains
        assertNull(store.getDownloadedTrack("src1", "t1"))
        assertNull(store.getDownloadedTrack("src1", "t2"))
        assertEquals(5000L, store.totalDownloadedBytes())
        assertEquals("t3", store.getDownloadedTrack("src1", "t3")?.ratingKey)

        store.clearAllDownloadedTracks()
    }

    @Test
    fun testBuildAlbumGroupsMultiDiscAndTrackNumberSorting() {
        val baseTrack = DownloadedAudioTrack(
            sourceId = "src1",
            ratingKey = "base",
            partKey = "/base",
            title = "Title",
            artistName = "Artist",
            albumTitle = "Double Album",
            albumRatingKey = "double_alb",
            durationMillis = 180000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 900,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/path/base.flac",
            localThumbPath = null,
            localLyricPath = null,
            fileSize = 10_000_000L,
            downloadedAtEpochSeconds = 1000L,
        )

        val d1t2 = baseTrack.copy(ratingKey = "d1t2", title = "Apple", discNumber = 1, trackNumber = 2)
        val d1t1 = baseTrack.copy(ratingKey = "d1t1", title = "Zebra", discNumber = 1, trackNumber = 1)
        val d2t1 = baseTrack.copy(ratingKey = "d2t1", title = "Banana", discNumber = 2, trackNumber = 1)
        val noNumB = baseTrack.copy(ratingKey = "nonumb", title = "Bonus B", discNumber = null, trackNumber = null)
        val noNumA = baseTrack.copy(ratingKey = "nonuma", title = "Bonus A", discNumber = 1, trackNumber = null)

        // Pass tracks in random order
        val groups = buildAlbumGroups(listOf(noNumB, d2t1, d1t2, noNumA, d1t1))
        assertEquals(1, groups.size)

        val sortedTracks = groups[0].tracks
        assertEquals(5, sortedTracks.size)

        // Disc 1 Track 1 should be first (Zebra, despite Z)
        assertEquals("d1t1", sortedTracks[0].ratingKey)
        assertEquals("Zebra", sortedTracks[0].title)

        // Disc 1 Track 2 should be second (Apple)
        assertEquals("d1t2", sortedTracks[1].ratingKey)
        assertEquals("Apple", sortedTracks[1].title)

        // Disc 1 with null trackNumber (Bonus A vs Bonus B: Bonus A has disc 1, Bonus B has null disc -> defaults to 1, then tie break by title)
        // noNumA (disc 1, null track) and noNumB (null disc -> defaults to 1, null track):
        // both disc 1, both track Int.MAX_VALUE, so sorted by title: "Bonus A" before "Bonus B"
        assertEquals("nonuma", sortedTracks[2].ratingKey)
        assertEquals("nonumb", sortedTracks[3].ratingKey)

        // Disc 2 Track 1 should be last (disc 2 > disc 1)
        assertEquals("d2t1", sortedTracks[4].ratingKey)
        assertEquals("Banana", sortedTracks[4].title)
    }
}
