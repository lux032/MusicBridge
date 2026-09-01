package com.lux032.musicbridge.local

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutingDecisionTest {
    @Test fun directFilesUseReadWriteCache() {
        assertEquals(PlaybackDataSourceRoute.DIRECT_CACHE, playbackDataSourceRoute(false, false))
        assertEquals(PlaybackDataSourceRoute.DIRECT_CACHE, playbackDataSourceRoute(false, true))
    }

    @Test fun incompleteTranscodeUsesPureUpstream() {
        assertEquals(PlaybackDataSourceRoute.TRANSCODE_UPSTREAM, playbackDataSourceRoute(true, false))
    }

    @Test fun completeTranscodeUsesReadOnlyCache() {
        assertEquals(PlaybackDataSourceRoute.TRANSCODE_CACHE, playbackDataSourceRoute(true, true))
    }
}
