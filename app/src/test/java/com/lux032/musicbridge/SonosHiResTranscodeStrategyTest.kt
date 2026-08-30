package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.buildPlaybackUrl
import com.lux032.musicbridge.plex.parseFirstAudioSampleRateFromJson
import com.lux032.musicbridge.plex.parseFirstAudioSampleRateFromXml
import com.lux032.musicbridge.plex.transcodedPlaybackMimeType
import com.lux032.musicbridge.plex.withPlexTranscodeOffsetSeconds
import com.lux032.musicbridge.plex.withSonosHiResTranscodeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosHiResTranscodeStrategyTest {
    private fun url(codec: String, rate: Int?, mode: SonosHiResTranscodeMode = SonosHiResTranscodeMode.TRANSCODE_MP3) =
        buildPlaybackUrl(
            serverUri = "http://192.168.1.10:32400",
            ratingKey = "123",
            partKey = "/library/parts/1/file.$codec",
            sourceAudioCodec = codec,
            sourceAudioSampleRate = rate,
            token = "token",
            clientIdentifier = "client",
            opusMode = OpusTranscodeMode.DIRECT_MP3,
            hiResMode = mode,
        )

    private fun directTrack(codec: String = "flac", rate: Int? = 96000) = PlexTrackStream(
        ratingKey = "123",
        title = "Hi-Res",
        streamUrl = "http://192.168.1.10:32400/library/parts/1/file.$codec?X-Plex-Token=token",
        partKey = "/library/parts/1/file.$codec",
        durationMillis = 120000,
        userRating = null,
        sourceAudioCodec = codec,
        sourceAudioSampleRate = rate,
    )

    @Test fun flac44100Direct() { assertFalse(url("flac", 44100).contains("/transcode/")); assertNull("flac".transcodedPlaybackMimeType(sourceAudioSampleRate = 44100)) }
    @Test fun flac48000DirectRegardlessOfBitDepth() { assertFalse(url("flac", 48000).contains("/transcode/")); assertNull("flac".transcodedPlaybackMimeType(sourceAudioSampleRate = 48000)) }
    @Test fun unknownFlacRateDirect() { assertFalse(url("flac", null).contains("/transcode/")); assertNull("flac".transcodedPlaybackMimeType(sourceAudioSampleRate = null)) }
    @Test fun flac96000TranscodesMp3() { assertTrue(url("flac", 96000).contains("/start.mp3")); assertEquals("audio/mpeg", "flac".transcodedPlaybackMimeType(sourceAudioSampleRate = 96000)) }
    @Test fun alac192000TranscodesMp3() { assertTrue(url("alac", 192000).contains("/start.mp3")); assertEquals("audio/mpeg", "alac".transcodedPlaybackMimeType(sourceAudioSampleRate = 192000)) }
    @Test fun directOriginalDisablesHiResTranscode() { assertFalse(url("flac", 96000, SonosHiResTranscodeMode.DIRECT_ORIGINAL).contains("/transcode/")); assertNull("flac".transcodedPlaybackMimeType(sourceAudioSampleRate = 96000, hiResMode = SonosHiResTranscodeMode.DIRECT_ORIGINAL)) }
    @Test fun opusStillUsesOpusMode() { assertTrue(url("opus", 48000).contains("/start.mp3")); assertEquals("audio/mpeg", "opus".transcodedPlaybackMimeType()) }
    @Test fun liveTranscodeSeekUpdatesOffset() { val seek = url("flac", 96000).withPlexTranscodeOffsetSeconds(73); assertTrue(seek.contains("offset=73")); assertFalse(seek.contains("offset=0")) }

    @Test fun trackSwitchesBetweenHiResModes() {
        val transcoded = directTrack().withSonosHiResTranscodeMode(SonosHiResTranscodeMode.TRANSCODE_MP3, "client")
        assertTrue(transcoded.streamUrl.contains("/start.mp3"))
        assertEquals("audio/mpeg", transcoded.playbackMimeType)
        val original = transcoded.withSonosHiResTranscodeMode(SonosHiResTranscodeMode.DIRECT_ORIGINAL, "client")
        assertTrue(original.streamUrl.contains("/library/parts/1/file.flac"))
        assertFalse(original.streamUrl.contains("/transcode/"))
        assertNull(original.playbackMimeType)
    }

    @Test fun opusTrackIsUnaffectedByHiResModeSwitch() {
        val opus = directTrack(codec = "opus", rate = 96000)
        assertEquals(opus, opus.withSonosHiResTranscodeMode(SonosHiResTranscodeMode.TRANSCODE_MP3, "client"))
        assertEquals(opus, opus.withSonosHiResTranscodeMode(SonosHiResTranscodeMode.DIRECT_ORIGINAL, "client"))
    }

    @Test fun xmlSampleRatePrefersAudioStreamAndHandlesMissingValues() {
        assertEquals(96000, parseFirstAudioSampleRateFromXml("""<Track><Media samplingRate="48000"><Part><Stream streamType="2" samplingRate="96000"/></Part></Media></Track>"""))
        assertEquals(48000, parseFirstAudioSampleRateFromXml("""<Track><Media samplingRate="48000"><Part/></Media></Track>"""))
        assertNull(parseFirstAudioSampleRateFromXml("""<Track><Media samplingRate=""><Part><Stream streamType="2" samplingRate=""/></Part></Media></Track>"""))
        assertNull(parseFirstAudioSampleRateFromXml("""<Track><Media><Part/></Media></Track>"""))
    }

    @Test fun jsonSampleRateHandlesNumbersStringsAndMissingStreams() {
        assertEquals(96000, parseFirstAudioSampleRateFromJson("""{"MediaContainer":{"Metadata":[{"Media":[{"samplingRate":48000,"Part":[{"Stream":[{"streamType":2,"samplingRate":"96000"}]}]}]}]}}"""))
        assertEquals(48000, parseFirstAudioSampleRateFromJson("""{"Media":{"samplingRate":"48000","Part":[]}}"""))
        assertNull(parseFirstAudioSampleRateFromJson("""{"Media":{"samplingRate":"","Part":[{"Stream":[]}]}}"""))
        assertNull(parseFirstAudioSampleRateFromJson("""{"Media":{"Part":[]}}"""))
    }
}
