package com.lux032.musicbridge

import androidx.media3.common.Player
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterPlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure routing/mapping helpers that decide how engine and Sonos observations map onto 032
 * playback session events. Plex paths never use these (they keep the legacy timeline
 * behavior), so the mapping honesty rules are 032-only by construction.
 */
class PlaybackReporterRoutingTest {

    @Test
    fun `engine transitions map to end reasons`() {
        // Auto-advance and repeat-one both mean the previous track played to its end.
        assertEquals(
            ReporterEndReason.Completed,
            reporterEndReasonForTransition(Player.MEDIA_ITEM_TRANSITION_REASON_AUTO),
        )
        assertEquals(
            ReporterEndReason.Completed,
            reporterEndReasonForTransition(Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT),
        )
        // A user-initiated jump is a skip candidate; the server applies its own threshold.
        assertEquals(
            ReporterEndReason.Skipped,
            reporterEndReasonForTransition(Player.MEDIA_ITEM_TRANSITION_REASON_SEEK),
        )
    }

    @Test
    fun `local reporter state is honest about suppression`() {
        assertEquals(
            ReporterPlayState.Playing,
            localReporterPlayState(isPlaying = true, isPlayRequested = true, isBuffering = false),
        )
        assertEquals(
            ReporterPlayState.Paused,
            localReporterPlayState(isPlaying = false, isPlayRequested = false, isBuffering = false),
        )
        assertEquals(
            ReporterPlayState.Buffering,
            localReporterPlayState(isPlaying = false, isPlayRequested = true, isBuffering = true),
        )
        // playWhenReady without actual playback (audio focus loss / suppression): not playing.
        assertEquals(
            ReporterPlayState.Buffering,
            localReporterPlayState(isPlaying = false, isPlayRequested = true, isBuffering = false),
        )
    }

    @Test
    fun `sonos reporter state maps transport states honestly`() {
        assertEquals(ReporterPlayState.Playing, sonosReporterPlayState("playing"))
        assertEquals(ReporterPlayState.Buffering, sonosReporterPlayState("buffering"))
        assertEquals(ReporterPlayState.Paused, sonosReporterPlayState("paused"))
        assertEquals(ReporterPlayState.Paused, sonosReporterPlayState("stopped"))
    }

    @Test
    fun `same track repeat wrap needs a near-end to near-start position drop`() {
        // Loop of a 4-minute track.
        assertTrue(isSameTrackRepeatWrap(235_000, 1_000, 240_000, "playing"))
        assertTrue(isSameTrackRepeatWrap(230_000, 10_000, 240_000, "buffering"))
        // Seek-back from mid track is not a loop.
        assertFalse(isSameTrackRepeatWrap(120_000, 5_000, 240_000, "playing"))
        // Seek-back from near the end but landing past the 10s boundary is not a loop.
        assertFalse(isSameTrackRepeatWrap(235_000, 60_000, 240_000, "playing"))
        // Stopped transport (end-of-track readback) is not a loop.
        assertFalse(isSameTrackRepeatWrap(239_000, 0, 240_000, "stopped"))
        // Unknown or tiny durations never trigger.
        assertFalse(isSameTrackRepeatWrap(100, 0, null, "playing"))
        assertFalse(isSameTrackRepeatWrap(15_000, 0, 20_000, "playing"))
    }
}
