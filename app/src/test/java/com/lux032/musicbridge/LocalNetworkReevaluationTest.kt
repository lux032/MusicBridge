package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkReevaluationTest {
    @Test
    fun everyModeReevaluatesAllNonCurrentItems() {
        PlaybackMode.entries.forEach { mode ->
            assertEquals(listOf(0, 1, 3, 4), networkReevaluationIndices(5, 2, mode))
        }
    }

    @Test
    fun invalidCurrentIndexSafelyReevaluatesWholeQueue() {
        assertEquals(listOf(0, 1, 2), networkReevaluationIndices(3, -1, PlaybackMode.Shuffle))
    }

    @Test
    fun emptyQueueHasNoWork() {
        assertEquals(emptyList<Int>(), networkReevaluationIndices(0, 0, PlaybackMode.RepeatAll))
    }
}
