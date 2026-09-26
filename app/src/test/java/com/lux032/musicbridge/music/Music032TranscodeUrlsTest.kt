package com.lux032.musicbridge.music

import org.junit.Assert.*
import org.junit.Test

class Music032TranscodeUrlsTest {
    private val stream = "https://h/music/api/v1/tracks/12/stream?mediaToken=a%2Bb&x=1"

    @Test fun derivesAndPreservesMediaTokenAndReverseProxy() {
        assertEquals("https://h/music/api/v1/tracks/12/transcode.mp3?mediaToken=a%2Bb&x=1&bitrate=320",
            Music032TranscodeUrls.derive(stream, "12", "mp3", listOf("bitrate" to "320")))
        assertNull(Music032TranscodeUrls.derive(stream, "13", "mp3", emptyList()))
        assertNull(Music032TranscodeUrls.derive("https://plex/library/parts/12", "12", "mp3", emptyList()))
    }

    @Test fun offsetsReplaceAndClampBeforeDuration() {
        val url = "https://h/api/v1/tracks/12/transcode.mp3?mediaToken=x&offsetMs=5&z=1&offsetMs=9"
        assertEquals("https://h/api/v1/tracks/12/transcode.mp3?mediaToken=x&z=1&offsetMs=9000",
            Music032TranscodeUrls.withOffsetMs(url, 10000, 10000))
        assertEquals("https://h/api/v1/tracks/12/transcode.mp3?mediaToken=x&z=1",
            Music032TranscodeUrls.withOffsetMs(url, 123, 999))
        assertEquals("https://h/api/v1/tracks/12/transcode.mp3?mediaToken=x&z=1",
            Music032TranscodeUrls.withOffsetMs(url, 0, null))
        assertEquals("https://h/api/v1/tracks/12/transcode.mp3?mediaToken=x&z=1&offsetMs=3",
            Music032TranscodeUrls.withOffsetMs(url, 3, null))
    }

    @Test fun onlyMp3AndOggAreLive() {
        for (fmt in listOf("mp3", "ogg")) assertTrue(Music032TranscodeUrls.isLive("https://h/music/api/v1/tracks/12/transcode.$fmt?mediaToken=x"))
        for (url in listOf("https://h/api/v1/tracks/12/transcode.flac", stream, "https://h/library/parts/1"))
            assertFalse(Music032TranscodeUrls.isLive(url))
    }
}
