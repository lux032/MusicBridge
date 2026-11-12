package com.lux032.musicbridge.music

import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.TrackCreditsRepository
import com.lux032.musicbridge.lyrics.LyricLine
import com.lux032.musicbridge.lyricsCarryCredits
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackCreditsTest {
    private val track = MusicTrack("9", "Song", "", "", 240000, null, sourceId = "source-a")

    @Test fun structuredCreditsWinAndTagTextFillsMissingRoles() {
        val credits = Music032Json.parseTrackCredits(JSONObject("""{
            "composer": "Tag Composer", "lyricist": "Tag Lyricist", "arranger": " ",
            "credits": [
              {"role": "producer", "artists": [{"id": 5, "name": "P"}]},
              {"role": "composer", "artists": [{"id": 3, "name": "C"}, {"id": 3, "name": "C"}, {"id": 0, "name": "dropped"}]},
              {"role": "arranger", "artists": []}
            ]}"""))
        assertEquals(
            listOf(
                TrackCredit("lyricist", listOf(ArtistRef("", "Tag Lyricist"))),
                TrackCredit("composer", listOf(ArtistRef("3", "C"))),
                TrackCredit("producer", listOf(ArtistRef("5", "P"))),
            ),
            credits,
        )
        assertTrue(Music032Json.parseTrackCredits(JSONObject("""{"id": 9, "title": "Song"}""")).isEmpty())
    }

    @Test fun lyricsThatOpenWithTheirOwnCreditLinesAreDetected() {
        fun lines(vararg text: String) = text.mapIndexed { index, line -> LyricLine(index * 1000L, line) }
        assertTrue(lyricsCarryCredits(lines("Song - Artist", "作词 : A", "作曲 : B", "first line")))
        assertTrue(lyricsCarryCredits(lines("編曲：C", "first line")))
        assertFalse(lyricsCarryCredits(lines("first line", "作曲家の夢", "second line")))
        assertFalse(lyricsCarryCredits(emptyList()))
    }

    @Test fun repositoryReadsTrackDetailOnceAndCachesOnlySuccess() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0
        var fail = false
        server.createContext("/api/v1/tracks/9") { exchange ->
            requests++
            assertEquals("Bearer api-secret", exchange.requestHeaders.getFirst("Authorization"))
            val body = """{"id":9,"title":"Song","credits":[
                {"role":"lyricist","artists":[{"id":3,"name":"Composer"},{"id":7,"name":"Singer"}]},
                {"role":"composer","artists":[{"id":3,"name":"Composer"}]}]}""".toByteArray()
            exchange.sendResponseHeaders(if (fail) 500 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = Music032Client(MusicServerProfile(id = "source-a", baseUrl = "http://127.0.0.1:${server.address.port}", token = "api-secret"))
            val repository = TrackCreditsRepository { id -> client.takeIf { id == "source-a" } }
            runBlocking {
                // Someone else's source is never asked; a failed lookup is empty and not cached.
                assertTrue(repository.load(track.copy(sourceId = "other")).isEmpty())
                fail = true
                assertTrue(repository.load(track).isEmpty())
                fail = false
                val expected = listOf(
                    TrackCredit("lyricist", listOf(ArtistRef("3", "Composer"), ArtistRef("7", "Singer"))),
                    TrackCredit("composer", listOf(ArtistRef("3", "Composer"))),
                )
                assertEquals(expected, repository.load(track))
                assertEquals(expected, repository.load(track))
            }
            assertEquals(2, requests)
        } finally { server.stop(0) }
    }

    @Test fun artistCreditsPageCarriesRoleFilterPagingAndThisArtistsRoles() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val queries = mutableListOf<String>()
        server.createContext("/api/v1/artists/3/credits") { exchange ->
            queries += exchange.requestURI.rawQuery
            val body = """{"total":12,"limit":30,"offset":30,
                "roles":[{"role":"lyricist","count":4},{"role":"composer","count":12},{"role":"arranger","count":0}],
                "items":[{"id":9,"title":"Song","album":"OST","credits":[
                  {"role":"lyricist","artists":[{"id":7,"name":"Singer"}]},
                  {"role":"composer","artists":[{"id":3,"name":"Composer"}]},
                  {"role":"arranger","artists":[{"id":3,"name":"Composer"},{"id":8,"name":"Band"}]}]}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = Music032Client(MusicServerProfile(id = "source-a", baseUrl = "http://127.0.0.1:${server.address.port}", token = "api-secret"))
            val page = runBlocking { client.fetchArtistCredits("3", role = "composer", limit = 30, offset = 30) }
            assertEquals(listOf("limit=30&offset=30&role=composer"), queries)
            assertEquals(12, page.total)
            assertEquals(listOf("lyricist" to 4, "composer" to 12), page.roles)
            val entry = page.items.single()
            assertEquals("9", entry.track.ratingKey)
            assertEquals("source-a", entry.track.sourceId)
            assertEquals(listOf("composer", "arranger"), entry.roles)
        } finally { server.stop(0) }
    }
}
