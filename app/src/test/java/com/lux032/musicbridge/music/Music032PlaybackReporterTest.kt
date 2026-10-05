package com.lux032.musicbridge.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032PlaybackReporterTest {

    private class FakePreferences : Music032ReporterPreferences {
        var clientId: String? = null
        var lastSession: Music032PersistedSession? = null
        var resumeBlock: Long? = null
        override fun loadClientId(): String? = clientId
        override fun saveClientId(clientId: String) {
            this.clientId = clientId
        }
        override fun loadLastSession(): Music032PersistedSession? = lastSession
        override fun saveLastSession(session: Music032PersistedSession?) {
            lastSession = session
        }
        override fun loadResumeBlock(): Long? = resumeBlock
        override fun saveResumeBlock(trackId: Long?) {
            resumeBlock = trackId
        }
    }

    private class FakeTransport {
        val requests = mutableListOf<JSONObject>()
        private val responses = ArrayDeque<PlaybackEventHttpResult>()

        fun enqueue(status: Int, body: String? = null) {
            responses.addLast(PlaybackEventHttpResult(status, body))
        }

        fun ok(state: String = "playing") = enqueue(200, """{"applied":true,"sessionId":"s","state":"$state","counted":false}""")
        fun networkFailure() = enqueue(0, null)
        fun conflict(code: String) = enqueue(409, """{"error":{"code":"$code","message":"x"}}""")
        fun notFound(code: String) = enqueue(404, """{"error":{"code":"$code","message":"x"}}""")

        val transport = PlaybackEventTransport { payload ->
            requests += JSONObject(payload.toString())
            responses.removeFirstOrNull() ?: PlaybackEventHttpResult(200, """{"applied":true,"state":"playing"}""")
        }

        fun types() = requests.map { it.getString("type") }
    }

    private class Harness(
        eventDeadlineMillis: Long = 6_000L,
        val notices: MutableList<ReporterNotice> = mutableListOf(),
    ) {
        val transport = FakeTransport()
        val preferences = FakePreferences()
        var now = 1_000_000L
        val reporter = Music032PlaybackReporter(
            transport = transport.transport,
            preferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { now },
            sleeper = { millis -> now += millis },
            notifier = { notice -> notices += notice },
            eventDeadlineMillis = eventDeadlineMillis,
            startActor = false,
        )

        suspend fun drain() = reporter.drainActorForTest()

        fun advance(millis: Long) {
            now += millis
        }
    }

    private fun JSONObject.sessionId() = getString("sessionId")

    // ------------------------------------------------------------------ basics

    @Test
    fun `start sends start event with persisted clientId and seq 1`() = runBlocking {
        val h = Harness()
        h.reporter.requestStart(42, 5_000, 240_000, ReporterPlayState.Buffering)
        h.drain()

        val request = h.transport.requests.single()
        assertEquals("start", request.getString("type"))
        assertEquals(1L, request.getLong("seq"))
        assertEquals("android", request.getString("clientKind"))
        assertEquals(42L, request.getLong("trackId"))
        assertEquals("buffering", request.getString("state"))
        assertEquals(5_000L, request.getLong("positionMillis"))
        assertEquals(h.preferences.clientId, request.getString("clientId"))
        assertFalse(request.has("resumedFromSessionId"))
        assertTrue(h.preferences.clientId!!.matches(Regex("^[A-Za-z0-9-]+$")))
        assertNotNull(h.reporter.openSessionId())
    }

    @Test
    fun `clientId is stable across reporter instances`() = runBlocking {
        val h = Harness()
        h.reporter.requestStart(1, 0, 100_000, ReporterPlayState.Playing)
        h.drain()
        val second = Music032PlaybackReporter(
            transport = h.transport.transport,
            preferences = h.preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { h.now },
            startActor = false,
        )
        assertEquals(h.reporter.clientId, second.clientId)
    }

    @Test
    fun `start retries with the same sessionId and seq on network failure`() = runBlocking {
        val h = Harness()
        h.transport.networkFailure()
        h.transport.networkFailure()
        h.transport.ok()

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()

        assertEquals(3, h.transport.requests.size)
        assertEquals(1, h.transport.requests.map { it.sessionId() }.toSet().size)
        assertTrue(h.transport.requests.all { it.getLong("seq") == 1L })
        assertNotNull(h.reporter.openSessionId())
    }

    @Test
    fun `exhausted start retries keep the pending start and retry it verbatim`() = runBlocking {
        val h = Harness()
        repeat(3) { h.transport.networkFailure() }

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()

        assertEquals(3, h.transport.requests.size)
        assertNull(h.reporter.openSessionId())

        // No immediate hammering: the backoff gates the next attempt.
        h.drain()
        assertEquals(3, h.transport.requests.size)

        // Commit-lost protection: the next attempt reuses the SAME session id and seq.
        h.transport.ok()
        h.advance(120_000)
        h.drain()

        assertEquals(4, h.transport.requests.size)
        val retry = h.transport.requests.last()
        assertEquals("start", retry.getString("type"))
        assertEquals(1L, retry.getLong("seq"))
        assertEquals(h.transport.requests.first().sessionId(), retry.sessionId())
        assertNotNull(h.reporter.openSessionId())
    }

    @Test
    fun `events are serialized with monotonic seq`() = runBlocking {
        val h = Harness()
        repeat(4) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Paused, 1_000, 240_000)
        h.drain()
        h.reporter.requestSeek(30_000, 240_000)
        h.drain()
        h.advance(60_000)
        h.reporter.observe(ReporterPlayState.Paused, 31_000, 240_000)
        h.drain()

        assertEquals(listOf(1L, 2L, 3L, 4L), h.transport.requests.map { it.getLong("seq") })
        assertEquals(listOf("start", "pause", "seek", "heartbeat"), h.transport.types())
    }

    @Test
    fun `heartbeat cadence is 15s active and 60s paused`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()

        h.advance(14_999)
        h.reporter.observe(ReporterPlayState.Playing, 14_000, 240_000)
        h.drain()
        assertEquals(1, h.transport.requests.size)

        h.advance(1)
        h.reporter.observe(ReporterPlayState.Playing, 15_000, 240_000)
        h.drain()
        assertEquals("heartbeat", h.transport.requests.last().getString("type"))
        assertEquals("playing", h.transport.requests.last().getString("state"))

        val paused = Harness()
        repeat(2) { paused.transport.ok() }
        paused.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Paused)
        paused.drain()
        paused.advance(59_999)
        paused.reporter.observe(ReporterPlayState.Paused, 0, 240_000)
        paused.drain()
        assertEquals(1, paused.transport.requests.size)
        paused.advance(1)
        paused.reporter.observe(ReporterPlayState.Paused, 0, 240_000)
        paused.drain()
        assertEquals("heartbeat", paused.transport.requests.last().getString("type"))
        assertEquals("paused", paused.transport.requests.last().getString("state"))
    }

    @Test
    fun `typed events carry no state and end carries endReason`() = runBlocking {
        val h = Harness()
        repeat(4) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Paused, 1_000, 240_000)
        h.drain()
        h.reporter.observe(ReporterPlayState.Buffering, 1_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertFalse(h.transport.requests[1].has("state"))
        assertFalse(h.transport.requests[2].has("state"))
        val end = h.transport.requests[3]
        assertFalse(end.has("state"))
        assertEquals("stopped", end.getString("endReason"))
    }

    // ------------------------------------------------------------------ hasPlayed

    @Test
    fun `hasPlayed is only set by an observed playing state`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 130_000, 240_000, ReporterPlayState.Paused)
        h.reporter.observe(ReporterPlayState.Paused, 130_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "end"), h.transport.types())
        assertTrue(h.transport.requests.none { it.optString("state") == "playing" })
        assertEquals(false, h.preferences.lastSession?.hasPlayed)
    }

    // ------------------------------------------------------------------ 409 session_expired

    @Test
    fun `409 expired mid session resumes exactly once and resends the event`() = runBlocking {
        val h = Harness()
        h.transport.ok()                        // start
        h.transport.conflict("session_expired") // heartbeat -> 409
        h.transport.ok()                        // recovery start
        h.transport.ok()                        // resent heartbeat
        h.transport.ok()                        // later heartbeat on the new session

        h.reporter.requestStart(42, 60_000, 240_000, ReporterPlayState.Playing)
        h.drain()
        val firstSessionId = h.transport.requests[0].sessionId()
        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 61_000, 240_000)
        h.drain()

        assertEquals(listOf("start", "heartbeat", "start", "heartbeat"), h.transport.types())
        val recovery = h.transport.requests[2]
        assertNotEquals(firstSessionId, recovery.sessionId())
        assertEquals(firstSessionId, recovery.getString("resumedFromSessionId"))
        assertEquals("playing", recovery.getString("state")) // hasPlayed evidence
        assertEquals(61_000L, recovery.getLong("positionMillis"))
        assertEquals(1L, recovery.getLong("seq"))

        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 76_000, 240_000)
        h.drain()
        assertEquals(recovery.sessionId(), h.transport.requests.last().sessionId())
        assertTrue(h.transport.requests.drop(2).none { it.sessionId() == firstSessionId })
    }

    @Test
    fun `409 expired on end with hasPlayed replays start at final position plus end`() = runBlocking {
        val h = Harness()
        h.transport.ok()                        // start
        h.transport.conflict("session_expired") // end -> 409
        h.transport.ok()                        // recovery start
        h.transport.ok()                        // resent end

        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 200_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Completed)
        h.drain()

        assertEquals(listOf("start", "end", "start", "end"), h.transport.types())
        val recovery = h.transport.requests[2]
        assertEquals("playing", recovery.getString("state"))
        assertEquals(200_000L, recovery.getLong("positionMillis")) // FINAL observed position
        assertEquals(h.transport.requests[0].sessionId(), recovery.getString("resumedFromSessionId"))
        val end = h.transport.requests[3]
        assertEquals("completed", end.getString("endReason"))
        assertEquals(200_000L, end.getLong("positionMillis"))
        assertEquals(recovery.sessionId(), end.sessionId())
    }

    @Test
    fun `409 expired on end without hasPlayed never fakes playing`() = runBlocking {
        val h = Harness()
        h.transport.ok()                        // start (paused)
        h.transport.conflict("session_expired") // end -> 409
        h.transport.ok()                        // recovery start (honest paused)
        h.transport.ok()                        // resent end

        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Paused)
        h.reporter.observe(ReporterPlayState.Paused, 200_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "end", "start", "end"), h.transport.types())
        assertEquals("paused", h.transport.requests[2].getString("state"))
        assertTrue(h.transport.requests.none { it.optString("state") == "playing" })
    }

    @Test
    fun `resume_invalid during expired recovery blocks and persists the block`() = runBlocking {
        val h = Harness()
        h.transport.ok()                         // start
        h.transport.conflict("session_expired")  // heartbeat -> 409
        h.transport.conflict("resume_invalid")   // recovery start rejected

        h.reporter.requestStart(42, 60_000, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 61_000, 240_000)
        h.drain()

        assertEquals(listOf("start", "heartbeat", "start"), h.transport.types())
        assertEquals(42L, h.preferences.resumeBlock)
        assertTrue(h.notices.any { it is ReporterNotice.ResumeBlocked })
        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 76_000, 240_000)
        h.drain()
        assertEquals(3, h.transport.requests.size)

        // The persisted block survives a process restart and blocks poll fallbacks...
        val restarted = Harness(notices = h.notices)
        restarted.preferences.clientId = h.preferences.clientId
        restarted.preferences.resumeBlock = h.preferences.resumeBlock
        val revived = Music032PlaybackReporter(
            transport = restarted.transport.transport,
            preferences = restarted.preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { restarted.now },
            startActor = false,
        )
        revived.requestFallbackStart(42, 61_000, 240_000, ReporterPlayState.Playing)
        revived.drainActorForTest()
        assertEquals(0, restarted.transport.requests.size)

        // ...and only an explicit new play lifts it.
        restarted.transport.ok()
        revived.requestStart(42, 0, 240_000, ReporterPlayState.Buffering)
        revived.drainActorForTest()
        assertEquals(1, restarted.transport.requests.size)
        assertNull(restarted.preferences.resumeBlock)
    }

    // ------------------------------------------------------------------ 404 session_not_found

    @Test
    fun `404 mid session rebuilds the same sessionId once then gives up`() = runBlocking {
        val h = Harness()
        h.transport.ok()                            // start
        h.transport.notFound("session_not_found")   // heartbeat -> 404
        h.transport.ok()                            // rebuild start
        h.transport.ok()                            // resent heartbeat
        h.transport.notFound("session_not_found")   // next heartbeat -> 404 again: give up

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        val sessionId = h.transport.requests[0].sessionId()

        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 15_000, 240_000)
        h.drain()
        assertEquals(listOf("start", "heartbeat", "start", "heartbeat"), h.transport.types())
        assertTrue(h.transport.requests.all { it.sessionId() == sessionId })

        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 30_000, 240_000)
        h.drain()
        assertEquals(5, h.transport.requests.size)
        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 45_000, 240_000)
        h.drain()
        assertEquals(5, h.transport.requests.size)
        assertNull(h.reporter.openSessionId())
    }

    @Test
    fun `404 mid session rebuild uses the honest state, never fabricated playing`() = runBlocking {
        // Regression (P2-4): played 5s -> paused -> seek to 80% -> history cleared ->
        // the rebuild must not turn the paused session into a countable play.
        val h = Harness()
        h.transport.ok()                            // start (playing)
        h.transport.ok()                            // pause
        h.transport.ok()                            // seek to 80%
        h.transport.notFound("session_not_found")   // heartbeat -> 404
        h.transport.ok()                            // rebuild start
        h.transport.ok()                            // resent heartbeat

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(5_000)
        h.reporter.observe(ReporterPlayState.Paused, 5_000, 240_000)
        h.drain()
        h.reporter.requestSeek(192_000, 240_000)
        h.drain()
        h.advance(60_000)
        h.reporter.observe(ReporterPlayState.Paused, 192_000, 240_000)
        h.drain()

        assertEquals(listOf("start", "pause", "seek", "heartbeat", "start", "heartbeat"), h.transport.types())
        val rebuild = h.transport.requests[4]
        assertEquals("paused", rebuild.getString("state")) // honest, NOT playing
        assertEquals(h.transport.requests[0].sessionId(), rebuild.sessionId())
        assertTrue(h.transport.requests.drop(4).none { it.optString("state") == "playing" })
    }

    @Test
    fun `404 on end recovers a countable play with fresh start plus end`() = runBlocking {
        val h = Harness()
        h.transport.ok()                            // start
        h.transport.notFound("session_not_found")   // end -> 404
        h.transport.ok()                            // recovery start
        h.transport.ok()                            // resent end

        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 200_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "end", "start", "end"), h.transport.types())
        val recovery = h.transport.requests[2]
        assertNotEquals(h.transport.requests[0].sessionId(), recovery.sessionId())
        assertFalse(recovery.has("resumedFromSessionId"))
        assertEquals("playing", recovery.getString("state"))
        assertEquals(200_000L, recovery.getLong("positionMillis"))
        assertEquals("stopped", h.transport.requests[3].getString("endReason"))
    }

    @Test
    fun `409 non-end recovery uses the honest state even with hasPlayed`() = runBlocking {
        val h = Harness()
        h.transport.ok()                        // start (playing)
        h.transport.ok()                        // pause
        h.transport.conflict("session_expired") // heartbeat -> 409
        h.transport.ok()                        // recovery start
        h.transport.ok()                        // resent heartbeat

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(5_000)
        h.reporter.observe(ReporterPlayState.Paused, 5_000, 240_000)
        h.drain()
        h.advance(60_000)
        h.reporter.observe(ReporterPlayState.Paused, 5_000, 240_000)
        h.drain()

        assertEquals(listOf("start", "pause", "heartbeat", "start", "heartbeat"), h.transport.types())
        // hasPlayed is true (the track really played 5s), but the current state is
        // paused: the non-end recovery must not claim playing.
        assertEquals("paused", h.transport.requests[3].getString("state"))
        assertEquals("paused", h.transport.requests[4].getString("state"))
    }

    @Test
    fun `409 end recovery does not fake playing from historical hasPlayed plus a seeked position`() = runBlocking {
        // play 5s -> pause -> seek to 80% -> end: the 80% was never actually played, so
        // the recovery must stay honest even though hasPlayed is true.
        val h = Harness()
        h.transport.ok()                        // start
        h.transport.ok()                        // pause
        h.transport.ok()                        // seek
        h.transport.conflict("session_expired") // end -> 409
        h.transport.ok()                        // recovery start (honest)
        h.transport.ok()                        // resent end

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(5_000)
        h.reporter.observe(ReporterPlayState.Paused, 5_000, 240_000)
        h.drain()
        h.reporter.requestSeek(192_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "pause", "seek", "end", "start", "end"), h.transport.types())
        val recovery = h.transport.requests[4]
        assertEquals("paused", recovery.getString("state"))
        assertEquals(192_000L, recovery.getLong("positionMillis"))
        assertTrue(h.transport.requests.drop(3).none { it.optString("state") == "playing" })
    }

    @Test
    fun `409 end recovery claims playing when the played high-water passed the threshold`() = runBlocking {
        // Really played to 60%, then paused and ended there: the high-water mark is real
        // evidence, so the recovery may claim playing at the final position.
        val h = Harness()
        h.transport.ok()                        // start
        h.transport.ok()                        // heartbeat at 60%
        h.transport.ok()                        // pause
        h.transport.conflict("session_expired") // end -> 409
        h.transport.ok()                        // recovery start (playing evidence)
        h.transport.ok()                        // resent end

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(60_000)
        h.reporter.observe(ReporterPlayState.Playing, 144_000, 240_000)
        h.drain()
        h.reporter.observe(ReporterPlayState.Paused, 144_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "heartbeat", "pause", "end", "start", "end"), h.transport.types())
        val recovery = h.transport.requests[4]
        assertEquals("playing", recovery.getString("state"))
        assertEquals(144_000L, recovery.getLong("positionMillis"))
        assertEquals("stopped", h.transport.requests[5].getString("endReason"))
    }

    @Test
    fun `404 on end drops historical hasPlayed plus a seeked position without real evidence`() = runBlocking {
        val h = Harness()
        h.transport.ok()                            // start
        h.transport.ok()                            // pause
        h.transport.ok()                            // seek
        h.transport.notFound("session_not_found")   // end -> 404

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(5_000)
        h.reporter.observe(ReporterPlayState.Paused, 5_000, 240_000)
        h.drain()
        h.reporter.requestSeek(192_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        // hasPlayed is true and the final position is past the threshold, but the track
        // never actually played there: no fabricated start+end recovery.
        assertEquals(listOf("start", "pause", "seek", "end"), h.transport.types())
    }

    @Test
    fun `404 on end recovers when the played high-water passed the threshold`() = runBlocking {
        val h = Harness()
        h.transport.ok()                            // start
        h.transport.ok()                            // heartbeat at 60%
        h.transport.ok()                            // pause
        h.transport.notFound("session_not_found")   // end -> 404
        h.transport.ok()                            // recovery start
        h.transport.ok()                            // resent end

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        h.advance(60_000)
        h.reporter.observe(ReporterPlayState.Playing, 144_000, 240_000)
        h.drain()
        h.reporter.observe(ReporterPlayState.Paused, 144_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "heartbeat", "pause", "end", "start", "end"), h.transport.types())
        assertEquals("playing", h.transport.requests[4].getString("state"))
        assertEquals(144_000L, h.transport.requests[4].getLong("positionMillis"))
    }

    @Test
    fun `404 on end drops plays below the count threshold`() = runBlocking {
        val h = Harness()
        h.transport.ok()                            // start
        h.transport.notFound("session_not_found")   // end -> 404

        h.reporter.requestStart(42, 10_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 10_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Skipped)
        h.drain()

        assertEquals(listOf("start", "end"), h.transport.types())
    }

    @Test
    fun `404 on end drops plays without hasPlayed even past the threshold`() = runBlocking {
        val h = Harness()
        h.transport.ok()                            // start (paused at high position)
        h.transport.notFound("session_not_found")   // end -> 404

        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Paused)
        h.reporter.observe(ReporterPlayState.Paused, 200_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()

        assertEquals(listOf("start", "end"), h.transport.types())
        assertTrue(h.transport.requests.none { it.optString("state") == "playing" })
    }

    // ------------------------------------------------------------------ resume rules (P1-2)

    @Test
    fun `explicit start never resumes a persisted snapshot, takeover does`() = runBlocking {
        val h = Harness()
        h.preferences.lastSession = Music032PersistedSession("previous-session", 42, hasPlayed = true, savedAtMillis = h.now - 60_000)
        h.transport.ok()

        // An explicit new play is always a fresh chain, even with a matching snapshot.
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Buffering)
        h.drain()
        assertFalse(h.transport.requests[0].has("resumedFromSessionId"))

        // A genuine renderer switch links onto the persisted snapshot: the local side's
        // session was just ended (stopped keeps the snapshot), then the Sonos side takes
        // the same track over.
        h.transport.ok() // end (stopped)
        h.transport.ok() // takeover start
        h.reporter.requestEnd(ReporterEndReason.Stopped)
        h.drain()
        val endedSessionId = h.transport.requests[0].sessionId()
        h.reporter.requestTakeover(42, 30_000, 240_000, ReporterPlayState.Buffering)
        h.drain()
        val takeoverStart = h.transport.requests.last()
        assertEquals("start", takeoverStart.getString("type"))
        assertEquals(endedSessionId, takeoverStart.getString("resumedFromSessionId"))
    }

    @Test
    fun `resume_invalid on takeover blocks without a silent fresh restart`() = runBlocking {
        val h = Harness()
        h.preferences.lastSession = Music032PersistedSession("old-session", 42, hasPlayed = true, savedAtMillis = h.now)
        h.transport.conflict("resume_invalid")

        h.reporter.requestTakeover(42, 180_000, 240_000, ReporterPlayState.Playing)
        h.drain()

        assertEquals(1, h.transport.requests.size)
        assertEquals("old-session", h.transport.requests[0].getString("resumedFromSessionId"))
        assertEquals(42L, h.preferences.resumeBlock)
        assertNull(h.preferences.lastSession)
        assertTrue(h.notices.any { it is ReporterNotice.ResumeBlocked })

        // Poll-driven fallbacks must not keep hammering the blocked resume.
        h.reporter.requestFallbackStart(42, 180_000, 240_000, ReporterPlayState.Playing)
        h.drain()
        assertEquals(1, h.transport.requests.size)

        // The next explicit play unblocks and starts fresh.
        h.transport.ok()
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Buffering)
        h.drain()
        assertFalse(h.transport.requests.last().has("resumedFromSessionId"))
        assertNull(h.preferences.resumeBlock)
    }

    // ------------------------------------------------------------------ takeover

    @Test
    fun `takeover of the same track chains via resumedFromSessionId without an end`() = runBlocking {
        val h = Harness()
        h.transport.ok() // start
        h.transport.ok() // takeover start

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        val firstSessionId = h.transport.requests[0].sessionId()
        h.reporter.requestTakeover(42, 90_000, 240_000, ReporterPlayState.Buffering)
        h.drain()

        assertEquals(listOf("start", "start"), h.transport.types())
        val chained = h.transport.requests[1]
        assertEquals(firstSessionId, chained.getString("resumedFromSessionId"))
        assertNotEquals(firstSessionId, chained.sessionId())
        assertEquals(90_000L, chained.getLong("positionMillis"))
    }

    @Test
    fun `takeover of a different track ends the old session replaced then starts fresh`() = runBlocking {
        val h = Harness()
        h.transport.ok() // start 41
        h.transport.ok() // end replaced
        h.transport.ok() // start 42

        h.reporter.requestStart(41, 30_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 30_000, 240_000)
        h.drain()
        h.reporter.requestTakeover(42, 0, 200_000, ReporterPlayState.Buffering)
        h.drain()

        assertEquals(listOf("start", "end", "start"), h.transport.types())
        assertEquals("replaced", h.transport.requests[1].getString("endReason"))
        assertEquals(30_000L, h.transport.requests[1].getLong("positionMillis"))
        assertEquals(42L, h.transport.requests[2].getLong("trackId"))
    }

    // ------------------------------------------------------------------ play lifecycle

    @Test
    fun `repeat loop closes completed at the observed position and starts a new session`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        val firstSessionId = h.transport.requests[0].sessionId()
        h.reporter.observe(ReporterPlayState.Playing, 239_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Completed)
        h.drain()
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Buffering)
        h.drain()

        val end = h.transport.requests[1]
        assertEquals("completed", end.getString("endReason"))
        // completed carries the last observed position (≈duration), never 0 or fabricated.
        assertEquals(239_000L, end.getLong("positionMillis"))
        assertNotEquals(firstSessionId, h.transport.requests[2].sessionId())
    }

    @Test
    fun `end uses the old session's last observed position`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 61_000, 240_000)
        h.drain()
        h.reporter.requestEnd(ReporterEndReason.Skipped)
        h.drain()

        val end = h.transport.requests[1]
        assertEquals("skipped", end.getString("endReason"))
        assertEquals(61_000L, end.getLong("positionMillis"))
    }

    @Test
    fun `end with a mismatched expectedSessionId is ignored`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        val sessionId = h.transport.requests[0].sessionId()

        h.reporter.requestEnd(ReporterEndReason.Stopped, expectedSessionId = "someone-else")
        h.drain()
        assertEquals(1, h.transport.requests.size)
        assertEquals(sessionId, h.reporter.openSessionId())

        h.reporter.requestEnd(ReporterEndReason.Stopped, expectedSessionId = sessionId)
        h.drain()
        assertEquals(listOf("start", "end"), h.transport.types())
        assertNull(h.reporter.openSessionId())
    }

    @Test
    fun `shutdown ends the open session once and forget wipes the snapshot`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 12_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 12_000, 240_000)
        h.drain()

        h.reporter.requestShutdown(ReporterEndReason.ClientClosed)
        h.drain()
        assertEquals(listOf("start", "end"), h.transport.types())
        assertEquals("client_closed", h.transport.requests[1].getString("endReason"))
        assertEquals(12_000L, h.transport.requests[1].getLong("positionMillis"))
        assertEquals(h.transport.requests[0].sessionId(), h.preferences.lastSession?.sessionId)

        // A closed actor ignores further commands.
        h.reporter.requestShutdown(ReporterEndReason.ClientClosed)
        h.drain()
        assertEquals(2, h.transport.requests.size)

        val forgetful = Harness()
        repeat(2) { forgetful.transport.ok() }
        forgetful.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        forgetful.drain()
        assertNotNull(forgetful.preferences.lastSession)
        forgetful.reporter.requestShutdown(ReporterEndReason.Stopped, forget = true)
        forgetful.drain()
        assertNull(forgetful.preferences.lastSession)
    }

    @Test
    fun `shutdown with a pending start attempts it once then ends (commit-lost)`() = runBlocking {
        val h = Harness()
        repeat(3) { h.transport.networkFailure() }
        h.transport.ok() // deferred start attempt during shutdown
        h.transport.ok() // end

        h.reporter.requestStart(42, 30_000, 240_000, ReporterPlayState.Playing)
        h.drain()
        assertNull(h.reporter.openSessionId())

        h.reporter.requestShutdown(ReporterEndReason.ClientClosed)
        h.drain()

        assertEquals(listOf("start", "start", "start", "start", "end"), h.transport.types())
        assertEquals(1, h.transport.requests.map { it.sessionId() }.toSet().size)
        val end = h.transport.requests.last()
        assertEquals("client_closed", end.getString("endReason"))
        assertEquals(30_000L, end.getLong("positionMillis"))
    }

    // ------------------------------------------------------------------ resilience (P2-2)

    @Test
    fun `fallback start is a no-op when the same track session just opened (P2-3 interleave)`() = runBlocking {
        val h = Harness()
        h.transport.ok()
        // Interleave: the transition handler's start and the poll's fallback race; the
        // actor must not let the fallback replace the freshly opened session.
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.reporter.requestFallbackStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()

        assertEquals(listOf("start"), h.transport.types())
        assertNotNull(h.reporter.openSessionId())

        // A fallback for a DIFFERENT track still ends the old one replaced and starts.
        h.transport.ok()
        h.transport.ok()
        h.reporter.requestFallbackStart(43, 0, 200_000, ReporterPlayState.Playing)
        h.drain()
        assertEquals(listOf("start", "end", "start"), h.transport.types())
        assertEquals("replaced", h.transport.requests[1].getString("endReason"))
    }

    @Test
    fun `offline heartbeats do not pile up and respect backoff`() = runBlocking {
        val h = Harness()
        repeat(2) { h.transport.ok() }
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()

        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 15_000, 240_000)
        h.transport.networkFailure()
        h.drain()
        assertEquals(2, h.transport.requests.size)

        // A failed heartbeat updates the schedule: no per-tick retries without time passing.
        repeat(5) { h.drain() }
        assertEquals(2, h.transport.requests.size)

        // The next attempt happens on cadence, not on every tick.
        h.transport.networkFailure()
        h.advance(15_000)
        h.reporter.observe(ReporterPlayState.Playing, 30_000, 240_000)
        h.drain()
        assertEquals(3, h.transport.requests.size)
        repeat(5) { h.drain() }
        assertEquals(3, h.transport.requests.size)
    }

    @Test
    fun `401 on start halts reporting with a visible notice until the next explicit play`() = runBlocking {
        val h = Harness()
        h.transport.enqueue(401, """{"error":{"code":"unauthorized","message":"x"}}""")

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()

        assertEquals(1, h.transport.requests.size)
        assertNull(h.reporter.openSessionId())
        assertTrue(h.notices.any { it is ReporterNotice.StartRejected && it.status == 401 })

        // Poll fallbacks and observations stay silent while halted.
        h.reporter.requestFallbackStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 10_000, 240_000)
        h.advance(60_000)
        h.drain()
        assertEquals(1, h.transport.requests.size)

        // An explicit play retries (e.g. the user fixed the token).
        h.transport.ok()
        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        assertEquals(2, h.transport.requests.size)
        assertNotNull(h.reporter.openSessionId())
    }

    @Test
    fun `slow transport is deadline bounded and never blocks the caller`() = runBlocking {
        val hanging = PlaybackEventTransport {
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            @Suppress("UNREACHABLE_CODE")
            PlaybackEventHttpResult(0, null)
        }
        val preferences = FakePreferences()
        val notices = mutableListOf<ReporterNotice>()
        var now = 1_000_000L
        val reporter = Music032PlaybackReporter(
            transport = hanging,
            preferences = preferences,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { now },
            sleeper = { millis -> now += millis },
            eventDeadlineMillis = 50L,
            notifier = { notices += it },
            startActor = false,
        )

        // Public API is fire-and-forget: returns immediately even with a hanging transport.
        reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
        reporter.observe(ReporterPlayState.Playing, 1_000, 240_000)
        reporter.requestEnd(ReporterEndReason.Stopped)

        val startedAt = System.currentTimeMillis()
        reporter.drainActorForTest()
        val elapsed = System.currentTimeMillis() - startedAt

        // 3 inline start attempts, each bounded by the 50ms deadline.
        assertTrue("drain took ${elapsed}ms", elapsed < 3_000L)
        assertNull(reporter.openSessionId())
    }

    // ------------------------------------------------------------------ stale-session resume intent (P2-N2)

    @Test
    fun `stale auto-end keeps resume intent and the same track continues its chain (P2-N2)`() = runBlocking {
        val h = Harness()
        repeat(3) { h.transport.ok() } // start, auto-end, resume start
        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 200_000, 240_000)
        h.drain()
        val firstSessionId = h.transport.requests[0].sessionId()

        // Observations stop (phone and speaker lost each other): the freshness guard
        // ends the session after >2 active intervals.
        h.advance(31_000)
        h.drain()
        assertEquals(listOf("start", "end"), h.transport.types())
        assertEquals("stopped", h.transport.requests[1].getString("endReason"))
        assertNull(h.reporter.openSessionId())

        // The same track comes back: the fallback start continues the ORIGINAL chain
        // (resumedFrom, real current state/position) instead of counting again.
        h.reporter.observe(ReporterPlayState.Playing, 200_000, 240_000)
        h.reporter.requestFallbackStart(42, 200_000, 240_000, ReporterPlayState.Playing)
        h.drain()
        val resume = h.transport.requests[2]
        assertEquals("start", resume.getString("type"))
        assertEquals(firstSessionId, resume.getString("resumedFromSessionId"))
        assertEquals("playing", resume.getString("state"))
        assertEquals(200_000L, resume.getLong("positionMillis"))
        assertNotEquals(firstSessionId, resume.sessionId())
    }

    @Test
    fun `different track after stale auto-end starts a fresh chain`() = runBlocking {
        val h = Harness()
        repeat(3) { h.transport.ok() }
        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 200_000, 240_000)
        h.drain()
        h.advance(31_000)
        h.drain()
        assertEquals(listOf("start", "end"), h.transport.types())

        h.reporter.requestFallbackStart(43, 0, 240_000, ReporterPlayState.Playing)
        h.drain()
        val fresh = h.transport.requests[2]
        assertEquals("start", fresh.getString("type"))
        assertEquals(43L, fresh.getLong("trackId"))
        assertFalse(fresh.has("resumedFromSessionId"))
    }

    @Test
    fun `explicit start after stale auto-end never resumes (new play stays a fresh chain)`() = runBlocking {
        val h = Harness()
        repeat(3) { h.transport.ok() }
        h.reporter.requestStart(42, 200_000, 240_000, ReporterPlayState.Playing)
        h.reporter.observe(ReporterPlayState.Playing, 200_000, 240_000)
        h.drain()
        h.advance(31_000)
        h.drain()

        h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Buffering)
        h.drain()
        val fresh = h.transport.requests[2]
        assertEquals("start", fresh.getString("type"))
        assertFalse(fresh.has("resumedFromSessionId"))
    }

    // ------------------------------------------------------------------ pure helpers

    @Test
    fun `pastCountThreshold matches the server rule`() {
        assertTrue(pastCountThreshold(119_000, 240_000))
        assertFalse(pastCountThreshold(118_999, 240_000))
        assertFalse(pastCountThreshold(200_000, 0))
        assertTrue(pastCountThreshold(4_000, 8_000))
        assertFalse(pastCountThreshold(3_199, 8_000))
    }
}
