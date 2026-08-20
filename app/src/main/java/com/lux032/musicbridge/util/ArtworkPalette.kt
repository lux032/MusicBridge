package com.lux032.musicbridge

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Color set derived from the current album artwork. Drives the immersive playback screen:
 * a dark tinted background gradient plus one bright accent used for the progress bar,
 * the glowing play button and selected/favorite states.
 */
@Immutable
data class ArtworkPalette(
    val backgroundTop: Color,
    val backgroundBottom: Color,
    val accent: Color,
    val onAccent: Color,
)

val DefaultArtworkPalette = ArtworkPalette(
    backgroundTop = AppColors.BackgroundTop,
    backgroundBottom = AppColors.BackgroundBottom,
    accent = AppColors.Accent,
    onAccent = AppColors.SurfaceStrong,
)

/**
 * App-wide tint source. The root provides the now-playing track's palette; detail pages may
 * override it locally with their own artwork's palette. Components read [accent] from here so
 * highlights follow the current cover without threading colors through every signature.
 */
val LocalArtworkPalette = compositionLocalOf { DefaultArtworkPalette }

// Palette results are cached per artwork URL: tracks from the same album share the cover,
// and re-entering the player must not re-decode anything. A tiny 96px decode keeps both the
// initial extraction and the blurred background cheap.
private const val PaletteRequestPx = 96
private const val MaxCacheEntries = 64
private val paletteCacheLock = Any()
private val paletteCache = object : LinkedHashMap<String, ArtworkPalette>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkPalette>?): Boolean =
        size > MaxCacheEntries
}

/**
 * Resolves the [ArtworkPalette] for an artwork URL, extracting it off the main thread the
 * first time. Falls back to [DefaultArtworkPalette] when there is no artwork or decoding fails.
 */
@Composable
fun rememberArtworkPalette(imageUrl: String?): ArtworkPalette {
    val context = LocalContext.current
    val state = produceState<ArtworkPalette>(
        initialValue = imageUrl?.let { synchronized(paletteCacheLock) { paletteCache[it] } }
            ?: DefaultArtworkPalette,
        key1 = imageUrl,
    ) {
        if (imageUrl == null) {
            value = DefaultArtworkPalette
            return@produceState
        }
        synchronized(paletteCacheLock) { paletteCache[imageUrl] }?.let {
            value = it
            return@produceState
        }
        value = withContext(Dispatchers.Default) {
            extractArtworkPalette(context, imageUrl)
        }
        synchronized(paletteCacheLock) { paletteCache[imageUrl] = value }
    }
    return state.value
}

/** Animated variant: every color cross-fades when the artwork (and thus palette) changes. */
@Composable
fun rememberAnimatedArtworkPalette(imageUrl: String?): ArtworkPalette {
    val target = rememberArtworkPalette(imageUrl)
    val backgroundTop by animateArtworkColor(target.backgroundTop, "palette_bg_top")
    val backgroundBottom by animateArtworkColor(target.backgroundBottom, "palette_bg_bottom")
    val accent by animateArtworkColor(target.accent, "palette_accent")
    val onAccent by animateArtworkColor(target.onAccent, "palette_on_accent")
    return ArtworkPalette(
        backgroundTop = backgroundTop,
        backgroundBottom = backgroundBottom,
        accent = accent,
        onAccent = onAccent,
    )
}

@Composable
private fun animateArtworkColor(target: Color, label: String): State<Color> =
    animateColorAsState(targetValue = target, animationSpec = tween(durationMillis = 600), label = label)

private suspend fun extractArtworkPalette(context: android.content.Context, imageUrl: String): ArtworkPalette {
    return try {
        val request = ImageRequest.Builder(context)
            .data(plexThumbnailUrl(imageUrl, PaletteRequestPx))
            .size(Size(PaletteRequestPx, PaletteRequestPx))
            .allowHardware(false)
            .build()
        val result = AlbumArtworkImageLoader.get(context).execute(request)
        val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return DefaultArtworkPalette
        paletteFromBitmap(bitmap)
    } catch (_: Exception) {
        DefaultArtworkPalette
    }
}

/**
 * Plexamp-style theming: the background keeps the cover's hue clearly visible (a coloured
 * wash, not near-black), and the accent stays a real colour. Everything is derived in HSL so
 * saturation survives — the previous version blended towards black/white in RGB, which crushed
 * the background to black and washed the accent out to white on pastel covers.
 *
 * Truly monochrome artwork (max saturation below [MonochromeSaturationFloor]) falls back to the
 * neutral dark palette instead of forcing a tint.
 */
private const val MonochromeSaturationFloor = 0.12f

private fun paletteFromBitmap(bitmap: android.graphics.Bitmap): ArtworkPalette {
    val palette = Palette.from(bitmap)
        .resizeBitmapArea(PaletteRequestPx * PaletteRequestPx)
        .generate()
    val swatches = palette.swatches
    if (swatches.isEmpty()) return DefaultArtworkPalette

    // Background seed: the most *colourful dominant* swatch — saturation weighted by reach.
    // (darkMuted was the old first choice, but it is usually a greyish brown and produced black.)
    val hsl = FloatArray(3)
    var seedRgb = 0
    var seedScore = -1f
    var maxSaturation = 0f
    for (swatch in swatches) {
        ColorUtils.colorToHSL(swatch.rgb, hsl)
        if (hsl[1] > maxSaturation) maxSaturation = hsl[1]
        val score = hsl[1] * sqrt(swatch.population.toFloat())
        if (score > seedScore) {
            seedScore = score
            seedRgb = swatch.rgb
        }
    }
    if (maxSaturation < MonochromeSaturationFloor) return DefaultArtworkPalette

    ColorUtils.colorToHSL(seedRgb, hsl)
    val hue = hsl[0]
    val topSaturation = hsl[1].coerceIn(0.30f, 0.55f)
    val backgroundTop = Color(ColorUtils.HSLToColor(floatArrayOf(hue, topSaturation, 0.32f)))
    val backgroundBottom = Color(
        ColorUtils.HSLToColor(floatArrayOf(hue, (topSaturation * 0.8f).coerceIn(0.25f, 0.50f), 0.14f))
    )
    return ArtworkPalette(
        backgroundTop = backgroundTop,
        backgroundBottom = backgroundBottom,
        accent = pickAccent(palette, maxSaturation),
        onAccent = AppColors.SurfaceStrong,
    )
}

/**
 * Prefers the vibrant swatch, then the most saturated one. Lightness is lifted into the
 * 0.55–0.70 band *in HSL* so the accent stays colourful on dark backgrounds (blending towards
 * white in RGB, the old approach, desaturated it). Grey covers fall back to the neutral accent.
 */
private fun pickAccent(palette: Palette, maxSaturation: Float): Color {
    if (maxSaturation < MonochromeSaturationFloor) return AppColors.Accent
    val hsl = FloatArray(3)
    val candidate = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.swatches.maxByOrNull { swatch ->
            ColorUtils.colorToHSL(swatch.rgb, hsl)
            hsl[1]
        }?.rgb
        ?: return AppColors.Accent

    ColorUtils.colorToHSL(candidate, hsl)
    if (hsl[1] < 0.15f) return AppColors.Accent
    return Color(
        ColorUtils.HSLToColor(
            floatArrayOf(hsl[0], hsl[1].coerceAtLeast(0.45f), hsl[2].coerceIn(0.55f, 0.70f))
        )
    )
}
