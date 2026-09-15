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
    fun redactsSensitiveQueryParameters() {
        val value = PlaybackDiagnostics.redact(
            "https://host/path?X-Plex-Token=secret&session=abc&X-Plex-Client-Identifier=device&safe=yes",
        )
        assertFalse(value.contains("secret"))
        assertFalse(value.contains("session=abc"))
        assertFalse(value.contains("device"))
        assertTrue(value.contains("X-Plex-Token=***"))
        assertTrue(value.contains("session=***"))
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
    fun adbCommandUsesConfiguredApplicationId() {
        assertTrue(PlaybackDiagnostics.ADB_PULL_COMMAND.contains(com.lux032.musicbridge.BuildConfig.APPLICATION_ID))
    }
}
