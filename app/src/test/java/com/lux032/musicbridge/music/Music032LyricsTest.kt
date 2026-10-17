package com.lux032.musicbridge.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032LyricsTest {
    @Test
    fun parsesServerLyricsAndKeepsServerIndicesForBlankLines() {
        val lyrics = Music032LyricsJson.parseLyrics(
            """{"synced":true,"lines":[{"timeMs":1000,"text":"一"},{"timeMs":2000,"text":"  "},{"timeMs":3000,"text":"二"}],
               "source":"netease","sourceName":"NetEase","manual":true,"remoteTitle":"晴天","remoteArtist":"周杰伦"}""",
        )
        assertTrue(lyrics.lyrics.isSynced)
        assertEquals(listOf("一", "二"), lyrics.lyrics.lines.map { it.text })
        assertEquals(listOf(1000L, 3000L), lyrics.lyrics.lines.map { it.timeMillis })
        assertEquals(listOf(0, 2), lyrics.serverIndices)
        assertTrue(lyrics.isOnline)
        assertTrue(lyrics.manual)
        assertEquals("晴天", lyrics.remoteTitle)

        val translated = lyrics.withTranslation(listOf("one", "", "two"))
        assertEquals(listOf("one", "two"), translated.lines.map { it.translation })
    }

    @Test
    fun plainLyricsGetStaticSpacingAndLocalSourcesAreNotOnline() {
        val lyrics = Music032LyricsJson.parseLyrics(
            """{"synced":false,"lines":[{"timeMs":0,"text":"a"},{"timeMs":0,"text":"b"}],"source":"file","sourceName":"","manual":false}""",
        )
        assertFalse(lyrics.lyrics.isSynced)
        assertEquals(listOf(0L, 5000L), lyrics.lyrics.lines.map { it.timeMillis })
        assertFalse(lyrics.isOnline)
        assertNull(lyrics.remoteTitle)
        // A short or empty translation leaves lines untranslated instead of failing.
        assertEquals(listOf(null, null), lyrics.withTranslation(emptyList()).lines.map { it.translation })
    }

    @Test
    fun parsesSearchResultsSortedByScore() {
        val results = Music032LyricsJson.parseSearch(
            """{"results":[
                {"source":"lrclib","id":"9","name":"B","artist":"x","durationMs":200000,"synced":true,"score":0.4},
                {"source":"netease","id":"1","name":"A","artist":"y","synced":null,"score":0.1},
                {"source":"","id":"2","name":"bad","artist":"","score":0}]}""",
        )
        assertEquals(listOf("1", "9"), results.map { it.id })
        assertNull(results[0].synced)
        assertEquals(true, results[1].synced)
        assertEquals(200000L, results[1].durationMillis)
    }

    @Test
    fun parsesTranslationLines() {
        assertEquals(listOf("a", "", "b"), Music032LyricsJson.parseTranslation("""{"lines":["a","","b"],"source":"ai"}"""))
    }

    @Test
    fun simplifiedChineseCodes() {
        assertTrue(LyricsTranslationSettings.isSimplifiedChinese("zh-Hans"))
        assertTrue(LyricsTranslationSettings.isSimplifiedChinese("zh-CN"))
        assertFalse(LyricsTranslationSettings.isSimplifiedChinese("zh-Hant"))
    }

    @Test
    fun derivesLyricsUrlFromOriginalStream() {
        assertEquals(
            "http://h:4533/api/v1/tracks/42/lyrics.lrc?mediaToken=t",
            Music032TranscodeUrls.lyricsUrl("http://h:4533/api/v1/tracks/42/stream?mediaToken=t", "42"),
        )
        assertNull(Music032TranscodeUrls.lyricsUrl("http://h:4533/api/v1/tracks/42/stream?mediaToken=t", "43"))
        assertNull(Music032TranscodeUrls.lyricsUrl("file:///data/a.flac", "42"))
    }
}
