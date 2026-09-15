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
    fun liveTranscodeRetriesAtCurrentPositionOutsideTrackEndWindow() {
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                isLiveTranscode = true,
                durationMillis = 180_000L,
                currentPositionMillis = 90_000L,
            ),
        )
    }

    @Test
    fun liveTranscodeSkipsRecoveryInsideTrackEndWindow() {
        assertEquals(
            LiveTranscodeRetryAction.SKIP_NEAR_END,
            liveTranscodeRetryAction(
                isLiveTranscode = true,
                durationMillis = 180_000L,
                currentPositionMillis = 177_500L,
            ),
        )
    }

    @Test
    fun liveTranscodeWithUnknownDurationRetriesAtCurrentPosition() {
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                isLiveTranscode = true,
                durationMillis = null,
                currentPositionMillis = 90_000L,
            ),
        )
        assertEquals(
            LiveTranscodeRetryAction.RESTART_AT_POSITION,
            liveTranscodeRetryAction(
                isLiveTranscode = true,
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
                isLiveTranscode = false,
                durationMillis = 180_000L,
                currentPositionMillis = 90_000L,
            ),
        )
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
