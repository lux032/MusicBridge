package com.lux032.musicbridge.music

import com.lux032.musicbridge.NavigationEntry
import com.lux032.musicbridge.AppSection
import com.lux032.musicbridge.music.LibrarySection
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Music032WorksTest {
    private val media: (String) -> String? = { Music032Json.mediaUrl(it, "https://music.example", "media-secret") }
    private val track: (JSONObject) -> com.lux032.musicbridge.music.MusicTrack = {
        Music032Json.parseTrack(it, "https://music.example", "media-secret", "source-a")
    }

    @Test fun rotationPreservesServerBatchAndGenerationAndArtworkFallback() {
        val rotation = Music032WorksJson.rotation(JSONObject("""{
          "batch":2,"pages":4,"generation":"2026-11-11.a+b","renewed":true,
          "items":[{"id":12,"title":"K-ON!","workType":"anime","year":2009,
          "artworkUrl":"","posterUrl":"","coverUrl":"/api/v1/artwork/5",
          "roleCounts":[{"role":"op","count":1}],"series":{"id":3,"title":"K-ON"}}]
        }"""), media)
        assertEquals(2, rotation.batch)
        assertEquals(4, rotation.pages)
        assertEquals("2026-11-11.a+b", rotation.generation)
        assertTrue(rotation.renewed)
        val work = rotation.items.single()
        assertEquals("anime", work.type)
        assertEquals(3L, work.seriesId)
        assertEquals(listOf("op" to 1), work.roleCounts)
        assertEquals("https://music.example/api/v1/artwork/5?mediaToken=media-secret", work.artworkUrl)
    }

    @Test fun detailUsesAuthenticatedPosterNotUpstreamEditingUrl() {
        val work = Music032WorksJson.work(JSONObject("""{"id":12,"title":"K-ON!",
          "posterUrl":"https://upstream.example/poster.jpg","posterApiUrl":"/api/v1/works/12/poster?v=abc",
          "series":{"id":3,"position":2,"title":"K-ON"}}"""), media, detail = true)
        assertEquals("https://music.example/api/v1/works/12/poster?v=abc&mediaToken=media-secret", work.artworkUrl)
        assertEquals(2, work.seriesPosition)
        val noPoster = Music032WorksJson.work(JSONObject("""{"id":13,"posterUrl":"https://upstream.example/x"}"""), media, detail = true)
        assertNull(noPoster.artworkUrl)
    }

    @Test fun playerLinksPrioritizeRolesAndCollapseMultipleUsagesOfSameWork() {
        val links = Music032WorksJson.trackWorks(JSONObject("""{"items":[
          {"id":1,"title":"One","role":"insert"},
          {"id":2,"title":"Two","role":"ed"},
          {"id":1,"title":"One","role":"op","seriesId":8},
          {"id":3,"title":"Three","role":"new-role"}] }"""), media)
        assertEquals(listOf(1L, 2L, 3L), links.map { it.work.id })
        assertEquals("op", links.first().role)
        assertEquals(8L, links.first().work.seriesId)
    }

    @Test fun soundtrackIsNotUnfoldedIntoSongListAndPlaybackDeduplicatesTracks() {
        val tracks = Music032WorksJson.tracks(JSONObject("""{"items":[
          {"id":1,"title":"Opening","role":"op","sequence":2,"source":"manual"},
          {"id":1,"title":"Opening","role":"ed","source":"auto"},
          {"id":2,"title":"Score","role":"ost","source":"album"},
          {"id":3,"title":"Album theme","role":"theme","source":"album"},
          {"id":4,"title":"Insert","role":"insert","source":"bangumi"},
          {"id":5,"title":"Other","role":"other","source":"manual"}]}"""), track)
        assertEquals(listOf("1", "1", "4"), tracks.filter { it.isSong }.map { it.track.ratingKey })
        assertEquals(listOf("1", "2", "3", "4", "5"), tracks.uniqueStreams().map { it.ratingKey })
        assertEquals(2, tracks.first().sequence)
        assertTrue(tracks.uniqueStreams().all { it.sourceId == "source-a" })
    }

    @Test fun albumWorkUsesAlbumIdRatherThanWorkIdForNavigation() {
        val albums = Music032WorksJson.albums(JSONObject("""{"items":[
          {"albumId":45,"workId":12,"title":"Soundtrack","albumRole":"ost",
          "trackRoles":["op","insert"],"artworkUrl":"/api/v1/artwork/9","trackCount":36}]}""")) {
            Music032Json.parseAlbum(it, "https://music.example", "media-secret", LibrarySection("032", "Library", "artist"), "source-a")
        }
        val album = albums.single()
        assertEquals("45", album.album.ratingKey)
        assertEquals("source-a", album.album.sourceId)
        assertEquals(36, album.album.leafCount)
        assertEquals("ost", album.albumRole)
        assertEquals(listOf("op", "insert"), album.trackRoles)
    }

    @Test fun emptyOldServerResponsesAndSeriesMembersAreSafe() {
        assertTrue(Music032WorksJson.rotation(JSONObject(), media).items.isEmpty())
        assertTrue(Music032WorksJson.trackWorks(JSONObject(), media).isEmpty())
        val series = Music032WorksJson.series(JSONObject("""{"series":{"id":3,"title":"Series"},
          "members":[{"id":12,"title":"One","workType":"anime","year":2009,"posterUrl":"/api/v1/works/12/poster"}]}"""), media)
        assertEquals("Series", series.title)
        assertEquals("anime", series.members.single().type)
    }

    @Test fun eachWorkVisitRetainsItsOwnSourceAndIdentity() {
        val first = NavigationEntry(1, AppSection.WorkDetail, "a", 12)
        val second = NavigationEntry(2, AppSection.WorkDetail, "a", 13)
        val stack = mutableListOf(first, second)
        stack.removeAt(stack.lastIndex)
        assertEquals(12L, stack.last().workId)
        assertEquals("a", stack.last().sourceId)
    }
}
