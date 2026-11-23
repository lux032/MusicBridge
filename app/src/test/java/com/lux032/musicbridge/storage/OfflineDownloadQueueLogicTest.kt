package com.lux032.musicbridge.storage

import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.MusicTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineDownloadQueueLogicTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parseContentRangeHandlesValidWildcardAndMalformedHeaders() {
        assertEquals(ContentRangeInfo(1024L, 4095L, 4096L), parseContentRange("bytes 1024-4095/4096"))
        assertEquals(ContentRangeInfo(0L, 99L, -1L), parseContentRange("BYTES 0-99/*"))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange("bytes */4096"))
        assertNull(parseContentRange("bytes 500-400/1000"))
        assertNull(parseContentRange("bytes 0-1000/1000"))
    }

    @Test
    fun resolveResumePlanHandles206Append200Fallback416ResetAndErrors() {
        val append = resolveResumePlan(
            statusCode = 206,
            existingBytes = 1000L,
            contentLength = 3000L,
            contentRangeHeader = "bytes 1000-3999/4000",
        )
        assertEquals(ResumeResponsePlan.Append(existingBytes = 1000L, expectedTotalBytes = 4000L), append)

        val mismatchedRange = resolveResumePlan(
            statusCode = 206,
            existingBytes = 1000L,
            contentLength = 3000L,
            contentRangeHeader = "bytes 0-2999/4000",
        )
        assertEquals(ResumeResponsePlan.ResetPartialAndRetry, mismatchedRange)

        val fallback200 = resolveResumePlan(
            statusCode = 200,
            existingBytes = 1000L,
            contentLength = 4000L,
            contentRangeHeader = null,
        )
        assertEquals(ResumeResponsePlan.RestartFromZero(expectedTotalBytes = 4000L), fallback200)

        val reset416 = resolveResumePlan(
            statusCode = 416,
            existingBytes = 5000L,
            contentLength = -1L,
            contentRangeHeader = "bytes */4000",
        )
        assertEquals(ResumeResponsePlan.ResetPartialAndRetry, reset416)

        val serverError = resolveResumePlan(
            statusCode = 503,
            existingBytes = 0L,
            contentLength = -1L,
            contentRangeHeader = null,
        )
        assertEquals(ResumeResponsePlan.Fail(503), serverError)
    }

    @Test
    fun normalizeRestoredDownloadTasksPausesInterruptedActiveWorkConservatively() {
        val track = sampleTrack("t1")
        val normalized = normalizeRestoredDownloadTasks(
            listOf(
                DownloadTaskItem(track = track, status = DownloadTaskStatus.Downloading, progress = 0.4f),
                DownloadTaskItem(track = sampleTrack("t2"), status = DownloadTaskStatus.Queued),
                DownloadTaskItem(track = sampleTrack("t3"), status = DownloadTaskStatus.Paused, progress = 0.2f),
                DownloadTaskItem(track = sampleTrack("t4"), status = DownloadTaskStatus.Failed, errorMessage = "HTTP 500"),
            )
        )
        assertEquals(
            listOf(
                DownloadTaskStatus.Paused,
                DownloadTaskStatus.Paused,
                DownloadTaskStatus.Paused,
                DownloadTaskStatus.Failed,
            ),
            normalized.map { it.status },
        )
        assertEquals("HTTP 500", normalized[3].errorMessage)
    }

    @Test
    fun queueStoreRoundTripsTasksAndDeletesFileWhenEmpty() {
        val dir = tempFolder.newFolder("downloads")
        val store = OfflineDownloadQueueStore(dir)
        val tasks = listOf(
            DownloadTaskItem(
                track = sampleTrack("101"),
                status = DownloadTaskStatus.Paused,
                progress = 0.5f,
                bytesDownloaded = 2048L,
                totalBytes = 4096L,
                errorMessage = null,
                enqueuedAtMillis = 123456L,
            ),
            DownloadTaskItem(
                track = sampleTrack("102"),
                status = DownloadTaskStatus.Failed,
                progress = 0.1f,
                bytesDownloaded = 100L,
                totalBytes = 1000L,
                errorMessage = "Network timeout",
                enqueuedAtMillis = 123457L,
            ),
        )

        store.save(tasks)
        assertTrue(File(dir, "download_queue.json").exists())

        val reloaded = store.load()
        assertEquals(2, reloaded.size)
        assertEquals("srv-1:101", reloaded[0].taskKey)
        assertEquals(DownloadTaskStatus.Paused, reloaded[0].status)
        assertEquals(2048L, reloaded[0].bytesDownloaded)
        assertEquals(4096L, reloaded[0].totalBytes)
        assertEquals(DownloadTaskStatus.Failed, reloaded[1].status)
        assertEquals("Network timeout", reloaded[1].errorMessage)
        assertEquals(listOf(ArtistRef("a1", "Artist One")), reloaded[0].track.artists)

        store.save(emptyList())
        assertFalse(File(dir, "download_queue.json").exists())
        assertTrue(store.load().isEmpty())
    }

    private fun sampleTrack(ratingKey: String) = MusicTrack(
        ratingKey = ratingKey,
        title = "Track $ratingKey",
        streamUrl = "https://example.com/tracks/$ratingKey/stream",
        partKey = "/parts/$ratingKey.flac",
        durationMillis = 200_000L,
        userRating = null,
        albumRatingKey = "alb-1",
        albumTitle = "Test Album",
        artistName = "Artist One",
        sourceId = "srv-1",
        artists = listOf(ArtistRef("a1", "Artist One")),
    )
}
