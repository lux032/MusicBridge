package com.lux032.musicbridge.plex

import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.LocalPlaybackPolicy
import com.lux032.musicbridge.LocalPrecacheCount
import com.lux032.musicbridge.LocalStreamingLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPlaybackPolicyTest {
    private val track = PlexTrackStream(
        ratingKey = "42",
        title = "Track",
        streamUrl = "https://plex.example/library/parts/7/file.flac?X-Plex-Token=secret",
        partKey = "/library/parts/7/file.flac",
        durationMillis = 180_000L,
        userRating = null,
        sourceAudioCodec = "flac",
        sourceAudioBitrateKbps = 950,
        sourceId = "server-1",
    )

    @Test
    fun maximumKeepsOriginalFileUrl() {
        val result = track.withLocalPlaybackPolicy(policy(LocalStreamingLimit.MAXIMUM), "client")

        assertEquals(track.streamUrl, result.streamUrl)
    }

    @Test
    fun sourceBelowLimitKeepsOriginalBytes() {
        val result = track.copy(sourceAudioBitrateKbps = 256)
            .withLocalPlaybackPolicy(policy(LocalStreamingLimit.KBPS_320), "client")

        assertEquals(track.streamUrl, result.streamUrl)
    }

    @Test
    fun opusAt148KbpsStaysDirectUnderCellular320Limit() {
        val opus = track.copy(sourceAudioCodec = "opus", sourceAudioBitrateKbps = 148)

        val result = opus.withLocalPlaybackPolicy(policy(LocalStreamingLimit.KBPS_320), "client")

        assertEquals(opus.streamUrl, result.streamUrl)
        assertEquals("opus", result.sourceAudioCodec)
    }

    @Test
    fun opusWithMissingPlexBitrateStaysDirect() {
        val opus = track.copy(sourceAudioCodec = "opus", sourceAudioBitrateKbps = null)

        val result = opus.withLocalPlaybackPolicy(policy(LocalStreamingLimit.KBPS_320), "client")

        assertEquals(opus.streamUrl, result.streamUrl)
    }

    @Test
    fun sourceAboveLimitUsesSelectedOpusTier() {
        val result = track.withLocalPlaybackPolicy(policy(LocalStreamingLimit.KBPS_320), "client")

        assertTrue(result.streamUrl.contains("/music/:/transcode/universal/start.ogg"))
        assertTrue(result.streamUrl.contains("audioCodec=opus"))
        assertTrue(result.streamUrl.contains("maxAudioBitrate=128"))
        assertEquals("audio/ogg", result.playbackMimeType)
    }

    private fun policy(limit: LocalStreamingLimit) = LocalPlaybackPolicy(
        streamingLimit = limit,
        opusBitrate = LocalOpusBitrate.KBPS_128,
        precacheCount = LocalPrecacheCount.THREE,
    )
}
