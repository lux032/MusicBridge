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
import androidx.compose.ui.graphics.toArgb
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

private val OnAccentLight = Color(0xFFF5F5F5)
private val OnAccentDark = Color(0xFF0D0D0D)

val DefaultArtworkPalette = ArtworkPalette(
    // Keep the neutral fallback moving in the same direction as extracted palettes:
    // a slightly lifted top that settles into the darkest tone at the bottom.
    backgroundTop = AppColors.BackgroundBottom,
    backgroundBottom = AppColors.BackgroundTop,
    accent = AppColors.Accent,
    onAccent = onAccentFor(AppColors.Accent),
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
    return ArtworkPalette(
        backgroundTop = backgroundTop,
        backgroundBottom = backgroundBottom,
        accent = accent,
        // Derive this from the animated accent instead of cross-fading black and white through
        // an unreadable middle grey during palette changes.
        onAccent = onAccentFor(accent),
    )
}

@Composable
private fun animateArtworkColor(target: Color, label: String): State<Color> =
    animateColorAsState(targetValue = target, animationSpec = tween(durationMillis = 600), label = label)

private suspend fun extractArtworkPalette(context: android.content.Context, imageUrl: String): ArtworkPalette {
    return try {
        val request = ImageRequest.Builder(context)
            .data(thumbnailUrl(imageUrl, PaletteRequestPx))
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
 * Plexamp-style theming: the background keeps the cover's dominant colourful hue visible while
 * the accent remains close enough to that hue to form a coherent page. Near-monochrome artwork
 * blends gradually into the neutral palette rather than crossing a hard saturation threshold.
 */
private const val NeutralBlendStartSaturation = 0.08f
private const val NeutralBlendEndSaturation = 0.20f
private const val AccentHueRangeDegrees = 30f

private fun paletteFromBitmap(bitmap: android.graphics.Bitmap): ArtworkPalette {
    val palette = Palette.from(bitmap)
        .resizeBitmapArea(PaletteRequestPx * PaletteRequestPx)
        .generate()
    val swatches = palette.swatches
    if (swatches.isEmpty()) return DefaultArtworkPalette

    // Background seed: favour colour that also occupies meaningful artwork area.
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

    ColorUtils.colorToHSL(seedRgb, hsl)
    val seedHue = hsl[0]
    val topSaturation = hsl[1].coerceIn(0.30f, 0.55f)
    val extractedBackgroundTop = capLuminance(
        hue = seedHue,
        saturation = topSaturation,
        initialLightness = 0.32f,
        maxLuminance = 0.06,
    )
    val extractedBackgroundBottom = Color(
        ColorUtils.HSLToColor(
            floatArrayOf(seedHue, (topSaturation * 0.8f).coerceIn(0.25f, 0.50f), 0.14f)
        )
    )

    val saturationFactor = smoothSaturationFactor(maxSaturation)
    val backgroundTop = blendColor(DefaultArtworkPalette.backgroundTop, extractedBackgroundTop, saturationFactor)
    val backgroundBottom = blendColor(DefaultArtworkPalette.backgroundBottom, extractedBackgroundBottom, saturationFactor)
    val extractedAccent = pickAccent(palette, seedHue)
    val accent = blendColor(DefaultArtworkPalette.accent, extractedAccent, saturationFactor)

    return ArtworkPalette(
        backgroundTop = backgroundTop,
        backgroundBottom = backgroundBottom,
        accent = accent,
        onAccent = onAccentFor(accent),
    )
}

/**
 * Prefers a vibrant artwork swatch but constrains it to within 30 degrees of the background hue.
 * This retains nearby cover variation without allowing unrelated small details to recolour the UI.
 */
private fun pickAccent(palette: Palette, seedHue: Float): Color {
    val hsl = FloatArray(3)
    val candidate = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.swatches.maxByOrNull { swatch ->
            ColorUtils.colorToHSL(swatch.rgb, hsl)
            hsl[1]
        }?.rgb

    if (candidate != null) {
        ColorUtils.colorToHSL(candidate, hsl)
    } else {
        hsl[0] = seedHue
        hsl[1] = 0.60f
        hsl[2] = 0.62f
    }

    val hueDelta = shortestHueDelta(seedHue, hsl[0]).coerceIn(-AccentHueRangeDegrees, AccentHueRangeDegrees)
    val accentHue = (seedHue + hueDelta + 360f) % 360f
    val accentSaturation = hsl[1].coerceIn(0.60f, 0.85f)
    val accentLightness = hsl[2].coerceIn(0.55f, 0.70f)
    return Color(
        ColorUtils.HSLToColor(floatArrayOf(accentHue, accentSaturation, accentLightness))
    )
}

private fun shortestHueDelta(fromHue: Float, toHue: Float): Float =
    ((toHue - fromHue + 540f) % 360f) - 180f

private fun smoothSaturationFactor(saturation: Float): Float {
    val linear = ((saturation - NeutralBlendStartSaturation) /
        (NeutralBlendEndSaturation - NeutralBlendStartSaturation)).coerceIn(0f, 1f)
    return linear * linear * (3f - 2f * linear)
}

private fun blendColor(from: Color, to: Color, fraction: Float): Color =
    Color(ColorUtils.blendARGB(from.toArgb(), to.toArgb(), fraction))

/**
 * Preserves hue and saturation while lowering HSL lightness until the resulting colour stays
 * below [maxLuminance]. Binary search keeps bright yellow/green/cyan hues from lifting the top
 * of the page enough to weaken secondary-text contrast.
 */
private fun capLuminance(
    hue: Float,
    saturation: Float,
    initialLightness: Float,
    maxLuminance: Double,
): Color {
    fun colorAt(lightness: Float): Color =
        Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)))

    val initial = colorAt(initialLightness)
    if (ColorUtils.calculateLuminance(initial.toArgb()) <= maxLuminance) return initial

    var low = 0f
    var high = initialLightness
    repeat(16) {
        val middle = (low + high) / 2f
        if (ColorUtils.calculateLuminance(colorAt(middle).toArgb()) <= maxLuminance) {
            low = middle
        } else {
            high = middle
        }
    }
    return colorAt(low)
}

internal fun onAccentFor(accent: Color): Color {
    val backgroundLuminance = ColorUtils.calculateLuminance(accent.toArgb())
    val lightContrast =
        (ColorUtils.calculateLuminance(OnAccentLight.toArgb()) + 0.05) / (backgroundLuminance + 0.05)
    val darkContrast =
        (backgroundLuminance + 0.05) / (ColorUtils.calculateLuminance(OnAccentDark.toArgb()) + 0.05)
    return if (lightContrast >= darkContrast) OnAccentLight else OnAccentDark
}
