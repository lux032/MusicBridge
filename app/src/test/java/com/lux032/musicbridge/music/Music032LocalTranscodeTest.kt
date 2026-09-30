package com.lux032.musicbridge.music

import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.LocalPlaybackPolicy
import com.lux032.musicbridge.LocalPrecacheCount
import com.lux032.musicbridge.LocalStreamingLimit
import com.lux032.musicbridge.local.buildLocalSeekPlan
import com.lux032.musicbridge.local.isLiveTranscodeUrl
import com.lux032.musicbridge.local.isPlexLiveTranscodeUrl
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.willTranscodeLocally
import com.lux032.musicbridge.plex.withLocalPlaybackPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032LocalTranscodeTest {
    // 独立主机名，避免与其他测试共享进程级能力缓存。
    private val base = "https://local-transcode-test.example"
    private val track = PlexTrackStream(
        ratingKey = "42",
        title = "Track",
        streamUrl = "$base/api/v1/tracks/42/stream?mediaToken=tok",
        partKey = "/api/v1/tracks/42/stream",
        durationMillis = 180_000L,
        userRating = null,
        sourceAudioCodec = "flac",
        sourceAudioBitrateKbps = 950,
        sourceId = "032",
    )

    private fun policy(limit: LocalStreamingLimit, opus: LocalOpusBitrate = LocalOpusBitrate.KBPS_128) =
        LocalPlaybackPolicy(streamingLimit = limit, opusBitrate = opus, precacheCount = LocalPrecacheCount.THREE)

    private fun caps(oggAvailable: Boolean = true, bitrates: List<Int> = listOf(64, 96, 128, 160, 192, 256), revision: Int = 2) =
        Music032Capabilities(revision, emptySet(), mapOf("ogg" to Music032FormatCaps(oggAvailable, bitrates, emptyList())), emptyList())

    @Test fun cellularLimitTranscodesToServerOpus() {
        val selected = policy(LocalStreamingLimit.KBPS_320)
        val result = track.withLocalPlaybackPolicy(selected, "client")

        assertTrue(track.willTranscodeLocally(selected))
        assertEquals("$base/api/v1/tracks/42/transcode.ogg?mediaToken=tok&bitrate=128", result.streamUrl)
        assertEquals("audio/ogg", result.playbackMimeType)
        assertTrue(result.streamUrl.isLiveTranscodeUrl())
        assertFalse(result.streamUrl.isPlexLiveTranscodeUrl())
    }

    @Test fun maximumAndBelowLimitKeepOriginal() {
        assertEquals(track.streamUrl, track.withLocalPlaybackPolicy(policy(LocalStreamingLimit.MAXIMUM), "client").streamUrl)
        val small = track.copy(sourceAudioCodec = "mp3", sourceAudioBitrateKbps = 256)
        assertFalse(small.willTranscodeLocally(policy(LocalStreamingLimit.KBPS_320)))
        assertEquals(small.streamUrl, small.withLocalPlaybackPolicy(policy(LocalStreamingLimit.KBPS_320), "client").streamUrl)
    }

    @Test fun unknownBitrateFollowsPlexRules() {
        val selected = policy(LocalStreamingLimit.KBPS_320)
        assertFalse(track.copy(sourceAudioCodec = "opus", sourceAudioBitrateKbps = null).willTranscodeLocally(selected))
        assertTrue(track.copy(sourceAudioBitrateKbps = null).willTranscodeLocally(selected))
    }

    @Test fun serverCapabilitiesGateTranscode() {
        val selected = policy(LocalStreamingLimit.KBPS_320)
        assertNull(Music032LocalTranscode.transcodeUrl(track, selected, caps(oggAvailable = false)))
        assertNull(Music032LocalTranscode.transcodeUrl(track, selected, Music032Capabilities.LEGACY))
        // 能力未知时乐观转码。
        assertTrue(Music032LocalTranscode.transcodeUrl(track, selected, null)!!.contains("transcode.ogg"))
    }

    @Test fun opusBitrateMapsToServerWhitelist() {
        assertEquals(128, Music032LocalTranscode.opusBitrate(128, null))
        assertEquals(96, Music032LocalTranscode.opusBitrate(128, caps(bitrates = listOf(64, 96, 160))))
        assertEquals(64, Music032LocalTranscode.opusBitrate(32, caps(bitrates = listOf(64, 96))))
        val url = Music032LocalTranscode.transcodeUrl(track, policy(LocalStreamingLimit.KBPS_320, LocalOpusBitrate.KBPS_256), caps(bitrates = listOf(64, 192)))
        assertTrue(url!!.endsWith("bitrate=192"))
    }

    @Test fun capabilitiesLookupByServerBaseUrl() = runBlocking {
        val key = Music032CapabilitiesStore.Key.of("$base/", "api-token")
        Music032CapabilitiesStore.invalidate(key)
        Music032CapabilitiesStore.ensure(key) {
            """{"apiRevision":2,"transcode":{"formats":{"ogg":{"available":false,"bitrates":[128]}}}}"""
        }
        try {
            assertEquals(false, Music032LocalTranscode.capabilitiesFor(track)?.isFormatAvailable("ogg"))
            assertFalse(track.willTranscodeLocally(policy(LocalStreamingLimit.KBPS_320)))
        } finally {
            Music032CapabilitiesStore.invalidate(key)
        }
    }

    @Test fun seekRestartsLiveStreamWithMillisecondOffset() {
        val live = "$base/api/v1/tracks/42/transcode.ogg?mediaToken=tok&bitrate=128"
        val plan = buildLocalSeekPlan(live, 137_890L, 180_000L)

        assertTrue(plan.restartsLiveTranscode)
        assertEquals("$live&offsetMs=137890", plan.url)
        assertEquals(0L, plan.playerPositionMillis)
        assertEquals(137_890L, plan.logicalBaseMillis)
        assertEquals(137_890L, plan.effectiveTargetMillis)
    }

    @Test fun seekReplacesOffsetAndClampsNearEnd() {
        val live = "$base/api/v1/tracks/42/transcode.ogg?mediaToken=tok&bitrate=128&offsetMs=5000"
        val clamped = buildLocalSeekPlan(live, 200_000L, 180_000L)
        assertEquals(179_000L, clamped.logicalBaseMillis)
        assertTrue(clamped.url.endsWith("offsetMs=179000"))
        assertFalse(clamped.url.contains("offsetMs=5000"))

        val start = buildLocalSeekPlan(live, 0L, 180_000L)
        assertFalse(start.url.contains("offsetMs"))
        assertEquals(0L, start.logicalBaseMillis)
    }

    @Test fun originalStreamKeepsNativeSeek() {
        val plan = buildLocalSeekPlan(track.streamUrl, 42_000L, 180_000L)
        assertFalse(plan.restartsLiveTranscode)
        assertEquals(track.streamUrl, plan.url)
        assertEquals(42_000L, plan.playerPositionMillis)
        assertFalse(track.streamUrl.isLiveTranscodeUrl())
    }
}
