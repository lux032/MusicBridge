package com.lux032.musicbridge.local

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
}
