package com.lux032.musicbridge.service

import android.app.Service
import com.lux032.musicbridge.local.LocalPlaybackEngine
import com.lux032.musicbridge.local.LocalPlaybackService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlaybackServiceLifecycleTest {

    @Test
    fun playbackForegroundServiceStopsOnNullRestartIntentAndIsNotSticky() {
        val controller = Robolectric.buildService(PlaybackForegroundService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }

    @Test
    fun localPlaybackServiceDoesNotStayStickyWithoutActiveEngine() {
        LocalPlaybackEngine.activeEngine = null
        val controller = Robolectric.buildService(LocalPlaybackService::class.java).create()
        val service = controller.get()
        val result = service.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, result)
        controller.destroy()
    }
}
