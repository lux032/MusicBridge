package com.lux032.musicbridge

import com.lux032.musicbridge.local.LoudnessGainPlan
import com.lux032.musicbridge.local.LoudnessSource
import com.lux032.musicbridge.local.LoudnessTrackMetrics
import com.lux032.musicbridge.local.computeLoudnessPlan
import com.lux032.musicbridge.local.effectiveTargetLufs
import com.lux032.musicbridge.local.smartUseAlbumForQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessNormalizationTest {
    private fun enabledLoudSettings(
        target: LoudnessTarget = LoudnessTarget.LOUD,
        mode: LoudnessMode = LoudnessMode.SMART,
        peak: LoudnessPeakProtection = LoudnessPeakProtection.SOFT_LIMITER,
    ) = LoudnessNormalizationSettings(
        enabled = true,
        target = target,
        mode = mode,
        peakProtection = peak,
    )

    @Test
    fun disabledOrUnsupportedServerReturnsBypass() {
        val metrics = LoudnessTrackMetrics(loudnessLufs = -8.0, truePeakDb = 0.2)
        assertEquals(
            LoudnessGainPlan.BYPASS,
            computeLoudnessPlan(metrics, LoudnessNormalizationSettings(enabled = false), false, -9.0, true),
        )
        assertEquals(
            LoudnessGainPlan.BYPASS,
            computeLoudnessPlan(metrics, enabledLoudSettings(), false, -9.0, false),
        )
    }

    @Test
    fun libraryMedianTargetClampsAndLeavesMedianTrackUntouchedWithoutLimiter() {
        assertEquals(-9.0, LoudnessTarget.LIBRARY_MEDIAN.effectiveTargetLufs(-9.0), 1e-6)
        assertEquals(-8.0, LoudnessTarget.LIBRARY_MEDIAN.effectiveTargetLufs(-6.0), 1e-6)
        assertEquals(-16.0, LoudnessTarget.LIBRARY_MEDIAN.effectiveTargetLufs(-20.0), 1e-6)
        assertEquals(-11.0, LoudnessTarget.LIBRARY_MEDIAN.effectiveTargetLufs(null), 1e-6)
        assertEquals(-11.0, LoudnessTarget.LIBRARY_MEDIAN.effectiveTargetLufs(Double.NaN), 1e-6)

        val settings = enabledLoudSettings(target = LoudnessTarget.LIBRARY_MEDIAN, mode = LoudnessMode.TRACK)
        // Median track (-9.0 LUFS / -0.2 dBTP): 0 dB gain, zero limiter bypass!
        val mid = LoudnessTrackMetrics(loudnessLufs = -9.0, truePeakDb = -0.2)
        val pMid = computeLoudnessPlan(mid, settings, false, -9.0, true)
        assertTrue(pMid.active)
        assertEquals(-9.0, pMid.targetLufs, 1e-6)
        assertEquals(0.0, pMid.gainDb, 1e-6)
        assertFalse(pMid.useLimiter)
        assertEquals(0.0, pMid.peakReductionDb, 1e-6)

        // Hot track (-7.5 LUFS / 0.0 dBTP): gentle -1.5 dB linear trim, zero limiter.
        val hot = LoudnessTrackMetrics(loudnessLufs = -7.5, truePeakDb = 0.0)
        val pHot = computeLoudnessPlan(hot, settings, false, -9.0, true)
        assertEquals(-1.5, pHot.gainDb, 1e-6)
        assertFalse(pHot.useLimiter)
    }

    @Test
    fun modernLoudTrackAttenuatesLinearlyAndSoftVsStrongLimiterCapQuietTracks() {
        val hot = LoudnessTrackMetrics(loudnessLufs = -8.0, truePeakDb = 0.5)
        val pHot = computeLoudnessPlan(hot, enabledLoudSettings(), false, -10.0, true)
        assertEquals(LoudnessSource.TRACK, pHot.source)
        assertEquals(-3.0, pHot.gainDb, 1e-6)
        assertFalse(pHot.useLimiter)

        // -14 LUFS / -1.0 dBTP at -11 target -> +3.0 dB boost, 3.0 dB transient GR (within 3.5 dB cap).
        val older = LoudnessTrackMetrics(loudnessLufs = -14.0, truePeakDb = -1.0)
        val pOlder = computeLoudnessPlan(older, enabledLoudSettings(), false, -10.0, true)
        assertEquals(3.0, pOlder.gainDb, 1e-6)
        assertTrue(pOlder.useLimiter)
        assertEquals(3.0, pOlder.peakReductionDb, 1e-6)

        // -22 LUFS / -1.0 dBTP -> capped at +3.5 dB under SoftLimiter.
        val classical = LoudnessTrackMetrics(loudnessLufs = -22.0, truePeakDb = -1.0)
        val pCls = computeLoudnessPlan(classical, enabledLoudSettings(), false, -10.0, true)
        assertEquals(3.5, pCls.gainDb, 1e-6)
        assertTrue(pCls.useLimiter)
        assertEquals(3.5, pCls.peakReductionDb, 1e-6)

        // Quiet OST (-15.0 LUFS / -0.5 dBTP) at -9.0 median:
        // SoftLimiter caps at (-1.0 - -0.5) + 3.5 = +3.0 dB; StrongLimiter reaches +5.5 dB (6.0 dB GR).
        val ost = LoudnessTrackMetrics(loudnessLufs = -15.0, truePeakDb = -0.5)
        val softMedian = enabledLoudSettings(LoudnessTarget.LIBRARY_MEDIAN, LoudnessMode.TRACK, LoudnessPeakProtection.SOFT_LIMITER)
        val strongMedian = softMedian.copy(peakProtection = LoudnessPeakProtection.STRONG_LIMITER)
        val pSoft = computeLoudnessPlan(ost, softMedian, false, -9.0, true)
        assertEquals(3.0, pSoft.gainDb, 1e-6)
        assertTrue(pSoft.useLimiter)
        assertEquals(3.5, pSoft.peakReductionDb, 1e-6)

        val pStrong = computeLoudnessPlan(ost, strongMedian, false, -9.0, true)
        assertEquals(5.5, pStrong.gainDb, 1e-6)
        assertTrue(pStrong.useLimiter)
        assertEquals(6.0, pStrong.peakReductionDb, 1e-6)
    }

    @Test
    fun quietLowPeakBoostsLinearlyAndStrictLinearNeverEngagesLimiter() {
        val lowPeak = LoudnessTrackMetrics(loudnessLufs = -16.0, truePeakDb = -6.0)
        val pLin = computeLoudnessPlan(lowPeak, enabledLoudSettings(), false, null, true)
        assertEquals(5.0, pLin.gainDb, 1e-6)
        assertFalse(pLin.useLimiter)

        val strict = enabledLoudSettings(peak = LoudnessPeakProtection.STRICT_LINEAR)
        val older = LoudnessTrackMetrics(loudnessLufs = -14.0, truePeakDb = -1.0)
        val pStrict = computeLoudnessPlan(older, strict, false, null, true)
        assertEquals(0.0, pStrict.gainDb, 1e-6)
        assertFalse(pStrict.useLimiter)
    }

    @Test
    fun missingLoudnessUsesConservativeNonPositiveFallback() {
        val unmeasured = LoudnessTrackMetrics()
        // Target -11, median -9 -> -2.0 dB conservative cut.
        val cut = computeLoudnessPlan(unmeasured, enabledLoudSettings(LoudnessTarget.LOUD), false, -9.0, true)
        assertEquals(LoudnessSource.FALLBACK, cut.source)
        assertEquals(-2.0, cut.gainDb, 1e-6)
        assertFalse(cut.useLimiter)

        // Target -11, median -15 -> never blindly boost -> 0.0 dB.
        val noBoost = computeLoudnessPlan(unmeasured, enabledLoudSettings(LoudnessTarget.LOUD), false, -15.0, true)
        assertEquals(0.0, noBoost.gainDb, 1e-6)

        // Target -18, median -8 -> clamped at -6.0 dB.
        val clamped = computeLoudnessPlan(unmeasured, enabledLoudSettings(LoudnessTarget.DYNAMIC), false, -8.0, true)
        assertEquals(-6.0, clamped.gainDb, 1e-6)

        // Target LibraryMedian (-9.0) with median -9.0 -> 0.0 dB.
        val medianFb = computeLoudnessPlan(unmeasured, enabledLoudSettings(LoudnessTarget.LIBRARY_MEDIAN), false, -9.0, true)
        assertEquals(0.0, medianFb.gainDb, 1e-6)
    }

    @Test
    fun smartModeFollowsQueueContextAndAlbumModeFallsBackToTrack() {
        val albums = listOf("albA", "albA", "albB", "albB")
        // Manual queue covers indices [0, 1, 2]; index 3 is in auto-similar tail.
        assertTrue(smartUseAlbumForQueue(albums, position = 0, manualQueueCount = 3, isShuffle = false))
        assertTrue(smartUseAlbumForQueue(albums, position = 1, manualQueueCount = 3, isShuffle = false))
        assertFalse(smartUseAlbumForQueue(albums, position = 2, manualQueueCount = 3, isShuffle = false))
        assertFalse(smartUseAlbumForQueue(albums, position = 3, manualQueueCount = 3, isShuffle = false))
        assertFalse(smartUseAlbumForQueue(albums, position = 0, manualQueueCount = 3, isShuffle = true))

        val albumMode = enabledLoudSettings(mode = LoudnessMode.ALBUM)
        val noAlbumMetrics = LoudnessTrackMetrics(loudnessLufs = -9.0, truePeakDb = -0.5)
        val pFallbackTrack = computeLoudnessPlan(noAlbumMetrics, albumMode, true, null, true)
        assertEquals(LoudnessSource.TRACK, pFallbackTrack.source)
        assertEquals(-2.0, pFallbackTrack.gainDb, 1e-6)

        val withAlbumMetrics = noAlbumMetrics.copy(albumLoudnessLufs = -10.0, albumTruePeakDb = -0.2)
        val pAlbum = computeLoudnessPlan(withAlbumMetrics, albumMode, true, null, true)
        assertEquals(LoudnessSource.ALBUM, pAlbum.source)
        assertEquals(-1.0, pAlbum.gainDb, 1e-6)
    }
}
