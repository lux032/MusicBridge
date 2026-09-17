package com.lux032.musicbridge.local

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingDecisionTest {
    @Test fun directFilesUseReadWriteCache() {
        assertEquals(PlaybackDataSourceRoute.DIRECT_CACHE, playbackDataSourceRoute(isTranscoded = false, isFullyCached = false))
        assertEquals(PlaybackDataSourceRoute.DIRECT_CACHE, playbackDataSourceRoute(isTranscoded = false, isFullyCached = true))
    }

    @Test fun incompleteTranscodeUsesPureUpstream() {
        assertEquals(PlaybackDataSourceRoute.TRANSCODE_UPSTREAM, playbackDataSourceRoute(isTranscoded = true, isFullyCached = false))
    }

    @Test fun completeTranscodeUsesReadOnlyCache() {
        assertEquals(PlaybackDataSourceRoute.TRANSCODE_CACHE, playbackDataSourceRoute(isTranscoded = true, isFullyCached = true))
    }

    @Test fun offlineFileUsesOfflineFileRoute() {
        assertEquals(PlaybackDataSourceRoute.OFFLINE_FILE, playbackDataSourceRoute(url = "file:///data/downloads/song.flac", isTranscoded = false, isFullyCached = false))
    }

    @Test fun wifiKeepsConfiguredQueuePrecacheDepthRegardlessOfCompleteness() {
        assertEquals(
            3,
            effectiveQueuePrecacheCount(
                isWifi = true,
                configuredCount = 3,
                currentTrackFullyLoaded = false,
            ),
        )
        assertEquals(
            3,
            effectiveQueuePrecacheCount(
                isWifi = true,
                configuredCount = 3,
                currentTrackFullyLoaded = true,
            ),
        )
    }

    @Test fun cellularPrecacheWaitsForCurrentTrackThenUsesControlledDepth() {
        assertEquals(
            0,
            effectiveQueuePrecacheCount(
                isWifi = false,
                configuredCount = 3,
                currentTrackFullyLoaded = false,
            ),
        )
        assertEquals(
            1,
            effectiveQueuePrecacheCount(
                isWifi = false,
                configuredCount = 3,
                currentTrackFullyLoaded = true,
            ),
        )
        assertEquals(
            0,
            effectiveQueuePrecacheCount(
                isWifi = false,
                configuredCount = 0,
                currentTrackFullyLoaded = true,
            ),
        )
    }

    @Test fun negativeConfiguredPrecacheDepthIsClampedToZero() {
        assertEquals(
            0,
            effectiveQueuePrecacheCount(
                isWifi = true,
                configuredCount = -1,
                currentTrackFullyLoaded = true,
            ),
        )
        assertEquals(
            0,
            effectiveQueuePrecacheCount(
                isWifi = false,
                configuredCount = -1,
                currentTrackFullyLoaded = true,
            ),
        )
    }

    @Test fun completeCacheMarksCurrentTrackFullyLoadedWithoutDuration() {
        assertEquals(
            true,
            isCurrentTrackFullyLoaded(
                isFullyCachedInCache = true,
                isLiveTranscode = false,
                bufferedPositionMs = C.TIME_UNSET,
                durationMs = null,
            ),
        )
    }

    @Test fun nonTranscodeBufferingToDurationDoesNotProveFullyLoaded() {
        assertEquals(
            false,
            isCurrentTrackFullyLoaded(
                isFullyCachedInCache = false,
                isLiveTranscode = false,
                bufferedPositionMs = 120_000L,
                durationMs = 120_000L,
            ),
        )
    }

    @Test fun liveTranscodeRequiresKnownPositiveDurationAndBufferedPosition() {
        assertEquals(
            false,
            isCurrentTrackFullyLoaded(false, true, 120_000L, null),
        )
        assertEquals(
            false,
            isCurrentTrackFullyLoaded(false, true, 120_000L, 0L),
        )
        assertEquals(
            false,
            isCurrentTrackFullyLoaded(false, true, C.TIME_UNSET, 120_000L),
        )
    }

    @Test fun liveTranscodeFullyLoadedToleranceHasConservativeBoundary() {
        val durationMs = 120_000L
        assertEquals(
            true,
            isCurrentTrackFullyLoaded(false, true, durationMs - 1_500L, durationMs),
        )
        assertEquals(
            false,
            isCurrentTrackFullyLoaded(false, true, durationMs - 1_501L, durationMs),
        )
        assertEquals(
            true,
            isCurrentTrackFullyLoaded(false, true, durationMs, durationMs),
        )
    }

}
