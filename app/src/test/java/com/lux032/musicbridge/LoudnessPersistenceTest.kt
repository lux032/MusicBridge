package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.storage.DownloadedAudioTrack
import com.lux032.musicbridge.storage.toJson
import com.lux032.musicbridge.storage.toTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LoudnessPersistenceTest {
    @Test
    fun loudnessEnumsDefaultAndFallbackOnUnknownIds() {
        val defaults = LoudnessNormalizationSettings()
        assertFalse(defaults.enabled)
        assertEquals(LoudnessTarget.LIBRARY_MEDIAN, defaults.target)
        assertEquals(LoudnessMode.SMART, defaults.mode)
        assertEquals(LoudnessPeakProtection.SOFT_LIMITER, defaults.peakProtection)

        assertEquals(LoudnessTarget.LIBRARY_MEDIAN, LoudnessTarget.fromId(null))
        assertEquals(LoudnessTarget.LIBRARY_MEDIAN, LoudnessTarget.fromId("unknown"))
        assertEquals(LoudnessTarget.LOUD, LoudnessTarget.fromId("loud"))
        assertEquals(LoudnessTarget.STANDARD, LoudnessTarget.fromId("standard"))
        assertEquals(LoudnessTarget.DYNAMIC, LoudnessTarget.fromId("dynamic"))

        assertEquals(LoudnessMode.SMART, LoudnessMode.fromId("bogus"))
        assertEquals(LoudnessMode.TRACK, LoudnessMode.fromId("track"))
        assertEquals(LoudnessMode.ALBUM, LoudnessMode.fromId("album"))

        assertEquals(LoudnessPeakProtection.SOFT_LIMITER, LoudnessPeakProtection.fromId("other"))
        assertEquals(LoudnessPeakProtection.STRONG_LIMITER, LoudnessPeakProtection.fromId("strong_limiter"))
        assertEquals(LoudnessPeakProtection.STRICT_LINEAR, LoudnessPeakProtection.fromId("strict_linear"))
        assertEquals(3.5, LoudnessPeakProtection.SOFT_LIMITER.maxGainReductionDb, 1e-6)
        assertEquals(6.0, LoudnessPeakProtection.STRONG_LIMITER.maxGainReductionDb, 1e-6)
        assertEquals(0.0, LoudnessPeakProtection.STRICT_LINEAR.maxGainReductionDb, 1e-6)
    }

    @Test
    fun playbackSessionJsonRoundTripsLoudnessIncludingExplicitZero() {
        val track = MusicTrack(
            ratingKey = "101",
            title = "Zero Peak Song",
            streamUrl = "http://127.0.0.1/api/v1/tracks/101/stream",
            partKey = "/api/v1/tracks/101/stream",
            durationMillis = 200_000L,
            userRating = null,
            sourceId = "srv1",
            loudnessLufs = -9.2,
            truePeakDb = 0.0,
            loudnessRange = 5.4,
            albumLoudnessLufs = -9.5,
            albumTruePeakDb = 0.0,
        )
        val restored = track.toJson().toTrack()
        assertEquals(-9.2, restored.loudnessLufs!!, 1e-6)
        assertEquals(0.0, restored.truePeakDb!!, 1e-6)
        assertEquals(5.4, restored.loudnessRange!!, 1e-6)
        assertEquals(-9.5, restored.albumLoudnessLufs!!, 1e-6)
        assertEquals(0.0, restored.albumTruePeakDb!!, 1e-6)

        val missing = track.copy(
            loudnessLufs = null,
            truePeakDb = null,
            loudnessRange = null,
            albumLoudnessLufs = null,
            albumTruePeakDb = null,
        ).toJson().toTrack()
        assertNull(missing.loudnessLufs)
        assertNull(missing.truePeakDb)
        assertNull(missing.loudnessRange)
        assertNull(missing.albumLoudnessLufs)
        assertNull(missing.albumTruePeakDb)
    }

    @Test
    fun downloadedAudioTrackToTrackStreamPreservesLoudness() {
        val downloaded = DownloadedAudioTrack(
            sourceId = "srv1",
            ratingKey = "101",
            partKey = "/api/v1/tracks/101/stream",
            title = "Offline FLAC",
            artistName = "Artist",
            albumTitle = "Album",
            albumRatingKey = "alb1",
            durationMillis = 180_000L,
            sourceAudioCodec = "flac",
            sourceAudioBitrateKbps = 950,
            sourceAudioSampleRate = 44100,
            bitDepth = 16,
            localAudioPath = "/tmp/offline.flac",
            localThumbPath = null,
            localLyricPath = null,
            fileSize = 10_000_000L,
            downloadedAtEpochSeconds = 1_700_000_000L,
            loudnessLufs = -10.3,
            truePeakDb = 0.0,
            loudnessRange = 7.1,
            albumLoudnessLufs = -10.8,
            albumTruePeakDb = -0.2,
        )
        val stream = downloaded.toTrackStream()
        assertEquals(-10.3, stream.loudnessLufs!!, 1e-6)
        assertEquals(0.0, stream.truePeakDb!!, 1e-6)
        assertEquals(7.1, stream.loudnessRange!!, 1e-6)
        assertEquals(-10.8, stream.albumLoudnessLufs!!, 1e-6)
        assertEquals(-0.2, stream.albumTruePeakDb!!, 1e-6)
    }
}
