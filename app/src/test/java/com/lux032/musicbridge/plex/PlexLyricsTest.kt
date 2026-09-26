package com.lux032.musicbridge.plex

import org.junit.Assert.assertEquals
import org.junit.Test

class PlexLyricsTest {
    @Test
    fun parsesAndSortsLrcTimestamps() {
        val result = parseLrcLyrics(
            """
            [00:10.50]Second
            [00:01.2][00:02.345]First
            [ar:Artist]
            """.trimIndent(),
        )

        assertEquals(true, result.isSynced)
        assertEquals(
            listOf(
                PlexLyricLine(1_200L, "First"),
                PlexLyricLine(2_345L, "First"),
                PlexLyricLine(10_500L, "Second"),
            ),
            result.lines,
        )
    }

    @Test
    fun plainTextLyricsReceiveSyntheticTimeline() {
        val result = parseLrcLyrics("First line\n\nSecond line")

        assertEquals(false, result.isSynced)
        assertEquals(
            listOf(
                PlexLyricLine(0L, "First line"),
                PlexLyricLine(5_000L, "Second line"),
            ),
            result.lines,
        )
    }

    @Test
    fun bomIsRemovedFromTimedAndPlainLyrics() {
        val timed = parseLrcLyrics("\uFEFF[00:01.25]First line")
        assertEquals(true, timed.isSynced)
        assertEquals(listOf(PlexLyricLine(1_250L, "First line")), timed.lines)

        val plain = parseLrcLyrics("\uFEFFFirst line\nSecond line")
        assertEquals(false, plain.isSynced)
        assertEquals("First line", plain.lines.first().text)
    }

    @Test
    fun parsesLyricStreamKeyFromTrackMetadata() {
        val xml = """
            <MediaContainer><Track><Media><Part>
              <Stream streamType="2" codec="flac" />
              <Stream streamType="4" key="/library/streams/99" codec="lrc" />
            </Part></Media></Track></MediaContainer>
        """.trimIndent()

        assertEquals("/library/streams/99", parseLyricKeyFromMetadata(xml))
    }
}
