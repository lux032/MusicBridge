package com.lux032.musicbridge.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Music032SimilarTest {
    @Test
    fun parseSimilarTracksExtractsTrackScoreDistanceAndReasons() {
        val body = """
            {
              "items": [
                {
                  "track": {
                    "id": 42,
                    "title": "星座になれたら",
                    "artist": "結束バンド",
                    "album": "結束バンド",
                    "albumId": 7,
                    "durationMillis": 258000,
                    "streamUrl": "/api/v1/tracks/42/stream",
                    "artworkUrl": "/api/v1/artwork/7",
                    "codec": "flac"
                  },
                  "score": 0.82,
                  "distance": 0.18,
                  "reasons": ["soundsSimilar", "genre", "sameAlbum"]
                }
              ]
            }
        """.trimIndent()

        val items = Music032SimilarJson.parse(
            body = body,
            baseUrl = "https://music.example.com",
            mediaToken = "media-tok",
            sourceId = "srv-1",
        )

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals("42", item.track.ratingKey)
        assertEquals("星座になれたら", item.track.title)
        assertEquals("srv-1", item.track.sourceId)
        assertEquals("https://music.example.com/api/v1/tracks/42/stream?mediaToken=media-tok", item.track.streamUrl)
        assertEquals(0.82, item.score, 0.0001)
        assertEquals(0.18, item.distance, 0.0001)
        assertEquals(listOf("soundsSimilar", "genre", "sameAlbum"), item.reasons)
    }

    @Test
    fun buildQueryClampsLimitDedupesAndCapsExcludeIds() {
        val exclude = (1L..250L).toList() + listOf(1L, -5L, 0L, 2L)
        val query = Music032SimilarJson.buildQuery(limit = 500, excludeIds = exclude, diverse = true)
        assert(query.startsWith("limit=100&diverse=1&exclude="))
        val excludePart = query.substringAfter("exclude=").split(",")
        assertEquals(200, excludePart.size)
        assertEquals("1", excludePart.first())
        assertEquals("200", excludePart.last())
    }

    @Test
    fun parseCapabilitiesReadsSimilarityMethod() {
        val hybridJson = """{"apiRevision":3,"features":{"similarTracks":true},"similarity":{"method":"hybrid","distanceRange":[0,1]}}"""
        val hybrid = Music032Json.parseCapabilities(hybridJson)
        assertEquals("hybrid", hybrid.similarityMethod)
        assert(hybrid.hasFeature("similarTracks"))

        val legacyJson = """{"apiRevision":2,"features":{"similarTracks":true}}"""
        val legacy = Music032Json.parseCapabilities(legacyJson)
        assertNull(legacy.similarityMethod)
    }
}
