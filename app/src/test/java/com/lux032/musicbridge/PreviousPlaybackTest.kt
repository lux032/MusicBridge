package com.lux032.musicbridge

import com.lux032.musicbridge.core.shouldRestartOnPrevious
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviousPlaybackTest {
    @Test fun pastOpeningThresholdRestarts() {
        assertTrue(shouldRestartOnPrevious(3_001L, hasPrevious = true))
        assertTrue(shouldRestartOnPrevious(120_000L, hasPrevious = true))
    }

    @Test fun openingSecondsIncludingBoundaryGoBack() {
        for (position in listOf(0L, 1L, 2_999L, 3_000L)) {
            assertFalse(shouldRestartOnPrevious(position, hasPrevious = true))
        }
    }

    @Test fun firstOrOnlyTrackAlwaysRestarts() {
        for (position in listOf(0L, 1L, 3_000L, 3_001L, 120_000L)) {
            assertTrue(shouldRestartOnPrevious(position, hasPrevious = false))
        }
    }

    @Test fun secondPressAfterRewindGoesBack() {
        assertTrue(shouldRestartOnPrevious(60_000L, hasPrevious = true))
        assertFalse(shouldRestartOnPrevious(200L, hasPrevious = true))
    }

    @Test fun engineThresholdIsRespected() {
        assertFalse(shouldRestartOnPrevious(5_000L, true, 5_000L))
        assertTrue(shouldRestartOnPrevious(5_001L, true, 5_000L))
    }
}
