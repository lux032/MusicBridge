package com.lux032.musicbridge.music

import com.lux032.musicbridge.MusicServerProfile
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class Music032ArtistApiTest {
    private fun withServer(block: (HttpServer, Music032Client) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try {
            block(server, Music032Client(MusicServerProfile(
                id = "test", baseUrl = "http://127.0.0.1:${server.address.port}", token = "secret",
            )))
        } finally { server.stop(0) }
    }

    @Test fun detailUsesTopTracksAndCanonicalIdAndAllTracksUsesPagedEndpoint() = withServer { server, client ->
        val paths = mutableListOf<String>()
        server.createContext("/api/v1/artists") { exchange ->
            assertEquals("Bearer secret", exchange.requestHeaders.getFirst("Authorization"))
            paths += exchange.requestURI.toString()
            val body = when (exchange.requestURI.path) {
                "/api/v1/artists/1" -> """{"artist":{"id":2,"name":"Merged","mergedFrom":1},"releases":[],"topTracks":[{"id":9,"title":"Popular","playCount":12}],"topTracksArePopular":true,"tracksTotal":501}"""
                else -> if (exchange.requestURI.query.contains("offset=0")) {
                    """{"items":[{"id":10,"title":"First"}],"total":501,"limit":500,"offset":0}"""
                } else {
                    """{"items":[{"id":11,"title":"Last"}],"total":501,"limit":500,"offset":500}"""
                }
            }
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        runBlocking {
            val detail = client.fetchArtistDetail("1")
            assertEquals("2", detail.artist.ratingKey)
            assertEquals(501, detail.tracksTotal)
            assertTrue(detail.topTracksArePopular)
            assertEquals(12L, detail.topTracks.single().viewCount)
            val tracks = client.fetchArtistTracks(detail.artist.copy(ratingKey = "1"))
            assertEquals(listOf("10", "11"), tracks.map { it.ratingKey })
            assertTrue(paths.contains("/api/v1/artists/2/tracks?sort=album&order=asc&limit=500&offset=500"))
        }
    }

    @Test fun sortDirectionsAndProtocolErrors() = withServer { server, client ->
        val paths = mutableListOf<String>()
        server.createContext("/api/v1/artists") { exchange ->
            paths += exchange.requestURI.toString()
            val status = if (exchange.requestURI.path.contains("missing")) 404 else 400
            val body = """{"error":{"code":"invalid_request","message":"bad request"}}"""
            exchange.sendResponseHeaders(status, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        runBlocking {
            for (sort in ArtistTrackSort.entries) {
                try {
                    client.fetchArtistTracksPage("1", sort)
                    fail("Expected HTTP 400")
                } catch (error: Music032HttpException) { assertEquals(400, error.status) }
                assertTrue(paths.last().contains("sort=${sort.apiValue}&order=${sort.defaultOrder}&limit=100&offset=0"))
            }
            try {
                client.fetchArtistTracksPage("missing", ArtistTrackSort.Recent, "asc", offset = 100)
                fail("Expected HTTP 404")
            } catch (error: Music032HttpException) { assertEquals(404, error.status) }
        }
    }

    @Test fun slowPageRequestCanBeCancelled() = withServer { server, client ->
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        server.createContext("/api/v1/artists") { exchange ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            exchange.close()
        }
        try {
            runBlocking {
                val request = async { client.fetchArtistTracksPage("1") }
                // Let the child enter its cancellable transport before awaiting the server.
                kotlinx.coroutines.yield()
                assertTrue(started.await(3, TimeUnit.SECONDS))
                withTimeout(1000) { request.cancelAndJoin() }
                assertTrue(request.isCancelled)
            }
        } finally { release.countDown() }
    }

    @Test fun fallbackFlagDefaultsToFalseAndRefreshGetsPopularTracks() = withServer { server, client ->
        var popular = false
        var includeFlag = false
        server.createContext("/api/v1/artists/1") { exchange ->
            val flag = if (includeFlag) ",\"topTracksArePopular\":$popular" else ""
            val tracks = if (popular) """[{"id":2,"title":"Played","playCount":1}]"""
                else """[{"id":1,"title":"First","playCount":0},{"id":2,"title":"Second","playCount":0}]"""
            val body = """{"artist":{"id":1,"name":"Artist"},"releases":[],"topTracks":$tracks,"tracksTotal":2$flag}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        runBlocking {
            val missing = client.fetchArtistDetail("1")
            assertFalse(missing.topTracksArePopular)
            assertEquals(listOf("1", "2"), missing.topTracks.map { it.ratingKey })
            includeFlag = true
            val fallback = client.fetchArtistDetail("1", refresh = true)
            assertFalse(fallback.topTracksArePopular)
            assertEquals(2, fallback.topTracks.size)
            popular = true
            val refreshed = client.fetchArtistDetail("1", refresh = true)
            assertTrue(refreshed.topTracksArePopular)
            assertEquals("2", refreshed.topTracks.single().ratingKey)
            assertEquals(1L, refreshed.topTracks.single().viewCount)
        }
    }

    @Test fun emptyTopTracksAndUnplayedTrack() = withServer { server, client ->
        server.createContext("/api/v1/artists/1") { exchange ->
            val body = """{"artist":{"id":1,"name":"New"},"releases":[],"topTracks":[],"tracksTotal":0}"""
            exchange.sendResponseHeaders(200, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        runBlocking {
            val detail = client.fetchArtistDetail("1")
            assertTrue(detail.topTracks.isEmpty())
            assertFalse(detail.topTracksArePopular)
        }
        val track = Music032Json.parseTrack(org.json.JSONObject("""{"id":1,"playCount":0,"viewCount":99}"""), "https://example.org", "token", "test")
        assertEquals(0L, track.viewCount)
        assertNull(track.lastViewedAtEpochSeconds)
    }
}
