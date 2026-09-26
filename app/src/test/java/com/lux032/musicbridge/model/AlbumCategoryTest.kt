package com.lux032.musicbridge.model

import com.lux032.musicbridge.music.Music032Json
import com.lux032.musicbridge.plex.PlexAlbum
import org.json.JSONObject
import com.lux032.musicbridge.plex.PlexSection
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumCategoryTest {
    @Test fun music032MappedReleasesClassifyByTypeAndFlags() {
        val section = PlexSection("032", "032", "artist")
        for ((json, kind) in listOf(
            """{"id":1,"title":"Ordinary","albumType":"single","formats":"FLAC"}""" to ReleaseKind.Single,
            """{"id":2,"title":"Ordinary","albumType":"ep","formats":"MP3"}""" to ReleaseKind.Ep,
            """{"id":3,"title":"Ordinary","albumType":"album","live":true}""" to ReleaseKind.Live,
            """{"id":4,"title":"Ordinary","albumType":"compilation"}""" to ReleaseKind.Compilation,
        )) {
            val album = Music032Json.parseAlbum(JSONObject(json), "https://example.org", "token", section, "032")
            assertEquals(kind, classifyArtistAlbum(album).kind)
        }
    }

    @Test fun plexFormatsAreAuthoritative() {
        assertKind(album(format = "Single", tracks = 12), ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
        assertKind(album(format = "EP", tracks = 12), ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
        assertKind(album(format = "Album", tracks = 1), ArtistAlbumCategory.Album, ReleaseKind.Album)
    }

    @Test fun plexSubformatsOverridePrimaryFormat() {
        assertKind(album(format = "Album", subformat = "Live"), ArtistAlbumCategory.CompilationLive, ReleaseKind.Live)
        assertKind(album(format = "Single", subformat = "Compilation"), ArtistAlbumCategory.CompilationLive, ReleaseKind.Compilation)
        assertKind(album(format = "Album", subformat = "Live,Remix"), ArtistAlbumCategory.CompilationLive, ReleaseKind.Live)
    }

    @Test fun missingNativeTagsUseSafeFallback() {
        assertKind(album(title = "Release - Single", tracks = 12), ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
        assertKind(album(title = "Mini Album", tracks = 12), ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
        assertKind(album(title = "The Best", tracks = 12), ArtistAlbumCategory.CompilationLive, ReleaseKind.Compilation)
        assertKind(album(title = "Tour Live", tracks = 12), ArtistAlbumCategory.CompilationLive, ReleaseKind.Live)
        assertKind(album(title = "Ordinary", tracks = 2), ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
        assertKind(album(title = "Ordinary", tracks = 5), ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
        assertKind(album(title = "Ordinary", tracks = 8), ArtistAlbumCategory.Album, ReleaseKind.Album)
        assertKind(album(title = "Ordinary", tracks = null), ArtistAlbumCategory.Album, ReleaseKind.Album)
        assertKind(album(title = "Ordinary", tracks = 0), ArtistAlbumCategory.Album, ReleaseKind.Album)
    }

    @Test fun fallbackDoesNotMatchWordsContainingMarkers() {
        for (title in listOf("Deep Blue", "Sleepless", "EPISODE 1", "Alive", "Delivery", "Best Friend")) {
            assertKind(album(title = title, tracks = 8), ArtistAlbumCategory.Album, ReleaseKind.Album)
        }
    }

    @Test fun fallbackNormalizesFullWidthMarkers() {
        assertKind(album(title = "作品［ＥＰ］", tracks = 12), ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
    }

    @Test fun unknownNonEmptyPlexTagsContinueThroughFallback() {
        assertKind(album(title = "Release - Single", format = "Other", tracks = 12), ArtistAlbumCategory.SingleEp, ReleaseKind.Single)
        assertKind(album(title = "Ordinary", format = "Other", tracks = 5), ArtistAlbumCategory.SingleEp, ReleaseKind.Ep)
    }

    private fun assertKind(album: PlexAlbum, category: ArtistAlbumCategory, kind: ReleaseKind) {
        assertEquals(ArtistAlbumClassification(category, kind), classifyArtistAlbum(album))
    }

    private fun album(
        title: String = "Release",
        tracks: Int? = 10,
        format: String? = null,
        subformat: String? = null,
    ) = PlexAlbum(
        ratingKey = title,
        title = title,
        artistName = "Artist",
        year = 2024,
        thumbUrl = null,
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = PlexSection("1", "Music", "artist"),
        leafCount = tracks,
        format = format,
        subformat = subformat,
    )
}
