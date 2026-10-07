package com.lux032.musicbridge.model

import com.lux032.musicbridge.AlbumSortOption
import com.lux032.musicbridge.ArtistSummary
import com.lux032.musicbridge.filterArtists
import com.lux032.musicbridge.music.Music032Json
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.releaseSortKey
import com.lux032.musicbridge.sortedByRelease
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumReleaseSortTest {
    @Test fun releaseSortKeyParsesSeparatorsAndPartialDates() {
        assertEquals(20040512, album("a", 2004, "2004-05-12").releaseSortKey())
        assertEquals(20041201, album("a", 2004, "2004.12.01").releaseSortKey())
        assertEquals(20040301, album("a", 2004, "2004/3/1").releaseSortKey())
        assertEquals(20040801, album("a", 2004, "20040801").releaseSortKey())
        assertEquals(20040800, album("a", 2004, "2004-08").releaseSortKey())
        assertEquals(20040000, album("a", 2004, "2004").releaseSortKey())
        // Plex originallyAvailableAt can carry a time component.
        assertEquals(20040512, album("a", 2004, "2004-05-12T00:00:00Z").releaseSortKey())
    }

    @Test fun releaseSortKeyFallsBackToYear() {
        assertEquals(19990000, album("a", 1999, null).releaseSortKey())
        assertEquals(19990000, album("a", 1999, "garbage").releaseSortKey())
        // An edited year that contradicts the tagged date wins.
        assertEquals(20100000, album("a", 2010, "2004-05-12").releaseSortKey())
        assertEquals(20040512, album("a", null, "2004-05-12").releaseSortKey())
        assertNull(album("a", null, null).releaseSortKey())
    }

    @Test fun sortsByFullDateWithUndatedLast() {
        val albums = listOf(
            album("YearOnly", 2004, null),
            album("Undated", null, null),
            album("May", 2004, "2004-05-12"),
            album("Older", 2001, "2001-03-03"),
            album("Dec", 2004, "2004.12.01"),
        )
        assertEquals(
            listOf("Dec", "May", "YearOnly", "Older", "Undated"),
            albums.sortedByRelease(newestFirst = true).map { it.title },
        )
        assertEquals(
            listOf("Older", "YearOnly", "May", "Dec", "Undated"),
            albums.sortedByRelease(newestFirst = false).map { it.title },
        )
    }

    @Test fun music032ParsesReleaseDate() {
        val section = PlexSection("032", "032", "artist")
        val synced = Music032Json.parseAlbum(
            JSONObject("""{"id":1,"title":"A","year":2004,"releaseDate":"2004-12-01"}"""),
            "https://example.org", "token", section, "032",
        )
        assertEquals("2004-12-01", synced.releaseDate)
        val detail = Music032Json.parseAlbum(
            JSONObject("""{"id":2,"title":"B","year":2004,"releaseDate":"","originalReleaseDate":"2004-02-02"}"""),
            "https://example.org", "token", section, "032",
        )
        assertEquals("2004-02-02", detail.releaseDate)
    }

    @Test fun legacyYearSortPreferenceMapsToReleaseDate() {
        assertEquals(AlbumSortOption.ReleaseDateDesc, AlbumSortOption.fromStoredName("YearDesc"))
        assertEquals(AlbumSortOption.ReleaseDateAsc, AlbumSortOption.fromStoredName("YearAsc"))
        assertEquals(AlbumSortOption.Artist, AlbumSortOption.fromStoredName("Artist"))
        assertNull(AlbumSortOption.fromStoredName("Unknown"))
    }

    @Test fun artistFilterMatchesNameAndFavorites() {
        val artists = listOf(
            ArtistSummary("ReoNa", null, 3, userRating = 10f),
            ArtistSummary("Ｙｏａｓｏｂｉ", null, 2),
            ArtistSummary("ずっと真夜中でいいのに。", null, 5, userRating = 10f),
        )
        assertEquals(artists, filterArtists(artists, "  ", favoritesOnly = false))
        assertEquals(listOf("Ｙｏａｓｏｂｉ"), filterArtists(artists, "yoa", favoritesOnly = false).map { it.name })
        assertEquals(listOf("ReoNa", "ずっと真夜中でいいのに。"), filterArtists(artists, "", favoritesOnly = true).map { it.name })
        assertEquals(listOf("ずっと真夜中でいいのに。"), filterArtists(artists, "真夜中", favoritesOnly = true).map { it.name })
        assertEquals(emptyList<String>(), filterArtists(artists, "yoa", favoritesOnly = true).map { it.name })
    }

    private fun album(title: String, year: Int?, releaseDate: String?) = PlexAlbum(
        ratingKey = title,
        title = title,
        artistName = "Artist",
        year = year,
        thumbUrl = null,
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = PlexSection("1", "Music", "artist"),
        releaseDate = releaseDate,
    )
}
