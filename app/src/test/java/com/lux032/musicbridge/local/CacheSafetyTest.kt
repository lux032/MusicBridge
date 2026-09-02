package com.lux032.musicbridge.local

import android.net.Uri
import androidx.media3.common.ParserException
import androidx.media3.exoplayer.source.UnrecognizedInputFormatException
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CacheSafetyTest {
    @Test fun failingDataSourceUsesExplicitMissingSpanFailure() {
        val error = missingTranscodeCacheSpanException()
        assertEquals("Cache span evicted or missing for transcode stream", error.message)
    }

    @Test fun singleCompleteSpanSatisfiesInvariant() {
        assertTrue(transcodeCacheInvariantHolds(true, listOf(0L to 1234L), 1234L))
    }

    @Test fun incompleteOrFragmentedCacheFailsInvariant() {
        assertFalse(transcodeCacheInvariantHolds(false, listOf(0L to 1234L), 1234L))
        assertFalse(transcodeCacheInvariantHolds(true, listOf(0L to 600L, 600L to 634L), 1234L))
        assertFalse(transcodeCacheInvariantHolds(true, listOf(10L to 1234L), 1234L))
        assertFalse(transcodeCacheInvariantHolds(true, listOf(0L to 1200L), 1234L))
    }

    @Test fun parsesAndDecodesPlexEndpoint() {
        val endpoint = parsePlexTranscodeEndpoint(
            "https://plex.example:32400/music/:/transcode/universal/start.ogg?X-Plex-Token=a%2Bb%20c",
        )
        assertEquals("https://plex.example:32400", endpoint?.serverUri)
        assertEquals("a+b c", endpoint?.token)
    }

    @Test fun transcodeUpstreamRequestInjectsSessionAndPreservesCacheKey() {
        val result = transcodeUpstreamRequest(transcodeUrl, "stable-cache-key", "fg-test-session")

        assertTrue(result.url.contains("session=fg-test-session"))
        assertTrue(result.url.contains("X-Plex-Session-Identifier=fg-test-session"))
        assertEquals("stable-cache-key", result.cacheKey)
    }

    @Test fun foregroundSessionRotatesOnlyForNewLoads() {
        var generated = 0
        val newLoad = foregroundTranscodeSessionId(0L, "fg-old") { "fg-new-${++generated}" }
        val resumedLoad = foregroundTranscodeSessionId(4096L, "fg-old") { "fg-new-${++generated}" }

        assertEquals("fg-new-1", newLoad)
        assertEquals("fg-old", resumedLoad)
        assertEquals(1, generated)
    }

    @Test fun transcodeSeekShortCircuitRequiresSameSecondAndLiveUpstream() {
        assertTrue(shouldShortCircuitTranscodeSeek(true, false, 1_900L, 1_000L))
        assertFalse(shouldShortCircuitTranscodeSeek(true, false, 2_000L, 1_000L))
        assertFalse(shouldShortCircuitTranscodeSeek(false, false, 1_900L, 1_000L))
        assertFalse(shouldShortCircuitTranscodeSeek(true, true, 1_900L, 1_000L))
    }

    @Test fun zeroByteTranscodeParsingFailuresAreFatalCandidates() {
        assertTrue(isFatalZeroByteTranscodeLoad(transcodeUrl, 0L, EOFException()))
        assertTrue(isFatalZeroByteTranscodeLoad(transcodeUrl, 0L, java.io.IOException(EOFException())))
        assertTrue(
            isFatalZeroByteTranscodeLoad(
                transcodeUrl,
                0L,
                ParserException.createForMalformedContainer("malformed", null),
            ),
        )
        assertTrue(
            isFatalZeroByteTranscodeLoad(
                transcodeUrl,
                0L,
                UnrecognizedInputFormatException("unknown", Uri.parse(transcodeUrl)),
            ),
        )
        assertFalse(isFatalZeroByteTranscodeLoad(transcodeUrl, 1L, EOFException()))
        assertFalse(isFatalZeroByteTranscodeLoad("https://plex.example/library/parts/1/file.flac", 0L, EOFException()))
    }

    @Test fun transcode4xxResponsesAreFatalCandidates() {
        assertTrue(isFatalTranscodeHttpResponse(400))
        assertTrue(isFatalTranscodeHttpResponse(499))
        assertFalse(isFatalTranscodeHttpResponse(399))
        assertFalse(isFatalTranscodeHttpResponse(500))
    }

    private companion object {
        const val transcodeUrl =
            "https://plex.example/music/:/transcode/universal/start.ogg?X-Plex-Token=token"
    }
}
