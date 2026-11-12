package com.lux032.musicbridge.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsTest {
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
                LyricLine(1_200L, "First"),
                LyricLine(2_345L, "First"),
                LyricLine(10_500L, "Second"),
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
                LyricLine(0L, "First line"),
                LyricLine(5_000L, "Second line"),
            ),
            result.lines,
        )
    }

    @Test
    fun bomIsRemovedFromTimedAndPlainLyrics() {
        val timed = parseLrcLyrics("\uFEFF[00:01.25]First line")
        assertEquals(true, timed.isSynced)
        assertEquals(listOf(LyricLine(1_250L, "First line")), timed.lines)

        val plain = parseLrcLyrics("\uFEFFFirst line\nSecond line")
        assertEquals(false, plain.isSynced)
        assertEquals("First line", plain.lines.first().text)
    }
}
