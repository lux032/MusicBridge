package com.lux032.musicbridge.model

import com.lux032.musicbridge.music.Music032Json
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import org.json.JSONObject
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

    @Test fun music032DefaultAlbumTypesRetainTitleAndCountHeuristics() {
        val section = PlexSection("032", "032", "artist")
        for (albumType in listOf("album", "soundtrack")) {
            val tagged = Music032Json.parseAlbum(
                JSONObject("""{"id":5,"title":"Ordinary","trackCount":2,"albumType":"$albumType"}"""),
                "https://example.org", "token", section, "032",
            )
            val legacy = Music032Json.parseAlbum(
                JSONObject("""{"id":5,"title":"Ordinary","trackCount":2}"""),
                "https://example.org", "token", section, "032",
            )
            assertEquals(null, tagged.format)
            assertEquals(ReleaseKind.Single, classifyArtistAlbum(tagged).kind)
            assertEquals(classifyArtistAlbum(legacy), classifyArtistAlbum(tagged))
        }
    }

    @Test fun music032ReleaseKindIsAuthoritative() {
        val section = PlexSection("032", "032", "artist")
        for ((json, kind) in listOf(
            // 服务端判定为 album 的 2 曲专辑不再被本地曲目数启发式判成单曲。
            """{"id":6,"title":"Ordinary","trackCount":2,"albumType":"album","releaseKind":"album"}""" to ReleaseKind.Album,
            // 服务端推断为单曲的 4 曲日系单曲（含伴奏）不再被判成 EP。
            """{"id":7,"title":"Ordinary","trackCount":4,"albumType":"album","releaseKind":"single"}""" to ReleaseKind.Single,
            // 标题含 BEST 但服务端判定为 EP。
            """{"id":8,"title":"Best of Summer","trackCount":5,"releaseKind":"ep"}""" to ReleaseKind.Ep,
            """{"id":9,"title":"Ordinary","trackCount":20,"releaseKind":"live"}""" to ReleaseKind.Live,
            """{"id":10,"title":"Ordinary","trackCount":2,"releaseKind":"soundtrack"}""" to ReleaseKind.Album,
        )) {
            val album = Music032Json.parseAlbum(JSONObject(json), "https://example.org", "token", section, "032")
            assertEquals(json, kind, classifyArtistAlbum(album).kind)
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
