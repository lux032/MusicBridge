package com.lux032.musicbridge

import com.lux032.musicbridge.sonos.SonosRoom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Sleep-timer countdown, shared by both playback targets. It used to live inside
 * PlaybackForegroundService, but local playback no longer runs that service (it publishes
 * a MediaSession via LocalPlaybackService instead), so the timer now lives in the app-state
 * scope: a foreground playback service is always keeping the process alive while a timer
 * can be active.
 */
internal object SleepTimerController {
    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var job: Job? = null
    private var attachedRoom: SonosRoom? = null

    /** Invoked once when the countdown elapses, with the Sonos room attached at that moment. */
    var onExpired: ((SonosRoom?) -> Unit)? = null

    fun start(scope: CoroutineScope, durationMillis: Long, room: SonosRoom?) {
        cancel()
        if (durationMillis <= 0L) return
        attachedRoom = room
        val endEpochMillis = System.currentTimeMillis() + durationMillis
        _state.value = SleepTimerState(endEpochMillis, durationMillis)
        job = scope.launch {
            while (isActive) {
                val remainingMillis = (endEpochMillis - System.currentTimeMillis()).coerceAtLeast(0L)
                _state.value = SleepTimerState(
                    endEpochMillis = endEpochMillis.takeIf { remainingMillis > 0L },
                    remainingMillis = remainingMillis,
                )
                if (remainingMillis <= 0L) break
                delay(1_000L)
            }
            val expiredRoom = attachedRoom
            attachedRoom = null
            job = null
            onExpired?.invoke(expiredRoom)
        }
    }

    /** Re-points the running timer at another Sonos room (or none) after a target switch. */
    fun attachRoom(room: SonosRoom?) {
        attachedRoom = room
    }

    fun cancel() {
        job?.cancel()
        job = null
        attachedRoom = null
        _state.value = SleepTimerState()
    }
}
