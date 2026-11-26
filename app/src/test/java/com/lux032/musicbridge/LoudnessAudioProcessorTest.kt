package com.lux032.musicbridge

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.lux032.musicbridge.local.LOUDNESS_CEILING_DB
import com.lux032.musicbridge.local.LoudnessAudioProcessor
import com.lux032.musicbridge.local.LoudnessGainPlan
import com.lux032.musicbridge.local.LoudnessSource
import com.lux032.musicbridge.local.dbToLinear
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class LoudnessAudioProcessorTest {
    private fun makePlan(gainDb: Double, useLimiter: Boolean = false) = LoudnessGainPlan(
        active = true,
        gainDb = gainDb,
        ceilingDb = LOUDNESS_CEILING_DB,
        useLimiter = useLimiter,
        source = LoudnessSource.TRACK,
        targetLufs = -11.0,
        peakReductionDb = if (useLimiter) 3.0 else 0.0,
    )

    private fun drainOutput(processor: LoudnessAudioProcessor): ByteArray {
        val out = processor.output
        val bytes = ByteArray(out.remaining())
        out.get(bytes)
        return bytes
    }

    @Test
    fun bypassAndZeroDbPlanPassThroughBitExact() {
        val processor = LoudnessAudioProcessor()
        val format = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(format, processor.configure(format))
        processor.flush()

        val raw = ByteArray(128) { (it * 37 - 64).toByte() }
        processor.queueInput(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN))
        assertArrayEquals(raw, drainOutput(processor))

        // Active plan at 0.0 dB with no limiter also stays bit-exact.
        processor.setGainPlan(makePlan(0.0, useLimiter = false))
        processor.flush()
        processor.queueInput(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN))
        assertArrayEquals(raw, drainOutput(processor))
    }

    @Test
    fun appliesGainAccuratelyAcrossPcm16Pcm24Pcm32AndFloat() {
        val gainDb = -6.0206 // ~0.5x linear
        val expectedFactor = dbToLinear(gainDb)
        val plan = makePlan(gainDb, useLimiter = false)

        // 1. PCM 16-bit
        run {
            val p = LoudnessAudioProcessor()
            p.setGainPlan(plan)
            p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
            p.flush()
            val input = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(20_000).putShort(-10_000).apply { flip() }
            p.queueInput(input)
            val out = ByteBuffer.wrap(drainOutput(p)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(20_000 * expectedFactor, out.short.toDouble(), 2.0)
            assertEquals(-10_000 * expectedFactor, out.short.toDouble(), 2.0)
        }

        // 2. PCM 24-bit signed little-endian (tests sign extension for negative values too!)
        run {
            val p = LoudnessAudioProcessor()
            p.setGainPlan(plan)
            p.configure(AudioFormat(96_000, 2, C.ENCODING_PCM_24BIT))
            p.flush()
            val pos24 = 4_000_000
            val neg24 = -3_000_000
            val input = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).apply {
                put((pos24 and 0xFF).toByte()); put(((pos24 ushr 8) and 0xFF).toByte()); put(((pos24 ushr 16) and 0xFF).toByte())
                put((neg24 and 0xFF).toByte()); put(((neg24 ushr 8) and 0xFF).toByte()); put(((neg24 ushr 16) and 0xFF).toByte())
                flip()
            }
            p.queueInput(input)
            val out = ByteBuffer.wrap(drainOutput(p)).order(ByteOrder.LITTLE_ENDIAN)
            fun read24(): Int {
                val b0 = out.get().toInt() and 0xFF
                val b1 = out.get().toInt() and 0xFF
                val b2 = out.get().toInt()
                return (b2 shl 16) or (b1 shl 8) or b0
            }
            assertEquals(pos24 * expectedFactor, read24().toDouble(), 8.0)
            assertEquals(neg24 * expectedFactor, read24().toDouble(), 8.0)
        }

        // 3. PCM 32-bit signed
        run {
            val p = LoudnessAudioProcessor()
            p.setGainPlan(plan)
            p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_32BIT))
            p.flush()
            val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(1_000_000_000).putInt(-500_000_000).apply { flip() }
            p.queueInput(input)
            val out = ByteBuffer.wrap(drainOutput(p)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1_000_000_000 * expectedFactor, out.int.toDouble(), 16.0)
            assertEquals(-500_000_000 * expectedFactor, out.int.toDouble(), 16.0)
        }

        // 4. PCM Float
        run {
            val p = LoudnessAudioProcessor()
            p.setGainPlan(plan)
            p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
            p.flush()
            val input = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(0.8f).putFloat(-0.4f).apply { flip() }
            p.queueInput(input)
            val out = ByteBuffer.wrap(drainOutput(p)).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals((0.8 * expectedFactor).toFloat(), out.float, 1e-4f)
            assertEquals((-0.4 * expectedFactor).toFloat(), out.float, 1e-4f)
        }
    }

    @Test
    fun liveMidStreamGainChangeRampsSmoothlyWithoutStepDiscontinuity() {
        val processor = LoudnessAudioProcessor()
        processor.configure(AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        processor.flush() // starts at 1.0 gain

        // Enable -6 dB attenuation mid-stream without flushing.
        processor.setGainPlan(makePlan(-6.0206, useLimiter = false))

        val frames = 128
        val input = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { input.putFloat(1.0f) }
        input.flip()

        processor.queueInput(input)
        val out = ByteBuffer.wrap(drainOutput(processor)).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(frames) { out.float }

        // First sample should still be near 1.0 (smooth ramp), midpoint ~0.75, end settled at ~0.5.
        assertTrue("expected smooth ramp start near 1.0, got ${samples[0]}", samples[0] > 0.95f)
        assertTrue("expected smooth ramp midpoint near 0.75, got ${samples[31]}", samples[31] in 0.70f..0.80f)
        assertEquals(0.5f, samples[frames - 1], 1e-3f)
    }

    @Test
    fun limiterPreventsClippingOnBoostedFloatAndDrainsEndOfStream() {
        val processor = LoudnessAudioProcessor()
        val plan = makePlan(gainDb = 6.0, useLimiter = true) // ~2.0x linear
        processor.setGainPlan(plan)
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()

        val frames = 1024
        val input = ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN)
        for (f in 0 until frames) {
            input.putFloat(0.8f)
            input.putFloat(0.4f)
        }
        input.flip()

        processor.queueInput(input)
        val outBytes = drainOutput(processor)
        val out = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN)
        val ceiling = plan.ceilingLinear
        var maxSeen = 0f
        while (out.hasRemaining()) {
            val v = abs(out.float)
            if (v > maxSeen) maxSeen = v
            assertTrue("exceeded ceiling $ceiling with $v", v <= ceiling + 1e-4f)
        }
        assertTrue("expected audible limited output near ceiling, got $maxSeen", maxSeen > ceiling * 0.9f)

        // End of stream drains the 5 ms lookahead tail (240 stereo frames at 48 kHz = 1920 bytes).
        processor.queueEndOfStream()
        val drained = drainOutput(processor)
        assertEquals(240 * 2 * 4, drained.size)
    }
}
