package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import org.junit.Assert.*
import org.junit.Test

class Music032SonosGateTest {
    private fun track(codec: String, rate: Int, url: String) = PlexTrackStream(
        ratingKey = "1", title = "Test", streamUrl = url, partKey = "/api/v1/tracks/1/stream",
        durationMillis = 1000, userRating = null, sourceAudioCodec = codec, sourceAudioSampleRate = rate,
    )

    @Test fun music032DoesNotEnterPlexSonosConversions() {
        for (source in listOf(track("opus", 48000, "https://032.example/stream"), track("flac", 96000, "https://032.example/flac"))) {
            assertFalse(shouldApplyPlexSonosTranscode(false, source.streamUrl))
            assertFalse(configuredPlexDirectMp3(source, false, OpusTranscodeMode.DIRECT_MP3, SonosHiResTranscodeMode.TRANSCODE_MP3))
            val result = preparePlexSonosTranscode(source, false, OpusTranscodeMode.PROXY_FLAC, SonosHiResTranscodeMode.TRANSCODE_MP3, "id")
            assertEquals(source.streamUrl, result.streamUrl)
            assertNull(result.playbackMimeType)
            val directResult = preparePlexSonosTranscode(source, false, OpusTranscodeMode.DIRECT_MP3,
                SonosHiResTranscodeMode.TRANSCODE_MP3, "id")
            assertEquals(source.streamUrl, directResult.streamUrl)
            assertNull(directResult.playbackMimeType)
        }
    }

    @Test fun plexGatePreservesConversionAndFallback() {
        val source = track("opus", 48000, "https://plex.example/library/parts/1?X-Plex-Token=secret")
        assertTrue(shouldApplyPlexSonosTranscode(true, source.streamUrl))
        assertTrue(shouldApplyPlexSonosTranscode(null, source.streamUrl))
        assertFalse(shouldApplyPlexSonosTranscode(null, "https://032.example/stream"))
        assertTrue(configuredPlexDirectMp3(source, true, OpusTranscodeMode.DIRECT_MP3, SonosHiResTranscodeMode.TRANSCODE_MP3))
        assertEquals("audio/mpeg", preparePlexSonosTranscode(source, true, OpusTranscodeMode.DIRECT_MP3,
            SonosHiResTranscodeMode.TRANSCODE_MP3, "id").playbackMimeType)
    }
}
