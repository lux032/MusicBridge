package com.lux032.musicbridge.music

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

/**
 * 032 playback session reporter (plan C, apiRevision 3).
 *
 * Every play is a durable server-side session driven by typed events on
 * `POST /api/v1/playback/events`. The reporter runs a single-threaded actor on an
 * injected, process-level scope: ALL public methods are non-blocking fire-and-forget so
 * the 1s playback/UI observation loops never suspend on the network. Observations are
 * conflated (only the latest matters), while start/end/seek commands keep their order.
 * Network sends are deadline-bounded and the transport is genuinely cancellable; periodic
 * work (heartbeats, pending-start retries) backs off exponentially while the network is
 * down instead of hammering POSTs. The actor can be driven manually from tests
 * ([drainActorForTest]) with an injected clock/sleeper, so the protocol state machine is
 * fully deterministic under test.
 *
 * Contract anchors (see server README.md and /api/v1/capabilities playback section):
 * - heartbeats: 15s while playing/buffering, 60s while paused; server leases are 90s/10min.
 * - `start` is idempotent: retries reuse the SAME sessionId and seq — including the
 *   pending start that survives exhausted inline retries (a commit-lost start must never
 *   be replaced by a fresh sessionId).
 * - Explicit new plays never resume a persisted chain; resume links are only created by a
 *   genuine renderer-switch takeover (or by 409/404 recovery of the same session).
 * - 409 session_expired: exactly one serialized resume (new session with
 *   resumedFromSessionId) then the interrupted event is re-sent; a late end repeats
 *   start(resume at the FINAL position) + end(original endReason). Non-end events always
 *   recover with the honest current state. An end recovery may only claim `playing` with
 *   REAL evidence: the session is playing right now, or its played high-water mark (the
 *   highest position observed while actually playing) already passed the count threshold.
 *   Historical hasPlayed + a high position is never enough (play 5s -> pause -> seek 80%).
 * - 404 session_not_found mid-session: rebuild the same sessionId once with the HONEST
 *   current state; on end, only a play that provably passed the count threshold (same
 *   evidence rule) is recovered as start(playing, final position) + end.
 * - 409 resume_invalid: never silently start fresh at a high position. The block is
 *   persisted per source+track and only an explicit new play lifts it.
 * - hasPlayed: only a session that observed a real `playing` state may recover with
 *   `state: "playing"` at all, and even then only under the evidence rule above; a session
 *   that only ever paused/buffered must never fake playing.
 * - Offline events are not replayed and heartbeats are not backlogged (D9).
 */
internal const val MUSIC032_ACTIVE_HEARTBEAT_MILLIS = 15_000L
internal const val MUSIC032_PAUSED_HEARTBEAT_MILLIS = 60_000L

private val START_RETRY_DELAYS_MILLIS = longArrayOf(1_000L, 2_000L)
private const val COUNT_SLACK_MILLIS = 1_000L
private const val MAX_BACKOFF_MILLIS = 120_000L
private const val REGULAR_EVENT_DEADLINE_MILLIS = 6_000L
private const val SHUTDOWN_EVENT_DEADLINE_MILLIS = 1_500L
private val PLAYBACK_ID_PATTERN = Regex("^[A-Za-z0-9-]+$")

internal enum class ReporterPlayState(val wire: String) {
    Playing("playing"),
    Buffering("buffering"),
    Paused("paused"),
}

internal enum class ReporterEndReason(val wire: String) {
    Completed("completed"),
    Skipped("skipped"),
    Stopped("stopped"),
    Replaced("replaced"),
    Error("error"),
    ClientClosed("client_closed"),
}

/** User-visible reporting problems, delivered on the reporter's notice callback. */
internal sealed interface ReporterNotice {
    /** 409 resume_invalid: reporting for this play is blocked until the next explicit play. */
    data class ResumeBlocked(val trackId: Long) : ReporterNotice

    /** start rejected with a non-retryable status (400/401/403/404): reporting halted. */
    data class StartRejected(val trackId: Long, val status: Int) : ReporterNotice
}

/** HTTP result of one event POST. [status] 0 means the request never got a response. */
internal data class PlaybackEventHttpResult(val status: Int, val body: String?)

internal fun interface PlaybackEventTransport {
    suspend fun post(payload: JSONObject): PlaybackEventHttpResult
}

/** Snapshot of the most recent resumable session, persisted across process restarts. */
internal data class Music032PersistedSession(
    val sessionId: String,
    val trackId: Long,
    val hasPlayed: Boolean,
    val playedHighWaterMillis: Long = 0L,
    val savedAtMillis: Long,
)

internal interface Music032ReporterPreferences {
    fun loadClientId(): String?
    fun saveClientId(clientId: String)
    fun loadLastSession(): Music032PersistedSession?
    fun saveLastSession(session: Music032PersistedSession?)
    /** Persisted resume_invalid block: only an explicit new play may lift it. */
    fun loadResumeBlock(): Long?
    fun saveResumeBlock(trackId: Long?)
}

/** Server count rule (capabilities scrobble): position >= duration/2 - min(1s, duration/10). */
internal fun pastCountThreshold(positionMillis: Long, durationMillis: Long): Boolean {
    if (durationMillis <= 0L) return false
    val slack = minOf(COUNT_SLACK_MILLIS, durationMillis / 10)
    return positionMillis >= durationMillis / 2 - slack
}

/**
 * Non-blocking facade of one 032 source's playback session reporter. The coordinator
 * layer only ever calls these methods (real implementation: [Music032PlaybackReporter];
 * tests use fakes), which keeps the actual coordinator routing logic unit-testable.
 */
internal interface Music032ReporterApi {
    val clientId: String

    /** Currently open session's id, if any (volatile snapshot, never suspends). */
    fun openSessionId(): String?

    /** Currently open session's track, if any (volatile snapshot, never suspends). */
    fun openSessionTrackId(): Long?

    /** Explicit new play: fresh chain, never a persisted resume. Lifts any resume block. */
    fun requestStart(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        context: Music032PlaybackContext? = null,
    )

    /** Genuine renderer switch (local <-> Sonos): chains onto the open/persisted session. */
    fun requestTakeover(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        context: Music032PlaybackContext? = null,
    )

    /** Poll-driven session repair: no-op when the same track is already open or blocked. */
    fun requestFallbackStart(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        context: Music032PlaybackContext? = null,
    )

    /** Conflated playback observation (1s ticks and engine callbacks). Never suspends. */
    fun observe(state: ReporterPlayState, positionMillis: Long, durationMillis: Long)

    fun requestSeek(positionMillis: Long, durationMillis: Long)

    fun requestEnd(reason: ReporterEndReason, finalPositionMillis: Long? = null, expectedSessionId: String? = null)

    /** Bounded best-effort final end; [forget] also wipes the persisted resume snapshot. */
    fun requestShutdown(reason: ReporterEndReason, forget: Boolean = false)

    /** Id of a deferred (not yet accepted) start, if any. */
    fun pendingSessionId(): String? = null

    /** Session this reporter may end on cleanup: the open session, else the pending start. */
    fun ownedSessionId(): String? = openSessionId() ?: pendingSessionId()
}

internal class Music032PlaybackReporter(
    private val transport: PlaybackEventTransport,
    private val preferences: Music032ReporterPreferences,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleeper: suspend (Long) -> Unit = { delay(it) },
    private val logger: (String) -> Unit = {},
    private val notifier: (ReporterNotice) -> Unit = {},
    private val eventDeadlineMillis: Long = REGULAR_EVENT_DEADLINE_MILLIS,
    private val activeHeartbeatMillis: Long = MUSIC032_ACTIVE_HEARTBEAT_MILLIS,
    private val pausedHeartbeatMillis: Long = MUSIC032_PAUSED_HEARTBEAT_MILLIS,
    private val backoffBaseMillis: Long = 2_000L,
    /** Server advertises `features.playbackContext`; unknown fields would be rejected. */
    private val contextSupported: () -> Boolean = { false },
    startActor: Boolean = true,
) : Music032ReporterApi {

    // ------------------------------------------------------------------ state

    private class Session(
        val sessionId: String,
        val trackId: Long,
        var seq: Long,
        var state: ReporterPlayState,
        var hasPlayed: Boolean,
        var lastPositionMillis: Long,
        var durationMillis: Long,
        var lastEventAtMillis: Long,
        /**
         * Highest position OBSERVED WHILE ACTUALLY PLAYING. Historical hasPlayed alone
         * does not prove a high position was really played (play 5s -> pause -> seek to
         * 80%): only this high-water mark (or being playing right now) may justify a
         * `playing` claim in an end recovery.
         */
        var playedHighWaterMillis: Long,
        var ended: Boolean = false,
        var rebuildAttempted: Boolean = false,
        val context: Music032PlaybackContext? = null,
    )

    /** A start whose inline retries were exhausted; retried verbatim (same id+seq). */
    private class PendingStart(
        val trackId: Long,
        val sessionId: String,
        val positionMillis: Long,
        val durationMillis: Long,
        val initialState: ReporterPlayState,
        val resumedFromSessionId: String?,
        val inheritedHasPlayed: Boolean,
        val takeover: Boolean,
        val sessionIdOverride: String?,
        val observationStampAtEnqueue: Long,
        val initialHighWaterMillis: Long = 0L,
        /** Sent only on a fresh chain; a resume inherits it server-side. */
        val context: Music032PlaybackContext? = null,
    )

    private sealed interface Command {
        data class Start(val trackId: Long, val position: Long, val duration: Long, val state: ReporterPlayState, val obsStamp: Long, val context: Music032PlaybackContext? = null) : Command
        data class Takeover(val trackId: Long, val position: Long, val duration: Long, val state: ReporterPlayState, val obsStamp: Long, val context: Music032PlaybackContext? = null) : Command
        data class FallbackStart(val trackId: Long, val position: Long, val duration: Long, val state: ReporterPlayState, val obsStamp: Long, val context: Music032PlaybackContext? = null) : Command
        data class Seek(val position: Long, val duration: Long) : Command
        data class End(val reason: ReporterEndReason, val finalPosition: Long?, val expectedSessionId: String?) : Command
        data class Shutdown(val reason: ReporterEndReason, val forget: Boolean) : Command
    }

    private data class Observation(
        val state: ReporterPlayState,
        val positionMillis: Long,
        val durationMillis: Long,
    )

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val observationPoke = Channel<Unit>(Channel.CONFLATED)

    // Actor-confined state (only touched by the actor thread / test drain).
    private var session: Session? = null
    private var pendingStart: PendingStart? = null
    private var resumeBlockedTrackId: Long? = preferences.loadResumeBlock()
    private var halted = false
    @Volatile private var closed = false
    private var consecutiveFailures = 0
    private var backoffUntilMillis = 0L
    /** Session THIS reporter auto-ended for stale observations (P2-N2): its resume
     *  intent survives, so the same track coming back continues the original chain. */
    private var staleEndedSession: Session? = null

    // Volatile snapshots for non-suspending readers on any thread.
    @Volatile private var latestObservation = Observation(ReporterPlayState.Paused, 0L, 0L)
    @Volatile private var observationStamp = 0L
    @Volatile private var observationAtMillis = 0L
    @Volatile private var openSnapshot: Pair<String, Long>? = null
    @Volatile private var pendingSnapshotId: String? = null

    // Actor-confined: the stamp of the newest observation already folded into the session.
    private var lastAppliedObservationStamp = 0L

    override val clientId: String = preferences.loadClientId()
        ?.takeIf { it.length <= 64 && PLAYBACK_ID_PATTERN.matches(it) }
        ?: UUID.randomUUID().toString().also(preferences::saveClientId)

    init {
        if (startActor) {
            scope.launch { runActor() }
        }
    }

    // ------------------------------------------------------------------ public API (non-blocking)

    override fun openSessionId(): String? = openSnapshot?.first

    override fun openSessionTrackId(): Long? = openSnapshot?.second

    override fun pendingSessionId(): String? = pendingSnapshotId

    override fun requestStart(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        context: Music032PlaybackContext?,
    ) {
        if (closed) return
        commands.trySend(Command.Start(trackId, positionMillis, durationMillis, initialState, observationStamp, context))
    }

    override fun requestTakeover(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        context: Music032PlaybackContext?,
    ) {
        if (closed) return
        commands.trySend(Command.Takeover(trackId, positionMillis, durationMillis, initialState, observationStamp, context))
    }

    override fun requestFallbackStart(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        context: Music032PlaybackContext?,
    ) {
        if (closed) return
        commands.trySend(Command.FallbackStart(trackId, positionMillis, durationMillis, initialState, observationStamp, context))
    }

    override fun observe(state: ReporterPlayState, positionMillis: Long, durationMillis: Long) {
        if (closed) return
        latestObservation = Observation(state, positionMillis.coerceAtLeast(0L), durationMillis.coerceAtLeast(0L))
        observationStamp += 1
        observationAtMillis = clock()
        observationPoke.trySend(Unit)
    }

    override fun requestSeek(positionMillis: Long, durationMillis: Long) {
        if (closed) return
        commands.trySend(Command.Seek(positionMillis, durationMillis))
    }

    override fun requestEnd(reason: ReporterEndReason, finalPositionMillis: Long?, expectedSessionId: String?) {
        if (closed) return
        commands.trySend(Command.End(reason, finalPositionMillis, expectedSessionId))
    }

    override fun requestShutdown(reason: ReporterEndReason, forget: Boolean) {
        if (closed) return
        commands.trySend(Command.Shutdown(reason, forget))
    }

    // ------------------------------------------------------------------ actor loop

    private object PokeMarker
    private object TimeoutMarker

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun runActor() {
        var consecutiveErrors = 0
        // P3-a: the loop ENDS when the actor closes (shutdown or fatal errors) — no
        // coroutine, channel or client is held suspended forever.
        while (!closed) {
            try {
                drainQueuedWork()
                val waitMillis = nextWakeupInMillis()
                if (waitMillis <= 0L) {
                    processTick()
                    continue
                }
                // P1-A: only the WAIT is time-bounded; a consumed command/poke is always
                // processed to completion OUTSIDE the timeout. P3-c: the timeout is an
                // atomic select branch, so a command delivered in the same scheduling
                // window as the deadline can never be silently dropped.
                val event = select<Any> {
                    commands.onReceive { it }
                    observationPoke.onReceiveCatching { PokeMarker }
                    onTimeout(waitMillis) { TimeoutMarker }
                }
                when (event) {
                    is Command -> handleCommand(event)
                    else -> processTick() // poke or wakeup deadline
                }
                consecutiveErrors = 0
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                // P3: an unexpected failure must not silently kill reporting forever,
                // and must not hot-spin either: bounded backoff, then a bounded life.
                consecutiveErrors += 1
                logger("playback reporter actor error ($consecutiveErrors): ${error.message}")
                if (consecutiveErrors >= 8) {
                    // P3-b: latch dead. Public API calls are rejected (see request*),
                    // and the channels are closed below, so a polling loop cannot grow
                    // an unbounded queue into a dead actor.
                    logger("playback reporter actor stopped after repeated errors")
                    closed = true
                }
                if (!closed) sleeper((1_000L * consecutiveErrors).coerceAtMost(10_000L))
            }
        }
        // P3-a/P3-b: finite lifecycle — release the channels and discard anything still
        // queued; trySend on closed channels fails harmlessly and the public API
        // rejects new work before it ever reaches a queue.
        commands.close()
        observationPoke.close()
        while (commands.tryReceive().isSuccess) Unit
    }

    /** Test introspection: whether the production actor loop is still running. */
    internal fun actorActiveForTest(): Boolean = !closed

    private suspend fun drainQueuedWork() {
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            handleCommand(command)
        }
        if (observationPoke.tryReceive().isSuccess) processTick()
    }

    /** Test driver: process every queued command/poke and one timer wakeup, deterministically. */
    internal suspend fun drainActorForTest() {
        drainQueuedWork()
        processTick()
    }

    /** Milliseconds until the next periodic action (heartbeat / pending-start retry). */
    private fun nextWakeupInMillis(): Long {
        // P1-B: a closed actor has no periodic work left; it must suspend until the next
        // command (or forever) instead of spinning on stale deadlines.
        if (closed) return Long.MAX_VALUE
        val now = clock()
        val deadlines = mutableListOf<Long>()
        session?.takeIf { !it.ended }?.let { s ->
            val interval = if (s.state == ReporterPlayState.Paused) {
                pausedHeartbeatMillis
            } else {
                activeHeartbeatMillis
            }
            deadlines += maxOf(s.lastEventAtMillis + interval, backoffUntilMillis)
        }
        if (pendingStart != null) deadlines += backoffUntilMillis
        val deadline = deadlines.minOrNull() ?: return Long.MAX_VALUE
        return deadline - now
    }

    /** Observation poke or timer wakeup: fold the latest observation into the session. */
    private suspend fun processTick() {
        if (closed) return
        val s = session?.takeIf { !it.ended }
        if (s == null) {
            retryPendingStartIfDue()
            publishOpenSnapshot()
            return
        }
        // P2-C freshness guard: when observations stop arriving (reporting loop died, a
        // cleanup path missed its end, the speaker went silent) the session must not be
        // lease-renewed forever — end it at the last real position after ~2 heartbeat
        // intervals without any fresh observation. Normal 1s polls (including hours of
        // paused playback) never trip this, and a blocked network does not backdate the
        // latest observation stamp.
        val freshnessInterval = if (s.state == ReporterPlayState.Paused) {
            pausedHeartbeatMillis
        } else {
            activeHeartbeatMillis
        }
        if (observationAtMillis > 0L && clock() - observationAtMillis > 2 * freshnessInterval) {
            logger("playback session ${s.sessionId} ended: observations stale")
            sendEnd(s, ReporterEndReason.Stopped, s.lastPositionMillis, eventDeadlineMillis, forget = false)
            // P2-N2: the staleness end is an interruption, not a user intent — keep the
            // resume link so the same track coming back continues THIS chain instead of
            // counting again on a fresh one. Only when no recovery is still pending.
            if (s.ended && pendingStart == null) staleEndedSession = s
            publishOpenSnapshot()
            return
        }
        // Only fold a FRESH observation: anything observed before this session's start
        // command was enqueued belongs to the previous play and must not rewrite it.
        if (observationStamp != lastAppliedObservationStamp) {
            lastAppliedObservationStamp = observationStamp
            val observation = latestObservation
            observeInto(s, observation.positionMillis, observation.durationMillis)
            if (observation.state == ReporterPlayState.Playing) {
                // The played high-water mark only ever advances while actually playing.
                s.playedHighWaterMillis = maxOf(s.playedHighWaterMillis, s.lastPositionMillis)
            }
            if (s.state != observation.state) {
                s.state = observation.state
                if (observation.state == ReporterPlayState.Playing) s.hasPlayed = true
                sendEvent(s, stateEventType(observation.state), eventDeadlineMillis)
                publishOpenSnapshot()
                return
            }
        }
        if (clock() >= nextWakeupDeadlineFor(s)) {
            sendEvent(s, "heartbeat", eventDeadlineMillis)
        }
        publishOpenSnapshot()
    }

    private fun nextWakeupDeadlineFor(s: Session): Long {
        val interval = if (s.state == ReporterPlayState.Paused) {
            pausedHeartbeatMillis
        } else {
            activeHeartbeatMillis
        }
        return maxOf(s.lastEventAtMillis + interval, backoffUntilMillis)
    }

    // ------------------------------------------------------------------ command handlers

    private suspend fun handleCommand(command: Command) {
        if (closed && command !is Command.Shutdown) return
        when (command) {
            is Command.Start -> {
                // An explicit new play is the ONLY thing that lifts a resume block / halt.
                resumeBlockedTrackId = null
                preferences.saveResumeBlock(null)
                halted = false
                staleEndedSession = null
                endOpenSession(ReporterEndReason.Replaced, eventDeadlineMillis)
                pendingStart = null
                beginStart(
                    trackId = command.trackId,
                    positionMillis = command.position,
                    durationMillis = command.duration,
                    initialState = command.state,
                    resumedFromSessionId = null,
                    inheritedHasPlayed = false,
                    takeover = false,
                    inlineAttempts = 1 + START_RETRY_DELAYS_MILLIS.size,
                    obsStamp = command.obsStamp,
                    context = command.context,
                )
            }
            is Command.Takeover -> {
                // A renderer switch continues the same listen; it is NOT an explicit play
                // and therefore does not lift a persisted resume block. It DOES clear a
                // halt, exactly like an explicit start (P1-B): otherwise a halted reporter
                // could open a session whose heartbeats are then dropped forever.
                halted = false
                val current = session?.takeIf { !it.ended }
                if (current != null && current.trackId == command.trackId) {
                    current.ended = true
                    pendingStart = null
                    staleEndedSession = null
                    beginStart(
                        trackId = command.trackId,
                        positionMillis = command.position,
                        durationMillis = command.duration,
                        initialState = command.state,
                        resumedFromSessionId = current.sessionId,
                        inheritedHasPlayed = current.hasPlayed,
                        takeover = true,
                        inlineAttempts = 1 + START_RETRY_DELAYS_MILLIS.size,
                        obsStamp = command.obsStamp,
                        initialHighWaterMillis = current.playedHighWaterMillis,
                        context = command.context ?: current.context,
                    )
                    publishOpenSnapshot()
                    return
                }
                endOpenSession(ReporterEndReason.Replaced, eventDeadlineMillis)
                pendingStart = null
                staleEndedSession = null
                if (resumeBlockedTrackId == command.trackId) {
                    logger("takeover for track ${command.trackId} skipped: resume is blocked")
                    notifier(ReporterNotice.ResumeBlocked(command.trackId))
                    publishOpenSnapshot()
                    return
                }
                // The old side may already have ended the session (e.g. its finally sent
                // stopped): the persisted snapshot is the fallback link for a genuine
                // renderer switch only.
                val snapshot = preferences.loadLastSession()
                val resume = snapshot != null && snapshot.trackId == command.trackId
                beginStart(
                    trackId = command.trackId,
                    positionMillis = command.position,
                    durationMillis = command.duration,
                    initialState = command.state,
                    resumedFromSessionId = if (resume) snapshot?.sessionId else null,
                    inheritedHasPlayed = resume && snapshot?.hasPlayed == true,
                    takeover = true,
                    inlineAttempts = 1 + START_RETRY_DELAYS_MILLIS.size,
                    obsStamp = command.obsStamp,
                    initialHighWaterMillis = if (resume) snapshot?.playedHighWaterMillis ?: 0L else 0L,
                    context = command.context,
                )
            }
            is Command.FallbackStart -> {
                if (halted || resumeBlockedTrackId == command.trackId) {
                    publishOpenSnapshot()
                    return
                }
                session?.takeIf { !it.ended }?.let { open ->
                    // P2-3: a poll that races the transition handler must not replace the
                    // freshly opened session for the same track.
                    if (open.trackId == command.trackId) {
                        publishOpenSnapshot()
                        return
                    }
                    endOpenSession(ReporterEndReason.Replaced, eventDeadlineMillis)
                }
                pendingStart?.let { pending ->
                    if (pending.trackId == command.trackId) {
                        // A start for this track is already in flight (possibly commit-lost):
                        // retry it verbatim (respecting backoff) instead of inventing a
                        // new session id.
                        retryPendingStartIfDue()
                        publishOpenSnapshot()
                        return
                    }
                }
                // P2-N2: the session THIS reporter auto-ended for stale observations keeps
                // its resume intent: the same track coming back continues the original
                // chain (resumedFrom + inherited evidence), never a fresh double-count.
                staleEndedSession?.let { stale ->
                    staleEndedSession = null
                    if (stale.trackId == command.trackId) {
                        beginStart(
                            trackId = command.trackId,
                            positionMillis = command.position,
                            durationMillis = command.duration,
                            initialState = command.state,
                            resumedFromSessionId = stale.sessionId,
                            inheritedHasPlayed = stale.hasPlayed,
                            takeover = true,
                            inlineAttempts = 1 + START_RETRY_DELAYS_MILLIS.size,
                            obsStamp = command.obsStamp,
                            initialHighWaterMillis = stale.playedHighWaterMillis,
                            context = command.context ?: stale.context,
                        )
                        publishOpenSnapshot()
                        return
                    }
                }
                beginStart(
                    trackId = command.trackId,
                    positionMillis = command.position,
                    durationMillis = command.duration,
                    initialState = command.state,
                    resumedFromSessionId = null,
                    inheritedHasPlayed = false,
                    takeover = false,
                    inlineAttempts = 1,
                    obsStamp = command.obsStamp,
                    context = command.context,
                )
            }
            is Command.Seek -> {
                val s = session?.takeIf { !it.ended } ?: run { publishOpenSnapshot(); return }
                observeInto(s, command.position, command.duration)
                sendEvent(s, "seek", eventDeadlineMillis)
            }
            is Command.End -> {
                val s = session?.takeIf { !it.ended }
                if (s == null) {
                    // P2-C: even without an open session, an end must retire a matching
                    // pending start, or the deferred start would replay later and open a
                    // ghost session nobody ever ends.
                    pendingStart?.let { pending ->
                        if (command.expectedSessionId == null || pending.sessionId == command.expectedSessionId) {
                            pendingStart = null
                        }
                    }
                    publishOpenSnapshot()
                    return
                }
                if (command.expectedSessionId != null && s.sessionId != command.expectedSessionId) {
                    publishOpenSnapshot()
                    return
                }
                val finalPosition = command.finalPosition ?: s.lastPositionMillis
                sendEnd(s, command.reason, finalPosition, eventDeadlineMillis, forget = false)
            }
            is Command.Shutdown -> handleShutdown(command.reason, command.forget)
        }
        publishOpenSnapshot()
    }

    private suspend fun handleShutdown(reason: ReporterEndReason, forget: Boolean) {
        closed = true
        // Commit-lost protection: give a pending start one bounded shot so its session can
        // be ended properly instead of expiring as a ghost.
        pendingStart?.let { pending -> attemptStart(pending, SHUTDOWN_EVENT_DEADLINE_MILLIS) }
        session?.takeIf { !it.ended }?.let { s ->
            sendEnd(s, reason, s.lastPositionMillis, SHUTDOWN_EVENT_DEADLINE_MILLIS, forget)
        }
        // P1-B: a recovery during shutdown may have produced another pending start — a
        // closed actor never retries it (no ghost session later, no spin).
        pendingStart = null
        staleEndedSession = null
        session = session?.takeIf { !it.ended }
        if (forget) preferences.saveLastSession(null)
        publishOpenSnapshot()
    }

    // ------------------------------------------------------------------ start machinery

    private suspend fun beginStart(
        trackId: Long,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
        resumedFromSessionId: String?,
        inheritedHasPlayed: Boolean,
        takeover: Boolean,
        inlineAttempts: Int,
        sessionIdOverride: String? = null,
        obsStamp: Long,
        initialHighWaterMillis: Long = 0L,
        context: Music032PlaybackContext? = null,
    ) {
        val pending = PendingStart(
            trackId = trackId,
            sessionId = sessionIdOverride ?: UUID.randomUUID().toString(),
            positionMillis = positionMillis.coerceAtLeast(0L),
            durationMillis = durationMillis.coerceAtLeast(0L),
            initialState = initialState,
            resumedFromSessionId = resumedFromSessionId,
            inheritedHasPlayed = inheritedHasPlayed,
            takeover = takeover,
            sessionIdOverride = sessionIdOverride,
            observationStampAtEnqueue = obsStamp,
            initialHighWaterMillis = initialHighWaterMillis,
            context = context,
        )
        pendingStart = pending
        var attempts = 0
        // Protocol resolutions (resume_invalid / halt) clear pendingStart: only genuine
        // network/transient failures may consume further inline attempts.
        while (attempts < inlineAttempts && pendingStart === pending) {
            if (attemptStart(pending, eventDeadlineMillis)) return
            attempts += 1
            if (attempts < inlineAttempts) sleeper(START_RETRY_DELAYS_MILLIS[attempts - 1])
        }
        // Inline retries exhausted: the pending start stays and is retried verbatim by
        // the backoff-driven wakeup (never a fresh session id).
        if (pendingStart === pending) {
            logger("playback session start for track $trackId deferred after $inlineAttempts attempts")
        }
    }

    private suspend fun retryPendingStartIfDue() {
        val pending = pendingStart ?: return
        if (closed) return
        if (clock() < backoffUntilMillis) return
        attemptStart(pending, eventDeadlineMillis)
    }

    /**
     * One bounded send of the pending start. On success the session opens; on protocol
     * errors the pending start is resolved (blocked/halted); on network failure it stays
     * pending. Returns true when the start was accepted by the server.
     */
    private suspend fun attemptStart(pending: PendingStart, deadlineMillis: Long): Boolean {
        val payload = JSONObject()
            .put("clientId", clientId)
            .put("clientKind", "android")
            .put("sessionId", pending.sessionId)
            .put("seq", 1)
            .put("type", "start")
            .put("trackId", pending.trackId)
            .put("state", pending.initialState.wire)
            .put("positionMillis", pending.positionMillis)
            .put("durationMillis", pending.durationMillis)
        if (pending.resumedFromSessionId != null) {
            payload.put("resumedFromSessionId", pending.resumedFromSessionId)
        } else if (pending.context != null && contextSupported()) {
            payload.put("context", pending.context.toJson())
        }
        val result = post(payload, deadlineMillis)
        when {
            result.status in 200..299 -> {
                pendingStart = null
                registerSuccess()
                openSessionFrom(pending)
                return true
            }
            result.status == 409 && errorCode(result) == "resume_invalid" -> {
                pendingStart = null
                resumeBlockedTrackId = pending.trackId
                preferences.saveResumeBlock(pending.trackId)
                preferences.saveLastSession(null)
                logger(
                    "playback resume rejected (resume_invalid) for track ${pending.trackId}; " +
                        "reporting is blocked until the next explicit play",
                )
                notifier(ReporterNotice.ResumeBlocked(pending.trackId))
            }
            result.status == 400 || result.status == 401 || result.status == 403 ||
                (result.status == 404 && errorCode(result) == "not_found") -> {
                pendingStart = null
                halted = true
                logger("playback session start rejected (status=${result.status}); reporting halted until the next explicit play")
                notifier(ReporterNotice.StartRejected(pending.trackId, result.status))
            }
            result.status == 0 -> {
                registerNetworkFailure()
            }
            else -> {
                // 5xx and other transient failures: keep the pending start for the
                // backoff-driven retry (same session id and seq).
                registerNetworkFailure()
            }
        }
        return false
    }

    private fun openSessionFrom(pending: PendingStart) {
        val opened = Session(
            sessionId = pending.sessionId,
            trackId = pending.trackId,
            seq = 1L,
            state = pending.initialState,
            hasPlayed = pending.inheritedHasPlayed || pending.initialState == ReporterPlayState.Playing,
            lastPositionMillis = pending.positionMillis,
            durationMillis = pending.durationMillis,
            lastEventAtMillis = clock(),
            playedHighWaterMillis = maxOf(
                pending.initialHighWaterMillis,
                if (pending.initialState == ReporterPlayState.Playing) pending.positionMillis else 0L,
            ),
            rebuildAttempted = pending.sessionIdOverride != null,
            context = pending.context,
        )
        session = opened
        // Observations made before this session's start was enqueued belong to the
        // previous play: never fold them into the freshly opened session. The freshness
        // clock (P2-C) starts at the session open.
        lastAppliedObservationStamp = pending.observationStampAtEnqueue
        observationAtMillis = clock()
        persistSnapshot(opened)
        publishOpenSnapshot()
    }

    // ------------------------------------------------------------------ event machinery

    private fun stateEventType(state: ReporterPlayState): String = when (state) {
        ReporterPlayState.Playing -> "resume"
        ReporterPlayState.Paused -> "pause"
        ReporterPlayState.Buffering -> "buffering"
    }

    private fun observeInto(s: Session, positionMillis: Long, durationMillis: Long) {
        s.lastPositionMillis = positionMillis.coerceAtLeast(0L)
        if (durationMillis > 0L) s.durationMillis = durationMillis
    }

    private suspend fun sendEvent(s: Session, type: String, deadlineMillis: Long) {
        if (halted) {
            // P1-B: dropping the event must still push the next wakeup out, or the actor
            // spins on a permanently-expired deadline.
            s.lastEventAtMillis = clock()
            return
        }
        val payload = eventPayload(s, type)
        if (type == "heartbeat") payload.put("state", s.state.wire)
        val result = post(payload, deadlineMillis)
        // Fire-and-forget: the send attempt itself pushes the next heartbeat out, so a
        // stalled network can never build an unbounded backlog of stale heartbeats.
        s.lastEventAtMillis = clock()
        handleEventResult(s, result, type, s.lastPositionMillis, endReason = null, deadlineMillis)
    }

    private suspend fun sendEnd(
        s: Session,
        reason: ReporterEndReason,
        finalPositionMillis: Long,
        deadlineMillis: Long,
        forget: Boolean,
    ) {
        val payload = eventPayload(s, "end")
            .put("endReason", reason.wire)
        payload.put("positionMillis", finalPositionMillis.coerceAtLeast(0L))
        val result = post(payload, deadlineMillis)
        s.lastEventAtMillis = clock()
        when {
            result.status in 200..299 -> {
                registerSuccess()
                s.ended = true
                onSessionEnded(s, reason, forget)
            }
            result.status == 409 && errorCode(result) == "session_expired" ->
                recoverExpired(s, "end", finalPositionMillis, reason, deadlineMillis)
            result.status == 404 && errorCode(result) == "session_not_found" ->
                recoverMissing(s, "end", finalPositionMillis, reason, deadlineMillis)
            result.status == 401 || result.status == 403 -> {
                halted = true
                s.ended = true
                logger("playback end rejected (status=${result.status}); reporting halted until the next explicit play")
            }
            else -> {
                // Lost end: the server-side lease (90s / 10min) finalizes the session as
                // expired. Locally we are done either way — no offline replay (D9).
                if (result.status == 0) registerNetworkFailure() else logger("playback end failed (status=${result.status})")
                s.ended = true
                onSessionEnded(s, reason, forget)
            }
        }
        publishOpenSnapshot()
    }

    private suspend fun endOpenSession(reason: ReporterEndReason, deadlineMillis: Long) {
        val s = session?.takeIf { !it.ended } ?: return
        sendEnd(s, reason, s.lastPositionMillis, deadlineMillis, forget = false)
    }

    private suspend fun handleEventResult(
        s: Session,
        result: PlaybackEventHttpResult,
        type: String,
        positionMillis: Long,
        endReason: ReporterEndReason?,
        deadlineMillis: Long,
    ) {
        when {
            result.status in 200..299 -> {
                registerSuccess()
                val body = parseBody(result)
                if (body?.optBoolean("applied", true) == false && body.optString("state") == "ended") {
                    // The session is terminal server-side: late events are idempotent
                    // no-ops and local activity must stop (H1).
                    s.ended = true
                } else {
                    persistSnapshot(s)
                }
            }
            result.status == 409 && errorCode(result) == "session_expired" ->
                recoverExpired(s, type, positionMillis, endReason, deadlineMillis)
            result.status == 404 && errorCode(result) == "session_not_found" ->
                recoverMissing(s, type, positionMillis, endReason, deadlineMillis)
            result.status == 409 &&
                errorCode(result) in setOf("session_owner_mismatch", "session_conflict") -> {
                logger("playback session ${s.sessionId} rejected (${errorCode(result)}); closing it locally")
                s.ended = true
            }
            result.status == 401 || result.status == 403 -> {
                halted = true
                s.ended = true
                logger("playback event $type rejected (status=${result.status}); reporting halted until the next explicit play")
            }
            else -> {
                // Network/server failure: drop the event. The next due heartbeat or the
                // server lease keeps the state honest (no backlog, no offline replay).
                if (result.status == 0) registerNetworkFailure() else logger("playback event $type failed (status=${result.status})")
            }
        }
        publishOpenSnapshot()
    }

    /**
     * Evidence that this session really played up where it claims: either it is playing
     * right now (the end was generated while the track genuinely played at the final
     * position), or the played high-water mark already passed the count threshold.
     * Historical hasPlayed + a high (seeked-to) position is NOT evidence.
     */
    private fun endRecoveryState(s: Session): ReporterPlayState =
        if (s.state == ReporterPlayState.Playing ||
            pastCountThreshold(s.playedHighWaterMillis, s.durationMillis)
        ) {
            ReporterPlayState.Playing
        } else {
            s.state
        }

    /** True when the play provably already passed the server count threshold. */
    private fun hasCountableEvidence(s: Session, finalPositionMillis: Long): Boolean =
        pastCountThreshold(s.playedHighWaterMillis, s.durationMillis) ||
            (s.state == ReporterPlayState.Playing && pastCountThreshold(finalPositionMillis, s.durationMillis))

    /**
     * 409 session_expired: exactly one serialized resume per expired session. Non-end
     * events recover with the HONEST current state. An end recovery may only claim
     * `playing` with real evidence (see [endRecoveryState]); the replacement session
     * itself keeps the honest current state, then the interrupted event is re-sent (an
     * end keeps its final position and reason).
     */
    private suspend fun recoverExpired(
        s: Session,
        type: String,
        positionMillis: Long,
        endReason: ReporterEndReason?,
        deadlineMillis: Long,
    ) {
        val honestState = s.state
        val recoveryState = if (endReason != null) endRecoveryState(s) else honestState
        s.ended = true
        val pending = PendingStart(
            trackId = s.trackId,
            sessionId = UUID.randomUUID().toString(),
            positionMillis = positionMillis,
            durationMillis = s.durationMillis,
            initialState = recoveryState,
            resumedFromSessionId = s.sessionId,
            inheritedHasPlayed = s.hasPlayed,
            takeover = true,
            sessionIdOverride = null,
            observationStampAtEnqueue = observationStamp,
            initialHighWaterMillis = s.playedHighWaterMillis,
            context = s.context,
        )
        pendingStart = pending
        if (!attemptStart(pending, deadlineMillis)) return
        val fresh = session?.takeIf { !it.ended } ?: return
        // The recovery start claims `playing` as evidence; the session itself must stay
        // honest or a following pause/resume transition would be swallowed by dedup.
        fresh.state = honestState
        if (endReason != null) {
            sendEnd(fresh, endReason, positionMillis, deadlineMillis, forget = false)
        } else if (type in setOf("heartbeat", "pause", "buffering", "resume", "seek")) {
            sendEvent(fresh, type, deadlineMillis)
        }
    }

    /**
     * 404 session_not_found: mid-session events rebuild the same sessionId once with the
     * HONEST current state (never `playing` on hasPlayed alone — a paused session that
     * seeked to 80% must not become a countable play). An end is only recovered when the
     * play really happened and already passed the count threshold.
     */
    private suspend fun recoverMissing(
        s: Session,
        type: String,
        positionMillis: Long,
        endReason: ReporterEndReason?,
        deadlineMillis: Long,
    ) {
        if (endReason != null) {
            s.ended = true
            if (!hasCountableEvidence(s, positionMillis)) {
                logger(
                    "playback end dropped: the server lost session ${s.sessionId} and the play " +
                        "has no countable evidence (hasPlayed=${s.hasPlayed}, highWater=${s.playedHighWaterMillis})",
                )
                return
            }
            val pending = PendingStart(
                trackId = s.trackId,
                sessionId = UUID.randomUUID().toString(),
                positionMillis = positionMillis,
                durationMillis = s.durationMillis,
                initialState = ReporterPlayState.Playing,
                resumedFromSessionId = null,
                inheritedHasPlayed = true,
                takeover = false,
                sessionIdOverride = null,
                observationStampAtEnqueue = observationStamp,
                initialHighWaterMillis = s.playedHighWaterMillis,
                context = s.context,
            )
            pendingStart = pending
            if (!attemptStart(pending, deadlineMillis)) return
            val fresh = session?.takeIf { !it.ended } ?: return
            sendEnd(fresh, endReason, positionMillis, deadlineMillis, forget = false)
            return
        }
        if (s.rebuildAttempted) {
            logger("playback session ${s.sessionId} is still unknown after one rebuild; giving up")
            s.ended = true
            return
        }
        s.rebuildAttempted = true
        // Mid-session rebuild: the honest current state, never a fabricated `playing`.
        val pending = PendingStart(
            trackId = s.trackId,
            sessionId = s.sessionId,
            positionMillis = positionMillis.coerceAtLeast(0L),
            durationMillis = s.durationMillis,
            initialState = s.state,
            resumedFromSessionId = null,
            inheritedHasPlayed = s.hasPlayed,
            takeover = false,
            sessionIdOverride = s.sessionId,
            observationStampAtEnqueue = observationStamp,
            initialHighWaterMillis = s.playedHighWaterMillis,
            context = s.context,
        )
        s.ended = true
        pendingStart = pending
        if (!attemptStart(pending, deadlineMillis)) return
        val rebuilt = session?.takeIf { !it.ended } ?: return
        if (type in setOf("heartbeat", "pause", "buffering", "resume", "seek")) {
            sendEvent(rebuilt, type, deadlineMillis)
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun eventPayload(s: Session, type: String): JSONObject {
        s.seq += 1
        return JSONObject()
            .put("clientId", clientId)
            .put("clientKind", "android")
            .put("sessionId", s.sessionId)
            .put("seq", s.seq)
            .put("type", type)
            .put("trackId", s.trackId)
            .put("positionMillis", s.lastPositionMillis)
            .put("durationMillis", s.durationMillis)
    }

    private suspend fun post(payload: JSONObject, deadlineMillis: Long): PlaybackEventHttpResult =
        withTimeoutOrNull(deadlineMillis) { transport.post(payload) }
            ?: PlaybackEventHttpResult(0, null)

    private fun registerSuccess() {
        consecutiveFailures = 0
        backoffUntilMillis = 0L
    }

    private fun registerNetworkFailure() {
        consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(8)
        val backoff = (backoffBaseMillis shl (consecutiveFailures - 1)).coerceAtMost(MAX_BACKOFF_MILLIS)
        backoffUntilMillis = clock() + backoff
    }

    private fun persistSnapshot(s: Session) {
        preferences.saveLastSession(
            Music032PersistedSession(
                sessionId = s.sessionId,
                trackId = s.trackId,
                hasPlayed = s.hasPlayed,
                playedHighWaterMillis = s.playedHighWaterMillis,
                savedAtMillis = clock(),
            ),
        )
    }

    private fun onSessionEnded(s: Session, reason: ReporterEndReason, forget: Boolean) {
        if (forget) {
            preferences.saveLastSession(null)
            return
        }
        when (reason) {
            // Finished business: resuming from these is rejected server-side, so the
            // snapshot must not outlive them.
            ReporterEndReason.Completed,
            ReporterEndReason.Skipped,
            ReporterEndReason.Replaced -> preferences.saveLastSession(null)
            // Resumable endings: keep the chain recoverable for a genuine renderer switch.
            ReporterEndReason.Stopped,
            ReporterEndReason.Error,
            ReporterEndReason.ClientClosed -> persistSnapshot(s)
        }
    }

    private fun publishOpenSnapshot() {
        openSnapshot = session?.takeIf { !it.ended }?.let { it.sessionId to it.trackId }
        pendingSnapshotId = pendingStart?.sessionId
    }

    private fun parseBody(result: PlaybackEventHttpResult): JSONObject? =
        result.body?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun errorCode(result: PlaybackEventHttpResult): String? =
        parseBody(result)?.optJSONObject("error")?.optString("code")?.takeIf(String::isNotBlank)
}
