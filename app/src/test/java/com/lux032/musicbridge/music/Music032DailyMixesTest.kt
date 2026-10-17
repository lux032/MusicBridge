package com.lux032.musicbridge.music

import com.lux032.musicbridge.dailyMixPlaylistKey
import com.lux032.musicbridge.dailyMixPlaylistResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032DailyMixesTest {
    private val base = "https://example.org"
    private val mediaUrl: (String) -> String? = { Music032Json.mediaUrl(it, base, "token") }
    private val parseTrack: (JSONObject) -> com.lux032.musicbridge.plex.PlexTrackStream =
        { Music032Json.parseTrack(it, base, "token", "032") }

    private val mixJson = """
        {"id":12,"day":"2026-10-17","slot":0,"status":"active","title":"清晨摇滚微风","description":"吉他声里的早晨",
         "themeType":"mood_style","themeTypeLabel":"情绪 × 风格","themeKey":"ms:relaxed:rock",
         "tags":[{"kind":"mood","tag":"relaxed","label":"宁静放松"},{"kind":"style","tag":"rock","label":"摇滚"}],
         "trackCount":25,"durationMillis":5880000,
         "coverUrls":["/api/v1/albums/3/artwork","/api/v1/albums/4/artwork"],
         "rating":-1,"ratingReasons":["theme","flow"],"savedPlaylistId":7,"createdAt":"x"}
    """.trimIndent()

    @Test fun parsesListWithStatusAndAbsoluteCovers() {
        val root = JSONObject("""{"status":"ready","day":"2026-10-17","replacementsLeft":2,"items":[$mixJson]}""")
        val list = Music032DailyMixJson.parseList(root, mediaUrl)
        assertEquals(Music032DailyMixStatus.Ready, list.status)
        assertEquals(2, list.replacementsLeft)
        val mix = list.items.single()
        assertEquals(12L, mix.id)
        assertTrue(mix.isActive)
        assertEquals(listOf("宁静放松", "摇滚"), mix.tags.map { it.label })
        assertTrue(mix.coverUrls.all { it.startsWith(base) })
        assertEquals(-1, mix.rating)
        assertEquals(listOf("theme", "flow"), mix.ratingReasons)
        assertEquals(7L, mix.savedPlaylistId)
    }

    @Test fun generatingAndUnknownStatuses() {
        val generating = Music032DailyMixJson.parseList(JSONObject("""{"status":"generating","items":[]}"""), mediaUrl)
        assertEquals(Music032DailyMixStatus.Generating, generating.status)
        assertTrue(generating.items.isEmpty())
        assertEquals(Music032DailyMixStatus.Disabled, Music032DailyMixJson.parseStatus("disabled"))
        assertEquals(Music032DailyMixStatus.NotReady, Music032DailyMixJson.parseStatus("something_new"))
    }

    @Test fun detailDropsMissingTracksAndKeepsMarkers() {
        val root = JSONObject(
            """{"mix":$mixJson,"tracks":[
              {"position":0,"bucket":"familiar","track":{"id":101,"title":"A","albumId":3,"artist":"X","durationMillis":200000}},
              {"position":1,"bucket":"explore","blocked":true,"track":{"id":102,"title":"B","albumId":4}},
              {"position":2,"bucket":"explore","track":{"id":103,"title":"C","missing":true}}
            ]}""",
        )
        val detail = Music032DailyMixJson.parseDetail(root, mediaUrl, parseTrack)
        assertEquals(listOf("101", "102"), detail.tracks.map { it.ratingKey })
        assertEquals(setOf("102"), detail.exploreTrackIds)
        assertEquals(setOf("102"), detail.blockedTrackIds)
        assertEquals(Music032PlaybackContext(12, 1), detail.tracks[1].playbackContext)

        val result = dailyMixPlaylistResult("032", detail)
        assertEquals(dailyMixPlaylistKey(12), result.playlist.ratingKey)
        assertEquals("清晨摇滚微风", result.playlist.title)
        assertEquals(2, result.playlist.leafCount)
        // Synthetic key: never treated as an editable server playlist.
        assertNull(result.playlist.ratingKey.toLongOrNull())
    }

    @Test fun ratingBodySendsReasonsOnlyForDislikes() {
        val dislike = Music032DailyMixJson.ratingBody(-1, listOf("theme", "bogus", "theme", "flow"))
        assertEquals(-1, dislike.getInt("value"))
        val reasons = dislike.getJSONArray("reasons")
        assertEquals(listOf("theme", "flow"), (0 until reasons.length()).map { reasons.getString(it) })

        val like = Music032DailyMixJson.ratingBody(1, listOf("theme"))
        assertEquals(0, like.getJSONArray("reasons").length())
        assertFalse(Music032DailyMixJson.ratingBody(0, emptyList()).getJSONArray("reasons").length() > 0)
    }
}
