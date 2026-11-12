package com.lux032.musicbridge.music

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Music032AiPlaylistsTest {
    private val base = "https://example.org"
    private val parseTrack: (JSONObject) -> MusicTrack = { Music032Json.parseTrack(it, base, "token", "032") }

    @Test fun requestTrimsPromptAndOmitsDefaultTrackCount() {
        val body = Music032AiPlaylistJson.request("  深夜爵士  ", 0)
        assertEquals("深夜爵士", body.getString("prompt"))
        assertFalse(body.has("trackCount"))
        assertEquals(50, Music032AiPlaylistJson.request("x", 80).getInt("trackCount"))
        assertEquals(Music032AiPlaylistJson.MAX_PROMPT_LENGTH, Music032AiPlaylistJson.request("长".repeat(300), 0).getString("prompt").length)
    }

    @Test fun parsesTracksConditionsAndExploreBucket() {
        val root = JSONObject(
            """{"title":"深夜爵士","description":"微醺的夜晚","titleSource":"llm",
              "conditions":{"moods":[{"kind":"mood","tag":"relaxed","label":"宁静放松"}],"styles":[{"kind":"style","tag":"jazz","label":"爵士/融合"}],
                "people":[{"id":7,"name":"Artist 7"}],"works":[],"energy":"low","familiarity":"mixed"},
              "trackCount":3,"durationMillis":600000,"note":"",
              "tracks":[
                {"position":0,"bucket":"familiar","track":{"id":101,"title":"A","albumId":3,"artist":"X","durationMillis":200000}},
                {"position":1,"bucket":"explore","track":{"id":102,"title":"B","albumId":4}},
                {"position":2,"bucket":"explore","track":{"id":103,"title":"C","missing":true}}
              ]}""",
        )
        val playlist = Music032AiPlaylistJson.parse(root, parseTrack)
        assertEquals("深夜爵士", playlist.title)
        assertEquals("微醺的夜晚", playlist.description)
        assertEquals(listOf("101", "102"), playlist.tracks.map { it.ratingKey })
        assertEquals(setOf("102"), playlist.exploreTrackIds)
        assertEquals(listOf("Artist 7", "宁静放松", "爵士/融合"), playlist.conditions)
        assertTrue(playlist.tracks.all { it.playbackContext == null })
        assertNull(playlist.note.takeIf { it.isNotEmpty() })
    }
}
