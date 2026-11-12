package com.lux032.musicbridge

import com.lux032.musicbridge.music.playbackUrlIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpusTranscodeStrategyTest {

    @Test
    fun opusTranscodeModeEnumAndDefault() {
        assertEquals(OpusTranscodeMode.DIRECT_MP3, OpusTranscodeMode.DEFAULT)
        assertEquals("direct_mp3", OpusTranscodeMode.DIRECT_MP3.id)
        assertEquals("proxy_flac", OpusTranscodeMode.PROXY_FLAC.id)
        assertEquals("audio/mpeg", OpusTranscodeMode.DIRECT_MP3.mimeType)
        assertEquals("audio/flac", OpusTranscodeMode.PROXY_FLAC.mimeType)

        assertEquals(OpusTranscodeMode.DIRECT_MP3, OpusTranscodeMode.fromId("direct_mp3"))
        assertEquals(OpusTranscodeMode.PROXY_FLAC, OpusTranscodeMode.fromId("proxy_flac"))
        assertEquals(OpusTranscodeMode.DIRECT_MP3, OpusTranscodeMode.fromId("unknown"))
        assertEquals(OpusTranscodeMode.DIRECT_MP3, OpusTranscodeMode.fromId(null))
    }

    @Test
    fun sonosHiResTranscodeModeIdsRoundTrip() {
        SonosHiResTranscodeMode.entries.forEach { assertEquals(it, SonosHiResTranscodeMode.fromId(it.id)) }
        assertEquals(SonosHiResTranscodeMode.DEFAULT, SonosHiResTranscodeMode.fromId("invalid"))
        assertEquals(SonosHiResTranscodeMode.DEFAULT, SonosHiResTranscodeMode.fromId(null))
    }

    @Test
    fun playbackUrlIdentityIgnoresQueryParameters() {
        val base = "http://192.168.1.10:4533/api/v1/tracks/42/transcode.mp3"
        assertEquals(base, playbackUrlIdentity("$base?bitrate=320&offsetMs=0"))
        assertEquals(base, playbackUrlIdentity("$base?bitrate=320&offsetMs=93000"))
        assertNotEquals(
            playbackUrlIdentity(base),
            playbackUrlIdentity("http://192.168.1.10:4533/api/v1/tracks/43/transcode.mp3?bitrate=320"),
        )
    }

    @Test
    fun zeroSonosDurationFallsBackToServerMetadataDuringTranscodeStartup() {
        assertEquals(219, resolvePlaybackDurationSeconds(0, 219))
        assertEquals(219, resolvePlaybackDurationSeconds(null, 219))
        assertEquals(220, resolvePlaybackDurationSeconds(220, 219))
        assertNull(resolvePlaybackDurationSeconds(0, 0))
        assertNull(resolvePlaybackDurationSeconds(null, null))
    }
}
