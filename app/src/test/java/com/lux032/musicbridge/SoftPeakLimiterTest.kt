package com.lux032.musicbridge

import com.lux032.musicbridge.local.SoftPeakLimiter
import com.lux032.musicbridge.local.dbToLinear
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class SoftPeakLimiterTest {
    @Test
    fun belowCeilingSinePassesThroughBitIdentical() {
        val sr = 48_000
        val lim = SoftPeakLimiter(sr, channelCount = 2)
        val delay = lim.latencyFrames
        val limit = dbToLinear(-1.0).toFloat()
        val amp = dbToLinear(-1.5).toFloat()

        val total = delay + 1024
        val input = FloatArray(total * 2)
        for (f in 0 until total) {
            val s = (amp * sin(2.0 * PI * 1000.0 * f / sr)).toFloat()
            input[f * 2] = s
            input[f * 2 + 1] = -s * 0.5f
        }
        val out = input.copyOf()
        lim.processInPlace(total, out, limit, limitActive = true)

        for (f in 0 until 1024) {
            val outIdx = (f + delay) * 2
            val inIdx = f * 2
            assertEquals("L frame $f altered", input[inIdx], out[outIdx], 0f)
            assertEquals("R frame $f altered", input[inIdx + 1], out[outIdx + 1], 0f)
        }
    }

    @Test
    fun transientSpikeIsClampedToCeilingWithStereoLink() {
        val sr = 48_000
        val lim = SoftPeakLimiter(sr, channelCount = 2)
        val delay = lim.latencyFrames
        val limit = dbToLinear(-1.0).toFloat()

        val total = 4_800
        val buf = FloatArray(total * 2)
        for (f in 500 until 700) {
            buf[f * 2] = 1.6f
            buf[f * 2 + 1] = 0.8f
        }
        lim.processInPlace(total, buf, limit, limitActive = true)

        var maxOut = 0f
        for (f in 0 until total) {
            val l = buf[f * 2]
            val r = buf[f * 2 + 1]
            maxOut = max(maxOut, max(abs(l), abs(r)))
            assertTrue("frame $f exceeded ceiling: L=$l R=$r limit=$limit", abs(l) <= limit + 1e-5f && abs(r) <= limit + 1e-5f)
            if (f in (500 + delay) until (700 + delay)) {
                assertEquals("stereo 2:1 ratio broken at frame $f", 2f * r, l, 1e-5f)
            }
        }
        assertTrue("limiter over-attenuated: $maxOut", maxOut > limit * 0.9f)
    }

    @Test
    fun releaseRecoversToUnityAndResetClearsState() {
        val sr = 44_100
        val lim = SoftPeakLimiter(sr, channelCount = 2)
        val limit = dbToLinear(-1.0).toFloat()

        val total = 20_000
        val buf = FloatArray(total * 2)
        for (f in 0 until 400) {
            buf[f * 2] = 2.0f
            buf[f * 2 + 1] = 2.0f
        }
        for (f in 400 until total) {
            buf[f * 2] = 0.25f
            buf[f * 2 + 1] = 0.25f
        }
        lim.processInPlace(total, buf, limit, limitActive = true)
        assertEquals(0.25f, buf[(total - 1) * 2], 1e-3f)

        lim.reset()
        val zeros = FloatArray(lim.latencyFrames * 2)
        lim.processInPlace(lim.latencyFrames, zeros, limit, limitActive = true)
        assertTrue(zeros.all { it == 0f })
    }

    @Test
    fun inactiveLimiterKeepsFixedDelayWithoutAttenuation() {
        val sr = 48_000
        val lim = SoftPeakLimiter(sr, channelCount = 2)
        val delay = lim.latencyFrames
        val limit = dbToLinear(-1.0).toFloat()

        val total = delay + 64
        val buf = FloatArray(total * 2) { 1.5f }
        lim.processInPlace(total, buf, limit, limitActive = false)

        assertEquals(0f, buf[0], 0f)
        assertEquals(1.5f, buf[delay * 2], 0f)
        assertEquals(1.5f, buf[(delay + 10) * 2 + 1], 0f)
    }
}
