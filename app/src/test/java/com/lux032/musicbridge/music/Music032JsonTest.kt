package com.lux032.musicbridge.music

import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexSection
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Music032JsonTest {
    private val base = "https://example.org"
    private val section = PlexSection("032", "Music", "artist")
    private fun track(json: String) = Music032Json.parseTrack(JSONObject(json), base, "a b", "032")
    private fun album(type: String, extra: String = "") = Music032Json.parseAlbum(
        JSONObject("""{"id":1,"title":"Ordinary","trackCount":10,"formats":"FLAC,MP3","albumType":"$type"$extra}"""),
        base, "token", section, "032")

    @Test fun artistRefsAndSyncArtists() {
        val duet = album("album", ""","artist":"A, B","artists":[{"id":2,"name":"A"},{"id":3,"name":"B"},{"id":0,"name":"bad"},{"id":2,"name":"A"}]""")
        assertEquals(listOf(ArtistRef("2", "A"), ArtistRef("3", "B")), duet.artists)
        assertEquals("A, B", duet.artistName)
        assertTrue(album("album").artists.isEmpty())

        val guestTrack = track("""{"id":7,"title":"Song","artist":"Guest","artists":[{"id":4,"name":"Guest"}]}""")
        assertEquals(listOf(ArtistRef("4", "Guest")), guestTrack.artists)
        assertTrue(track("""{"id":8,"title":"Plain","artists":[]}""").artists.isEmpty())

        val artist = Music032Json.parseArtist(
            JSONObject("""{"id":4,"name":" Guest ","sortName":"guest","imageUrl":"/api/v1/artists/4/image","isFavorite":true,"albumCount":1,"trackCount":3}"""),
            base, "token", "032",
        )!!
        assertEquals("4", artist.ratingKey)
        assertEquals("Guest", artist.title)
        assertEquals(1, artist.albumCount)
        assertEquals(3, artist.trackCount)
        assertTrue(artist.isFavorite)
        assertEquals("https://example.org/api/v1/artists/4/image?mediaToken=token", artist.thumbUrl)
        assertNull(Music032Json.parseArtist(JSONObject("""{"id":0,"name":"x"}"""), base, "token", "032"))
    }

    @Test fun newAndOldTrackFields() {
        val track = track("""{"id":7,"title":"Song","codec":"FLAC","sampleRate":96000,"bitDepth":24,"bitrateKbps":2800,"discNumber":2,"trackNumber":3,"lyricsUrl":"/lyrics.lrc","viewCount":2,"skipCount":1,"lastViewedAt":1234}""")
        assertEquals("flac", track.sourceAudioCodec)
        assertEquals(96000, track.sourceAudioSampleRate)
        assertEquals(24, track.bitDepth)
        assertEquals(2800, track.sourceAudioBitrateKbps)
        assertEquals(2, track.discNumber)
        assertEquals(3, track.trackNumber)
        assertEquals("https://example.org/lyrics.lrc?mediaToken=a+b", track.lyricUrl)
        assertNull(track.playbackMimeType)
        assertEquals(1234L, track.lastViewedAtEpochSeconds)
        val old = track("""{"id":8,"title":"Old","lastPlayedAt":"2020-01-01T00:00:00Z"}""")
        assertEquals("/api/v1/tracks/8/stream", old.partKey)
        assertEquals("https://example.org/api/v1/tracks/8/stream?mediaToken=a+b", old.streamUrl)
        assertNull(old.sourceAudioCodec)
        assertNull(old.lyricUrl)
        assertNull(old.viewCount)
        assertNull(old.skipCount)
        assertEquals(1577836800L, old.lastViewedAtEpochSeconds)
        val sync = track("""{"id":9,"codec":"opus","sampleRate":0,"discNumber":0,"trackNumber":-1}""")
        assertNull(sync.discNumber)
        assertNull(sync.trackNumber)
        assertNull(sync.sourceAudioSampleRate)
        assertNull(sync.playbackMimeType)
    }

    @Test fun albumTypesAndFlags() {
        for ((type, format, sub) in listOf(
            listOf("single", "Single", ""), listOf("ep", "EP", ""),
            listOf("album", "", ""), listOf("soundtrack", "", ""),
            listOf("live", "", "Live"), listOf("compilation", "", "Compilation"),
        )) {
            val result = album(type)
            assertEquals(format.ifEmpty { null }, result.format)
            assertEquals(sub.ifEmpty { null }, result.subformat)
        }
        assertEquals("Live,Compilation", album("album", ",\"live\":true,\"compilation\":true").subformat)
    }

    @Test fun serverReleaseKindTakesPrecedence() {
        for ((kind, format, sub) in listOf(
            listOf("single", "Single", ""), listOf("ep", "EP", ""),
            listOf("album", "Album", ""), listOf("soundtrack", "Album", ""),
            listOf("bootleg", "Album", ""), listOf("other", "Album", ""),
            listOf("live", "", "Live"), listOf("compilation", "", "Compilation"),
        )) {
            // albumType 保持默认 album（未标注），releaseKind 是服务端最终判定。
            val result = album("album", ",\"releaseKind\":\"$kind\"")
            assertEquals(kind, format.ifEmpty { null }, result.format)
            assertEquals(kind, sub.ifEmpty { null }, result.subformat)
        }
        // 服务端已判定时不再叠加旧的 live/compilation 布尔标记。
        val single = album("album", ",\"releaseKind\":\"single\",\"live\":true,\"compilation\":true")
        assertEquals("Single", single.format)
        assertNull(single.subformat)
        // 空 releaseKind 视为旧版服务端。
        assertEquals("Single", album("single", ",\"releaseKind\":\"\"").format)
    }

    @Test fun capabilitiesAndMime() {
        val caps = Music032Json.parseCapabilities("""{"apiRevision":2,"features":{"lyrics":true,"transcode":false},"transcode":{"formats":{"flac":{"available":false,"maxSampleRates":[48000]},"mp3":{"available":true,"bitrates":[128,320]}}},"artwork":{"sizes":[256,512]}}""")
        assertTrue(caps.hasFeature("lyrics"))
        assertFalse(caps.hasFeature("transcode"))
        assertFalse(caps.isFormatAvailable("flac"))
        assertTrue(caps.isFormatAvailable("mp3"))
        assertEquals(listOf(48000), caps.transcodeFormats["flac"]?.maxSampleRates)
        assertEquals(emptyMap<String, Music032FormatCaps>(), Music032Json.parseCapabilities("""{"apiRevision":2}""").transcodeFormats)
        assertEquals(Music032Capabilities.LEGACY, Music032Json.parseCapabilities("not json"))
        for ((codec, mime) in mapOf("FLAC" to "audio/flac", "mp3" to "audio/mpeg", "aac" to "audio/mp4", "alac" to "audio/mp4", "opus" to "audio/ogg", "vorbis" to "audio/ogg"))
            assertEquals(mime, Music032Json.original032MimeType(codec, null))
        assertNull(Music032Json.original032MimeType("", null))
        assertEquals("audio/custom", Music032Json.original032MimeType("mp3", "audio/custom"))
    }

    @Test fun mediaUrls() {
        assertNull(Music032Json.mediaUrl(" ", base, "token"))
        assertEquals("https://example.org/a?mediaToken=token", Music032Json.mediaUrl("/a", base, "token"))
        assertEquals("https://example.org/a?x=1&mediaToken=token", Music032Json.mediaUrl("https://example.org/a?x=1", base, "token"))
        assertEquals("https://other.org/a", Music032Json.mediaUrl("https://other.org/a", base, "token"))
    }

    @Test fun statusMappings() {
        assertEquals(MediaTokenProbeResult.OK, mediaTokenProbeStatus(404))
        assertEquals(MediaTokenProbeResult.INVALID, mediaTokenProbeStatus(401))
        assertEquals(MediaTokenProbeResult.INVALID, mediaTokenProbeStatus(403))
        assertEquals(MediaTokenProbeResult.UNKNOWN, mediaTokenProbeStatus(500))
        assertTrue(trackDetailNotFound(404))
        assertFalse(trackDetailNotFound(401))
    }

    @Test fun storeTtlFailureAndInvalidate() = runBlocking {
        var time = 1000L
        val store = Music032CapabilitiesCache { time }
        val key = Music032CapabilitiesStore.Key.of("https://ttl.example/", "secret")
        var calls = 0
        suspend fun fetch(): String { calls++; return """{"apiRevision":2}""" }
        assertEquals(2, store.ensure(key, ::fetch)?.apiRevision)
        time += 599_999
        store.ensure(key, ::fetch)
        assertEquals(1, calls)
        time++
        store.ensure(key, ::fetch)
        assertEquals(2, calls)
        store.invalidate(key)
        assertNull(store.ensure(key) { calls++; error("offline") })
        assertNull(store.snapshot(key))
        time += 4_999
        assertNull(store.ensure(key, ::fetch))
        assertEquals(3, calls)
        time++
        store.ensure(key, ::fetch)
        assertEquals(4, calls)
        store.invalidate(key)
        assertEquals(Music032Capabilities.LEGACY,
            store.ensure(key) { calls++; throw Music032HttpException(404, "not found") })
        assertEquals(Music032Capabilities.LEGACY, store.snapshot(key))
        time += 29_999
        store.ensure(key, ::fetch)
        assertEquals(5, calls)
        time++
        assertEquals(2, store.ensure(key, ::fetch)?.apiRevision)
        assertEquals(6, calls)
    }

    @Test fun non404HttpErrorAndMalformedResponseStayUnknown() = runBlocking {
        val store = Music032CapabilitiesCache()
        val key = Music032CapabilitiesStore.Key.of(base, "failures")
        assertNull(store.ensure(key) { throw Music032HttpException(503, "unavailable") })
        assertNull(store.snapshot(key))
        store.invalidate(key)
        assertNull(store.ensure(key) { "not json" })
        assertNull(store.snapshot(key))
    }

    @Test fun differentKeysDoNotBlockEachOther() = runBlocking {
        withTimeout(5_000) {
            val store = Music032CapabilitiesCache()
            val first = Music032CapabilitiesStore.Key.of(base, "first")
            val second = Music032CapabilitiesStore.Key.of(base, "second")
            val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
            val started = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job = launch {
                store.ensure(first) { started.complete(Unit); waiting.await(); """{"apiRevision":2}""" }
            }
            started.await()
            assertEquals(3, store.ensure(second) { """{"apiRevision":3}""" }?.apiRevision)
            waiting.complete(Unit)
            job.join()
        }
    }

    @Test fun artistDetailGenerationRejectsStaleResponse() {
        assertTrue(shouldCacheArtistDetail(4, 4))
        assertFalse(shouldCacheArtistDetail(4, 5))
    }
}
