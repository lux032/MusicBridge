package com.lux032.musicbridge

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicPlaylist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.PlaylistTracksResult
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.storage.DownloadedAlbumGroup
import com.lux032.musicbridge.storage.DownloadedAudioTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SimilarRadioQueueLifecycleTest {

    private fun sampleTrack(id: Int, sourceId: String = "srv-032"): MusicTrack = MusicTrack(
        ratingKey = id.toString(),
        title = "Track $id",
        streamUrl = "https://example.com/api/v1/tracks/$id/stream",
        partKey = "/api/v1/tracks/$id/stream",
        durationMillis = 200_000L,
        userRating = null,
        artistName = "Artist",
        albumTitle = "Album",
        albumRatingKey = "100",
        sourceId = sourceId,
    )

    private fun samplePlaylistResult(count: Int, sourceId: String = "srv-032"): PlaylistTracksResult {
        val tracks = (1..count).map { sampleTrack(it, sourceId) }
        return PlaylistTracksResult(
            serverName = "032",
            playlist = MusicPlaylist(
                ratingKey = "artist:TestArtist",
                title = "TestArtist",
                thumbUrl = null,
                leafCount = tracks.size,
                sourceId = sourceId,
            ),
            tracks = tracks,
        )
    }

    @Test
    fun startPlaylistPlaybackResetsStaleManualBoundaryAndRadioState() {
        val app: Application = RuntimeEnvironment.getApplication()
        Strings.init(app)
        val store = ViewModelStore()
        try {
            val state = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[AppState::class.java]

            // Simulate a previous 2-track session with auto-continue tracks & stale radio metadata.
            state.similarRadioState = SimilarRadioState(
                autoPlayEnabled = true,
                manualQueueCount = 2,
                radioSeed = SimilarRadioSeed("srv-032", "999", "Old Seed"),
                lastAutoSeedTitle = "Old Seed",
                similarReasons = mapOf("srv-032:1001" to listOf("soundsSimilar")),
                isLoading = false,
                errorKind = SimilarRadioErrorKind.Empty,
            )

            // Importing a large playlist (the exact path used by Artist page shuffle) must reset
            // manualQueueCount to the full queue size and clear stale radio state.
            val largeArtistQueue = samplePlaylistResult(count = 40)
            state.startPlaylistPlayback(largeArtistQueue, shuffle = false)

            val player = state.miniPlayerState!!
            assertEquals(40, player.tracks.size)
            assertEquals(0, player.currentIndex)
            assertEquals(40, state.similarRadioState.manualQueueCount)
            assertNull(state.similarRadioState.radioSeed)
            assertNull(state.similarRadioState.lastAutoSeedTitle)
            assertFalse(state.similarRadioState.hasAutoTracks)
            assertEquals(emptyMap<String, List<String>>(), state.similarRadioState.similarReasons)
            assertNull(state.similarRadioState.errorKind)

            // Toggling auto-play off must NOT truncate the imported manual queue to 2 tracks.
            state.setAutoPlaySimilarEnabled(false)
            assertEquals(40, state.miniPlayerState!!.tracks.size)
            assertEquals(40, state.similarRadioState.manualQueueCount)

            // Re-enabling auto-play and enqueueing at End must insert at the end of all 40 tracks, not index 2.
            state.setAutoPlaySimilarEnabled(true)
            state.enqueueTrack(sampleTrack(500), QueuePlacement.End)
            assertEquals(41, state.miniPlayerState!!.tracks.size)
            assertEquals("500", state.miniPlayerState!!.tracks.last().ratingKey)
            assertEquals(41, state.similarRadioState.manualQueueCount)
        } finally {
            store.clear()
        }
    }

    @Test
    fun playDownloadedAlbumResetsStaleManualQueueCount() {
        val app: Application = RuntimeEnvironment.getApplication()
        Strings.init(app)
        val store = ViewModelStore()
        try {
            val state = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[AppState::class.java]

            state.similarRadioState = SimilarRadioState(
                autoPlayEnabled = true,
                manualQueueCount = 2,
                lastAutoSeedTitle = "Previous Single",
            )

            val downloadedTracks = (1..12).map { id ->
                DownloadedAudioTrack(
                    sourceId = "srv-032",
                    ratingKey = id.toString(),
                    partKey = "/api/v1/tracks/$id/stream",
                    title = "Offline $id",
                    artistName = "Artist",
                    albumTitle = "Offline Album",
                    albumRatingKey = "50",
                    durationMillis = 180_000L,
                    sourceAudioCodec = "flac",
                    sourceAudioBitrateKbps = 900,
                    sourceAudioSampleRate = 44100,
                    bitDepth = 16,
                    localAudioPath = "/tmp/fake_$id.flac",
                    localThumbPath = null,
                    localLyricPath = null,
                    fileSize = 1024L,
                    downloadedAtEpochSeconds = 1L,
                )
            }
            val group = DownloadedAlbumGroup(
                albumRatingKey = "50",
                albumTitle = "Offline Album",
                artistName = "Artist",
                thumbPath = null,
                sourceId = "srv-032",
                tracks = downloadedTracks,
                totalSizeBytes = 12 * 1024L,
            )

            state.playDownloadedAlbum(group, startIndex = 0)

            assertEquals(12, state.miniPlayerState!!.tracks.size)
            assertEquals(12, state.similarRadioState.manualQueueCount)
            assertNull(state.similarRadioState.lastAutoSeedTitle)
            assertFalse(state.similarRadioState.hasAutoTracks)
        } finally {
            store.clear()
        }
    }

    @Test
    fun startSingleTrackPlaybackThenSwitchBackPreservesFullManualBoundary() {
        val app: Application = RuntimeEnvironment.getApplication()
        Strings.init(app)
        val store = ViewModelStore()
        try {
            val state = ViewModelProvider(
                store,
                ViewModelProvider.AndroidViewModelFactory.getInstance(app),
            )[AppState::class.java]

            val album = MusicAlbum(
                ratingKey = "100",
                title = "Single CD",
                artistName = "Artist",
                year = 2025,
                thumbUrl = null,
                userRating = null,
                addedAtEpochSeconds = null,
                lastViewedAtEpochSeconds = null,
                section = LibrarySection("032", "032", "artist"),
                sourceId = "srv-032",
            )
            // Start with a 2-track single (sets manualQueueCount = 2).
            state.startSingleTrackPlayback(album, listOf(sampleTrack(1), sampleTrack(2)), 0)
            assertEquals(2, state.similarRadioState.manualQueueCount)

            // Now shuffle-import 30 tracks via playlist path (artist shuffle).
            state.startPlaylistPlayback(samplePlaylistResult(count = 30), shuffle = true)
            assertEquals(30, state.miniPlayerState!!.tracks.size)
            assertEquals(30, state.similarRadioState.manualQueueCount)
            assertFalse(state.similarRadioState.hasAutoTracks)

            // Refreshing auto batch while no auto tracks exist must be a no-op (never truncate!).
            state.refreshAutoSimilarBatch()
            assertEquals(30, state.miniPlayerState!!.tracks.size)
            assertEquals(30, state.similarRadioState.manualQueueCount)
        } finally {
            store.clear()
        }
    }
}
