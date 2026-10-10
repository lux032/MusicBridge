package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosPlaybackStatus
import com.lux032.musicbridge.sonos.SonosQueueBrowseEntry
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
    private var browseQueue: List<String> = emptyList()

    @Before fun startFake() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val action = exchange.requestHeaders.getFirst("SOAPACTION").orEmpty().trim('"').substringAfter('#')
            synchronized(actions) { actions += action to body }
            val fail = action == "AddMultipleURIsToQueue" && rejectBatch
            val inner = when (action) {
                "GetMediaInfo" -> "<CurrentURI>x-sonosapi-stream:radio</CurrentURI><NrTracks>1</NrTracks>"
                "GetTransportSettings" -> "<PlayMode>SHUFFLE_NOREPEAT</PlayMode>"
                "Browse" -> {
                    val start = Regex("<StartingIndex>(\\d+)</StartingIndex>").find(body)!!.groupValues[1].toInt()
                    val page = browseQueue.drop(start).take(100)
                    val didl = "<DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\">" +
                        page.joinToString("") { "<item id=\"Q:0/x\"><dc:title>t</dc:title><res protocolInfo=\"http-get:*:audio/flac:*\">${it.replace("&", "&amp;")}</res></item>" } +
                        "</DIDL-Lite>"
                    "<Result>${didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</Result>" +
                        "<NumberReturned>${page.size}</NumberReturned><TotalMatches>${browseQueue.size}</TotalMatches>"
                }
                else -> ""
            }
            val response = if (fail) {
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><detail><UPnPError><errorCode>402</errorCode></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
            } else {
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:${action}Response xmlns:u=\"x\">$inner</u:${action}Response></s:Body></s:Envelope>"
            }
            val bytes = response.toByteArray()
            // Real speakers send large replies (Browse) chunked.
            exchange.sendResponseHeaders(if (fail) 500 else 200, if (action == "Browse") 0L else bytes.size.toLong())
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

    @Test fun chunkedResponsesAreDecodedByBytes() {
        val body = "<r>卧室 音乐</r>".toByteArray(Charsets.UTF_8)
        // Split inside a multi-byte character, as a speaker may do.
        val first = body.copyOfRange(0, 5)
        val second = body.copyOfRange(5, body.size)
        val raw = java.io.ByteArrayOutputStream().apply {
            write("HTTP/1.1 200 OK\r\nTRANSFER-ENCODING: chunked\r\n\r\n".toByteArray())
            write("${first.size.toString(16)}\r\n".toByteArray()); write(first); write("\r\n".toByteArray())
            write("${second.size.toString(16)};ext=1\r\n".toByteArray()); write(second); write("\r\n0\r\n\r\n".toByteArray())
        }.toByteArray()
        val (headers, decoded) = com.lux032.musicbridge.sonos.splitHttpResponse(raw)
        assertTrue(headers.startsWith("HTTP/1.1 200"))
        assertEquals("<r>卧室 音乐</r>", decoded)
        val plain = com.lux032.musicbridge.sonos.splitHttpResponse("HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n<a/>".toByteArray())
        assertEquals("<a/>", plain.second)
    }

    @Test fun browseQueueReadsAllPages() = runBlocking {
        browseQueue = (1..230).map { "http://10.0.0.2:4533/api/v1/tracks/$it/stream?mediaToken=t&x=1" }
        val controller = SonosController()
        val read = controller.browseQueue(room())
        assertEquals(230, read.size)
        assertEquals(browseQueue[229], read[229].url)
        assertEquals("audio/flac", read[0].mimeType)
        assertEquals(3, actions.count { it.first == "Browse" })
        assertEquals("x-sonosapi-stream:radio", controller.getMediaUri(room()))
        assertEquals("SHUFFLE_NOREPEAT", controller.getPlayMode(room()))
    }

    private val servers = listOf(
        Music032ServerRef("home", "https://music.example.com:7085", setOf("media-tok", "api-tok")),
        Music032ServerRef("other", "http://192.168.9.9:4533", setOf("other-tok")),
    )

    private fun browsed(vararg urls: String) = urls.map { SonosQueueBrowseEntry(it, "audio/flac") }

    @Test fun adoptsWebPanelQueueByMediaTokenAcrossHosts() {
        // The web panel pushes LAN addresses; the app profile uses the public domain.
        val refs = resolveAdoptableQueue(browsed(
            "http://192.168.50.10:4533/api/v1/tracks/12/stream?mediaToken=media-tok",
            "http://192.168.50.10:4533/api/v1/tracks/13/transcode.flac?mediaToken=media-tok&maxSampleRate=48000",
            "http://192.168.50.10:4533/api/v1/tracks/14/transcode.mp3?mediaToken=media-tok&bitrate=320&offsetMs=65000",
        ), servers)!!
        assertEquals(listOf("home", "home", "home"), refs.map { it.sourceId })
        assertEquals(listOf("12", "13", "14"), refs.map { it.ratingKey })
        assertEquals(listOf(SonosPlanKind.ORIGINAL, SonosPlanKind.STATIC_FLAC, SonosPlanKind.LIVE_MP3), refs.map { it.kind })
        assertEquals(65_000L, refs[2].offsetMs)
        assertFalse(refs[2].url.contains("offsetMs"))
        assertEquals("audio/flac", refs[0].mime)
    }

    @Test fun adoptionFallsBackToBaseUrlAndRejectsForeignQueues() {
        val byHost = resolveAdoptableQueue(browsed("http://192.168.9.9:4533/api/v1/tracks/5/stream?mediaToken=rotated"), servers)!!
        assertEquals("other", byHost.single().sourceId)
        // A subpath deployment behind a reverse proxy.
        val sub = resolveAdoptableQueue(browsed("https://h/music/api/v1/tracks/7/stream?mediaToken=media-tok"), servers)!!
        assertEquals("7", sub.single().ratingKey)
        // Any foreign entry (Plex, streaming service, no token) leaves the speaker alone.
        assertNull(resolveAdoptableQueue(browsed(
            "http://192.168.50.10:4533/api/v1/tracks/12/stream?mediaToken=media-tok",
            "http://10.0.0.3:32400/library/parts/99/file.flac?X-Plex-Token=x",
        ), servers))
        assertNull(resolveAdoptableQueue(browsed("x-sonos-spotify:spotify%3atrack%3a1?sid=9"), servers))
        assertNull(resolveAdoptableQueue(browsed("http://192.168.50.10:4533/api/v1/tracks/12/stream"), servers))
        assertNull(resolveAdoptableQueue(emptyList(), servers))
        assertNull(resolveAdoptableQueue(browsed("http://h/api/v1/tracks/1/stream?mediaToken=x"), emptyList()))
        // With one 032 server an unknown token / host still maps to it.
        assertEquals("home", resolveAdoptableQueue(browsed("http://x:1/api/v1/tracks/1/stream?mediaToken=x"), servers.take(1))!!.single().sourceId)
    }

    @Test fun adoptedQueueResolvesPositionAndReusesSavedSession() {
        val refs = resolveAdoptableQueue(browsed(
            "http://192.168.50.10:4533/api/v1/tracks/1/stream?mediaToken=media-tok",
            "http://192.168.50.10:4533/api/v1/tracks/2/transcode.mp3?mediaToken=media-tok&bitrate=320&offsetMs=5000",
        ), servers)!!
        val status = SonosPlaybackStatus("PLAYING",
            "http://192.168.50.10:4533/api/v1/tracks/2/transcode.mp3?mediaToken=media-tok&bitrate=320&offsetMs=5000", 10, 200, 2)
        assertEquals(1, resolveSonosQueueIndex(refs.map { it.entry() }, status))
        val saved = listOf(track("1", "home"), track("2", "home"))
        assertTrue(reusableTracks(refs, saved))
        assertFalse(reusableTracks(refs, saved.reversed()))
        assertFalse(reusableTracks(refs, listOf(track("1", "other"), track("2", "other"))))
    }

    @Test fun sonosPlayModesMapBack() {
        assertEquals(PlaybackMode.Sequential, playbackModeFromSonos("NORMAL"))
        assertEquals(PlaybackMode.RepeatAll, playbackModeFromSonos("REPEAT_ALL"))
        assertEquals(PlaybackMode.RepeatOne, playbackModeFromSonos("REPEAT_ONE"))
        assertEquals(PlaybackMode.Shuffle, playbackModeFromSonos("SHUFFLE"))
        assertNull(playbackModeFromSonos(null))
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
