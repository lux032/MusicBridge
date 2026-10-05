package com.lux032.musicbridge

import androidx.media3.common.Player
import com.lux032.musicbridge.music.Music032ReporterApi
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterPlayState
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinator-logic tests: the REAL Local032SessionDriver routing (explicit start vs
 * takeover, transition end reasons, failed-last-track error, ghost-session latch) and the
 * ReportingJobCleanup lifecycle rules (P1-1: synchronous guarded service teardown before
 * the fire-and-forget session end).
 */
class Local032SessionDriverTest {

    private class FakeReporter(val sourceId: String) : Music032ReporterApi {
        override val clientId = "fake-client"
        var openSession: Pair<String, Long>? = null
        val calls = mutableListOf<String>()

        override fun openSessionId(): String? = openSession?.first
        override fun openSessionTrackId(): Long? = openSession?.second
        override fun requestStart(trackId: Long, positionMillis: Long, durationMillis: Long, initialState: ReporterPlayState) {
            calls += "start:$trackId@$positionMillis"
            openSession = "session-$trackId" to trackId
        }
        override fun requestTakeover(trackId: Long, positionMillis: Long, durationMillis: Long, initialState: ReporterPlayState) {
            calls += "takeover:$trackId@$positionMillis"
            openSession = "session-$trackId-t" to trackId
        }
        override fun requestFallbackStart(trackId: Long, positionMillis: Long, durationMillis: Long, initialState: ReporterPlayState) {
            calls += "fallback:$trackId@$positionMillis"
            openSession = "session-$trackId" to trackId
        }
        override fun observe(state: ReporterPlayState, positionMillis: Long, durationMillis: Long) {
            calls += "observe:${state.wire}@$positionMillis"
        }
        override fun requestSeek(positionMillis: Long, durationMillis: Long) {
            calls += "seek:$positionMillis"
        }
        override fun requestEnd(reason: ReporterEndReason, finalPositionMillis: Long?, expectedSessionId: String?) {
            calls += "end:${reason.wire}" + (expectedSessionId?.let { ":exp=$it" } ?: "")
            openSession = null
        }
        override fun requestShutdown(reason: ReporterEndReason, forget: Boolean) {
            calls += "shutdown:${reason.wire}"
            openSession = null
        }
    }

    private val plexReporter = FakeReporter("plex-src")
    private val reporter032 = FakeReporter("032-src")
    private val reporters = mapOf("032-src" to reporter032, "plex-src" to plexReporter)

    private fun track(key: String, sourceId: String) = PlexTrackStream(
        ratingKey = key,
        title = "track-$key",
        streamUrl = "http://example/$key",
        partKey = "/part/$key",
        durationMillis = 240_000L,
        userRating = null,
        sourceId = sourceId,
    )

    private val track42 = track("42", "032-src")
    private val track43 = track("43", "032-src")
    private val plexTrack = track("7", "plex-src")

    private fun newDriver() = Local032SessionDriver(
        reporterFor = { sourceId -> reporters[sourceId] },
        is032Track = { it.sourceId == "032-src" },
    )

    // ------------------------------------------------------------------ routing (P1-2)

    @Test
    fun `explicit start opens a fresh chain, only renderer switch takes over`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, positionMillis = 0L, durationMillis = 240_000L, continueChain = false)
        assertEquals(listOf("start:42@0"), reporter032.calls)

        // A track switch ends the outgoing session explicitly before the takeover.
        driver.onExplicitStart(track43, positionMillis = 5_000L, durationMillis = 240_000L, continueChain = true)
        assertEquals(listOf("start:42@0", "end:stopped:exp=session-42", "takeover:43@5000"), reporter032.calls)
    }

    @Test
    fun `re-selecting the current track is a new play from zero on a fresh chain (P2-A)`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        reporter032.openSession = "session-42" to 42L

        driver.onExplicitReplaySameTrack(track42, 240_000L)

        assertEquals(listOf("start:42@0", "end:stopped:exp=session-42", "start:42@0"), reporter032.calls)
    }

    @Test
    fun `switching to another 032 source ends the previous source's session (P2-C)`() {
        val otherReporter = FakeReporter("032-other")
        val driver = Local032SessionDriver(
            reporterFor = { sourceId -> if (sourceId == "032-src") reporter032 else otherReporter },
            is032Track = { it.sourceId.startsWith("032") },
        )
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        val otherTrack = track("99", "032-other")
        driver.onExplicitStart(otherTrack, 0L, 240_000L, continueChain = false)

        assertEquals(listOf("start:42@0", "end:stopped:exp=session-42"), reporter032.calls)
        assertEquals(listOf("start:99@0"), otherReporter.calls)
    }

    @Test
    fun `engine transitions end the old play with its own reason and open a fresh play`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)

        // Auto-advance: completed, then a fresh start for the next track.
        driver.onEngineTransition(track43, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, 0L, 240_000L, ReporterPlayState.Buffering)
        assertEquals(
            listOf("start:42@0", "end:completed:exp=session-42", "start:43@0"),
            reporter032.calls,
        )

        // User jump: skipped, then a fresh start.
        driver.onEngineTransition(track42, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 0L, 240_000L, ReporterPlayState.Playing)
        assertEquals("end:skipped:exp=session-43", reporter032.calls[3])
        assertEquals("start:42@0", reporter032.calls[4])
    }

    @Test
    fun `repeat-one loop of the same track ends completed and starts a genuinely new play at zero`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        reporter032.openSession = "session-42" to 42L

        driver.onEngineTransition(track42, Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT, 3_000L, 240_000L, ReporterPlayState.Buffering)

        assertEquals(
            listOf("start:42@0", "end:completed:exp=session-42", "start:42@0"),
            reporter032.calls,
        )
    }

    // ------------------------------------------------------------------ queue end (P1-3)

    @Test
    fun `natural queue end reports completed, failed last track reports error`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        driver.onQueueEnded(track42, failed = false)
        assertEquals("end:completed", reporter032.calls.last())

        val failedDriver = newDriver()
        failedDriver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        failedDriver.onQueueEnded(track42, failed = true)
        assertEquals("end:error", reporter032.calls.last())
    }

    @Test
    fun `failed track reports error with the session it owns`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        driver.onTrackFailed(track42)
        assertEquals(listOf("start:42@0", "end:error:exp=session-42"), reporter032.calls)
    }

    // ------------------------------------------------------------------ poll / ghost latch (P3)

    @Test
    fun `poll repairs a missing session but never after the queue ended`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        reporter032.openSession = null // simulate a lost session

        driver.onPoll(track42, "playing", ReporterPlayState.Playing, 61_000L, 240_000L)
        assertTrue(reporter032.calls.any { it == "fallback:42@61000" })

        // After the queue ended, the poll must not open ghost paused sessions.
        val endedDriver = newDriver()
        endedDriver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        endedDriver.onQueueEnded(track42, failed = false)
        reporter032.calls.clear()
        reporter032.openSession = null
        endedDriver.onPoll(track42, "paused", ReporterPlayState.Paused, 0L, 240_000L)
        assertTrue(reporter032.calls.none { it.startsWith("fallback") })
    }

    @Test
    fun `a genuinely playing observation after the queue ended is a replay and opens a session (P1-C)`() {
        val driver = newDriver()
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        driver.onQueueEnded(track42, failed = false)
        reporter032.calls.clear()
        reporter032.openSession = null

        // User pressed play again on the parked queue: the finished latch must not
        // suppress this genuinely-new play.
        driver.onPoll(track42, "playing", ReporterPlayState.Playing, 1_000L, 240_000L)

        assertTrue(reporter032.calls.any { it == "fallback:42@1000" })
        assertTrue(reporter032.calls.any { it.startsWith("observe:playing") })
    }

    @Test
    fun `poll does not start a fallback while stopped or for non-032 tracks`() {
        val driver = newDriver()
        driver.onPoll(track42, "stopped", ReporterPlayState.Paused, 0L, 240_000L)
        assertTrue(reporter032.calls.isEmpty())

        driver.onPoll(plexTrack, "playing", ReporterPlayState.Playing, 1_000L, 240_000L)
        assertTrue(reporter032.calls.isEmpty() && plexReporter.calls.isEmpty())
    }

    @Test
    fun `seek routes only for 032 tracks`() {
        val driver = newDriver()
        driver.onSeek(track42, 90_000L, 240_000L)
        driver.onSeek(plexTrack, 5_000L, 240_000L)
        assertEquals(listOf("seek:90000"), reporter032.calls)
        assertTrue(plexReporter.calls.isEmpty())
    }

    @Test
    fun `captureOwnedSession reflects the session the reporting job may end`() {
        val driver = newDriver()
        assertNull(driver.captureOwnedSession())
        driver.onExplicitStart(track42, 0L, 240_000L, continueChain = false)
        assertEquals("032-src" to "session-42", driver.captureOwnedSession())
    }

    // ------------------------------------------------------------------ job cleanup (P1-1)

    @Test
    fun `job cleanup runs only for the active job, and concurrent cleanups stop at most once`() = runBlocking {
        val events = mutableListOf<String>()
        // Active job: teardown runs first, end is requested afterwards, synchronously.
        ReportingJobCleanup(
            isActiveJob = { true },
            stopPlaybackService = { events += "stopService" },
            requestSessionEnd = { events += "requestEnd" },
        ).onJobFinished()
        assertEquals(listOf("stopService", "requestEnd"), events)

        // Superseded (inactive) job: no service stop AND no end (the superseding play's
        // own start ends or chains the old session).
        events.clear()
        ReportingJobCleanup(
            isActiveJob = { false },
            stopPlaybackService = { events += "stopService" },
            requestSessionEnd = { events += "requestEnd" },
        ).onJobFinished()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `concurrent job cleanups stop the service at most once across threads`() = runBlocking {
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val jobs = (1..16).map { Any() }
        val current = jobs.last()
        kotlinx.coroutines.coroutineScope {
            jobs.forEach { job ->
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    ReportingJobCleanup(
                        isActiveJob = { current === job },
                        stopPlaybackService = { events += "stopService" },
                        requestSessionEnd = { events += "requestEnd" },
                    ).onJobFinished()
                }
            }
        }
        assertEquals(1, events.count { it == "stopService" })
        assertEquals(1, events.count { it == "requestEnd" })
    }
}
