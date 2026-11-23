package com.lux032.musicbridge.storage

import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.SearchCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLibrarySearchTest {

    private val section = LibrarySection("1", "Music", "artist")

    private fun sampleAlbum(id: String, title: String, artist: String) = MusicAlbum(
        ratingKey = id,
        title = title,
        artistName = artist,
        year = 2023,
        thumbUrl = "https://srv/album/$id.jpg",
        userRating = null,
        addedAtEpochSeconds = 1000L,
        lastViewedAtEpochSeconds = null,
        section = section,
        sourceId = "srv1",
    )

    private fun sampleTrack(id: String, title: String, artist: String, albumId: String, albumTitle: String) = MusicTrack(
        ratingKey = id,
        title = title,
        streamUrl = "https://srv/api/v1/tracks/$id/stream?mediaToken=tok",
        partKey = "part-$id",
        durationMillis = 200_000L,
        userRating = null,
        albumRatingKey = albumId,
        albumTitle = albumTitle,
        artistName = artist,
        thumbUrl = "https://srv/track/$id.jpg",
        sourceAudioCodec = "flac",
        sourceAudioBitrateKbps = 950,
        sourceAudioSampleRate = 44100,
        bitDepth = 16,
        playbackMimeType = null,
        sourceId = "srv1",
        artists = listOf(ArtistRef("art-$artist", artist)),
    )

    private fun sampleDownloaded(id: String, title: String, artist: String, albumId: String, albumTitle: String) =
        DownloadedAudioTrack(
            sourceId = "srv1",
            ratingKey = id,
            partKey = "part-$id",
            title = title,
            artistName = artist,
            albumTitle = albumTitle,
            albumRatingKey = albumId,
            durationMillis = 200_000L,
            sourceAudioCodec = "opus",
            sourceAudioBitrateKbps = 128,
            sourceAudioSampleRate = 48000,
            bitDepth = null,
            localAudioPath = "/data/audio/$id.ogg",
            localThumbPath = "/data/covers/$albumId.cover",
            localLyricPath = null,
            fileSize = 3_200_000L,
            downloadedAtEpochSeconds = 2000L,
            artists = listOf(ArtistRef("art-$artist", artist)),
        )

    @Test
    fun `offline search matches multi-token CJK and Latin queries and ranks best match`() {
        val snapshot = LocalLibrarySearchSnapshot(
            artists = listOf(
                MusicArtist(ratingKey = "10", title = "YOASOBI", thumbUrl = null, sourceId = "srv1"),
                MusicArtist(ratingKey = "11", title = "周杰伦", thumbUrl = null, sourceId = "srv1"),
            ),
            albums = listOf(
                sampleAlbum("100", "THE BOOK 3", "YOASOBI"),
                sampleAlbum("101", "范特西", "周杰伦"),
            ),
            tracks = listOf(
                sampleTrack("1001", "Idol", "YOASOBI", "100", "THE BOOK 3"),
                sampleTrack("1002", "晴天", "周杰伦", "101", "范特西"),
            ),
            downloadedTracks = listOf(
                sampleDownloaded("1001", "Idol", "YOASOBI", "100", "THE BOOK 3"),
            ),
        )

        val multiToken = searchLocalLibrary(snapshot, "yoasobi idol", downloadedOnly = false)
        assertFalse(multiToken.isEmpty)
        assertEquals(1, multiToken.tracks.total)
        assertEquals("1001", multiToken.tracks.items.first().ratingKey)
        // Downloaded track overrides streamUrl with local file path for offline playback.
        assertEquals("file:///data/audio/1001.ogg", multiToken.tracks.items.first().streamUrl)
        assertEquals("audio/ogg", multiToken.tracks.items.first().playbackMimeType)
        assertEquals(SearchCategory.Tracks, multiToken.bestMatch?.category)

        val cjk = searchLocalLibrary(snapshot, "周杰伦", downloadedOnly = false)
        assertEquals(1, cjk.artists.total)
        assertEquals(1, cjk.albums.total)
        assertEquals(1, cjk.tracks.total)
        assertEquals(SearchCategory.Artists, cjk.bestMatch?.category)
        assertEquals("周杰伦", cjk.bestMatch?.title)
    }

    @Test
    fun `downloadedOnly excludes un-downloaded catalog entries and derives albums and artists`() {
        val snapshot = LocalLibrarySearchSnapshot(
            artists = emptyList(), // Simulates user who downloaded tracks without syncing full library
            albums = listOf(
                sampleAlbum("200", "Online Only Album", "YOASOBI"),
            ),
            tracks = listOf(
                sampleTrack("2001", "Online Track", "YOASOBI", "200", "Online Only Album"),
            ),
            downloadedTracks = listOf(
                sampleDownloaded("3001", "Yoru ni Kakeru", "YOASOBI", "300", "THE BOOK"),
            ),
        )

        val res = searchLocalLibrary(snapshot, "YOASOBI", downloadedOnly = true)
        assertEquals(1, res.tracks.total)
        assertEquals("3001", res.tracks.items.single().ratingKey)
        assertEquals(1, res.albums.total)
        assertEquals("THE BOOK", res.albums.items.single().title)
        assertEquals("file:///data/covers/300.cover", res.albums.items.single().thumbUrl)
        assertEquals(1, res.artists.total)
        assertEquals("YOASOBI", res.artists.items.single().title)

        val onlineOnlyQuery = searchLocalLibrary(snapshot, "Online Track", downloadedOnly = true)
        assertTrue(onlineOnlyQuery.isEmpty)
        assertNull(onlineOnlyQuery.bestMatch)
    }

    @Test
    fun `category pagination slices tracks accurately`() {
        val tracks = (1..25).map { i ->
            sampleTrack(
                id = i.toString(),
                title = "Nocturne %02d".format(i),
                artist = "Chopin",
                albumId = "500",
                albumTitle = "Complete Nocturnes",
            )
        }
        val snapshot = LocalLibrarySearchSnapshot(tracks = tracks)

        val page1 = searchLocalLibrary(
            snapshot = snapshot,
            query = "Nocturne",
            downloadedOnly = false,
            category = SearchCategory.Tracks,
            offset = 0,
            limit = 10,
        )
        assertEquals(25, page1.tracks.total)
        assertEquals(10, page1.tracks.items.size)
        assertEquals("Nocturne 01", page1.tracks.items.first().title)

        val page3 = searchLocalLibrary(
            snapshot = snapshot,
            query = "Nocturne",
            downloadedOnly = false,
            category = SearchCategory.Tracks,
            offset = 20,
            limit = 10,
        )
        assertEquals(25, page3.tracks.total)
        assertEquals(5, page3.tracks.items.size)
        assertEquals("Nocturne 21", page3.tracks.items.first().title)
        assertNotNull(searchLocalLibrary(snapshot, "Nocturne", downloadedOnly = false).bestMatch)
    }
}
