package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PLEX_FLAC_TRANSCODE_PROFILE
import com.lux032.musicbridge.plex.PLEX_MP3_320K_TRANSCODE_PROFILE
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.buildPlaybackUrl
import com.lux032.musicbridge.plex.playbackUrlIdentity
import com.lux032.musicbridge.plex.transcodedPlaybackMimeType
import com.lux032.musicbridge.plex.withOpusTranscodeMode
import com.lux032.musicbridge.plex.withPlexTranscodeOffsetSeconds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun nonOpusTrackUsesDirectPlayRegardlessOfMode() {
        val nonOpusCodecs = listOf("flac", "mp3", "aac", "alac", "wav", null)
        for (codec in nonOpusCodecs) {
            val urlDirect = buildPlaybackUrl(
                serverUri = "http://192.168.1.10:32400",
                ratingKey = "12345",
                partKey = "/library/parts/999/file.flac",
                sourceAudioCodec = codec,
                token = "secret-token",
                clientIdentifier = "test-client",
                opusMode = OpusTranscodeMode.DIRECT_MP3,
            )
            val urlProxy = buildPlaybackUrl(
                serverUri = "http://192.168.1.10:32400",
                ratingKey = "12345",
                partKey = "/library/parts/999/file.flac",
                sourceAudioCodec = codec,
                token = "secret-token",
                clientIdentifier = "test-client",
                opusMode = OpusTranscodeMode.PROXY_FLAC,
            )

            val expected = "http://192.168.1.10:32400/library/parts/999/file.flac?X-Plex-Token=secret-token"
            assertEquals(expected, urlDirect)
            assertEquals(expected, urlProxy)
            assertNull(codec.transcodedPlaybackMimeType(OpusTranscodeMode.DIRECT_MP3))
            assertNull(codec.transcodedPlaybackMimeType(OpusTranscodeMode.PROXY_FLAC))
        }
    }

    @Test
    fun opusDirectMp3BuildsCorrectUrlAndMime() {
        val url = buildPlaybackUrl(
            serverUri = "http://192.168.1.10:32400",
            ratingKey = "456",
            partKey = "/library/parts/888/file.opus",
            sourceAudioCodec = "opus",
            token = "my-token",
            clientIdentifier = "my-client-id",
            opusMode = OpusTranscodeMode.DIRECT_MP3,
        )

        assertTrue(url.startsWith("http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?"))
        assertTrue(url.contains("audioCodec=mp3"))
        assertTrue(url.contains("maxAudioBitrate=320"))
        assertTrue(url.contains("X-Plex-Client-Identifier=my-client-id"))
        assertTrue(url.contains("X-Plex-Token=my-token"))
        assertTrue(url.contains("path=%2Flibrary%2Fmetadata%2F456"))

        val mime = "opus".transcodedPlaybackMimeType(OpusTranscodeMode.DIRECT_MP3)
        assertEquals("audio/mpeg", mime)
    }

    @Test
    fun opusDirectMp3SeekReplacesTranscodeOffsetWithoutChangingIdentity() {
        val original = buildPlaybackUrl(
            serverUri = "http://192.168.1.10:32400",
            ratingKey = "456",
            partKey = "/library/parts/888/file.opus",
            sourceAudioCodec = "opus",
            token = "my-token",
            clientIdentifier = "my-client-id",
            opusMode = OpusTranscodeMode.DIRECT_MP3,
        )

        val seekUrl = original.withPlexTranscodeOffsetSeconds(93)

        assertTrue(seekUrl.contains("offset=93"))
        assertFalse(seekUrl.contains("offset=0"))
        assertEquals(playbackUrlIdentity(original), playbackUrlIdentity(seekUrl))
    }

    @Test
    fun opusProxyFlacBuildsCorrectUrlAndMime() {
        val url = buildPlaybackUrl(
            serverUri = "http://192.168.1.10:32400",
            ratingKey = "456",
            partKey = "/library/parts/888/file.opus",
            sourceAudioCodec = "opus",
            token = "my-token",
            clientIdentifier = "my-client-id",
            opusMode = OpusTranscodeMode.PROXY_FLAC,
        )

        assertTrue(url.startsWith("http://192.168.1.10:32400/music/:/transcode/universal/start.flac?"))
        assertFalse(url.contains("audioCodec=mp3"))
        assertFalse(url.contains("maxAudioBitrate=320"))
        assertTrue(url.contains("X-Plex-Client-Identifier=my-client-id"))
        assertTrue(url.contains("X-Plex-Token=my-token"))
        assertTrue(url.contains("path=%2Flibrary%2Fmetadata%2F456"))

        val mime = "opus".transcodedPlaybackMimeType(OpusTranscodeMode.PROXY_FLAC)
        assertEquals("audio/flac", mime)
    }

    @Test
    fun trackStreamConversionBetweenOpusModes() {
        val flacUrl = buildPlaybackUrl(
            serverUri = "http://192.168.1.10:32400",
            ratingKey = "789",
            partKey = "/library/parts/101/0/file.opus",
            sourceAudioCodec = "opus",
            token = "tok",
            clientIdentifier = "client1",
            opusMode = OpusTranscodeMode.PROXY_FLAC,
        )

        val flacTrack = PlexTrackStream(
            ratingKey = "789",
            title = "Test Opus Track",
            streamUrl = flacUrl,
            partKey = "/library/parts/101/0/file.opus",
            durationMillis = 180000L,
            userRating = 10f,
            sourceAudioCodec = "opus",
            playbackMimeType = "audio/flac",
            sourceId = "src1",
        )

        // Convert FLAC track to DIRECT_MP3
        val mp3Track = flacTrack.withOpusTranscodeMode(OpusTranscodeMode.DIRECT_MP3, "client1")
        assertEquals("audio/mpeg", mp3Track.playbackMimeType)
        assertTrue(mp3Track.streamUrl.contains("/start.mp3"))
        assertTrue(mp3Track.streamUrl.contains("audioCodec=mp3"))
        assertTrue(mp3Track.streamUrl.contains("maxAudioBitrate=320"))
        assertFalse(mp3Track.streamUrl.contains("/start.flac"))

        // Convert MP3 track back to PROXY_FLAC
        val convertedFlacTrack = mp3Track.withOpusTranscodeMode(OpusTranscodeMode.PROXY_FLAC, "client1")
        assertEquals("audio/flac", convertedFlacTrack.playbackMimeType)
        assertTrue(convertedFlacTrack.streamUrl.contains("/start.flac"))
        assertFalse(convertedFlacTrack.streamUrl.contains("/start.mp3"))
        assertFalse(convertedFlacTrack.streamUrl.contains("audioCodec=mp3"))
    }

    @Test
    fun nonOpusTrackRemainsUnchangedByWithOpusTranscodeMode() {
        val originalTrack = PlexTrackStream(
            ratingKey = "111",
            title = "FLAC Track",
            streamUrl = "http://192.168.1.10:32400/library/parts/555/0/file.flac?X-Plex-Token=tok",
            partKey = "/library/parts/555/0/file.flac",
            durationMillis = 200000L,
            userRating = null,
            sourceAudioCodec = "flac",
            playbackMimeType = null,
            sourceId = "src1",
        )

        val converted = originalTrack.withOpusTranscodeMode(OpusTranscodeMode.DIRECT_MP3)
        assertEquals(originalTrack.streamUrl, converted.streamUrl)
        assertEquals(originalTrack.playbackMimeType, converted.playbackMimeType)
    }

    @Test
    fun playbackUrlIdentityNormalizesBothStartFlacAndStartMp3() {
        val flacUrl = "http://192.168.1.10:32400/music/:/transcode/universal/start.flac?path=%2Flibrary%2Fmetadata%2F999&session=abc"
        val mp3Url = "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F999&session=def"
        val directUrl = "http://192.168.1.10:32400/library/parts/123/file.flac?X-Plex-Token=tok"

        assertEquals("http://192.168.1.10:32400/music/:/transcode/universal/start.flac?path=/library/metadata/999", playbackUrlIdentity(flacUrl))
        assertEquals("http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=/library/metadata/999", playbackUrlIdentity(mp3Url))
        assertEquals("http://192.168.1.10:32400/library/parts/123/file.flac", playbackUrlIdentity(directUrl))
    }

    @Test
    fun playbackUrlIdentityTreatsSonosDecodedPathAsSameTranscodeTrack() {
        val appUrl = "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F999&X-Plex-Token=secret"
        val sonosUrl = "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?session=sonos&path=/library/metadata/999"

        assertEquals(playbackUrlIdentity(appUrl), playbackUrlIdentity(sonosUrl))
    }

    @Test
    fun playbackUrlIdentityKeepsDifferentTranscodeTracksDistinct() {
        val firstUrl = "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F999"
        val secondUrl = "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=/library/metadata/1000"

        assertNotEquals(playbackUrlIdentity(firstUrl), playbackUrlIdentity(secondUrl))
    }

    @Test
    fun zeroSonosDurationFallsBackToPlexMetadataDuringTranscodeStartup() {
        assertEquals(219, resolvePlaybackDurationSeconds(0, 219))
        assertEquals(219, resolvePlaybackDurationSeconds(null, 219))
        assertEquals(220, resolvePlaybackDurationSeconds(220, 219))
        assertNull(resolvePlaybackDurationSeconds(0, 0))
        assertNull(resolvePlaybackDurationSeconds(null, null))
    }
}
