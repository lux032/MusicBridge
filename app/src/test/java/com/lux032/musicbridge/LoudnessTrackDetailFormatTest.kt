package com.lux032.musicbridge

import com.lux032.musicbridge.local.LOUDNESS_CEILING_DB
import com.lux032.musicbridge.local.LoudnessGainPlan
import com.lux032.musicbridge.local.LoudnessSource
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.sonos.SonosRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessTrackDetailFormatTest {
    private fun sampleTrack(
        lufs: Double? = -9.0,
        peak: Double? = -0.2,
        lra: Double? = 6.2,
        albumLufs: Double? = -9.4,
        albumPeak: Double? = 0.1,
    ) = MusicTrack(
        ratingKey = "1",
        title = "Sample",
        streamUrl = "http://127.0.0.1/stream",
        partKey = "/stream",
        durationMillis = 200_000L,
        userRating = null,
        sourceId = "srv1",
        loudnessLufs = lufs,
        truePeakDb = peak,
        loudnessRange = lra,
        albumLoudnessLufs = albumLufs,
        albumTruePeakDb = albumPeak,
    )

    @Test
    fun formatsMeasuredTrackAlbumAndActiveLimiterStates() {
        val softPlan = LoudnessGainPlan(
            active = true,
            gainDb = 3.0,
            ceilingDb = LOUDNESS_CEILING_DB,
            useLimiter = true,
            source = LoudnessSource.TRACK,
            targetLufs = -11.0,
            peakReductionDb = 3.0,
        )
        val lines = formatLoudnessTechnicalLines(
            track = sampleTrack(),
            target = PlaybackTarget.LocalDevice,
            loudnessEnabled = true,
            serverSupported = true,
            plan = softPlan,
            isZhCN = true,
        )
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("-9.0 LUFS") && lines[0].contains("-0.2 dBTP") && lines[0].contains("LRA 6.2 LU"))
        assertTrue(lines[1].contains("-9.4 LUFS") && lines[1].contains("+0.1 dBTP"))
        assertTrue(lines[2].contains("+3.0 dB") && lines[2].contains("-11.0 LUFS") && lines[2].contains("柔和限幅"))

        val strongPlan = softPlan.copy(gainDb = 5.5, peakReductionDb = 6.0)
        val strongLines = formatLoudnessTechnicalLines(
            track = sampleTrack(),
            target = PlaybackTarget.LocalDevice,
            loudnessEnabled = true,
            serverSupported = true,
            plan = strongPlan,
            isZhCN = true,
        )
        assertTrue(strongLines[2].contains("强力限幅"))

        val zeroBypassPlan = softPlan.copy(gainDb = 0.0, useLimiter = false, peakReductionDb = 0.0, source = LoudnessSource.ALBUM)
        val bypassLines = formatLoudnessTechnicalLines(
            track = sampleTrack(),
            target = PlaybackTarget.LocalDevice,
            loudnessEnabled = true,
            serverSupported = true,
            plan = zeroBypassPlan,
            isZhCN = true,
        )
        assertTrue(bypassLines[2].contains("+0.0 dB") && bypassLines[2].contains("按专辑") && bypassLines[2].contains("零限幅直通"))
    }

    @Test
    fun formatsSonosIsolationUnmeasuredAndUnsupportedServer() {
        val room = SonosRoom(
            roomName = "Living Room",
            coordinatorUuid = "uuid",
            coordinatorBaseUrl = "http://192.168.1.10:1400",
            memberCount = 1,
            rawCoordinatorName = "Living Room",
            memberBaseUrls = listOf("http://192.168.1.10:1400"),
        )
        val sonosLines = formatLoudnessTechnicalLines(
            track = sampleTrack(),
            target = PlaybackTarget.Sonos(room),
            loudnessEnabled = true,
            serverSupported = true,
            plan = LoudnessGainPlan.BYPASS,
            isZhCN = true,
        )
        assertTrue(sonosLines.any { it.contains("Sonos") })

        val unmeasuredLines = formatLoudnessTechnicalLines(
            track = sampleTrack(lufs = null, peak = null, lra = null, albumLufs = null, albumPeak = null),
            target = PlaybackTarget.LocalDevice,
            loudnessEnabled = true,
            serverSupported = true,
            plan = LoudnessGainPlan(
                active = true,
                gainDb = -2.0,
                ceilingDb = LOUDNESS_CEILING_DB,
                useLimiter = false,
                source = LoudnessSource.FALLBACK,
                targetLufs = -11.0,
                peakReductionDb = 0.0,
            ),
            isZhCN = true,
        )
        assertTrue(unmeasuredLines[0].contains("未分析"))
        assertTrue(unmeasuredLines[1].contains("保守回退") && unmeasuredLines[1].contains("纯线性"))

        val legacyLines = formatLoudnessTechnicalLines(
            track = sampleTrack(lufs = null, peak = null, lra = null, albumLufs = null, albumPeak = null),
            target = PlaybackTarget.LocalDevice,
            loudnessEnabled = true,
            serverSupported = false,
            plan = LoudnessGainPlan.BYPASS,
            isZhCN = true,
        )
        assertEquals(listOf("响度均衡：当前服务器不支持"), legacyLines)
    }
}
