package com.lux032.musicbridge.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

class SpectrumAnalyzerTest {

    private fun sine(hz: Double, rate: Int, n: Int, amplitude: Double = 0.5) =
        FloatArray(n) { i -> (amplitude * sin(2 * PI * hz * i / rate)).toFloat() }

    /** Index of the log-spaced band (40 Hz – 16 kHz) that contains [hz]. */
    private fun expectedBand(hz: Double, bands: Int): Int {
        val lo = ln(SpectrumAnalyzer.MIN_HZ)
        val hi = ln(SpectrumAnalyzer.MAX_HZ)
        return ((ln(hz) - lo) / (hi - lo) * bands).toInt()
    }

    @Test
    fun `pure tone lights the band that contains it`() {
        val analyzer = SpectrumAnalyzer()
        val out = FloatArray(20)
        for ((hz, rate) in listOf(60.0 to 44_100, 1_000.0 to 48_000, 8_000.0 to 96_000, 500.0 to 192_000)) {
            val n = SpectrumAnalyzer.windowSizeFor(rate)
            analyzer.bands(sine(hz, rate, n), rate, out)
            val loudest = out.indices.maxBy { out[it] }
            assertEquals("tone $hz Hz @ $rate", expectedBand(hz, out.size), loudest)
            assertTrue("tone $hz Hz should be near full scale, was ${out[loudest]}", out[loudest] > 0.8f)
        }
    }

    @Test
    fun `silence gives empty bars`() {
        val out = FloatArray(20) { 1f }
        SpectrumAnalyzer().bands(FloatArray(4096), 48_000, out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `quieter tone gives a lower bar`() {
        val analyzer = SpectrumAnalyzer()
        val loud = FloatArray(20)
        val quiet = FloatArray(20)
        // −26 dBFS keeps the loud bar below full scale so the difference is not clipped.
        analyzer.bands(sine(1_000.0, 48_000, 4096, 0.05), 48_000, loud)
        analyzer.bands(sine(1_000.0, 48_000, 4096, 0.05 * exp(ln(10.0) * -30 / 20)), 48_000, quiet)
        val band = expectedBand(1_000.0, 20)
        assertTrue("loud bar must not clip, was ${loud[band]}", loud[band] < 0.99f)
        // −30 dB over a 60 dB range is half a bar.
        assertEquals(0.5f, loud[band] - quiet[band], 0.05f)
    }
}
