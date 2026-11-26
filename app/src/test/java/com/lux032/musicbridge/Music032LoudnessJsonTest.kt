package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032Json
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032LoudnessJsonTest {
    @Test
    fun parseCapabilitiesReadsLoudnessFeatureAndStats() {
        val raw = """
            {
              "apiRevision": 2,
              "features": { "loudness": true, "dailyMixes": true },
              "loudness": {
                "available": true,
                "analyzedTracks": 18125,
                "totalTracks": 18126,
                "libraryMedianLufs": -9.0
              }
            }
        """.trimIndent()

        val caps = Music032Json.parseCapabilities(raw)
        assertTrue(caps.hasFeature("loudness"))
        assertTrue(caps.hasLoudness)
        val loudness = caps.loudness
        assertNotNull(loudness)
        assertTrue(loudness!!.available)
        assertEquals(18125L, loudness.analyzedTracks)
        assertEquals(18126L, loudness.totalTracks)
        assertEquals(-9.0, loudness.libraryMedianLufs!!, 1e-6)
    }

    @Test
    fun parseCapabilitiesHandlesExplicitZeroAndMissingMedianAndLegacy() {
        val zeroMedian = """
            {
              "apiRevision": 2,
              "features": { "loudness": true },
              "loudness": {
                "available": true,
                "analyzedTracks": 10,
                "totalTracks": 10,
                "libraryMedianLufs": 0.0
              }
            }
        """.trimIndent()
        assertEquals(0.0, Music032Json.parseCapabilities(zeroMedian).loudness?.libraryMedianLufs!!, 1e-6)

        val missingMedian = """
            {
              "apiRevision": 2,
              "features": { "loudness": true },
              "loudness": {
                "available": false,
                "analyzedTracks": 0,
                "totalTracks": 100
              }
            }
        """.trimIndent()
        val missingCaps = Music032Json.parseCapabilities(missingMedian)
        assertTrue(missingCaps.hasLoudness)
        assertFalse(missingCaps.loudness!!.available)
        assertNull(missingCaps.loudness!!.libraryMedianLufs)

        val legacy = """{ "apiRevision": 2, "features": { "dailyMixes": true } }"""
        val legacyCaps = Music032Json.parseCapabilities(legacy)
        assertFalse(legacyCaps.hasLoudness)
        assertNull(legacyCaps.loudness)
    }

    @Test
    fun parseTrackPreservesExplicitZeroTruePeakAndRejectsInvalidValues() {
        val json = JSONObject(
            """
            {
              "id": 42,
              "title": "Test Track",
              "albumId": 7,
              "loudnessLufs": -11.4,
              "truePeakDb": 0.0,
              "loudnessRange": 6.2,
              "albumLoudnessLufs": -10.8,
              "albumTruePeakDb": 0
            }
            """.trimIndent(),
        )
        val track = Music032Json.parseTrack(json, "http://127.0.0.1:8080", "tok", "srv1")
        assertEquals(-11.4, track.loudnessLufs!!, 1e-6)
        assertEquals(0.0, track.truePeakDb!!, 1e-6)
        assertEquals(6.2, track.loudnessRange!!, 1e-6)
        assertEquals(-10.8, track.albumLoudnessLufs!!, 1e-6)
        assertEquals(0.0, track.albumTruePeakDb!!, 1e-6)

        val invalid = JSONObject(
            """
            {
              "id": 43,
              "title": "Missing Loudness",
              "loudnessLufs": null,
              "truePeakDb": "NaN",
              "loudnessRange": "Infinity",
              "albumLoudnessLufs": "bad"
            }
            """.trimIndent(),
        )
        val fallback = Music032Json.parseTrack(invalid, "http://127.0.0.1:8080", "tok", "srv1")
        assertNull(fallback.loudnessLufs)
        assertNull(fallback.truePeakDb)
        assertNull(fallback.loudnessRange)
        assertNull(fallback.albumLoudnessLufs)
        assertNull(fallback.albumTruePeakDb)
    }
}
