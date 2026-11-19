package com.lux032.musicbridge.model

import com.lux032.musicbridge.Strings
import com.lux032.musicbridge.albumDetailMetaText
import com.lux032.musicbridge.albumTrackRowPerformerSummary
import com.lux032.musicbridge.formattedReleaseDate
import com.lux032.musicbridge.hasDifferentPerformersFrom
import com.lux032.musicbridge.music.ArtistRef
import com.lux032.musicbridge.music.LibrarySection
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.resolvedPerformers
import com.lux032.musicbridge.sourceAudioDetailText
import com.lux032.musicbridge.splitArtistNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumDetailMetadataTest {
    private val section = LibrarySection("1", "Music", "artist")

    @Test fun formattedReleaseDatePreservesAvailablePrecisionAndFallsBackToYear() {
        assertEquals("2024-05-15", album(2024, "2024-05-15").formattedReleaseDate())
        assertEquals("2024-05-01", album(2024, "2024/5/1").formattedReleaseDate())
        assertEquals("2024-05-15", album(2024, "2024-05-15T00:00:00Z").formattedReleaseDate())
        assertEquals("2024-08", album(2024, "2024-08").formattedReleaseDate())
        assertEquals("2024", album(2024, "2024").formattedReleaseDate())
        // Edited year that contradicts tagged date wins, matching releaseSortKey().
        assertEquals("2025", album(2025, "2024-05-15").formattedReleaseDate())
        assertEquals("1999", album(1999, null).formattedReleaseDate())
        assertEquals("1999", album(1999, "unknown").formattedReleaseDate())
        assertNull(album(null, null).formattedReleaseDate())
    }

    @Test fun albumDetailMetaTextCombinesDateDiscsTracksDurationAndCodec() {
        val sampleAlbum = album(2010, "2010-10-27")
        val tracks = listOf(
            track("1", durationMillis = 120_000L, codec = "flac"),
            track("2", durationMillis = 185_000L, codec = "flac"),
        )
        assertEquals(
            "2010-10-27 · ${Strings.discCountFact(2)} · ${Strings.tracks(2)} · 5:05 · FLAC",
            albumDetailMetaText(sampleAlbum, tracks, isMultiDisc = true, discCount = 2),
        )
        assertEquals(
            "2010-10-27 · ${Strings.tracks(2)} · 5:05 · FLAC",
            albumDetailMetaText(sampleAlbum, tracks, isMultiDisc = false, discCount = 1),
        )
    }

    @Test fun trackRowPerformerSummaryHidesMatchingAlbumArtistAndCondensesManyPerformers() {
        val soloAlbum = album(2024, "2024-01-01", artistName = "Aimer", artists = listOf(ArtistRef("1", "Aimer")))

        val sameArtistTrack = track("1", artistName = "Aimer", artists = listOf(ArtistRef("1", "Aimer")))
        assertFalse(sameArtistTrack.hasDifferentPerformersFrom(soloAlbum))
        assertNull(sameArtistTrack.albumTrackRowPerformerSummary(soloAlbum))

        val duetTrack = track(
            "2",
            artistName = "Aimer, milet",
            artists = listOf(ArtistRef("1", "Aimer"), ArtistRef("2", "milet")),
        )
        assertTrue(duetTrack.hasDifferentPerformersFrom(soloAlbum))
        assertEquals("Aimer, milet", duetTrack.albumTrackRowPerformerSummary(soloAlbum))

        val fiveSingersTrack = track(
            "3",
            artistName = "S1 feat. S2 & S3, S4, S5",
            artists = (1..5).map { ArtistRef(it.toString(), "S$it", if (it == 2) " feat. " else null) },
        )
        assertEquals("S1 feat. S2 & S3, S4, S5", fiveSingersTrack.albumTrackRowPerformerSummary(soloAlbum))
        assertEquals(
            Strings.trackPerformersSummary("S1", "S2", 5, " feat. "),
            fiveSingersTrack.albumTrackRowPerformerSummary(soloAlbum, compact = true),
        )
    }

    @Test fun albumTrackRowShowsFeatureEvenWhenTrackAndAlbumCreditTheSameArtists() {
        val refs = listOf(ArtistRef("1", "Aiobahn +81"), ArtistRef("2", "DEMONDICE"))
        val release = album(2026, "2026-09-30", artistName = "Aiobahn +81, DEMONDICE", artists = refs)
        val song = track("1", artistName = "Aiobahn +81 feat. DEMONDICE", artists = refs)
        assertEquals("Aiobahn +81 feat. DEMONDICE", song.albumTrackRowPerformerSummary(release))
    }

    @Test fun trackPerformerComparisonFallsBackToNamesWhenIdsAreAbsent() {
        val compilationAlbum = album(2024, "2024-03-01", artistName = "Various Artists", artists = emptyList())
        val guestTrack = track("1", artistName = "ReoNa、SawanoHiroyuki[nZk]", artists = emptyList())
        assertTrue(guestTrack.hasDifferentPerformersFrom(compilationAlbum))
        assertEquals(
            "ReoNa、SawanoHiroyuki[nZk]",
            guestTrack.albumTrackRowPerformerSummary(compilationAlbum),
        )

        val sameNameTrack = track("2", artistName = "various artists", artists = emptyList())
        assertFalse(sameNameTrack.hasDifferentPerformersFrom(compilationAlbum))
        assertNull(sameNameTrack.albumTrackRowPerformerSummary(compilationAlbum))
    }

    @Test fun resolvedPerformersFallBackToAlbumArtistsWhenTrackArtistsAreEmpty() {
        val sampleAlbum = album(
            2024,
            "2024-01-01",
            artistName = "HTT",
            artists = listOf(ArtistRef("10", "HTT")),
        )
        val emptyTrack = track("1", artistName = null, artists = emptyList())
        assertEquals(listOf(ArtistRef("10", "HTT")), emptyTrack.resolvedPerformers(sampleAlbum))

        val splitTrack = track("2", artistName = "A, B / C", artists = emptyList())
        assertEquals(
            listOf(ArtistRef("", "A"), ArtistRef("", "B"), ArtistRef("", "C")),
            splitTrack.resolvedPerformers(sampleAlbum),
        )
        assertEquals(listOf("A", "B", "C"), splitArtistNames("A, B / C、A"))
    }

    @Test fun sourceAudioDetailTextFormatsSpecsOrDash() {
        val hiRes = track("1", codec = "flac", bitDepth = 24, sampleRate = 96_000, bitrateKbps = 2840)
        assertEquals("FLAC · 24bit · 96kHz · 2840 kbps", sourceAudioDetailText(hiRes))
        assertEquals("—", sourceAudioDetailText(track("2")))
    }

    private fun album(
        year: Int?,
        releaseDate: String?,
        artistName: String? = "Artist",
        artists: List<ArtistRef> = emptyList(),
    ) = MusicAlbum(
        ratingKey = "album-1",
        title = "Album",
        artistName = artistName,
        year = year,
        thumbUrl = null,
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = section,
        releaseDate = releaseDate,
        artists = artists,
    )

    private fun track(
        id: String,
        artistName: String? = null,
        artists: List<ArtistRef> = emptyList(),
        durationMillis: Long? = null,
        codec: String? = null,
        bitDepth: Int? = null,
        sampleRate: Int? = null,
        bitrateKbps: Int? = null,
    ) = MusicTrack(
        ratingKey = id,
        title = "Track $id",
        streamUrl = "",
        partKey = "",
        durationMillis = durationMillis,
        userRating = null,
        artistName = artistName,
        artists = artists,
        sourceAudioCodec = codec,
        bitDepth = bitDepth,
        sourceAudioSampleRate = sampleRate,
        sourceAudioBitrateKbps = bitrateKbps,
    )
}
