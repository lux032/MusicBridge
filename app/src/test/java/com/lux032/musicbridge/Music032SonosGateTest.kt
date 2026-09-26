package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.transcodedPlaybackMimeType
import com.lux032.musicbridge.plex.withOpusTranscodeMode
import com.lux032.musicbridge.plex.withSonosHiResTranscodeMode
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
    @Test fun newFlac48kSettingFallsBackToPlexMp3Verbatim() {
        val newMode = SonosHiResTranscodeMode.TRANSCODE_FLAC_48K
        val oldMode = SonosHiResTranscodeMode.TRANSCODE_MP3
        assertEquals(newMode, SonosHiResTranscodeMode.fromId(newMode.id))
        assertEquals(SonosHiResTranscodeMode.DEFAULT, SonosHiResTranscodeMode.fromId("invalid"))
        assertEquals(oldMode, newMode.forPlex())
        assertEquals("http://192.168.1.10:32400/library/parts/1/file.flac?X-Plex-Token=token",
            com.lux032.musicbridge.plex.buildPlaybackUrl("http://192.168.1.10:32400", "123",
                "/library/parts/1/file.flac", "flac", 96000, "token", "client", hiResMode = newMode))
        val source = track("flac", 96000, "http://192.168.1.10:32400/library/parts/1/file.flac?X-Plex-Token=token")
            .copy(ratingKey = "123", partKey = "/library/parts/1/file.flac")
        val expected = preparePlexSonosTranscode(source, true, OpusTranscodeMode.DIRECT_MP3, oldMode, "client")
        assertEquals(expected, preparePlexSonosTranscode(source, true, OpusTranscodeMode.DIRECT_MP3, newMode, "client"))
        assertEquals(configuredPlexDirectMp3(source, true, OpusTranscodeMode.DIRECT_MP3, oldMode),
            configuredPlexDirectMp3(source, true, OpusTranscodeMode.DIRECT_MP3, newMode))
        assertEquals(
            com.lux032.musicbridge.plex.buildPlaybackUrl("http://192.168.1.10:32400", "123", source.partKey,
                "flac", 96000, "token", "client", hiResMode = oldMode),
            com.lux032.musicbridge.plex.buildPlaybackUrl("http://192.168.1.10:32400", "123", source.partKey,
                "flac", 96000, "token", "client", hiResMode = newMode.forPlex()),
        )
    }

    @Test fun plexBaselineSnapshots() {
        val base = "http://192.168.1.10:32400"
        val snapshots = listOf(
            Snapshot("opus_48000_direct_mp3_transcode_mp3", "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F123&mediaIndex=0&partIndex=0&protocol=http&offset=0&directPlay=0&directStream=0&directStreamAudio=0&audioCodec=mp3&maxAudioBitrate=320&audioChannelCount=2&location=lan&X-Plex-Client-Identifier=client&X-Plex-Client-Profile-Name=generic&X-Plex-Client-Profile-Extra=add-transcode-target%28type%3DmusicProfile%26context%3Dstreaming%26protocol%3Dhttp%26container%3Dmp3%26audioCodec%3Dmp3%26maxAudioBitrate%3D320%26replace%3Dtrue%29&X-Plex-Product=MusicBridge&X-Plex-Version=0.1.0&X-Plex-Platform=Android&X-Plex-Device-Name=MusicBridge&X-Plex-Token=token", "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F123&mediaIndex=0&partIndex=0&protocol=http&offset=0&directPlay=0&directStream=0&directStreamAudio=0&audioCodec=mp3&maxAudioBitrate=320&audioChannelCount=2&location=lan&X-Plex-Client-Identifier=client&X-Plex-Client-Profile-Name=generic&X-Plex-Client-Profile-Extra=add-transcode-target%28type%3DmusicProfile%26context%3Dstreaming%26protocol%3Dhttp%26container%3Dmp3%26audioCodec%3Dmp3%26maxAudioBitrate%3D320%26replace%3Dtrue%29&X-Plex-Product=MusicBridge&X-Plex-Version=0.1.0&X-Plex-Platform=Android&X-Plex-Device-Name=MusicBridge&X-Plex-Token=token", "audio/mpeg", true),
            Snapshot("opus_48000_proxy_flac_transcode_mp3", "http://192.168.1.10:32400/music/:/transcode/universal/start.flac?path=%2Flibrary%2Fmetadata%2F123&mediaIndex=0&partIndex=0&protocol=http&offset=0&directPlay=0&directStream=0&directStreamAudio=0&audioChannelCount=2&location=lan&X-Plex-Client-Identifier=client&X-Plex-Client-Profile-Name=generic&X-Plex-Client-Profile-Extra=add-transcode-target%28type%3DmusicProfile%26context%3Dstreaming%26protocol%3Dhttp%26container%3Dflac%26audioCodec%3Dflac%26replace%3Dtrue%29&X-Plex-Product=MusicBridge&X-Plex-Version=0.1.0&X-Plex-Platform=Android&X-Plex-Device-Name=MusicBridge&X-Plex-Token=token", "http://192.168.1.10:32400/music/:/transcode/universal/start.flac?path=%2Flibrary%2Fmetadata%2F123&mediaIndex=0&partIndex=0&protocol=http&offset=0&directPlay=0&directStream=0&directStreamAudio=0&audioChannelCount=2&location=lan&X-Plex-Client-Identifier=client&X-Plex-Client-Profile-Name=generic&X-Plex-Client-Profile-Extra=add-transcode-target%28type%3DmusicProfile%26context%3Dstreaming%26protocol%3Dhttp%26container%3Dflac%26audioCodec%3Dflac%26replace%3Dtrue%29&X-Plex-Product=MusicBridge&X-Plex-Version=0.1.0&X-Plex-Platform=Android&X-Plex-Device-Name=MusicBridge&X-Plex-Token=token", "audio/flac", false),
            Snapshot("flac_96000_direct_mp3_transcode_mp3", "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F123&mediaIndex=0&partIndex=0&protocol=http&offset=0&directPlay=0&directStream=0&directStreamAudio=0&audioCodec=mp3&maxAudioBitrate=320&audioChannelCount=2&location=lan&X-Plex-Client-Identifier=client&X-Plex-Client-Profile-Name=generic&X-Plex-Client-Profile-Extra=add-transcode-target%28type%3DmusicProfile%26context%3Dstreaming%26protocol%3Dhttp%26container%3Dmp3%26audioCodec%3Dmp3%26maxAudioBitrate%3D320%26replace%3Dtrue%29&X-Plex-Product=MusicBridge&X-Plex-Version=0.1.0&X-Plex-Platform=Android&X-Plex-Device-Name=MusicBridge&X-Plex-Token=token", "http://192.168.1.10:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F123&mediaIndex=0&partIndex=0&protocol=http&offset=0&directPlay=0&directStream=0&directStreamAudio=0&audioCodec=mp3&maxAudioBitrate=320&audioChannelCount=2&location=lan&X-Plex-Client-Identifier=client&X-Plex-Client-Profile-Name=generic&X-Plex-Client-Profile-Extra=add-transcode-target%28type%3DmusicProfile%26context%3Dstreaming%26protocol%3Dhttp%26container%3Dmp3%26audioCodec%3Dmp3%26maxAudioBitrate%3D320%26replace%3Dtrue%29&X-Plex-Product=MusicBridge&X-Plex-Version=0.1.0&X-Plex-Platform=Android&X-Plex-Device-Name=MusicBridge&X-Plex-Token=token", "audio/mpeg", true),
            Snapshot("flac_96000_direct_mp3_direct_original", "http://192.168.1.10:32400/library/parts/1/file.flac?X-Plex-Token=token", "http://192.168.1.10:32400/library/parts/1/file.flac?X-Plex-Token=token", null, false),
            Snapshot("flac_44100_direct_mp3_transcode_mp3", "http://192.168.1.10:32400/library/parts/1/file.flac?X-Plex-Token=token", "http://192.168.1.10:32400/library/parts/1/file.flac?X-Plex-Token=token", null, false),
        )
        for (snapshot in snapshots) {
            val (codec, rate, opus, hi) = when (snapshot.name) {
                "opus_48000_direct_mp3_transcode_mp3" -> Quad("opus", 48000, OpusTranscodeMode.DIRECT_MP3, SonosHiResTranscodeMode.TRANSCODE_MP3)
                "opus_48000_proxy_flac_transcode_mp3" -> Quad("opus", 48000, OpusTranscodeMode.PROXY_FLAC, SonosHiResTranscodeMode.TRANSCODE_MP3)
                "flac_96000_direct_mp3_transcode_mp3" -> Quad("flac", 96000, OpusTranscodeMode.DIRECT_MP3, SonosHiResTranscodeMode.TRANSCODE_MP3)
                "flac_96000_direct_mp3_direct_original" -> Quad("flac", 96000, OpusTranscodeMode.DIRECT_MP3, SonosHiResTranscodeMode.DIRECT_ORIGINAL)
                else -> Quad("flac", 44100, OpusTranscodeMode.DIRECT_MP3, SonosHiResTranscodeMode.TRANSCODE_MP3)
            }
            val url = com.lux032.musicbridge.plex.buildPlaybackUrl(base, "123", "/library/parts/1/file.$codec", codec, rate, "token", "client", opus, hi)
            val source = track(codec, rate, "$base/library/parts/1/file.$codec?X-Plex-Token=token")
                .copy(ratingKey = "123", partKey = "/library/parts/1/file.$codec")
            val transformed = preparePlexSonosTranscode(source, true, opus, hi, "client")
            assertEquals(snapshot.name, snapshot.url, url)
            assertEquals(snapshot.name, snapshot.converted, transformed.streamUrl)
            assertEquals(snapshot.name, snapshot.mime, transformed.playbackMimeType)
            assertEquals(snapshot.name, snapshot.direct, configuredPlexDirectMp3(source, true, opus, hi))
            assertEquals(snapshot.name, snapshot.mime, codec.transcodedPlaybackMimeType(opus, rate, hi))
            val opusOnly = source.withOpusTranscodeMode(opus, "client")
            val expectedOpusUrl = if (codec == "opus") snapshot.url else source.streamUrl
            assertEquals(snapshot.name, expectedOpusUrl, opusOnly.streamUrl)
            assertEquals(snapshot.name, if (codec == "opus") snapshot.mime else null, opusOnly.playbackMimeType)
            val hiResOnly = source.withSonosHiResTranscodeMode(hi, "client")
            val expectedHiResUrl = if (codec == "flac") snapshot.url else source.streamUrl
            assertEquals(snapshot.name, expectedHiResUrl, hiResOnly.streamUrl)
            assertEquals(snapshot.name, if (codec == "flac") snapshot.mime else null, hiResOnly.playbackMimeType)
        }
    }
    private data class Snapshot(val name: String, val url: String, val converted: String, val mime: String?, val direct: Boolean)
    private data class Quad(val codec:String,val rate:Int,val opus:OpusTranscodeMode,val hi:SonosHiResTranscodeMode)
}
