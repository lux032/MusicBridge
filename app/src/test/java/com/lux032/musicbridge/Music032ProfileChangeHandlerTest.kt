package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032ReporterApi
import com.lux032.musicbridge.music.ReporterEndReason
import com.lux032.musicbridge.music.ReporterPlayState
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production profile-change logic (P1-N1/P2-B/P2-N3): the handler that decides retire /
 * handover / latch-off when a 032 profile is saved. Fakes record the exact call order so
 * the credential-rebuild-before-reporter-construction contract is verified, and the
 * handover takeover carries the real current track/position/state.
 */
class Music032ProfileChangeHandlerTest {

    private class FakeReporter : Music032ReporterApi {
        override val clientId = "fake-client"
        val calls = mutableListOf<String>()
        override fun openSessionId(): String? = null
        override fun openSessionTrackId(): Long? = null
        override fun requestStart(trackId: Long, positionMillis: Long, durationMillis: Long, initialState: ReporterPlayState) {
            calls += "start:$trackId"
        }
        override fun requestTakeover(trackId: Long, positionMillis: Long, durationMillis: Long, initialState: ReporterPlayState) {
            calls += "takeover:$trackId@$positionMillis:${initialState.wire}:$durationMillis"
        }
        override fun requestFallbackStart(trackId: Long, positionMillis: Long, durationMillis: Long, initialState: ReporterPlayState) {
            calls += "fallback:$trackId"
        }
        override fun observe(state: ReporterPlayState, positionMillis: Long, durationMillis: Long) {}
        override fun requestSeek(positionMillis: Long, durationMillis: Long) {}
        override fun requestEnd(reason: ReporterEndReason, finalPositionMillis: Long?, expectedSessionId: String?) {
            calls += "end:${reason.wire}"
        }
        override fun requestShutdown(reason: ReporterEndReason, forget: Boolean) {
            calls += "shutdown:${reason.wire}"
        }
    }

    private class Harness {
        val events = mutableListOf<String>()
        val reporter = FakeReporter()
        val handler = Music032ProfileChangeHandler(
            retireReporter = { sourceId, forget -> events += "retire:$sourceId:forget=$forget" },
            reporterFactory = { sourceId -> events += "factory:$sourceId"; reporter },
            blockResume = { sourceId, trackId -> events += "block:$sourceId:$trackId" },
            notify = { message -> events += "notify:$message" },
        )
    }

    private fun profile(
        baseUrl: String = "http://old-host:4533",
        token: String = "token-old",
        name: String = "server",
    ) = MusicServerProfile(
        id = "src-1",
        name = name,
        type = MusicServerType.Music032,
        token = token,
        baseUrl = baseUrl,
        mediaToken = "media",
    )

    private fun player(trackKey: String, position: Long, paused: Boolean): MiniPlayerState = MiniPlayerState(
        album = PlexAlbum("alb", "album", null, null, null, null, null, null, PlexSection("", "", ""), "src-1"),
        tracks = listOf(
            PlexTrackStream(trackKey, "t", "http://x", "/p", 240_000L, null, sourceId = "src-1"),
        ),
        currentIndex = 0,
        target = PlaybackTarget.LocalDevice,
        isPaused = paused,
        currentPositionMillis = position,
        durationMillis = 240_000L,
    )

    @Test
    fun `name-only edit never touches the reporter`() {
        val h = Harness()
        h.handler.onProfileSaved(
            previous = profile(),
            normalized = profile(name = "renamed"),
            player = player("42", 150_000L, paused = false),
        ) { 150_000L }
        assertEquals(emptyList<String>(), h.events)
        assertEquals(emptyList<String>(), h.reporter.calls)
    }

    @Test
    fun `token change hands the current listen over with real position and state`() {
        val h = Harness()
        h.handler.onProfileSaved(
            previous = profile(),
            normalized = profile(token = "token-new"),
            player = player("42", 150_000L, paused = false),
        ) { 150_000L }

        // The reporter is retired WITHOUT forgetting the snapshot, then a NEW reporter is
        // built (on the new credentials — the caller has already swapped cache+profile)
        // and receives the takeover with the real current track/position/state.
        assertEquals(listOf("retire:src-1:forget=false", "factory:src-1"), h.events)
        assertEquals(listOf("takeover:42@150000:playing:240000"), h.reporter.calls)
    }

    @Test
    fun `paused current track hands over as paused`() {
        val h = Harness()
        h.handler.onProfileSaved(
            previous = profile(),
            normalized = profile(token = "token-new"),
            player = player("42", 90_000L, paused = true),
        ) { 90_000L }
        assertEquals(listOf("takeover:42@90000:paused:240000"), h.reporter.calls)
    }

    @Test
    fun `no handover when nothing is playing the edited source`() {
        val h = Harness()
        h.handler.onProfileSaved(
            previous = profile(),
            normalized = profile(token = "token-new"),
            player = null,
        ) { 0L }
        assertEquals(listOf("retire:src-1:forget=false"), h.events)
        assertEquals(emptyList<String>(), h.reporter.calls)
    }

    @Test
    fun `baseUrl change latches the current track off and never hands over`() {
        val h = Harness()
        h.handler.onProfileSaved(
            previous = profile(),
            normalized = profile(baseUrl = "http://new-host:4533"),
            player = player("42", 150_000L, paused = false),
        ) { 150_000L }

        // Conservative strategy: forget the snapshot, latch the current track's reporting
        // off until an explicit play, and NEVER build a takeover reporter.
        assertTrue(h.events.any { it == "retire:src-1:forget=true" })
        assertTrue(h.events.any { it == "block:src-1:42" })
        assertTrue(h.events.any { it.startsWith("notify:") })
        assertTrue(h.events.none { it.startsWith("factory:") })
        assertEquals(emptyList<String>(), h.reporter.calls)
    }
}
