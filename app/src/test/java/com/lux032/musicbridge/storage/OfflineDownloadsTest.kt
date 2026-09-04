package com.lux032.musicbridge.storage

import android.content.Context
import com.lux032.musicbridge.local.PlaybackDataSourceRoute
import com.lux032.musicbridge.local.playbackDataSourceRoute
import com.lux032.musicbridge.plex.isFavorite
import org.junit.Assert.assertEquals
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
        assertTrue("track101" in keys)

        val stream = retrieved?.toTrackStream()
        assertEquals("file:///data/user/0/com.lux032.musicbridge/files/audio_downloads/song.flac", stream?.streamUrl)
        assertEquals("file:///data/user/0/com.lux032.musicbridge/files/audio_downloads/covers/cover.jpg", stream?.thumbUrl)
        assertEquals("file:///data/user/0/com.lux032.musicbridge/files/audio_downloads/lyrics/lyric.lrc", stream?.lyricUrl)
        assertEquals(10f, stream?.userRating)
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
}
