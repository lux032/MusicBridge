package com.lux032.musicbridge.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCacheRoutingTest {
    @Test
    fun cacheKeysAreStableAndOutputSpecific() {
        val original = originalCacheKey("server", "42", "/library/parts/7/file.flac")
        val sameOriginal = originalCacheKey("server", "42", "/library/parts/7/file.flac")
        val transcode = transcodedCacheKey("server", "42", "/library/parts/7/file.flac", 128)

        assertEquals(original, sameOriginal)
        assertNotEquals(original, transcode)
        assertEquals(70, original.length)
    }

    @Test
    fun cacheHitDecisionRequiresBothTranscodeNeedAndCompleteOriginal() {
        assertTrue(shouldUseCachedOriginal(needsTranscode = true, isFullyCached = true))
        assertFalse(shouldUseCachedOriginal(needsTranscode = true, isFullyCached = false))
        assertFalse(shouldUseCachedOriginal(needsTranscode = false, isFullyCached = true))
        assertFalse(shouldUseCachedOriginal(needsTranscode = false, isFullyCached = false))
    }

    @Test
    fun cachedOriginalFallbackReplacesWholeItemAndResetsPositionBase() {
        val fallback = item("transcode", isTranscoded = true)
        val cached = item("original", isCachedOriginal = true, fallbackItem = fallback)

        val result = cachedOriginalFallback(cached)

        assertEquals(fallback, result?.replacement)
        assertEquals(0L, result?.positionBaseMillis)
        assertNull(cachedOriginalFallback(item("ordinary")))
    }

    @Test
    fun precacheSelectionExcludesCachedOriginalItems() {
        val direct = item("direct")
        val cachedOriginal = item("cached", isCachedOriginal = true)
        val transcode = item("transcode", isTranscoded = true)

        assertEquals(listOf(direct, transcode), filterPrecacheItems(listOf(direct, cachedOriginal, transcode)))
    }

    @Test
    fun cachedOriginalAlwaysUsesReadOnlyRoute() {
        listOf(false, true).forEach { isTranscoded ->
            listOf(false, true).forEach { isFullyCached ->
                assertEquals(
                    PlaybackDataSourceRoute.CACHED_ORIGINAL,
                    playbackDataSourceRoute(isTranscoded, isFullyCached, isCachedOriginal = true),
                )
            }
        }
    }

    @Test
    fun cacheOnlyUpstreamIsFailClosedAndNeverAnHttpDataSource() {
        val upstream: androidx.media3.datasource.DataSource = FailingDataSource()

        assertEquals(FailingDataSource::class.java, upstream.javaClass)
        assertEquals("Cache span evicted or missing for transcode stream", missingTranscodeCacheSpanException().message)
        assertTrue(isMissingCacheSpanError(java.io.IOException(missingTranscodeCacheSpanException())))
    }

    @Test
    fun normalRoutingRemainsUnchanged() {
        assertEquals(
            PlaybackDataSourceRoute.DIRECT_CACHE,
            playbackDataSourceRoute(isTranscoded = false, isFullyCached = true),
        )
        assertEquals(
            PlaybackDataSourceRoute.TRANSCODE_CACHE,
            playbackDataSourceRoute(isTranscoded = true, isFullyCached = true),
        )
        assertEquals(
            PlaybackDataSourceRoute.TRANSCODE_UPSTREAM,
            playbackDataSourceRoute(isTranscoded = true, isFullyCached = false),
        )
    }

    private fun item(
        key: String,
        isTranscoded: Boolean = false,
        isCachedOriginal: Boolean = false,
        fallbackItem: LocalQueueItem? = null,
    ) = LocalQueueItem(
        url = "https://plex.example/$key",
        cacheKey = key,
        isTranscoded = isTranscoded,
        title = key,
        artist = null,
        artworkUrl = null,
        durationMillis = 1_000L,
        isCachedOriginal = isCachedOriginal,
        fallbackItem = fallbackItem,
    )
}
