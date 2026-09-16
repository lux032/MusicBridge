package com.lux032.musicbridge.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test
    fun bufferEnforcesCapacityAndEvictsExpiredEntries() {
        var now = 1_000L
        val buffer = DiagnosticBuffer(
            capacity = 2,
            maxAgeMillis = 100L,
            clock = { now },
            elapsedClock = { now },
        )
        buffer.add("A", "one")
        now = 1_010L
        buffer.add("B", "two")
        now = 1_020L
        buffer.add("C", "three")
        assertEquals(listOf("B", "C"), buffer.snapshot().map(DiagnosticEntry::type))

        now = 1_200L
        assertTrue(buffer.snapshot().isEmpty())
    }

    @Test
    fun redactsSensitiveValuesInUrlsAndArbitraryText() {
        val value = PlaybackDiagnostics.redact(
            "https://host/path?X-Plex-Token=secret&session=abc&X-Plex-Client-Identifier=device&safe=yes " +
                "HTML X-Plex-Session-Identifier=foreground sessionId='quoted' X-Plex-Token=body-token",
        )
        listOf("secret", "session=abc", "device", "foreground", "body-token").forEach {
            assertFalse(value.contains(it))
        }
        assertTrue(value.contains("X-Plex-Token=***"))
        assertTrue(value.contains("session=***"))
        assertTrue(value.contains("X-Plex-Client-Identifier=***"))
        assertTrue(value.contains("X-Plex-Session-Identifier=***"))
        assertTrue(value.contains("safe=yes"))
    }

    @Test
    fun snapshotContainsHeaderEntriesAndTMinusSixtyMarker() {
        val now = 200_000L
        val text = PlaybackDiagnostics.renderSnapshot(
            entries = listOf(
                DiagnosticEntry(100_000L, 5L, "OLD", "before"),
                DiagnosticEntry(150_000L, 6L, "HEARTBEAT", "positionMs=1"),
            ),
            nowMillis = now,
            reason = "manual",
            versionName = "v1",
            versionCode = 2L,
            buildType = "debug",
        )
        assertTrue(text.contains("versionName=v1 versionCode=2 BUILD_TYPE=debug"))
        assertTrue(text.contains("timestamp=200000 reason=manual"))
        assertTrue(text.contains("wallTimeMs=150000 elapsedMs=6 [HEARTBEAT]"))
        assertFalse(text.contains(" +6 [HEARTBEAT]"))
        assertTrue(text.contains("--- [T - 60s 卡顿临近区间] ---"))
        assertTrue(text.indexOf("--- [T - 60s 卡顿临近区间] ---") < text.indexOf("[HEARTBEAT]"))
    }

    @Test
    fun heartbeatContainsTrackIndexAndDuration() {
        PlaybackDiagnostics.clearInMemoryForTest()
        PlaybackDiagnostics.recordHeartbeat(
            currentIndex = 3,
            positionMs = 12_000L,
            bufferedPositionMs = 25_000L,
            durationMs = 180_000L,
            playbackState = "playing",
        )

        val heartbeat = PlaybackDiagnostics.entriesForTest().single { it.type == "HEARTBEAT" }.detail
        assertTrue(heartbeat.contains("index=3"))
        assertTrue(heartbeat.contains("durationMs=180000"))
    }

    @Test
    fun throughputUsesActualHeartbeatInterval() {
        assertEquals(8.0, PlaybackDiagnostics.throughputKbps(bytes = 2_000L, intervalMillis = 2_000L), 0.001)
        assertEquals(0.0, PlaybackDiagnostics.throughputKbps(bytes = 2_000L, intervalMillis = 0L), 0.001)
    }

    @Test
    fun offsetProbeCasesReuseProductionUrlAndVaryOnlyControlledInputs() {
        val productionUrl = "https://plex.example/music/:/transcode/universal/start.ogg?" +
            "path=%2Flibrary%2Fmetadata%2F123&offset=0&audioCodec=opus&maxAudioBitrate=256&" +
            "location=lan&X-Plex-Client-Identifier=client-1&X-Plex-Client-Profile-Name=generic&" +
            "X-Plex-Client-Profile-Extra=profile&X-Plex-Token=secret"
        val cases = PlaybackDiagnostics.buildPlexOffsetProbeCases(productionUrl)

        assertEquals(
            listOf(
                "baseline_offset0_noRange",
                "offset30_noRange",
                "offset30_fastSeek",
                "offset30_hasMDE",
                "offset30_mp3",
                "baseline_offset0_withRange",
            ),
            cases.map { it.name },
        )
        assertTrue(cases.all { it.sessionId.startsWith("probe-") })
        assertTrue(cases.all { it.url.contains("X-Plex-Client-Identifier=client-1") })
        assertTrue(cases.all { it.url.contains("X-Plex-Client-Profile-Extra=profile") })
        assertTrue(cases[0].url.contains("offset=0"))
        assertTrue(cases[1].url.contains("offset=30"))
        assertTrue(cases[2].url.contains("fastSeek=1"))
        assertTrue(cases[3].url.contains("hasMDE=1"))
        assertTrue(cases[4].url.contains("start.mp3"))
        assertTrue(cases[4].url.contains("audioCodec=mp3"))
        assertTrue(cases[4].url.contains("maxAudioBitrate=320"))
        assertFalse(cases[0].useRange)
        assertTrue(cases[5].useRange)
    }

    @Test
    fun offsetProbeResultRendersVerdictHeadersBodyAndRedactedUrl() {
        val text = PlaybackDiagnostics.renderPlexOffsetProbeResult(
            listOf(
                PlexOffsetProbeOutcome(
                    name = "baseline_offset0_noRange",
                    requestUrl = "https://host/start.ogg?X-Plex-Token=secret",
                    statusCode = 200,
                    location = "https://host/next?X-Plex-Token=redirect-secret",
                    plexProtocol = "1.0",
                    server = "Plex",
                    contentType = "audio/ogg",
                    contentLength = "12",
                    acceptRanges = "none",
                    responseBody = "<success-body-not-captured>",
                ),
                PlexOffsetProbeOutcome(
                    name = "offset30_noRange",
                    requestUrl = "https://host/start.ogg?session=probe-secret",
                    statusCode = 400,
                    location = "",
                    plexProtocol = "1.0",
                    server = "Plex",
                    contentType = "text/html",
                    contentLength = "89",
                    acceptRanges = "",
                    responseBody = "error X-Plex-Token=body-secret",
                ),
            ),
        )
        assertTrue(text.startsWith("VERDICT: BASELINE=200 -> offset 探针结果有效"))
        assertTrue(text.contains("xPlexProtocol=1.0"))
        assertTrue(text.contains("server=Plex"))
        assertTrue(text.contains("body=error X-Plex-Token=***"))
        assertFalse(text.contains("body-secret"))
        assertFalse(text.contains("redirect-secret"))
        assertFalse(text.contains("probe-secret"))
    }

    @Test
    fun offsetProbeResultMarksFailedBaselineInvalid() {
        val text = PlaybackDiagnostics.renderPlexOffsetProbeResult(
            listOf(
                PlexOffsetProbeOutcome(
                    name = "baseline_offset0_noRange",
                    requestUrl = "https://host/start.ogg",
                    statusCode = 400,
                    location = "",
                    plexProtocol = "",
                    server = "",
                    contentType = "text/html",
                    contentLength = "89",
                    acceptRanges = "",
                    responseBody = "bad request",
                ),
            ),
        )
        assertTrue(text.startsWith("VERDICT: BASELINE=400 -> 基准失败，探针无效"))
    }

    @Test
    fun adbCommandUsesConfiguredApplicationId() {
        assertTrue(PlaybackDiagnostics.ADB_PULL_COMMAND.contains(com.lux032.musicbridge.BuildConfig.APPLICATION_ID))
    }
}
