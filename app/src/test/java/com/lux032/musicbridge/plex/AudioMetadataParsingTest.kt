package com.lux032.musicbridge.plex

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class AudioMetadataParsingTest {
    @Test fun xmlAudioStreamParsesBitDepthAndSampleRate() {
        val xml = """<Track><Media><Part><Stream streamType="2" bitDepth="24" samplingRate="48000"/></Part></Media></Track>"""
        val track = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray())).documentElement
        assertEquals(24, track.firstAudioBitDepth())
        assertEquals(48_000, track.firstAudioSampleRate())
    }

    @Test fun xmlIgnoresSubtitleAndMissingAudioDepth() {
        val xml = """<Track><Media><Part><Stream streamType="4" bitDepth="24"/></Part></Media></Track>"""
        val track = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray())).documentElement
        assertNull(track.firstAudioBitDepth())
    }

    @Test fun jsonAudioStreamParsesBitDepthAndSampleRate() {
        val media = JSONObject("""{"Part":[{"Stream":[{"streamType":2,"bitDepth":16,"samplingRate":44100}]}]}""")
        assertEquals(16, media.firstAudioBitDepth())
        assertEquals(44_100, media.firstAudioSampleRate())
    }
}
