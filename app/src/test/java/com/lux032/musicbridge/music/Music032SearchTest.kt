package com.lux032.musicbridge.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032SearchTest {
    private val base = "https://music.example.org"
    private val mediaToken = "tok"
    private val section = LibrarySection("032", "Music", "artist")

    @Test
    fun parsesOverviewWithBestMatchAndAllCategories() {
        val json = """
            {
              "query": "星街彗星",
              "category": "",
              "bestMatch": {
                "type": "artists",
                "id": 42,
                "title": "Suisei Hoshimachi",
                "subtitle": "",
                "imageUrl": "/api/v1/artists/42/image"
              },
              "artists": {
                "items": [
                  { "id": 42, "name": "Suisei Hoshimachi", "imageUrl": "/api/v1/artists/42/image", "albumCount": 4, "trackCount": 48, "isFavorite": true }
                ],
                "total": 1, "limit": 5, "offset": 0
              },
              "albums": {
                "items": [
                  { "id": 100, "title": "Still Still Stellar", "artist": "Hoshimachi Suisei", "artworkUrl": "/api/v1/albums/100/artwork", "trackCount": 12 }
                ],
                "total": 4, "limit": 6, "offset": 0
              },
              "tracks": {
                "items": [
                  { "id": 700, "title": "Ghost", "artist": "Hoshimachi Suisei", "album": "Still Still Stellar", "albumId": 100, "durationMillis": 282000, "artworkUrl": "/api/v1/albums/100/artwork" }
                ],
                "total": 12, "limit": 10, "offset": 0
              },
              "composers": {
                "items": [
                  { "id": 42, "name": "Suisei Hoshimachi", "trackCount": 3 }
                ],
                "total": 1, "limit": 5, "offset": 0
              }
            }
        """.trimIndent()

        val result = Music032SearchJson.parse(json, base, mediaToken, section, "srv1")
        assertEquals("星街彗星", result.query)
        assertFalse(result.isEmpty)

        val best = result.bestMatch
        assertNotNull(best)
        assertEquals(SearchCategory.Artists, best!!.category)
        assertEquals("42", best.id)
        assertEquals("Suisei Hoshimachi", best.title)
        assertEquals("https://music.example.org/api/v1/artists/42/image?mediaToken=tok", best.imageUrl)

        assertEquals(1, result.artists.total)
        assertTrue(result.artists.items.single().isFavorite)
        assertEquals(4, result.albums.total)
        assertEquals("Still Still Stellar", result.albums.items.single().title)
        assertEquals(12, result.tracks.total)
        assertEquals("100", result.tracks.items.single().albumRatingKey)
        assertEquals(1, result.composers.total)
        assertEquals(3, result.composers.items.single().trackCount)
    }

    @Test
    fun parsesCategoryPageWithNullBestMatchAndTrackAlbumId() {
        val json = """
            {
              "query": "Ghost",
              "category": "tracks",
              "bestMatch": { "type": "unknown", "id": 1, "title": "Ignore" },
              "artists": { "items": [], "total": 0, "limit": 5, "offset": 0 },
              "albums": { "items": [], "total": 0, "limit": 6, "offset": 0 },
              "tracks": {
                "items": [
                  { "id": 701, "title": "Ghost (Live)", "albumId": 101 }
                ],
                "total": 12, "limit": 100, "offset": 10
              },
              "composers": { "items": [], "total": 0, "limit": 5, "offset": 0 }
            }
        """.trimIndent()

        val result = Music032SearchJson.parse(json, base, mediaToken, section, "srv1")
        assertEquals("tracks", result.category)
        assertNull(result.bestMatch)
        assertEquals(10, result.tracks.offset)
        assertEquals(100, result.tracks.limit)
        assertEquals(12, result.tracks.total)
        assertEquals("101", result.tracks.items.single().albumRatingKey)
        assertEquals(SearchCategory.Tracks, SearchCategory.fromApi("TRACKS"))
        assertNull(SearchCategory.fromApi("playlists"))
    }

    @Test
    fun detectsEmptyResults() {
        val json = """
            {
              "query": "absent",
              "category": "",
              "bestMatch": null,
              "artists": { "items": [], "total": 0, "limit": 5, "offset": 0 },
              "albums": { "items": [], "total": 0, "limit": 6, "offset": 0 },
              "tracks": { "items": [], "total": 0, "limit": 10, "offset": 0 },
              "composers": { "items": [], "total": 0, "limit": 5, "offset": 0 }
            }
        """.trimIndent()

        val result = Music032SearchJson.parse(json, base, mediaToken, section, "srv1")
        assertTrue(result.isEmpty)
        assertNull(result.bestMatch)
    }
}
