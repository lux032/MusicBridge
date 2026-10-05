package com.lux032.musicbridge.music

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Production-loop tests: every test here runs the REAL actor (startActor = true) on a
 * real dispatcher with real (tiny) heartbeat intervals and a controlled transport — no
 * drainActorForTest. Covers the review's P1-A/P1-B/P2-C actor-lifecycle defects.
 */
class Music032PlaybackReporterActorTest {

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

    /** Scripted, thread-safe transport; requests and the response each got are recorded. */
    private class ScriptedTransport {
        data class Record(val payload: JSONObject, val status: Int)

        val records = ConcurrentLinkedQueue<Record>()
        val handler = AtomicReference<suspend (JSONObject) -> PlaybackEventHttpResult> { ok() }

        val transport = PlaybackEventTransport { payload ->
            val response = handler.get()(payload)
            records += Record(JSONObject(payload.toString()), response.status)
            response
        }

        fun requests() = records.map { it.payload }

        companion object {
            fun ok() = PlaybackEventHttpResult(200, """{"applied":true,"state":"playing"}""")
            fun failure() = PlaybackEventHttpResult(0, null)
            fun conflict(code: String) = PlaybackEventHttpResult(409, """{"error":{"code":"$code","message":"x"}}""")
        }
    }

    private class ActorHarness {
        val transport = ScriptedTransport()
        val preferences = FakePreferences()
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "actor-test").apply { isDaemon = true }
        }
        val scope = CoroutineScope(executor.asCoroutineDispatcher())
        val reporter = Music032PlaybackReporter(
            transport = transport.transport,
            preferences = preferences,
            scope = scope,
            clock = System::currentTimeMillis,
            sleeper = { delay(10) },
            logger = {},
            eventDeadlineMillis = 2_000L,
            activeHeartbeatMillis = 60L,
            pausedHeartbeatMillis = 120L,
            backoffBaseMillis = 50L,
            startActor = true,
        )

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    private suspend fun awaitTrue(timeoutMillis: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("condition not met within ${timeoutMillis}ms")
            }
            delay(10)
        }
    }

    @Test
    fun `P1-A - a slow end survives heartbeat deadlines and is always sent`() = runBlocking {
        val h = ActorHarness()
        try {
            val endGate = CountDownLatch(1)
            h.transport.handler.set { payload ->
                if (payload.getString("type") == "end") {
                    // Slower than several 60ms heartbeat deadlines: with the command
                    // processed outside the timeout this still completes.
                    endGate.await(500, TimeUnit.MILLISECONDS)
                }
                ScriptedTransport.ok()
            }
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            h.reporter.requestEnd(ReporterEndReason.Stopped)
            awaitTrue { h.transport.requests().any { it.getString("type") == "end" } }
            awaitTrue { h.reporter.openSessionId() == null }

            assertEquals("stopped", h.transport.requests().last { it.getString("type") == "end" }.getString("endReason"))
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P1-B - shutdown with failed end recovery leaves no pending start and no spin`() = runBlocking {
        val h = ActorHarness()
        try {
            h.transport.handler.set { payload ->
                when (payload.getString("type")) {
                    "end" -> ScriptedTransport.conflict("session_expired")
                    else -> ScriptedTransport.ok()
                }
            }
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            // The recovery start always fails on the network.
            h.transport.handler.set { payload ->
                when (payload.getString("type")) {
                    "end" -> ScriptedTransport.conflict("session_expired")
                    "start" -> ScriptedTransport.failure()
                    else -> ScriptedTransport.ok()
                }
            }
            h.reporter.requestShutdown(ReporterEndReason.ClientClosed)

            // Let the bounded shutdown attempt and any (wrong) retries play out.
            delay(600)
            val settled = h.transport.records.size
            delay(400)
            assertEquals("closed actor keeps issuing requests (spin/retry)", settled, h.transport.records.size)
            assertNull(h.reporter.openSessionId())
            assertNull(h.reporter.pendingSessionId())

            // Even after the network recovers, no ghost start may be replayed.
            h.transport.handler.set { ScriptedTransport.ok() }
            delay(400)
            assertTrue(h.transport.requests().none { it.getString("type") == "start" && it.getLong("seq") > 1L })
            assertEquals(settled, h.transport.records.size)
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P1-B - halted reporter recovers via takeover without stalling heartbeats`() = runBlocking {
        val h = ActorHarness()
        try {
            h.transport.handler.set { payload ->
                if (payload.getString("type") == "heartbeat") {
                    PlaybackEventHttpResult(401, """{"error":{"code":"unauthorized","message":"x"}}""")
                } else {
                    ScriptedTransport.ok()
                }
            }
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }
            // The 401 halts and closes the session locally.
            awaitTrue { h.reporter.openSessionId() == null }

            h.transport.handler.set { ScriptedTransport.ok() }
            h.reporter.requestTakeover(42, 30_000, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            // Heartbeats must keep flowing (actor neither stalled nor hot-spinning).
            val before = h.transport.records.count { it.payload.getString("type") == "heartbeat" }
            awaitTrue { h.transport.records.count { it.payload.getString("type") == "heartbeat" } > before }
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P2-C - sessions without fresh observations auto-end instead of renewing forever`() = runBlocking {
        val h = ActorHarness()
        try {
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            // No observations at all: after ~2 active intervals the actor ends the session.
            awaitTrue {
                h.transport.requests().any {
                    it.getString("type") == "end" && it.getString("endReason") == "stopped"
                }
            }
            assertNull(h.reporter.openSessionId())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P2-C - continuous observations keep the session alive (freshness rule is safe)`() = runBlocking {
        val h = ActorHarness()
        try {
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            // Simulate the healthy 1s poll (here at 30ms for speed) across several
            // heartbeat intervals: the session must survive.
            repeat(10) { iteration ->
                h.reporter.observe(ReporterPlayState.Playing, iteration * 1_000L, 240_000)
                delay(30)
            }
            assertNotNull(h.reporter.openSessionId())
            assertTrue(h.transport.requests().none { it.getString("type") == "end" })
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P2-C - an end with no open session retires the pending start (no later ghost)`() = runBlocking {
        val h = ActorHarness()
        try {
            // The start never lands: pending start retained.
            h.transport.handler.set { ScriptedTransport.failure() }
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.pendingSessionId() != null }

            // A cleanup end with the pending id retires it.
            val pendingId = h.reporter.pendingSessionId()
            h.reporter.requestEnd(ReporterEndReason.Stopped, expectedSessionId = pendingId)
            awaitTrue { h.reporter.pendingSessionId() == null }

            // Network recovers: nothing may be replayed.
            h.transport.handler.set { ScriptedTransport.ok() }
            delay(400)
            assertTrue(h.transport.requests().none { it.getString("type") == "start" && it.getLong("seq") > 1L })
            assertNull(h.reporter.openSessionId())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `natural end then replay opens a fresh session (P1-C production loop)`() = runBlocking {
        val h = ActorHarness()
        try {
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }
            val firstSessionId = h.reporter.openSessionId()

            // Queue ended, then the user replays: observations keep arriving and the
            // fallback start opens a genuinely new session.
            h.reporter.requestEnd(ReporterEndReason.Completed)
            awaitTrue { h.reporter.openSessionId() == null }

            h.reporter.requestFallbackStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }
            assertTrue(firstSessionId != h.reporter.openSessionId())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P3-a - a closed actor exits and refuses further commands`() = runBlocking {
        val h = ActorHarness()
        try {
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            h.reporter.requestShutdown(ReporterEndReason.ClientClosed)
            awaitTrue { h.reporter.openSessionId() == null }
            // Finite lifecycle: the actor loop actually ends.
            awaitTrue { !h.reporter.actorActiveForTest() }

            val settled = h.transport.records.size
            h.reporter.requestStart(43, 0, 240_000, ReporterPlayState.Playing)
            h.reporter.observe(ReporterPlayState.Playing, 1_000, 240_000)
            h.reporter.requestEnd(ReporterEndReason.Stopped)
            delay(250)
            assertEquals("a closed actor accepted new work", settled, h.transport.records.size)
            assertNull(h.reporter.openSessionId())
        } finally {
            h.shutdown()
        }
    }

    @Test
    fun `P3-b - a repeatedly failing actor latches dead, logs visibly and refuses new work`() = runBlocking {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "actor-dead-test").apply { isDaemon = true }
        }
        val reporter = Music032PlaybackReporter(
            transport = PlaybackEventTransport { throw IllegalStateException("boom") },
            preferences = FakePreferences(),
            scope = CoroutineScope(executor.asCoroutineDispatcher()),
            clock = System::currentTimeMillis,
            sleeper = { },
            logger = { message -> logs += message },
            activeHeartbeatMillis = 60L,
            pausedHeartbeatMillis = 120L,
            backoffBaseMillis = 50L,
            startActor = true,
        )
        try {
            reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { logs.any { it.contains("stopped after repeated errors") } }
            awaitTrue { !reporter.actorActiveForTest() }

            // Dead latch: polling loops must not be able to queue into a dead actor.
            reporter.requestFallbackStart(42, 0, 240_000, ReporterPlayState.Playing)
            reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            reporter.requestEnd(ReporterEndReason.Stopped)
            delay(200)
            assertNull(reporter.openSessionId())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `P3-c - commands racing heartbeat deadlines are never lost (stress)`() = runBlocking {
        val h = ActorHarness()
        try {
            h.reporter.requestStart(42, 0, 240_000, ReporterPlayState.Playing)
            awaitTrue { h.reporter.openSessionId() != null }

            // 120 seeks at ~2ms spacing: many land inside the same scheduling window as
            // the 60ms select deadlines. None may be dropped.
            repeat(120) { index ->
                h.reporter.requestSeek(index * 1_000L, 240_000)
                delay(2)
            }
            h.reporter.requestEnd(ReporterEndReason.Stopped)
            awaitTrue { h.transport.requests().any { it.getString("type") == "end" } }
            awaitTrue { h.reporter.openSessionId() == null }

            assertEquals(120, h.transport.requests().count { it.getString("type") == "seek" })
            assertEquals(1, h.transport.requests().count { it.getString("type") == "end" })
        } finally {
            h.shutdown()
        }
    }
}
