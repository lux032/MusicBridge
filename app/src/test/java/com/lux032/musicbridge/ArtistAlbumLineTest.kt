package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtistAlbumLineTest {
    @Test fun bothFitKeepNaturalWidths() {
        assertEquals(50 to 80, allocateArtistAlbumWidths(available = 300, artistWidth = 50, albumWidth = 80))
    }

    @Test fun shortArtistGivesRestToLongAlbum() {
        assertEquals(50 to 250, allocateArtistAlbumWidths(available = 300, artistWidth = 50, albumWidth = 500))
    }

    @Test fun shortAlbumGivesRestToLongArtist() {
        assertEquals(240 to 60, allocateArtistAlbumWidths(available = 300, artistWidth = 500, albumWidth = 60))
    }

    @Test fun bothLongSplitByShare() {
        assertEquals(150 to 150, allocateArtistAlbumWidths(available = 300, artistWidth = 400, albumWidth = 400))
        assertEquals(120 to 180, allocateArtistAlbumWidths(300, 400, 400, artistShare = 0.4f))
    }

    @Test fun noSpaceYieldsZeroWidths() {
        assertEquals(0 to 0, allocateArtistAlbumWidths(available = -10, artistWidth = 40, albumWidth = 40))
    }
}
