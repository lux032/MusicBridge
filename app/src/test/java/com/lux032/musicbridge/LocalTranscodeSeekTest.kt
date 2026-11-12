package com.lux032.musicbridge

import com.lux032.musicbridge.local.buildLocalSeekPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTranscodeSeekTest {
    @Test
    fun directFileKeepsNativeMedia3SeekPath() {
        val url = "https://032.example/api/v1/tracks/42/stream?mediaToken=token"

        val plan = buildLocalSeekPlan(url, 137_890L)

        assertFalse(plan.restartsLiveTranscode)
        assertEquals(url, plan.url)
        assertEquals(137_890L, plan.playerPositionMillis)
        assertEquals(0L, plan.logicalBaseMillis)
        assertEquals(137_890L, plan.effectiveTargetMillis)
    }

    @Test
    fun liveTranscodeRestartsAtMillisecondOffset() {
        val url = "https://032.example/api/v1/tracks/42/transcode.ogg?mediaToken=token&bitrate=128"

        val plan = buildLocalSeekPlan(url, 137_890L, 180_000L)

        assertTrue(plan.restartsLiveTranscode)
        assertTrue(plan.url.contains("offsetMs=137890"))
        assertEquals(0L, plan.playerPositionMillis)
        assertEquals(137_890L, plan.logicalBaseMillis)
        assertEquals(137_890L, plan.effectiveTargetMillis)
    }
}
