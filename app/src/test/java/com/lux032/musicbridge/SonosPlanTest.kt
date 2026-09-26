package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032Capabilities
import com.lux032.musicbridge.music.Music032FormatCaps
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class SonosPlanTest {
    private fun track(codec: String?, rate: Int? = null, url: String = "https://h/api/v1/tracks/12/stream?mediaToken=x") = PlexTrackStream(
        "12", "Track", url, "/api/v1/tracks/12/stream", 120000, null,
        sourceAudioCodec = codec, sourceAudioSampleRate = rate)
    private val available = Music032Capabilities(2, emptySet(), mapOf("mp3" to Music032FormatCaps(true, listOf(320), emptyList())), emptyList())
    private val unavailable = Music032Capabilities(2, emptySet(), mapOf("mp3" to Music032FormatCaps(false, emptyList(), emptyList())), emptyList())
    private fun plan(codec: String?, rate: Int?, opus: OpusTranscodeMode = OpusTranscodeMode.DIRECT_MP3,
                     hi: SonosHiResTranscodeMode = SonosHiResTranscodeMode.TRANSCODE_MP3,
                     caps: Music032Capabilities? = null) = plan032(track(codec, rate), opus, hi, caps)

    @Test fun opusDirectModeUsesLiveMp3() {
        for (mode in listOf(OpusTranscodeMode.DIRECT_MP3)) {
            val result = plan("opus", 48000, mode).getOrThrow()
            assertEquals(SonosPlanKind.LIVE_MP3, result.kind)
            assertEquals("https://h/api/v1/tracks/12/transcode.mp3?mediaToken=x&bitrate=320", result.url)
            assertEquals("audio/mpeg", result.mime)
        }
        assertEquals(SonosPlanKind.LIVE_MP3, predictSonosKind(track("opus"), OpusTranscodeMode.DIRECT_MP3,
            SonosHiResTranscodeMode.TRANSCODE_MP3, null))
    }

    @Test fun hiResAndOriginalMappings() {
        assertEquals(SonosPlanKind.LIVE_MP3, plan("flac", 96000).getOrThrow().kind)
        assertEquals(SonosPlanKind.ORIGINAL, plan("flac", 96000, hi = SonosHiResTranscodeMode.DIRECT_ORIGINAL).getOrThrow().kind)
        assertEquals(SonosPlanKind.ORIGINAL, plan("flac", 44100).getOrThrow().kind)
        assertEquals("audio/flac", plan("flac", 44100).getOrThrow().mime)
        assertEquals(SonosPlanKind.ORIGINAL, plan(null, null).getOrThrow().kind)
        assertNull(plan(null, null).getOrThrow().mime)
    }

    @Test fun explicitUnavailabilityBlocksOpusButFallsBackForHiRes() {
        for (caps in listOf(Music032Capabilities.LEGACY, unavailable)) {
            assertEquals(Strings.music032SonosTranscodeUnavailable, plan("opus", 48000, caps = caps).exceptionOrNull()?.message)
            assertEquals(SonosPlanKind.ORIGINAL, plan("flac", 96000, caps = caps).getOrThrow().kind)
        }
        assertEquals(SonosPlanKind.LIVE_MP3, plan("opus", 48000, caps = available).getOrThrow().kind)
        assertTrue(plan032(track("opus", url = "https://other/stream"), OpusTranscodeMode.DIRECT_MP3,
            SonosHiResTranscodeMode.TRANSCODE_MP3, null).isFailure)
    }

    @Test fun flacPlansAndCapabilityFallbacks() {
        val flacCaps = Music032Capabilities(2, emptySet(), mapOf(
            "flac" to Music032FormatCaps(true, emptyList(), listOf(48000)),
            "mp3" to Music032FormatCaps(true, listOf(320), emptyList())), emptyList())
        val mp3Only = flacCaps.copy(transcodeFormats = flacCaps.transcodeFormats +
            ("flac" to Music032FormatCaps(false, emptyList(), emptyList())))
        val neither = mp3Only.copy(transcodeFormats = mp3Only.transcodeFormats +
            ("mp3" to Music032FormatCaps(false, emptyList(), emptyList())))
        val opusFlac = plan("opus", 48000, OpusTranscodeMode.PROXY_FLAC, caps = flacCaps).getOrThrow()
        assertEquals(SonosPlanKind.STATIC_FLAC, opusFlac.kind)
        assertTrue(opusFlac.needsWarmup)
        assertEquals("https://h/api/v1/tracks/12/transcode.flac?mediaToken=x", opusFlac.url)
        val hiRes = plan("flac", 96000, hi = SonosHiResTranscodeMode.TRANSCODE_FLAC_48K, caps = flacCaps).getOrThrow()
        assertEquals("https://h/api/v1/tracks/12/transcode.flac?mediaToken=x&maxSampleRate=48000", hiRes.url)
        assertEquals("audio/flac", hiRes.mime)
        assertEquals(SonosPlanKind.STATIC_FLAC, plan("opus", 48000, OpusTranscodeMode.PROXY_FLAC).getOrThrow().kind)
        assertEquals(SonosPlanKind.LIVE_MP3, plan("opus", 48000, OpusTranscodeMode.PROXY_FLAC, caps = mp3Only).getOrThrow().kind)
        assertEquals(SonosPlanKind.LIVE_MP3, plan("flac", 96000, hi = SonosHiResTranscodeMode.TRANSCODE_FLAC_48K, caps = mp3Only).getOrThrow().kind)
        assertTrue(plan("opus", 48000, OpusTranscodeMode.PROXY_FLAC, caps = neither).isFailure)
        assertEquals(SonosPlanKind.ORIGINAL, plan("flac", 96000, hi = SonosHiResTranscodeMode.TRANSCODE_FLAC_48K, caps = neither).getOrThrow().kind)
        assertEquals(SonosPlanKind.STATIC_FLAC, predictSonosKind(track("opus"), OpusTranscodeMode.PROXY_FLAC,
            SonosHiResTranscodeMode.TRANSCODE_MP3, flacCaps))
    }

    @Test fun nextSequentialStaticFlacOnly() {
        val tracks = listOf(track("flac", 44100), track("opus"))
        fun candidate(sequential: Boolean, mode: OpusTranscodeMode) = next032WarmupCandidate(
            tracks, 0, sequential, mode, SonosHiResTranscodeMode.TRANSCODE_MP3) { null }
        assertEquals(tracks[1], candidate(true, OpusTranscodeMode.PROXY_FLAC))
        assertNull(candidate(false, OpusTranscodeMode.PROXY_FLAC))
        assertNull(candidate(true, OpusTranscodeMode.DIRECT_MP3))
    }

    @Test fun hydrationOnlyForMissingCodecOrMissingLosslessSampleRate() {
        assertTrue(needs032SonosHydration(track(null)))
        assertTrue(needs032SonosHydration(track("flac")))
        assertFalse(needs032SonosHydration(track("flac", 44100)))
        assertFalse(needs032SonosHydration(track("opus")))
        val sparse = track("flac")
        val fresh = track("FLAC", 96000, "https://h/api/v1/tracks/12/stream?mediaToken=fresh")
        val hydrated = merge032Hydration(sparse, fresh)
        assertEquals(96000, hydrated.sourceAudioSampleRate)
        assertEquals("https://h/api/v1/tracks/12/stream?mediaToken=fresh", hydrated.streamUrl)
        assertEquals(hydrated, merge032Hydration(sparse, hydrated))
        assertEquals(sparse, merge032Hydration(sparse, null))
    }

    @Test fun canceledInitiatorDoesNotCancelAnotherHydrationWaiter() = runBlocking {
        withTimeout(5_000) {
            val cache = Music032HydrationCache(this)
            val fetched = CompletableDeferred<PlexTrackStream>()
            val started = CompletableDeferred<Unit>()
            var calls = 0
            val sparse = track("flac")
            val initiator = async {
                cache.hydrate(sparse) { calls++; started.complete(Unit); fetched.await() }
            }
            started.await()
            val waiter = async { cache.hydrate(sparse) { error("duplicate fetch") } }
            initiator.cancel()
            val fresh = track("flac", 96000)
            fetched.complete(fresh)
            assertEquals(96000, waiter.await().sourceAudioSampleRate)
            assertEquals(1, calls)
        }
    }

    @Test fun failedHydrationExpiresAfterTwoMinutes() = runBlocking {
        var time = 1000L
        val cache = Music032HydrationCache(this) { time }
        val sparse = track("flac")
        var calls = 0
        cache.hydrate(sparse) { calls++; null }
        time += 119_999
        cache.hydrate(sparse) { calls++; track("flac", 96000) }
        assertEquals(1, calls)
        time++
        assertEquals(96000, cache.hydrate(sparse) { calls++; track("flac", 96000) }.sourceAudioSampleRate)
        assertEquals(2, calls)
    }

    @Test fun automaticAdvanceRetainsOnlyNextTrackPlan() {
        assertTrue(retainSonosPlanForTrack("032", "12", track("opus").copy(sourceId = "032")))
        assertFalse(retainSonosPlanForTrack("032", "11", track("opus").copy(sourceId = "032")))
    }

    @Test fun predictedLiveButActualOriginalUsesNativeSeek() {
        assertFalse(shouldRestart032Seek(true, SonosPlanKind.ORIGINAL))
        assertTrue(shouldRestart032Seek(true, SonosPlanKind.LIVE_MP3))
    }

    @Test fun fallbackSuffixIsOnlyAppliedWhenFlacActuallyFailed() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE)
            assertEquals("已推送", sonosPushMessage("已推送", false))
            assertEquals("已推送（FLAC 准备失败，已改用 MP3）", sonosPushMessage("已推送", true))
            assertEquals("FLAC 准备失败，已改用 MP3", Strings.music032FlacFallback)
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun playlistMessageClearsPreviousFallbackOnlyFor032() {
        assertEquals("正在连续播放：下一首", playlistTrackMessage("下一首", is032 = true, fellBackFromFlac = false))
        assertEquals("正在连续播放：下一首（${Strings.music032FlacFallback}）",
            playlistTrackMessage("下一首", is032 = true, fellBackFromFlac = true))
        assertNull(playlistTrackMessage("Plex 曲目", is032 = false, fellBackFromFlac = true))
    }

    @Test fun seekAfterPushOnlyOriginalOrStatic() {
        assertFalse(shouldSonosSeekAfterPush(SonosPlanKind.LIVE_MP3, 1000))
        assertTrue(shouldSonosSeekAfterPush(SonosPlanKind.ORIGINAL, 1000))
        assertTrue(shouldSonosSeekAfterPush(SonosPlanKind.STATIC_FLAC, 1000))
        assertFalse(shouldSonosSeekAfterPush(SonosPlanKind.ORIGINAL, 0))
    }
}
