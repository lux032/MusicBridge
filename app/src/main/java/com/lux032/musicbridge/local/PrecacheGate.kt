package com.lux032.musicbridge.local

import android.os.SystemClock

internal data class PrecacheGateSnapshot(
    val foregroundUsesNetwork: Boolean,
    val isBuffering: Boolean,
    val headroomMillis: Long,
    val isNearTrackEnd: Boolean,
    val publishedAtMillis: Long,
)

internal enum class PrecacheGateTransition { NONE, YIELDED, RESUMED }

internal class PrecacheGateController(
    private val clock: () -> Long,
    private val staleAfterMillis: Long = 5_000L,
    private val recentForegroundTrafficMillis: Long = 2_000L,
    private val yieldBelowHeadroomMillis: Long = 15_000L,
    private val resumeAtHeadroomMillis: Long = 20_000L,
    private val minimumYieldMillis: Long = 3_000L,
) {
    @Volatile private var snapshot = PrecacheGateSnapshot(
        foregroundUsesNetwork = false,
        isBuffering = false,
        headroomMillis = Long.MAX_VALUE,
        isNearTrackEnd = false,
        publishedAtMillis = Long.MIN_VALUE,
    )
    @Volatile private var lastForegroundBytesAtMillis = Long.MIN_VALUE
    @Volatile private var yielded = false
    @Volatile private var yieldedAtMillis = Long.MIN_VALUE

    fun noteForegroundBytes() {
        lastForegroundBytesAtMillis = clock()
    }

    @Synchronized
    fun publish(newSnapshot: PrecacheGateSnapshot): PrecacheGateTransition {
        snapshot = newSnapshot
        return updateTransition(clock())
    }

    @Synchronized
    fun open(): PrecacheGateTransition {
        snapshot = PrecacheGateSnapshot(false, false, Long.MAX_VALUE, false, clock())
        lastForegroundBytesAtMillis = Long.MIN_VALUE
        return if (yielded) {
            yielded = false
            yieldedAtMillis = Long.MIN_VALUE
            PrecacheGateTransition.RESUMED
        } else {
            PrecacheGateTransition.NONE
        }
    }

    @Synchronized
    fun shouldYield(nowMillis: Long = clock()): Boolean {
        val current = snapshot
        // Stale publisher state must always fail open. The five-second TTL is longer than
        // the three-second minimum yield hold, so this cannot create rapid session churn.
        if (current.publishedAtMillis == Long.MIN_VALUE ||
            nowMillis - current.publishedAtMillis > staleAfterMillis
        ) return false
        if (!current.foregroundUsesNetwork) return false
        if (current.isNearTrackEnd) return false
        if (current.isBuffering) return true

        if (yielded) {
            if (yieldedAtMillis != Long.MIN_VALUE && nowMillis - yieldedAtMillis < minimumYieldMillis) {
                return true
            }
            // Hysteresis: once yielded, do not reopen at the 15s entry boundary. Wait until
            // foreground playback has built a safer 20s reservoir.
            return current.headroomMillis < resumeAtHeadroomMillis
        }

        val lastBytesAt = lastForegroundBytesAtMillis
        val foregroundRecentlyActive = lastBytesAt != Long.MIN_VALUE &&
            nowMillis - lastBytesAt <= recentForegroundTrafficMillis
        return foregroundRecentlyActive && current.headroomMillis < yieldBelowHeadroomMillis
    }

    @Synchronized
    private fun updateTransition(nowMillis: Long): PrecacheGateTransition {
        val shouldYield = shouldYield(nowMillis)
        return when {
            shouldYield && !yielded -> {
                yielded = true
                yieldedAtMillis = nowMillis
                PrecacheGateTransition.YIELDED
            }
            !shouldYield && yielded -> {
                yielded = false
                yieldedAtMillis = Long.MIN_VALUE
                PrecacheGateTransition.RESUMED
            }
            else -> PrecacheGateTransition.NONE
        }
    }
}

/**
 * Independent foreground/precache coordination gate. It never owns LocalAudioCache's monitor,
 * and background callers only read the volatile snapshot published by the player application thread.
 */
internal object PrecacheGate {
    private const val DIAGNOSTIC_DEBOUNCE_MILLIS = 500L
    private val controller = PrecacheGateController(SystemClock::elapsedRealtime)
    @Volatile private var lastYieldDiagnosticAtMillis = Long.MIN_VALUE
    @Volatile private var lastResumeDiagnosticAtMillis = Long.MIN_VALUE

    fun noteForegroundBytes() = controller.noteForegroundBytes()

    fun publish(snapshot: PrecacheGateSnapshot): PrecacheGateTransition =
        controller.publish(snapshot).also(::recordTransition)

    fun open(): PrecacheGateTransition = controller.open().also(::recordTransition)

    fun shouldYield(): Boolean = controller.shouldYield()

    fun awaitOpen(generationIsCurrent: () -> Boolean): Boolean {
        while (generationIsCurrent() && shouldYield()) {
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return generationIsCurrent()
    }

    private fun recordTransition(transition: PrecacheGateTransition) {
        if (transition == PrecacheGateTransition.NONE) return
        val now = SystemClock.elapsedRealtime()
        val previous = when (transition) {
            PrecacheGateTransition.YIELDED -> lastYieldDiagnosticAtMillis
            PrecacheGateTransition.RESUMED -> lastResumeDiagnosticAtMillis
            PrecacheGateTransition.NONE -> return
        }
        if (previous != Long.MIN_VALUE && now - previous < DIAGNOSTIC_DEBOUNCE_MILLIS) return
        when (transition) {
            PrecacheGateTransition.YIELDED -> lastYieldDiagnosticAtMillis = now
            PrecacheGateTransition.RESUMED -> lastResumeDiagnosticAtMillis = now
            PrecacheGateTransition.NONE -> return
        }
        PlaybackDiagnostics.record(
            if (transition == PrecacheGateTransition.YIELDED) "PRECACHE_YIELD" else "PRECACHE_RESUMED",
            "foreground priority gate ${transition.name.lowercase()}",
        )
    }
}
