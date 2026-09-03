package com.lux032.musicbridge.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecacheGateTest {
    @Test
    fun bufferingNetworkTrackYieldsButLocalAndCachedTracksDoNot() {
        var now = 1_000L
        val gate = PrecacheGateController(clock = { now })

        assertEquals(
            PrecacheGateTransition.YIELDED,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, isBuffering = true)),
        )
        assertTrue(gate.shouldYield())

        // Explicit local/cached exemption reopens even during a buffering player state.
        assertEquals(
            PrecacheGateTransition.RESUMED,
            gate.publish(snapshot(now, foregroundUsesNetwork = false, isBuffering = true)),
        )
        assertFalse(gate.shouldYield())
    }

    @Test
    fun headroomUsesAsymmetricHysteresisAndThreeSecondCooldown() {
        var now = 1_000L
        val gate = PrecacheGateController(clock = { now })
        gate.noteForegroundBytes()

        assertEquals(
            PrecacheGateTransition.YIELDED,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, headroomMillis = 14_999L)),
        )

        now += 2_999L
        assertEquals(
            PrecacheGateTransition.NONE,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, headroomMillis = 25_000L)),
        )
        assertTrue(gate.shouldYield())

        now += 1L
        assertEquals(
            PrecacheGateTransition.NONE,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, headroomMillis = 19_999L)),
        )
        assertTrue(gate.shouldYield())

        assertEquals(
            PrecacheGateTransition.RESUMED,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, headroomMillis = 20_000L)),
        )
        assertFalse(gate.shouldYield())
        assertEquals(
            PrecacheGateTransition.NONE,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, headroomMillis = 19_000L)),
        )
    }

    @Test
    fun nearTrackEndAllowsNextTrackPrecache() {
        var now = 1_000L
        val gate = PrecacheGateController(clock = { now })
        gate.noteForegroundBytes()
        assertEquals(
            PrecacheGateTransition.NONE,
            gate.publish(
                snapshot(
                    now,
                    foregroundUsesNetwork = true,
                    headroomMillis = 500L,
                    isNearTrackEnd = true,
                ),
            ),
        )
        assertFalse(gate.shouldYield())
    }

    @Test
    fun stalePublisherFailsOpenAfterFiveSeconds() {
        var now = 1_000L
        val gate = PrecacheGateController(clock = { now })
        assertEquals(
            PrecacheGateTransition.YIELDED,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, isBuffering = true)),
        )

        now += 5_001L
        assertFalse(gate.shouldYield())
        assertEquals(
            PrecacheGateTransition.RESUMED,
            gate.publish(snapshot(now, foregroundUsesNetwork = false)),
        )
    }

    @Test
    fun foregroundTrafficExpiresAfterTwoSecondsWhenGateIsOpen() {
        var now = 1_000L
        val gate = PrecacheGateController(clock = { now })
        gate.noteForegroundBytes()

        now += 2_001L
        assertEquals(
            PrecacheGateTransition.NONE,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, headroomMillis = 1_000L)),
        )
        assertFalse(gate.shouldYield())
    }

    @Test
    fun openExplicitlyReleasesYieldAndIsIdempotent() {
        var now = 1_000L
        val gate = PrecacheGateController(clock = { now })
        assertEquals(
            PrecacheGateTransition.YIELDED,
            gate.publish(snapshot(now, foregroundUsesNetwork = true, isBuffering = true)),
        )
        now += 100L
        assertEquals(PrecacheGateTransition.RESUMED, gate.open())
        assertFalse(gate.shouldYield())
        assertEquals(PrecacheGateTransition.NONE, gate.open())
    }

    private fun snapshot(
        now: Long,
        foregroundUsesNetwork: Boolean,
        isBuffering: Boolean = false,
        headroomMillis: Long = 30_000L,
        isNearTrackEnd: Boolean = false,
    ) = PrecacheGateSnapshot(
        foregroundUsesNetwork = foregroundUsesNetwork,
        isBuffering = isBuffering,
        headroomMillis = headroomMillis,
        isNearTrackEnd = isNearTrackEnd,
        publishedAtMillis = now,
    )
}
