package com.lux032.musicbridge.plex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineLyricsTest {
    private val query = OnlineLyricQuery(name = "晴天", artist = "周杰伦", album = "叶惠美", durationSeconds = 269.0)

    @Test
    fun exactMatchScoresNearZeroAndUnrelatedTextIsRejected() {
        assertTrue(fuzzyScore("Yesterday", "yesterday")!! < 0.01)
        assertNull(fuzzyScore("Yesterday", "Bohemian Rhapsody"))
    }

    @Test
    fun substringAndTypoMatchesStayWithinThreshold() {
        assertTrue(fuzzyScore("Yesterday", "Yesterday (Remastered 2009)")!! < 0.55)
        assertTrue(fuzzyScore("Yesterday", "Yesteday")!! < 0.55)
    }

    @Test
    fun rankingRequiresArtistAndTitleAndPrefersSyncedHits() {
        val ranked = orderLyricSearchResults(
            query,
            listOf(
                OnlineLyricCandidate(OnlineLyricSource.NETEASE, "1", "晴天", "周杰伦", isSynced = null),
                OnlineLyricCandidate(OnlineLyricSource.LRCLIB, "2", "晴天", "周杰伦", isSynced = true),
                OnlineLyricCandidate(OnlineLyricSource.NETEASE, "3", "晴天", "Someone Else", isSynced = null),
            ),
        )
        assertEquals(listOf("2", "1"), ranked.map { it.id })
    }

    @Test
    fun parsesNeteasePayloads() {
        val search = parseNeteaseSearch(
            """{"code":200,"result":{"songs":[{"id":186016,"name":"晴天","artists":[{"name":"周杰伦"},{"name":"X"}]}]}}""",
        )
        assertEquals(listOf(OnlineLyricCandidate(OnlineLyricSource.NETEASE, "186016", "晴天", "周杰伦, X", null)), search)
        assertEquals("[00:01.00]a", parseNeteaseLyric("""{"lrc":{"lyric":"[00:01.00]a"}}"""))
        assertNull(parseNeteaseLyric("""{"nolyric":true}"""))
    }

    @Test
    fun parsesLrclibPayloads() {
        val search = parseLrclibSearch(
            """[{"id":7,"name":"Song","artistName":"Artist","syncedLyrics":null,"plainLyrics":"x"},
               {"id":8,"name":"Song","artistName":"Artist","syncedLyrics":"[00:01.00]x","plainLyrics":"x"}]""",
        )
        assertEquals(listOf(false, true), search.map { it.isSynced })
        assertEquals("[00:01.00]x", parseLrclibLyric("""{"syncedLyrics":"[00:01.00]x","plainLyrics":"x"}"""))
        assertEquals("plain", parseLrclibLyric("""{"syncedLyrics":null,"plainLyrics":"plain"}"""))
    }
}
