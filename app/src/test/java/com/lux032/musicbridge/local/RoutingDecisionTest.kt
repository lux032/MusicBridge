package com.lux032.musicbridge.local

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

}
