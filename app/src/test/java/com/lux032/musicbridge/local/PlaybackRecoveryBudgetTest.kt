package com.lux032.musicbridge.local

import androidx.media3.common.C
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackRecoveryBudgetTest {
    @Test
    fun singleItemRetriesOnceThenSkips() {
        val budget = PlaybackRecoveryBudget()

        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(4))
        assertEquals(PlaybackRecoveryDecision.SkipCurrent, budget.onFailure(4))
    }

    @Test
    fun playbackProgressRefillsRetryBudget() {
        val budget = PlaybackRecoveryBudget()

        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(2, positionMs = 90_000L))
        assertEquals(
            PlaybackRecoveryDecision.SkipCurrent,
            budget.onFailure(2, positionMs = 92_000L),
        )
        // A later failure after > 10s of progress is treated as a new incident.
        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(2, positionMs = 103_000L))
    }

    @Test
    fun resumableTranscodeWithPlentyRemainingNeverSkips() {
        val budget = PlaybackRecoveryBudget()
        var positionMs = 60_000L
        repeat(12) {
            val decision = budget.onFailure(
                1,
                positionMs = positionMs,
                durationMs = 300_000L,
                resumableAtPosition = true,
            )
            assertEquals(PlaybackRecoveryDecision.RetryCurrent, decision)
            positionMs += 15_000L
        }
    }

    @Test
    fun resumableTranscodeWithoutProgressStopsInsteadOfSkipping() {
        val budget = PlaybackRecoveryBudget()

        assertEquals(
            PlaybackRecoveryDecision.RetryCurrent,
            budget.onFailure(1, positionMs = 60_000L, durationMs = 300_000L, resumableAtPosition = true),
        )
        repeat(3) {
            assertEquals(
                PlaybackRecoveryDecision.RetryCurrent,
                budget.onFailure(1, positionMs = 60_500L, durationMs = 300_000L, resumableAtPosition = true),
            )
        }
        assertEquals(
            PlaybackRecoveryDecision.Stop,
            budget.onFailure(1, positionMs = 60_500L, durationMs = 300_000L, resumableAtPosition = true),
        )
    }

    @Test
    fun resumableTranscodeNearTrackEndMaySkip() {
        val budget = PlaybackRecoveryBudget()

        assertEquals(
            PlaybackRecoveryDecision.RetryCurrent,
            budget.onFailure(1, positionMs = 295_000L, durationMs = 300_000L, resumableAtPosition = true),
        )
        assertEquals(
            PlaybackRecoveryDecision.SkipCurrent,
            budget.onFailure(1, positionMs = 296_000L, durationMs = 300_000L, resumableAtPosition = true),
        )
    }

    @Test
    fun resumableTranscodeWithUnknownDurationNeverSkips() {
        val budget = PlaybackRecoveryBudget()
        var positionMs = 0L
        repeat(12) {
            assertEquals(
                PlaybackRecoveryDecision.RetryCurrent,
                budget.onFailure(0, positionMs = positionMs, durationMs = null, resumableAtPosition = true),
            )
            positionMs += 15_000L
        }
    }

    @Test
    fun playingBetweenFailuresDoesNotRestoreRetryBudget() {
        val budget = PlaybackRecoveryBudget()

        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(2))
        // No success callback resets the budget merely because playback briefly started.
        assertEquals(PlaybackRecoveryDecision.SkipCurrent, budget.onFailure(2))
    }

    @Test
    fun threeConsecutivelyFailedItemsStops() {
        val budget = PlaybackRecoveryBudget()

        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(0))
        assertEquals(PlaybackRecoveryDecision.SkipCurrent, budget.onFailure(0))
        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(1))
        assertEquals(PlaybackRecoveryDecision.SkipCurrent, budget.onFailure(1))
        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(2))
        assertEquals(PlaybackRecoveryDecision.Stop, budget.onFailure(2))
    }

    @Test
    fun userActionResetsFailureBudget() {
        val budget = PlaybackRecoveryBudget()
        budget.onFailure(0)
        budget.onFailure(0)
        budget.resetForUserAction()

        assertEquals(PlaybackRecoveryDecision.RetryCurrent, budget.onFailure(0))
    }

    @Test
    fun mp3LiveTranscodeRetriesAtCurrentPositionOutsideTrackEndWindow() {
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                streamUrl = "https://plex/music/:/transcode/universal/start.mp3?offset=0",
                durationMillis = 180_000L,
                currentPositionMillis = 90_000L,
            ),
        )
    }

    @Test
    fun oggLiveTranscodeRetriesAtCurrentPositionWithFastSeekSupport() {
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                streamUrl = "https://plex/music/:/transcode/universal/start.ogg?offset=0",
                durationMillis = 180_000L,
                currentPositionMillis = 90_000L,
            ),
        )
    }

    @Test
    fun mp3LiveTranscodeSkipsRecoveryInsideTrackEndWindow() {
        assertEquals(
            LiveTranscodeRetryAction.SKIP_NEAR_END,
            liveTranscodeRetryAction(
                streamUrl = "https://plex/music/:/transcode/universal/start.mp3?offset=0",
                durationMillis = 180_000L,
                currentPositionMillis = 177_500L,
            ),
        )
    }

    @Test
    fun mp3LiveTranscodeWithUnknownDurationRetriesAtCurrentPosition() {
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                streamUrl = "https://plex/music/:/transcode/universal/start.mp3?offset=0",
                durationMillis = null,
                currentPositionMillis = 90_000L,
            ),
        )
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                streamUrl = "https://plex/music/:/transcode/universal/start.mp3?offset=0",
                durationMillis = 0L,
                currentPositionMillis = 90_000L,
            ),
        )
    }

    @Test
    fun nonTranscodeKeepsExistingRestartBehavior() {
        assertEquals(
            LiveTranscodeRetryAction.RETRY_FROM_START,
            liveTranscodeRetryAction(
                streamUrl = "https://plex/library/parts/1/file.flac",
                durationMillis = 180_000L,
                currentPositionMillis = 90_000L,
            ),
        )
    }

    @Test
    fun prematureTranscodeEndRequiresRemoteLiveStreamKnownDurationAndFiveSecondGap() {
        assertEquals(true, isPrematureTranscodeEnd(true, false, 80_000L, 261_000L))
        assertEquals(false, isPrematureTranscodeEnd(false, false, 80_000L, 261_000L))
        assertEquals(false, isPrematureTranscodeEnd(true, true, 80_000L, 261_000L))
        assertEquals(false, isPrematureTranscodeEnd(true, false, 257_000L, 261_000L))
        assertEquals(false, isPrematureTranscodeEnd(true, false, 80_000L, null))
    }

    @Test
    fun repeatOneNeverSelectsSameFailedItem() {
        assertEquals(
            C.INDEX_UNSET,
            nextSequentialRecoveryIndex(0, 1, Player.REPEAT_MODE_ONE),
        )
    }

    @Test
    fun repeatAllWrapsAtQueueEnd() {
        assertEquals(0, nextSequentialRecoveryIndex(2, 3, Player.REPEAT_MODE_ALL))
    }

    @Test
    fun sequentialQueueEndHasNoRecoveryTarget() {
        assertEquals(
            C.INDEX_UNSET,
            nextSequentialRecoveryIndex(2, 3, Player.REPEAT_MODE_OFF),
        )
    }

    @Test
    fun sequentialQueueAdvancesToNextIndex() {
        assertEquals(2, nextSequentialRecoveryIndex(1, 4, Player.REPEAT_MODE_ONE))
    }
}
