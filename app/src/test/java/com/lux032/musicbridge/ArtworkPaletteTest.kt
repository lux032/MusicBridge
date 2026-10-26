package com.lux032.musicbridge

import androidx.compose.ui.graphics.toArgb
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class ArtworkPaletteTest {

    private fun hueOf(argb: Int): Float {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(argb, hsl)
        return hsl[0]
    }

    private fun assertHueNear(expected: Float, actualArgb: Int, tolerance: Float = 12f) {
        val actual = hueOf(actualArgb)
        val delta = kotlin.math.abs(((actual - expected + 540f) % 360f) - 180f)
        assertTrue("hue $actual not within $tolerance of $expected", delta <= tolerance)
    }

    @Test
    fun chromaIgnoresLightnessInflation() {
        // Cream has HSL S ~0.44 but is visually almost grey.
        assertTrue(chromaOf(0xFFF3E6E0.toInt()) < 0.1f)
        assertTrue(chromaOf(0xFF6E8BA6.toInt()) > 0.2f)
        assertEquals(0f, chromaOf(0xFF808080.toInt()), 0f)
    }

    @Test
    fun paleCreamBackgroundDoesNotHijackBlueGrayArtwork() {
        // "Patient A": large cream paper background + slate blue-grey print.
        val palette = artworkPaletteFromSwatches(
            listOf(
                ArtworkSwatch(0xFFF3E6E0.toInt(), population = 6000),
                ArtworkSwatch(0xFFEFE2DA.toInt(), population = 1500),
                ArtworkSwatch(0xFF6E8BA6.toInt(), population = 2500),
                ArtworkSwatch(0xFF7891A8.toInt(), population = 800),
            )
        )
        assertHueNear(209f, palette.backgroundTop.toArgb())
        assertHueNear(209f, palette.backgroundBottom.toArgb())
        assertHueNear(209f, palette.accent.toArgb())
    }

    @Test
    fun saturatedSingleColourCoverKeepsItsHue() {
        val palette = artworkPaletteFromSwatches(
            listOf(
                ArtworkSwatch(0xFFD02020.toInt(), population = 5000),
                ArtworkSwatch(0xFF101010.toInt(), population = 3000),
            )
        )
        assertHueNear(0f, palette.backgroundTop.toArgb())
        assertHueNear(0f, palette.accent.toArgb())
    }

    @Test
    fun nearMonochromeCoverFallsBackToDefault() {
        val palette = artworkPaletteFromSwatches(
            listOf(
                ArtworkSwatch(0xFFF5F2F0.toInt(), population = 5000), // off-white, HSL S ~0.2
                ArtworkSwatch(0xFF1A1716.toInt(), population = 4000), // near-black, HSL S ~0.08
                ArtworkSwatch(0xFF808080.toInt(), population = 1000),
            )
        )
        assertEquals(DefaultArtworkPalette.backgroundTop.toArgb(), palette.backgroundTop.toArgb())
        assertEquals(DefaultArtworkPalette.backgroundBottom.toArgb(), palette.backgroundBottom.toArgb())
        assertEquals(DefaultArtworkPalette.accent.toArgb(), palette.accent.toArgb())
    }

    @Test
    fun smallBrightDetailOutsideHueRangeDoesNotBecomeAccent() {
        // Dark teal cover with a tiny yellow logo: accent must stay near teal.
        val palette = artworkPaletteFromSwatches(
            listOf(
                ArtworkSwatch(0xFF1F5F66.toInt(), population = 8000),
                ArtworkSwatch(0xFFFFD000.toInt(), population = 60),
            )
        )
        assertHueNear(186f, palette.backgroundTop.toArgb())
        assertHueNear(186f, palette.accent.toArgb(), tolerance = 30f)
    }

    @Test
    fun emptySwatchesUseDefault() {
        assertEquals(DefaultArtworkPalette, artworkPaletteFromSwatches(emptyList()))
    }
}
