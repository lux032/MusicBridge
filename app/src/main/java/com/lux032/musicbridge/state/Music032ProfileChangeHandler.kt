package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032ReporterApi
import com.lux032.musicbridge.music.ReporterPlayState

/**
 * Production handling of a saved 032 server profile (P1-N1 / P2-B / P2-N3).
 *
 * This handler runs STRICTLY AFTER the caller has removed the cached client and written
 * the new profile: any reporter built through [reporterFactory] is therefore bound to the
 * NEW credentials (baseUrl/token). The predecessor reporter's own shutdown end still goes
 * out over the OLD transport with its bounded 1.5s deadline — accepted, best-effort.
 *
 * Strategies by change kind:
 * - name-only (or any non-connection field): nothing happens, reporting never pauses.
 * - same baseUrl, new token (same library): the reporter is retired but the snapshot is
 *   kept, and the currently playing track is handed over via takeover so the listen is
 *   not counted twice.
 * - different baseUrl: the server offers no identity proof, so this is CONSERVATIVELY
 *   treated as another library — no fabricated cross-library resume, and no automatic
 *   high-position fresh count either: the current track's reporting is latched off
 *   (resume block) until the next explicit play, with a visible notice.
 */
internal class Music032ProfileChangeHandler(
    private val retireReporter: (sourceId: String, forget: Boolean) -> Unit,
    private val reporterFactory: (sourceId: String) -> Music032ReporterApi?,
    private val blockResume: (sourceId: String, trackId: Long) -> Unit,
    private val notify: (String) -> Unit,
) {
    fun onProfileSaved(
        previous: MusicServerProfile,
        normalized: MusicServerProfile,
        player: MiniPlayerState?,
        localPositionMillis: () -> Long,
    ) {
        val connectionChanged = previous.baseUrl != normalized.baseUrl || previous.token != normalized.token
        if (!connectionChanged) return
        val playerState = player
        val currentTrack = playerState?.tracks?.getOrNull(playerState.currentIndex)
            ?.takeIf { it.sourceId == previous.id }

        if (previous.baseUrl == normalized.baseUrl) {
            retireReporter(previous.id, false)
            if (playerState == null || currentTrack == null) return
            val trackId = currentTrack.ratingKey.toLongOrNull() ?: return
            val reporter = reporterFactory(previous.id) ?: return
            val position = when (playerState.target) {
                PlaybackTarget.LocalDevice -> localPositionMillis()
                else -> playerState.currentPositionMillis
            }.coerceAtLeast(0L)
            reporter.requestTakeover(
                trackId = trackId,
                positionMillis = position,
                durationMillis = playerState.durationMillis ?: currentTrack.durationMillis ?: 0L,
                initialState = if (playerState.isPaused) ReporterPlayState.Paused else ReporterPlayState.Playing,
            )
        } else {
            retireReporter(previous.id, true)
            currentTrack?.ratingKey?.toLongOrNull()?.let { trackId ->
                blockResume(previous.id, trackId)
                notify("服务器地址已变更，当前曲目的播放上报已暂停，重新播放后恢复")
            }
        }
    }
}
