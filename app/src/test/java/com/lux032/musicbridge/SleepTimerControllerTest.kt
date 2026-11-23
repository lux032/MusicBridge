package com.lux032.musicbridge

import com.lux032.musicbridge.sonos.SonosRoom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SleepTimerControllerTest {

    @After
    fun tearDown() {
        SleepTimerController.onExpired = null
        SleepTimerController.cancel()
    }

    @Test
    fun `stop after current track marks state active without countdown`() {
        SleepTimerController.startStopAfterCurrentTrack(room = null)

        val state = SleepTimerController.state.value
        assertTrue(state.isActive)
        assertTrue(state.stopAfterCurrentTrack)
        assertFalse(state.isCountdownActive)
        assertNull(state.endEpochMillis)
        assertEquals(0L, state.remainingMillis)
    }

    private fun room(name: String, uuid: String, host: String) = SonosRoom(
        roomName = name,
        coordinatorUuid = uuid,
        coordinatorBaseUrl = "http://$host:1400",
        memberCount = 1,
        rawCoordinatorName = name,
        memberBaseUrls = listOf("http://$host:1400"),
    )

    @Test
    fun `triggerIfStopAfterCurrentTrack fires once with attached room and clears state`() {
        val room = room("Living Room", "uuid-1", "192.168.1.50")
        var firedCount = 0
        var firedRoom: SonosRoom? = null
        SleepTimerController.onExpired = { expiredRoom ->
            firedCount += 1
            firedRoom = expiredRoom
        }

        SleepTimerController.startStopAfterCurrentTrack(room = room)

        assertTrue(SleepTimerController.triggerIfStopAfterCurrentTrack())
        assertEquals(1, firedCount)
        assertEquals(room, firedRoom)
        assertFalse(SleepTimerController.state.value.isActive)
        assertFalse(SleepTimerController.state.value.stopAfterCurrentTrack)

        // Subsequent triggers must be no-ops.
        assertFalse(SleepTimerController.triggerIfStopAfterCurrentTrack())
        assertEquals(1, firedCount)
    }

    @Test
    fun `attachRoom updates target room for armed stop-after-track timer`() {
        val initialRoom = room("Bedroom", "uuid-a", "192.168.1.10")
        val switchedRoom = room("Study", "uuid-b", "192.168.1.11")
        var firedRoom: SonosRoom? = null
        SleepTimerController.onExpired = { firedRoom = it }

        SleepTimerController.startStopAfterCurrentTrack(room = initialRoom)
        SleepTimerController.attachRoom(switchedRoom)

        assertTrue(SleepTimerController.triggerIfStopAfterCurrentTrack())
        assertEquals(switchedRoom, firedRoom)
    }

    @Test
    fun `triggerIfStopAfterCurrentTrack does nothing when inactive or cancelled`() {
        var fired = false
        SleepTimerController.onExpired = { fired = true }

        assertFalse(SleepTimerController.triggerIfStopAfterCurrentTrack())
        SleepTimerController.startStopAfterCurrentTrack(room = null)
        SleepTimerController.cancel()
        assertFalse(SleepTimerController.triggerIfStopAfterCurrentTrack())
        assertFalse(fired)
    }
}
