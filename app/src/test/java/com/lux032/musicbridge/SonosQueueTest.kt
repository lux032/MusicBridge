package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosPlaybackStatus
import com.lux032.musicbridge.sonos.SonosQueueItem
import com.lux032.musicbridge.sonos.SonosRoom
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

class SonosQueueTest {
    private fun track(id: String, source: String = "032") = PlexTrackStream(
        id, "Track $id", "http://10.0.0.2:4533/api/v1/tracks/$id/stream?mediaToken=t", "/api/v1/tracks/$id/stream", 180_000L, null,
        albumTitle = "Album", artistName = "Singer", sourceId = source,
    )

    private fun entry(id: String, kind: SonosPlanKind = SonosPlanKind.ORIGINAL) = SonosQueueEntry(
        "032", id, kind,
        if (kind == SonosPlanKind.LIVE_MP3) "http://10.0.0.2:4533/api/v1/tracks/$id/transcode.mp3?mediaToken=t&bitrate=320"
        else "http://10.0.0.2:4533/api/v1/tracks/$id/stream?mediaToken=t",
        null,
    )

    @Test fun queueModeOnlyFor032Tracks() {
        val tracks = listOf(track("1"), track("2"))
        assertTrue(canUseSonosQueue(tracks) { it.sourceId == "032" })
        assertFalse(canUseSonosQueue(tracks + track("3", source = "plex")) { it.sourceId == "032" })
        assertFalse(canUseSonosQueue(emptyList()) { true })
    }

    @Test fun plexStreamsNeverQualifyForQueueMode() {
        val is032 = { t: PlexTrackStream -> com.lux032.musicbridge.music.Music032TranscodeUrls.isOriginalStream(t.streamUrl) }
        val plex = PlexTrackStream("99", "Plex", "http://10.0.0.3:32400/library/parts/99/file.flac?X-Plex-Token=x",
            "/library/parts/99/file.flac", 1L, null, sourceId = "plex")
        val plexLive = plex.copy(streamUrl = "http://10.0.0.3:32400/music/:/transcode/universal/start.mp3?path=%2Flibrary%2Fmetadata%2F99&X-Plex-Token=x")
        assertTrue(canUseSonosQueue(listOf(track("1")), is032))
        assertFalse(canUseSonosQueue(listOf(plex), is032))
        assertFalse(canUseSonosQueue(listOf(plexLive), is032))
        assertFalse(canUseSonosQueue(listOf(track("1"), plex), is032))
    }

    @Test fun sessionMirrorsTrackList() {
        val session = SonosQueueSession("RINCON_1", listOf(entry("1"), entry("2")))
        assertTrue(session.mirrors(listOf(track("1"), track("2"))))
        assertFalse(session.mirrors(listOf(track("2"), track("1"))))
        assertFalse(session.mirrors(listOf(track("1"))))
    }

    @Test fun resolvesSpeakerPositionByTrackNumberAndUri() {
        val entries = listOf(entry("1"), entry("2", SonosPlanKind.LIVE_MP3), entry("1"))
        fun status(number: Int?, uri: String?) = SonosPlaybackStatus("PLAYING", uri, 0, 0, number)
        // Duplicates: the Track number disambiguates.
        assertEquals(2, resolveSonosQueueIndex(entries, status(3, entries[2].url)))
        // A live entry re-opened at an offset keeps its identity.
        assertEquals(1, resolveSonosQueueIndex(entries, status(2, entries[1].url + "&offsetMs=5000")))
        // Track number out of sync with the URI: trust the URI.
        assertEquals(1, resolveSonosQueueIndex(entries, status(1, entries[1].url)))
        assertEquals(0, resolveSonosQueueIndex(entries, status(1, null)))
        assertNull(resolveSonosQueueIndex(entries, status(null, "x-sonosapi-stream:radio")))
    }

    @Test fun playModesMapToSonos() {
        assertEquals("NORMAL", sonosPlayModeFor(PlaybackMode.Sequential))
        assertEquals("REPEAT_ALL", sonosPlayModeFor(PlaybackMode.RepeatAll))
        assertEquals("REPEAT_ONE", sonosPlayModeFor(PlaybackMode.RepeatOne))
        assertEquals("SHUFFLE_NOREPEAT", sonosPlayModeFor(PlaybackMode.Shuffle))
    }

    @Test fun didlCarriesFullMetadata() {
        val didl = SonosQueueItem("http://h/a?x=1&y=2", "A <B>", "C & D", "Singer", "http://h/art?s=1&t=2", "audio/flac", 61_000L).didl()
        for (part in listOf("<dc:title>A &lt;B&gt;</dc:title>", "<upnp:album>C &amp; D</upnp:album>", "<upnp:artist>Singer</upnp:artist>",
            "<upnp:albumArtURI>http://h/art?s=1&amp;t=2</upnp:albumArtURI>", "protocolInfo=\"http-get:*:audio/flac:*\"",
            "duration=\"00:01:01.000\"", ">http://h/a?x=1&amp;y=2</res>")) {
            assertTrue("missing $part in $didl", didl.contains(part))
        }
    }

    // --- SOAP sequence against a fake zone player ---------------------------------------

    private lateinit var server: HttpServer
    private val actions = mutableListOf<Pair<String, String>>()
    private var rejectBatch = false

    @Before fun startFake() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val action = exchange.requestHeaders.getFirst("SOAPACTION").orEmpty().trim('"').substringAfter('#')
            synchronized(actions) { actions += action to body }
            val fail = action == "AddMultipleURIsToQueue" && rejectBatch
            val inner = when (action) {
                "GetMediaInfo" -> "<CurrentURI>x-sonosapi-stream:radio</CurrentURI><NrTracks>1</NrTracks>"
                else -> ""
            }
            val response = if (fail) {
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail><UPnPError><errorCode>402</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
            } else {
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"x\">$inner</u:${action}Response></s:Body></s:Envelope>"
            }
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(if (fail) 500 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stopFake() = server.stop(0)

    private fun room() = SonosRoom("Living", "RINCON_ABC01400", "http://127.0.0.1:${server.address.port}", 1, "Living", emptyList())

    private fun items(n: Int) = (1..n).map { SonosQueueItem("http://10.0.0.2:4533/api/v1/tracks/$it/stream?mediaToken=t", "T$it", "Album") }

    @Test fun replaceQueueLoadsBatchesThenPlaysFromStartIndex() = runBlocking {
        SonosController().replaceQueue(room(), items(20), startIndex = 4, positionSeconds = 0)
        val names = actions.map { it.first }
        assertEquals(listOf("RemoveAllTracksFromQueue", "AddMultipleURIsToQueue", "AddMultipleURIsToQueue",
            "SetAVTransportURI", "Seek", "Play"), names)
        val firstBatch = actions[1].second
        assertTrue(firstBatch.contains("<NumberOfURIs>16</NumberOfURIs>"))
        assertTrue(actions[2].second.contains("<NumberOfURIs>4</NumberOfURIs>"))
        assertTrue(actions[3].second.contains("<CurrentURI>x-rincon-queue:RINCON_ABC01400#0</CurrentURI>"))
        assertTrue(actions[4].second.contains("<Unit>TRACK_NR</Unit><Target>5</Target>"))
    }

    @Test fun replaceQueueFallsBackToSingleAdds() = runBlocking {
        rejectBatch = true
        SonosController().replaceQueue(room(), items(3), startIndex = 0)
        assertEquals(3, actions.count { it.first == "AddURIToQueue" })
    }

    @Test fun insertAndJumpReselectQueue() = runBlocking {
        val controller = SonosController()
        controller.insertIntoQueue(room(), items(2), atIndex = 3)
        val adds = actions.filter { it.first == "AddURIToQueue" }.map { it.second }
        assertTrue(adds[0].contains("<DesiredFirstTrackNumberEnqueued>4</DesiredFirstTrackNumberEnqueued>"))
        assertTrue(adds[1].contains("<DesiredFirstTrackNumberEnqueued>5</DesiredFirstTrackNumberEnqueued>"))
        actions.clear()
        // The speaker was switched to a radio station: jumping re-selects our queue first.
        controller.playQueueIndex(room(), 2)
        assertEquals(listOf("GetMediaInfo", "SetAVTransportURI", "Seek", "Play"), actions.map { it.first })
        actions.clear()
        controller.replaceQueueItem(room(), 1, items(1).first())
        assertEquals(listOf("RemoveTrackFromQueue", "AddURIToQueue"), actions.map { it.first })
        assertTrue(actions[0].second.contains("<ObjectID>Q:0/2</ObjectID>"))
        assertTrue(actions[1].second.contains("<DesiredFirstTrackNumberEnqueued>2</DesiredFirstTrackNumberEnqueued>"))
    }
}
