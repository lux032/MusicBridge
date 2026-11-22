package com.lux032.musicbridge.storage

import android.content.Context
import com.lux032.musicbridge.local.PlaybackDataSourceRoute
import com.lux032.musicbridge.local.playbackDataSourceRoute
import com.lux032.musicbridge.music.isFavorite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class OfflineDownloadsTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun insertAndRetrieveDownloadedTrack() {
        val store = AlbumLocalStore(context)
        store.clearAllDownloadedTracks()

        val track = DownloadedAudioTrack(
            sourceId = "server1",
            ratingKey = "track101",
            partKey = "/library/parts/101/file.flac",
            title = "Offline Song",
            artistName = "Offline Artist",
            albumTitle = "Offline Album",
            albumRatingKey = "album1",
            durationMillis = 180000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 1000,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/data/user/0/com.lux032.musicbridge/files/audio_downloads/song.flac",
            localThumbPath = "/data/user/0/com.lux032.musicbridge/files/audio_downloads/covers/cover.jpg",
            localLyricPath = "/data/user/0/com.lux032.musicbridge/files/audio_downloads/lyrics/lyric.lrc",
            fileSize = 25000000L,
            downloadedAtEpochSeconds = 1700000000L,
            discNumber = 1,
            trackNumber = 3,
            userRating = 10f,
            artists = listOf(com.lux032.musicbridge.music.ArtistRef("101", "A"),
                com.lux032.musicbridge.music.ArtistRef("102", "B")),
        )

        store.insertDownloadedTrack(track)

        val retrieved = store.getDownloadedTrack("server1", "track101")
        assertNotNull(retrieved)
        assertEquals("Offline Song", retrieved?.title)
        assertEquals(25000000L, retrieved?.fileSize)
        assertEquals(25000000L, store.totalDownloadedBytes())
        assertEquals(10f, retrieved?.userRating)

        val keys = store.getAllDownloadedTrackKeys()
        assertTrue("server1:track101" in keys)
        assertFalse("track101" in keys)
        assertTrue(keys.containsDownloadedTrack("server1", "track101"))
        assertFalse(keys.containsDownloadedTrack("server2", "track101"))

        val stream = retrieved?.toTrackStream()
        assertEquals("file:///data/user/0/com.lux032.musicbridge/files/audio_downloads/song.flac", stream?.streamUrl)
        assertEquals("file:///data/user/0/com.lux032.musicbridge/files/audio_downloads/covers/cover.jpg", stream?.thumbUrl)
        assertEquals("file:///data/user/0/com.lux032.musicbridge/files/audio_downloads/lyrics/lyric.lrc", stream?.lyricUrl)
        assertEquals(track.artists, stream?.artists)
        store.refreshArtistMetadata("server1", listOf(stream!!.copy(
            artistName = "Corrected", artists = listOf(com.lux032.musicbridge.music.ArtistRef("103", "Corrected")))))
        val refreshed = store.getDownloadedTrack("server1", "track101")!!
        assertEquals("103", refreshed.artists.single().id)
        assertEquals(track.localAudioPath, refreshed.localAudioPath)
        assertEquals(track.fileSize, refreshed.fileSize)
        assertEquals(10f, stream.userRating)
        assertTrue(stream?.isFavorite == true)

        store.deleteDownloadedTrack("server1", "track101")
        assertNull(store.getDownloadedTrack("server1", "track101"))
        assertEquals(0L, store.totalDownloadedBytes())
    }

    @Test
    fun localFileRoutingTest() {
        val route = playbackDataSourceRoute(
            url = "file:///storage/emulated/0/Android/data/com.lux032.musicbridge/files/audio_downloads/sample.audio",
            isTranscoded = false,
            isFullyCached = false,
        )
        assertEquals(PlaybackDataSourceRoute.OFFLINE_FILE, route)
    }

    @Test
    fun downloadKeyIsolationAcrossServers() {
        val keys = setOf(downloadedTrackKey("server-a", "42"))
        assertTrue(keys.containsDownloadedTrack("server-a", "42"))
        assertFalse(keys.containsDownloadedTrack("server-b", "42"))
        assertFalse(keys.containsDownloadedTrack("", "42"))

        val legacyKeys = setOf(downloadedTrackKey("", "99"), "99")
        assertTrue(legacyKeys.containsDownloadedTrack("", "99"))
        assertFalse(legacyKeys.containsDownloadedTrack("server-a", "99"))
    }

    @Test
    fun downloadCompletenessRejectsTruncatedAndEmptyStreams() {
        assertFalse(isDownloadComplete(bytesRead = 0L, expectedContentLength = -1L))
        assertFalse(isDownloadComplete(bytesRead = 0L, expectedContentLength = 1024L))
        assertFalse(isDownloadComplete(bytesRead = 512L, expectedContentLength = 1024L))
        assertFalse(isDownloadComplete(bytesRead = 2048L, expectedContentLength = 1024L))
        assertTrue(isDownloadComplete(bytesRead = 1024L, expectedContentLength = 1024L))
        assertTrue(isDownloadComplete(bytesRead = 1024L, expectedContentLength = -1L))
    }

    @Test
    fun sharedAlbumCoverReferenceCountTracksRemainingDownloads() {
        val store = AlbumLocalStore(context)
        store.clearAllDownloadedTracks()

        val sharedThumb = "/tmp/covers/album_server1_album1.cover"
        val t1 = DownloadedAudioTrack(
            sourceId = "server1",
            ratingKey = "t1",
            partKey = "/p1",
            title = "Track 1",
            artistName = "Artist",
            albumTitle = "Album",
            albumRatingKey = "album1",
            durationMillis = 120000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 900,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/tmp/t1.audio",
            localThumbPath = sharedThumb,
            localLyricPath = null,
            fileSize = 1000L,
            downloadedAtEpochSeconds = 100L,
        )
        val t2 = t1.copy(ratingKey = "t2", partKey = "/p2", title = "Track 2", localAudioPath = "/tmp/t2.audio")

        store.insertDownloadedTrack(t1)
        store.insertDownloadedTrack(t2)
        assertEquals(2, store.countDownloadedTracksByThumbPath(sharedThumb))

        store.deleteDownloadedTrack("server1", "t1")
        assertEquals(1, store.countDownloadedTracksByThumbPath(sharedThumb))

        store.deleteDownloadedTrack("server1", "t2")
        assertEquals(0, store.countDownloadedTracksByThumbPath(sharedThumb))
    }
}
