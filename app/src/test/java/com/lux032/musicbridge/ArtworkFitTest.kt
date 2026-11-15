package com.lux032.musicbridge

import org.junit.Assert.assertEquals
import org.junit.Test

/** Mirrors `contain_fits_and_clamps_aspect_ratios` in 032player-rs `images.rs`. */
class ArtworkFitTest {
    @Test fun tallPosterFitsInsideSquare() {
        // 2:3 inside 300×300 → 200×300.
        assertEquals(200 to 300, fittedSize(400, 600, 300, 300))
    }

    @Test fun wideLandscapeFitsInsideSquare() {
        // 3:2 inside 300×300 → 300×200.
        assertEquals(300 to 200, fittedSize(600, 400, 300, 300))
    }

    @Test fun nearSquareSnapsToOneToOne() {
        // 0.94..=1.06 snaps to 1:1.
        assertEquals(300 to 300, fittedSize(1000, 960, 300, 300))
        assertEquals(300 to 300, fittedSize(960, 1000, 300, 300))
        assertEquals(300 to 300, fittedSize(94, 100, 300, 300))
        assertEquals(300 to 300, fittedSize(106, 100, 300, 300))
    }

    @Test fun justOutsideNearSquareKeepsAspect() {
        // 900:1000 = 0.9 is outside the snap window.
        assertEquals(270 to 300, fittedSize(900, 1000, 300, 300))
    }

    @Test fun extremeRatiosClampToOneToTwo() {
        // 100×500 and 500×100 clamp to 1:2 and 2:1.
        assertEquals(150 to 300, fittedSize(100, 500, 300, 300))
        assertEquals(300 to 150, fittedSize(500, 100, 300, 300))
    }

    @Test fun halfPixelsRoundAwayFromZero() {
        // 301 × 0.5 = 150.5. Rust rounds half away from zero (151), not to even.
        assertEquals(151 to 301, fittedSize(1, 2, 301, 301))
    }

    @Test fun zeroSideReturnsSource() {
        assertEquals(0 to 40, fittedSize(0, 40, 300, 300))
        assertEquals(40 to 0, fittedSize(40, 0, 300, 300))
        assertEquals(40 to 60, fittedSize(40, 60, 0, 300))
        assertEquals(40 to 60, fittedSize(40, 60, 300, 0))
    }
}
