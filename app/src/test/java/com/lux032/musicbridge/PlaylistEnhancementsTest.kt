package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.storage.DownloadTaskItem
import com.lux032.musicbridge.storage.DownloadTaskStatus
import com.lux032.musicbridge.storage.downloadedTrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistEnhancementsTest {
    private fun track(
        id: String,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        favorite: Boolean = false,
        sourceId: String = "s1",
    ) = MusicTrack(
        ratingKey = id,
        title = title,
        artistName = artist,
        albumTitle = album,
        durationMillis = 240_000L,
        streamUrl = "https://example.org/tracks/$id/stream",
        partKey = "part-$id",
        thumbUrl = null,
        userRating = if (favorite) 10f else null,
        sourceId = sourceId,
    )

    @Test fun filterMatchesMultiTokenAcrossTitleArtistAlbumAndPreservesOriginalIndex() {
        val tracks = listOf(
            track("1", "Merry Christmas Mr. Lawrence", artist = "坂本龍一", album = "Playing the Piano", favorite = true),
            track("2", "Sincerely", artist = "TRUE", album = "Violet Evergarden · 京阿尼原声", favorite = true),
            track("3", "Energy Flow", artist = "坂本龍一", album = "BTTB", favorite = false),
            track("4", "みちしるべ", artist = "茅原実里", album = "Violet Evergarden · 京阿尼原声", favorite = false),
        )
        val downloaded = setOf(downloadedTrackKey("s1", "1"), downloadedTrackKey("s1", "4"))

        // Multi-token AND across artist + album (case-insensitive)
        val byTokens = filterPlaylistTracks(
            tracks = tracks,
            filter = PlaylistFilterState(query = "  坂本   bttb "),
            downloadedTrackKeys = downloaded,
        )
        assertEquals(listOf(2), byTokens.map { it.originalIndex })
        assertEquals(listOf("3"), byTokens.map { it.track.ratingKey })

        // Combined downloaded + favorited chips
        val combined = filterPlaylistTracks(
            tracks = tracks,
            filter = PlaylistFilterState(downloadedOnly = true, favoritedOnly = true),
            downloadedTrackKeys = downloaded,
        )
        assertEquals(listOf(0), combined.map { it.originalIndex })

        // Downloaded + album keyword
        val dlAlbum = filterPlaylistTracks(
            tracks = tracks,
            filter = PlaylistFilterState(query = "京阿尼", downloadedOnly = true),
            downloadedTrackKeys = downloaded,
        )
        assertEquals(listOf(3), dlAlbum.map { it.originalIndex })
    }

    @Test fun findHighlightRangesMergesOverlapsAndIgnoresCase() {
        val ranges = findHighlightRanges("Violet Evergarden · 京阿尼原声", listOf("violet", "ver", "原声"))
        assertEquals(listOf(0..5, 8..10, 23..24), ranges)

        val merged = findHighlightRanges("abcdef", listOf("abc", "bcd"))
        assertEquals(listOf(0..3), merged)
    }

    @Test fun summarizePlaylistDownloadCoversAllPhasesAndScopesTasks() {
        val t1 = track("1", "A")
        val t2 = track("2", "B")
        val t3 = track("3", "C")
        val unrelated = track("99", "Other")
        val tracks = listOf(t1, t2, t3)
        val k1 = downloadedTrackKey("s1", "1")
        val k2 = downloadedTrackKey("s1", "2")
        val k3 = downloadedTrackKey("s1", "3")

        // None downloaded
        val none = summarizePlaylistDownload(tracks, emptySet(), emptyList(), emptyMap())
        assertEquals(PlaylistDownloadPhase.None, none.phase)
        assertEquals(0, none.downloadedCount)
        assertEquals(3, none.remainingCount)

        // Partial downloaded
        val partial = summarizePlaylistDownload(tracks, setOf(k1), emptyList(), emptyMap())
        assertEquals(PlaylistDownloadPhase.Partial, partial.phase)
        assertEquals(1, partial.downloadedCount)
        assertEquals(2, partial.remainingCount)
        assertEquals(1f / 3f, partial.fraction, 0.001f)

        // Downloading (ignores unrelated tasks)
        val downloading = summarizePlaylistDownload(
            tracks = tracks,
            downloadedTrackKeys = setOf(k1),
            downloadTasks = listOf(
                DownloadTaskItem(t2, DownloadTaskStatus.Downloading, progress = 0.5f),
                DownloadTaskItem(t3, DownloadTaskStatus.Queued, progress = 0f),
                DownloadTaskItem(unrelated, DownloadTaskStatus.Downloading, progress = 0.9f),
            ),
            downloadProgress = mapOf(k2 to 0.5f),
        )
        assertEquals(PlaylistDownloadPhase.Downloading, downloading.phase)
        assertEquals(listOf(k2, k3), downloading.activeTaskKeys)
        assertEquals(0.5f, downloading.fraction, 0.001f)

        // Paused only
        val paused = summarizePlaylistDownload(
            tracks = tracks,
            downloadedTrackKeys = setOf(k1),
            downloadTasks = listOf(DownloadTaskItem(t2, DownloadTaskStatus.Paused, progress = 0.5f)),
            downloadProgress = emptyMap(),
        )
        assertEquals(PlaylistDownloadPhase.Paused, paused.phase)
        assertEquals(listOf(k2), paused.pausedTaskKeys)

        // All done
        val allDone = summarizePlaylistDownload(tracks, setOf(k1, k2, k3), emptyList(), emptyMap())
        assertEquals(PlaylistDownloadPhase.AllDone, allDone.phase)
        assertEquals(1f, allDone.fraction, 0.001f)
        assertEquals(0, allDone.remainingCount)
    }

    @Test fun hasPlaylistOrderChangedDetectsPermutationDifferences() {
        val a = track("1", "A")
        val b = track("2", "B")
        val c = track("3", "C")
        assertFalse(hasPlaylistOrderChanged(listOf(a, b, c), listOf(a, b, c)))
        assertTrue(hasPlaylistOrderChanged(listOf(a, b, c), listOf(b, a, c)))
        assertTrue(hasPlaylistOrderChanged(listOf(a, b), listOf(a, b, c)))
    }
}
