package com.lux032.musicbridge.local

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionPrecacheTest {
    @Test fun releasedDirectOriginalQueuesOnlyOnWifiWhenIncomplete() {
        val original = item(isTranscoded = false, isCachedOriginal = false)
        assertTrue(shouldEnqueueCompletionPrecache(true, original, false))
        assertFalse(shouldEnqueueCompletionPrecache(false, original, false))
        assertFalse(shouldEnqueueCompletionPrecache(true, original, true))
        assertFalse(shouldEnqueueCompletionPrecache(true, item(isTranscoded = true), false))
        assertFalse(shouldEnqueueCompletionPrecache(true, item(isCachedOriginal = true), false))
    }

    @Test fun batchFiltersCellularCompletionAndProtectedOrCachedItemsAndDeduplicates() {
        val completion = listOf(item("released"), item("current"), item("cached", isCachedOriginal = true))
        val upcoming = listOf(item("next"), item("future"), item("future"))

        assertEquals(
            listOf("released", "future"),
            resolvePrecacheBatch(completion, upcoming, true, "current", "next").map { it.cacheKey },
        )
        assertEquals(
            listOf("future"),
            resolvePrecacheBatch(completion, upcoming, false, "current", "next").map { it.cacheKey },
        )
    }

    @Test fun pcmEncodingMapsToSourceBitDepth() {
        assertEquals(16, pcmBitDepth(C.ENCODING_PCM_16BIT))
        assertEquals(24, pcmBitDepth(C.ENCODING_PCM_24BIT))
        assertEquals(32, pcmBitDepth(C.ENCODING_PCM_32BIT))
        assertEquals(null, pcmBitDepth(C.ENCODING_INVALID))
    }

    private fun item(
        key: String = "key",
        isTranscoded: Boolean = false,
        isCachedOriginal: Boolean = false,
    ) = LocalQueueItem(
        url = "https://plex/file.flac", cacheKey = key, isTranscoded = isTranscoded,
        title = "Track", artist = null, artworkUrl = null, durationMillis = 1_000,
        isCachedOriginal = isCachedOriginal,
    )
}
