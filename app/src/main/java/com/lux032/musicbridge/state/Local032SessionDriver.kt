package com.lux032.musicbridge

import androidx.media3.common.Player
import com.lux032.musicbridge.music.Music032ReporterApi
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterPlayState
import com.lux032.musicbridge.plex.PlexTrackStream

/**
 * 032 session-flow routing for the LOCAL playback coordinator, extracted so the real
 * coordinator logic (not just pure mappings) is unit-testable with fake reporters:
 *
 * - Explicit new plays always open a FRESH chain (`requestStart`); they never resume a
 *   persisted snapshot. Only a genuine renderer switch (`continueChain`) takes over.
 * - Engine transitions close the old session with its own reason and observed position
 *   (AUTO/REPEAT = completed, SEEK = skipped) and open a fresh play — a repeat-one loop
 *   of the same ratingKey included.
 * - A queue that ends on a FAILED last item reports `error` with the last observed
 *   position, never a fabricated `completed` at full duration.
 * - After the queue ended (naturally or by failure) a latch stops the 1s poll from
 *   opening ghost paused sessions; only a new explicit play or transition lifts it.
 * - All reporter calls are non-blocking; nothing here ever suspends.
 */
internal class Local032SessionDriver(
    private val reporterFor: (sourceId: String) -> Music032ReporterApi?,
    private val is032Track: (PlexTrackStream) -> Boolean,
) {
    /** 032 track the reporter currently has (or last had) a session for — end routing. */
    var lastReportedTrack: PlexTrackStream? = null
        private set

    /** Latch: no ghost sessions after the queue ended until the next explicit play. */
    var playbackFinished: Boolean = false
        private set

    private fun reporterForTrack(track: PlexTrackStream): Pair<Music032ReporterApi, Long>? {
        if (!is032Track(track)) return null
        val reporter = reporterFor(track.sourceId) ?: return null
        val trackId = track.ratingKey.toLongOrNull() ?: return null
        return reporter to trackId
    }

    /** Explicit playback start (album/playlist/single tap, or renderer switch handover). */
    fun onExplicitStart(
        track: PlexTrackStream,
        positionMillis: Long,
        durationMillis: Long,
        continueChain: Boolean,
    ) {
        playbackFinished = false
        // P2-C: switching to a track of ANOTHER 032 source must explicitly end the
        // previous source's session — the new source's reporter cannot see it.
        lastReportedTrack?.let { previous ->
            if (previous.sourceId != track.sourceId || previous.ratingKey != track.ratingKey) {
                reporterFor(previous.sourceId)?.let { reporter ->
                    reporter.requestEnd(
                        ReporterEndReason.Stopped,
                        expectedSessionId = reporter.ownedSessionId(),
                    )
                }
            }
        }
        lastReportedTrack = null
        val (reporter, trackId) = reporterForTrack(track) ?: return
        // The engine queue is still being rebuilt at this point: the track has not
        // sounded yet, so the honest initial state is buffering.
        if (continueChain) {
            reporter.requestTakeover(trackId, positionMillis.coerceAtLeast(0L), durationMillis, ReporterPlayState.Buffering, track.playbackContext)
        } else {
            reporter.requestStart(trackId, positionMillis.coerceAtLeast(0L), durationMillis, ReporterPlayState.Buffering, track.playbackContext)
        }
        lastReportedTrack = track
    }

    /**
     * Explicit re-selection of the CURRENT track (queue panel tap on the playing item):
     * a genuinely new play from 0 on a fresh chain — never a takeover (P2-A).
     */
    fun onExplicitReplaySameTrack(track: PlexTrackStream, durationMillis: Long) {
        playbackFinished = false
        val (reporter, trackId) = reporterForTrack(track) ?: return
        reporter.requestEnd(ReporterEndReason.Stopped, expectedSessionId = reporter.ownedSessionId())
        reporter.requestStart(trackId, 0L, durationMillis, ReporterPlayState.Buffering, track.playbackContext)
        lastReportedTrack = track
    }

    /** Engine AUTO/SEEK/REPEAT transition: end the old play, open a genuinely new one. */
    fun onEngineTransition(
        newTrack: PlexTrackStream,
        reason: Int,
        positionMillis: Long,
        durationMillis: Long,
        initialState: ReporterPlayState,
    ) {
        val endReason = reporterEndReasonForTransition(reason)
        lastReportedTrack?.let { previous ->
            reporterFor(previous.sourceId)?.let { reporter ->
                reporter.requestEnd(endReason, expectedSessionId = reporter.openSessionId())
            }
        }
        lastReportedTrack = null
        playbackFinished = false
        val (reporter, trackId) = reporterForTrack(newTrack) ?: return
        // A transition inside the queue is always an explicit new play, never a resume.
        reporter.requestStart(
            trackId,
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) 0L else positionMillis.coerceAtLeast(0L),
            durationMillis,
            initialState,
            newTrack.playbackContext,
        )
        lastReportedTrack = newTrack
    }

    /**
     * Queue end: natural STATE_ENDED reports completed; a failed last item reports error.
     * The final position is the reporter's own last observation in both cases.
     */
    fun onQueueEnded(track: PlexTrackStream?, failed: Boolean) {
        playbackFinished = true
        track?.let { reporterForTrack(it) }?.let { (reporter, _) ->
            reporter.requestEnd(if (failed) ReporterEndReason.Error else ReporterEndReason.Completed)
        }
        lastReportedTrack = null
    }

    /** Engine gave up on a track (skipped-failed or unrecovered error). */
    fun onTrackFailed(track: PlexTrackStream) {
        reporterForTrack(track)?.let { (reporter, _) ->
            reporter.requestEnd(ReporterEndReason.Error, expectedSessionId = reporter.openSessionId())
        }
        lastReportedTrack?.let {
            if (it.sourceId == track.sourceId && it.ratingKey == track.ratingKey) lastReportedTrack = null
        }
    }

    /** User or MediaSession seek inside the current item. */
    fun onSeek(track: PlexTrackStream, positionMillis: Long, durationMillis: Long) {
        reporterForTrack(track)?.let { (reporter, _) ->
            reporter.requestSeek(positionMillis.coerceAtLeast(0L), durationMillis)
        }
    }

    /**
     * 1s poll observation. [enginePlaybackState] "stopped" means the closure paths own the
     * lifecycle (onQueueEnded/onTrackFailed); otherwise the latest observation is pushed
     * and a missing session is repaired — unless the playback-finished latch is set.
     */
    fun onPoll(
        track: PlexTrackStream,
        enginePlaybackState: String,
        reporterState: ReporterPlayState,
        positionMillis: Long,
        durationMillis: Long,
    ) {
        if (enginePlaybackState == "stopped") return
        // P1-C: a genuinely playing observation after the queue ended is a REPLAY (the
        // user pressed play again on the parked queue) — that is a new play on a fresh
        // chain, and the finished latch must not suppress it.
        if (reporterState == ReporterPlayState.Playing) playbackFinished = false
        val (reporter, trackId) = reporterForTrack(track) ?: return
        if (!playbackFinished && reporter.openSessionTrackId() != trackId) {
            reporter.requestFallbackStart(trackId, positionMillis.coerceAtLeast(0L), durationMillis, reporterState, track.playbackContext)
        }
        reporter.observe(reporterState, positionMillis.coerceAtLeast(0L), durationMillis)
        if (reporter.openSessionId() != null) lastReportedTrack = track
    }

    /**
     * Session this reporting job may end on its way out, captured at call time: the
     * caller keeps the result in a JOB-LOCAL variable so a newer job's session can never
     * be killed by an old job's cleanup.
     */
    fun captureOwnedSession(): Pair<String, String>? {
        val track = lastReportedTrack ?: return null
        val reporter = reporterFor(track.sourceId) ?: return null
        val sessionId = reporter.ownedSessionId() ?: return null
        return track.sourceId to sessionId
    }
}

/**
 * Owns the reporting-job teardown rules (P1-1): service cleanup runs SYNCHRONOUSLY and
 * only while this job is still the active reporting job, so a cancelled old job can never
 * stop the new playback's service or wipe its callbacks. An INACTIVE job sends no end at
 * all (P3): the superseding play's own start ends the old session (replaced) or chains it
 * (takeover), and an end from the old job could otherwise kill a session it no longer
 * owns. The bounded 032 session end is fire-and-forget and never gates teardown on network.
 */
internal class ReportingJobCleanup(
    private val isActiveJob: () -> Boolean,
    private val stopPlaybackService: () -> Unit,
    private val requestSessionEnd: () -> Unit,
) {
    fun onJobFinished() {
        if (isActiveJob()) {
            stopPlaybackService()
            requestSessionEnd()
        }
    }
}
