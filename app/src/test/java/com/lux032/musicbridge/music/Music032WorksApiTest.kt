package com.lux032.musicbridge.music

import com.lux032.musicbridge.MusicServerProfile
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Music032WorksApiTest {
    @Test fun realWirePathsAuthenticationRotationAndPlayableSourceIdentity() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        server.createContext("/api/v1/") { exchange ->
            assertEquals("Bearer api-secret", exchange.requestHeaders.getFirst("Authorization"))
            paths += exchange.requestURI.toString()
            val body = when (exchange.requestURI.path) {
                "/api/v1/home" -> """{"works":{"batch":0,"pages":3,"generation":"day.a+b /","items":[{"id":12,"title":"One"}]}}"""
                "/api/v1/home/works" -> {
                    assertEquals("type=all&batch=1&generation=day.a+b /", URLDecoder.decode(exchange.requestURI.rawQuery, "UTF-8"))
                    """{"batch":0,"pages":2,"generation":"new-day","renewed":true,"items":[]}"""
                }
                "/api/v1/works/12" -> """{"id":12,"title":"One","series":{"id":7,"title":"Series"}}"""
                "/api/v1/works/12/albums" -> """{"items":[{"albumId":4,"title":"OST","albumRole":"ost","artworkUrl":"/api/v1/artwork/4"}]}"""
                "/api/v1/works/12/tracks", "/api/v1/series/7/tracks" -> """{"items":[{"id":9,"title":"Opening","albumId":4,"role":"op","source":"manual"}]}"""
                "/api/v1/tracks/9/works" -> """{"items":[{"id":12,"title":"One","role":"op","seriesId":7}]}"""
                "/api/v1/series/7" -> """{"series":{"title":"Series"},"members":[{"id":12,"title":"One","workType":"anime"}]}"""
                else -> error("Unexpected path: ${exchange.requestURI}")
            }
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val client = Music032Client(MusicServerProfile(id = "source-a", baseUrl = "http://127.0.0.1:${server.address.port}", token = "api-secret", mediaToken = "media-secret"))
            runBlocking {
                val home = client.fetchHomeWorks()
                assertEquals(12L, home.items.single().id)
                assertTrue(client.fetchHomeWorks(home.batch + 1, home.generation).renewed)
                val detail = client.fetchWork(12)
                assertEquals("4", detail.albums.single().album.ratingKey)
                assertNotNull(detail.work.artworkUrl) // falls back to linked album artwork
                val track = detail.tracks.single().track
                assertEquals("source-a", track.sourceId)
                assertTrue(track.streamUrl.contains("mediaToken=media-secret"))
                assertFalse(track.streamUrl.contains("api-secret"))
                assertEquals(12L, client.fetchTrackWorks("9").single().work.id)
                assertEquals("Series", client.fetchWorkSeries(7).title)
                assertEquals("9", client.fetchSeriesTracks(7).single().ratingKey)
            }
            assertTrue(paths.contains("/api/v1/works/12/albums"))
        } finally { server.stop(0) }
    }

    @Test fun deletedWorkReturnsTyped404RatherThanEmptySuccess() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/works/12") { exchange ->
            val bytes = """{"error":{"code":"not_found","message":"Work not found"}}""".toByteArray()
            exchange.sendResponseHeaders(404, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val client = Music032Client(MusicServerProfile(id = "test", baseUrl = "http://127.0.0.1:${server.address.port}", token = "secret"))
            runBlocking {
                try { client.fetchWork(12); fail("Expected 404") }
                catch (error: Music032HttpException) { assertEquals(404, error.status) }
            }
        } finally { server.stop(0) }
    }
}
