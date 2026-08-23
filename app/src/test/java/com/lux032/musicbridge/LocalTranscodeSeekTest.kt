package com.lux032.musicbridge

import com.lux032.musicbridge.local.buildLocalSeekPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscodeSeekTest {
    @Test
    fun directFileKeepsNativeMedia3SeekPath() {
        val url = "https://plex.example/library/parts/42/file.flac?X-Plex-Token=token"

        val plan = buildLocalSeekPlan(url, 137_890L)

        assertFalse(plan.restartsLiveTranscode)
        assertEquals(url, plan.url)
        assertEquals(137_890L, plan.playerPositionMillis)
        assertEquals(0L, plan.logicalBaseMillis)
        assertEquals(137_890L, plan.effectiveTargetMillis)
    }

    @Test
    fun liveTranscodeRestartsAtWholeSecondPlexOffset() {
        val url = "https://plex.example/music/:/transcode/universal/start.mp3?offset=0&path=%2Flibrary%2Fmetadata%2F42"

        val plan = buildLocalSeekPlan(url, 137_890L)

        assertTrue(plan.restartsLiveTranscode)
        assertTrue(plan.url.contains("offset=137"))
        assertEquals(0L, plan.playerPositionMillis)
        assertEquals(137_000L, plan.logicalBaseMillis)
        assertEquals(137_000L, plan.effectiveTargetMillis)
    }
}
