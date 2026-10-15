package com.lux032.musicbridge.music

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032PlaylistsTest {
    private val base = "https://example.org"
    private fun playlist(json: JSONObject) = Music032Json.parsePlaylist(json, base, "token", "032")

    @Test fun emptyCreateOmitsSkipPolicySoOlderServersAcceptIt() {
        val body = Music032PlaylistRequests.create("  Night Drive ", " calm ", emptyList())
        assertEquals("Night Drive", body.getString("name"))
        assertEquals("calm", body.getString("description"))
        // The server rejects unknown JSON fields: neither key may appear without tracks.
        assertFalse(body.has("trackIds"))
        assertFalse(body.has("invalidTracks"))
    }

    @Test fun createWithTracksSkipsInvalidAndDropsNonNumericIds() {
        val body = Music032PlaylistRequests.create("Mix", "", listOf("7", "x", " 9 ", "7"))
        val ids = body.getJSONArray("trackIds")
        assertEquals(listOf(7L, 9L, 7L), (0 until ids.length()).map { ids.getLong(it) })
        assertEquals("skip", body.getString("invalidTracks"))
    }

    @Test fun createWithOnlyUnusableIdsFallsBackToStrictCreate() {
        val body = Music032PlaylistRequests.create("Mix", "", listOf("ai:1", ""))
        assertFalse(body.has("trackIds"))
        assertFalse(body.has("invalidTracks"))
    }

    @Test fun parsesBothCreateResponseShapes() {
        val strict = Music032PlaylistRequests.parseCreated(
            JSONObject("""{"id":5,"name":"A","description":"d","itemCount":0,"revision":0}"""),
            ::playlist,
        )
        assertEquals("5", strict.playlist.ratingKey)
        assertNull(strict.stats)

        val skipping = Music032PlaylistRequests.parseCreated(
            JSONObject("""{"playlist":{"id":6,"name":"B","itemCount":2,"revision":1},"stats":{"added":2,"skippedInvalid":1,"total":2,"revision":1}}"""),
            ::playlist,
        )
        assertEquals("6", skipping.playlist.ratingKey)
        assertEquals(2, skipping.stats!!.added)
        assertEquals(1, skipping.stats!!.skippedInvalid)
        assertEquals(1L, skipping.stats!!.revision)
    }

    @Test fun parsesItemResult() {
        val result = Music032PlaylistRequests.parseItemResult(
            JSONObject("""{"added":1,"removed":0,"skippedDuplicate":2,"skippedInvalid":0,"total":12,"revision":9}"""),
        )
        assertEquals(Music032PlaylistItemResult(added = 1, skippedDuplicate = 2, total = 12, revision = 9), result)
        assertNull(Music032PlaylistRequests.parseItemResult(JSONObject("{}")).revision)
    }

    @Test fun playlistCarriesDescriptionAndRevision() {
        val parsed = playlist(JSONObject("""{"id":3,"name":" Late ","description":" for 2am ","itemCount":4,"revision":7,"artworkUrl":"/api/v1/artwork/1"}"""))
        assertEquals("Late", parsed.title)
        assertEquals("for 2am", parsed.description)
        assertEquals(7L, parsed.revision)
        assertTrue(parsed.thumbUrl!!.endsWith("/api/v1/artwork/1?mediaToken=token"))
        assertNull(playlist(JSONObject("""{"id":3,"name":"x"}""")).revision)
    }
}
