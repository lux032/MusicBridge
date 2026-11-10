package com.lux032.musicbridge.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin

class SpectrumAnalyzerTest {

    private val frame = 1f / 60f

    private fun sine(hz: Double, rate: Int, n: Int, amplitude: Double = 0.5) =
        FloatArray(n) { i -> (amplitude * sin(2 * PI * hz * i / rate)).toFloat() }

    private fun dbfs(db: Double) = 10.0.pow(db / 20.0)

    /** Index of the log-spaced band (40 Hz – 16 kHz) that contains [hz]. */
    private fun expectedBand(hz: Double, bands: Int): Int {
        val lo = ln(SpectrumAnalyzer.MIN_HZ)
        val hi = ln(SpectrumAnalyzer.MAX_HZ)
        return ((ln(hz) - lo) / (hi - lo) * bands).toInt()
    }

    @Test
    fun `pure tone lights the band that contains it`() {
        val out = FloatArray(20)
        for ((hz, rate) in listOf(60.0 to 44_100, 1_000.0 to 48_000, 8_000.0 to 96_000, 500.0 to 192_000)) {
            val analyzer = SpectrumAnalyzer()
            val n = SpectrumAnalyzer.windowSizeFor(rate)
            analyzer.bands(sine(hz, rate, n), rate, out, frame)
            val loudest = out.indices.maxBy { out[it] }
            assertEquals("tone $hz Hz @ $rate", expectedBand(hz, out.size), loudest)
            assertTrue("tone $hz Hz should be near full scale, was ${out[loudest]}", out[loudest] > 0.8f)
        }
    }

    @Test
    fun `silence gives empty bars`() {
        val out = FloatArray(20) { 1f }
        SpectrumAnalyzer().bands(FloatArray(4096), 48_000, out, frame)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `bands well below the loudest one stay low`() {
        // Strong bass plus a 20 dB quieter mid tone: the mid bar must sit clearly lower,
        // which is the height spread the fixed-dBFS mapping lacked. It is not flattened
        // either: part of a band's shortfall is forgiven so quieter registers still move.
        val rate = 48_000
        val bass = sine(60.0, rate, 4096, 0.5)
        val mid = sine(1_000.0, rate, 4096, 0.5 * dbfs(-20.0))
        val mixed = FloatArray(4096) { bass[it] + mid[it] }
        val out = FloatArray(20)
        SpectrumAnalyzer().bands(mixed, rate, out, frame)
        val bassBar = out[expectedBand(60.0, 20)]
        val midBar = out[expectedBand(1_000.0, 20)]
        assertTrue("bass should be near the top, was $bassBar", bassBar > 0.8f)
        assertTrue("mid should be clearly lower, was $midBar vs $bassBar", bassBar - midBar > 0.3f)
        assertTrue("mid should still be visible, was $midBar", midBar > 0.2f)
    }

    @Test
    fun `quiet passage after a loud one stays lower`() {
        val analyzer = SpectrumAnalyzer()
        val out = FloatArray(20)
        val band = expectedBand(1_000.0, 20)
        analyzer.bands(sine(1_000.0, 48_000, 4096, 0.5), 48_000, out, frame)
        val loud = out[band]
        // One second later the same tone is 12 dB quieter; the reference has sunk only ~3 dB.
        analyzer.bands(sine(1_000.0, 48_000, 4096, 0.5 * dbfs(-12.0)), 48_000, out, 1f)
        val quiet = out[band]
        assertTrue("quiet ($quiet) should be well below loud ($loud)", loud - quiet > 0.3f)
    }

    @Test
    fun `near-silent material is not pumped up to full height`() {
        // A -55 dBFS tone into a fresh analyzer: the reference floor stops auto-gain from
        // stretching it to the top.
        val out = FloatArray(20)
        SpectrumAnalyzer().bands(sine(1_000.0, 48_000, 4096, dbfs(-55.0)), 48_000, out, frame)
        assertTrue("was ${out.max()}", out.max() < 0.6f)
    }

    @Test
    fun `treble follows only the middle of the window`() {
        // Bands above the split read the middle quarter, so a hi-hat lights up when it is
        // heard (window centre) and not while it is merely somewhere inside the long window.
        val rate = 48_000
        val n = 4096
        val band = expectedBand(6_000.0, 20)
        fun burstAt(start: Int): Float {
            val tone = sine(6_000.0, rate, n)
            val samples = FloatArray(n) { if (it in start until start + 512) tone[it] else 0f }
            val out = FloatArray(20)
            SpectrumAnalyzer().bands(samples, rate, out, frame)
            return out[band]
        }
        val centred = burstAt(n / 2 - 256)
        val early = burstAt(256)
        assertTrue("centred burst should show, was $centred", centred > 0.6f)
        assertTrue("early burst should be gone, was $early", early < 0.1f)
    }
}
