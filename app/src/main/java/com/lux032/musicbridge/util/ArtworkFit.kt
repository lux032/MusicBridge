package com.lux032.musicbridge

import kotlin.math.ceil
import kotlin.math.floor

/**
 * How album artwork is placed in its reserved box.
 *
 * [Cover] fills the box and center-crops. [Contain] preserves aspect ratio:
 * ratios in `0.94..1.06` snap to 1:1, extremes clamp to 1:2..2:1 (the excess
 * is center-cropped into that box), and everything else is letterboxed.
 * Matches `fitted_size` / `ArtworkFit` in 032player-rs.
 */
internal enum class ArtworkFit {
    Cover,
    Contain,
}

private const val MIN_ASPECT = 0.5
private const val MAX_ASPECT = 2.0
private const val NEAR_SQUARE_MIN = 0.94
private const val NEAR_SQUARE_MAX = 1.06

/**
 * Target `(width, height)` inside `(maxW, maxH)` for [ArtworkFit.Contain].
 * A zero side returns `(srcW, srcH)`. Rounding matches Rust `f64::round`
 * (half away from zero), not Kotlin's half-to-even.
 */
internal fun fittedSize(srcW: Int, srcH: Int, maxW: Int, maxH: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0 || maxW <= 0 || maxH <= 0) {
        return srcW to srcH
    }
    val rawRatio = srcW.toDouble() / srcH.toDouble()
    val ratio = if (rawRatio in NEAR_SQUARE_MIN..NEAR_SQUARE_MAX) {
        1.0
    } else {
        rawRatio.coerceIn(MIN_ASPECT, MAX_ASPECT)
    }
    val boxRatio = maxW.toDouble() / maxH.toDouble()
    return if (boxRatio > ratio) {
        val width = (maxH.toDouble() * ratio).roundHalfAwayFromZero().coerceIn(1, maxW)
        width to maxH
    } else {
        val height = (maxW.toDouble() / ratio).roundHalfAwayFromZero().coerceIn(1, maxH)
        maxW to height
    }
}

/** Rust `f64::round`: half away from zero. */
private fun Double.roundHalfAwayFromZero(): Int {
    val rounded = if (this >= 0.0) floor(this + 0.5) else ceil(this - 0.5)
    return rounded.toInt()
}
