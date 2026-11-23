package com.lux032.musicbridge.lyrics

import com.lux032.musicbridge.LyricClockAnchor
import com.lux032.musicbridge.computeLyricScrollDeltaPx
import com.lux032.musicbridge.computeLyricTargetScrollOffset
import com.lux032.musicbridge.findActiveLyricIndex
import com.lux032.musicbridge.interpolateLyricPositionMillis
import com.lux032.musicbridge.reconcileLyricClockAnchor
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

    @Test
    fun findActiveLyricIndexHandlesEmptyIntroExactAndDuplicateTimestamps() {
        assertEquals(-1, findActiveLyricIndex(emptyList(), 0L))

        val lines = listOf(
            LyricLine(2_000L, "Line 1"),
            LyricLine(5_000L, "Line 2a"),
            LyricLine(5_000L, "Line 2b"),
            LyricLine(9_500L, "Line 3"),
        )
        assertEquals(0, findActiveLyricIndex(lines, 500L))
        assertEquals(0, findActiveLyricIndex(lines, 2_000L))
        assertEquals(2, findActiveLyricIndex(lines, 5_000L))
        assertEquals(2, findActiveLyricIndex(lines, 9_499L))
        assertEquals(3, findActiveLyricIndex(lines, 9_500L))
        assertEquals(3, findActiveLyricIndex(lines, 99_000L))
    }

    @Test
    fun lyricScrollMathCentersItemAtViewportAnchor() {
        // Viewport height 1000px with 200px top contentPadding -> viewportStartOffset = -200.
        // 36% anchor is 360px from top edge -> offset +160 in layout coordinates.
        assertEquals(
            280f,
            computeLyricScrollDeltaPx(
                itemOffset = 400,
                itemSize = 80,
                viewportStartOffset = -200,
                viewportHeight = 1000,
            ),
            0.01f,
        )
        assertEquals(
            -170f,
            computeLyricScrollDeltaPx(
                itemOffset = -50,
                itemSize = 80,
                viewportStartOffset = -200,
                viewportHeight = 1000,
            ),
            0.01f,
        )
        assertEquals(
            0f,
            computeLyricScrollDeltaPx(
                itemOffset = 120,
                itemSize = 80,
                viewportStartOffset = -200,
                viewportHeight = 1000,
            ),
            0.01f,
        )
        assertEquals(
            -120,
            computeLyricTargetScrollOffset(
                viewportStartOffset = -200,
                viewportHeight = 1000,
                estimatedItemHeightPx = 80,
            ),
        )
    }

    @Test
    fun lyricClockAnchorAvoidsBackwardJitterAndSnapsOnSeekOrStall() {
        val initial = reconcileLyricClockAnchor(
            previous = null,
            reportedPositionMillis = 10_000L,
            nowElapsedRealtimeMillis = 1_000L,
            isPlaying = true,
            durationMillis = 180_000L,
        )
        assertEquals(10_500L, interpolateLyricPositionMillis(initial, 1_500L, isPlaying = true, durationMillis = 180_000L))

        // Sonos 1s truncation reports 11_000ms at t=2_080ms when monotonic estimate is 11_080ms:
        // keep monotonic estimate so the active line never flickers backward.
        val afterSlightLag = reconcileLyricClockAnchor(
            previous = initial,
            reportedPositionMillis = 11_000L,
            nowElapsedRealtimeMillis = 2_080L,
            isPlaying = true,
            durationMillis = 180_000L,
        )
        assertEquals(LyricClockAnchor(11_080L, 2_080L, 11_000L), afterSlightLag)

        // Genuine stall (> 1.5s behind estimate) snaps back to reported position.
        val afterStall = reconcileLyricClockAnchor(
            previous = afterSlightLag,
            reportedPositionMillis = 11_000L,
            nowElapsedRealtimeMillis = 3_700L,
            isPlaying = true,
            durationMillis = 180_000L,
        )
        assertEquals(11_000L, afterStall.positionMillis)

        // Backward seek snaps immediately.
        val afterSeekBack = reconcileLyricClockAnchor(
            previous = afterStall,
            reportedPositionMillis = 4_000L,
            nowElapsedRealtimeMillis = 3_800L,
            isPlaying = true,
            durationMillis = 180_000L,
        )
        assertEquals(4_000L, afterSeekBack.positionMillis)

        // Forward seek (> 2.5s jump) snaps immediately.
        val afterSeekForward = reconcileLyricClockAnchor(
            previous = afterSeekBack,
            reportedPositionMillis = 45_000L,
            nowElapsedRealtimeMillis = 3_900L,
            isPlaying = true,
            durationMillis = 180_000L,
        )
        assertEquals(45_000L, afterSeekForward.positionMillis)

        // Paused state freezes at reported position and clamps to duration.
        val paused = reconcileLyricClockAnchor(
            previous = afterSeekForward,
            reportedPositionMillis = 190_000L,
            nowElapsedRealtimeMillis = 4_000L,
            isPlaying = false,
            durationMillis = 180_000L,
        )
        assertEquals(180_000L, interpolateLyricPositionMillis(paused, 9_000L, isPlaying = false, durationMillis = 180_000L))
    }
}
