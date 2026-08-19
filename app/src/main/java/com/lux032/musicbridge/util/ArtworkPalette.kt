package com.lux032.musicbridge

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
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

private fun paletteFromBitmap(bitmap: android.graphics.Bitmap): ArtworkPalette {
    val palette = Palette.from(bitmap)
        .resizeBitmapArea(PaletteRequestPx * PaletteRequestPx)
        .generate()

    val accent = pickAccent(palette)
    val topSeed = palette.darkMutedSwatch?.rgb
        ?: palette.mutedSwatch?.rgb
        ?: palette.dominantSwatch?.rgb
        ?: return DefaultArtworkPalette

    val backgroundTop = Color(ColorUtils.blendARGB(topSeed, android.graphics.Color.BLACK, 0.60f))
    val backgroundBottom = Color(ColorUtils.blendARGB(topSeed, android.graphics.Color.BLACK, 0.88f))
    return ArtworkPalette(
        backgroundTop = backgroundTop,
        backgroundBottom = backgroundBottom,
        accent = accent,
        onAccent = AppColors.SurfaceStrong,
    )
}

/**
 * Prefers the vibrant swatch but keeps the result legible on a dark background: very dark
 * accents are pushed towards white, nearly-white ones are slightly deepened so the glowing
 * play button still reads as a colored element instead of a plain white circle.
 */
private fun pickAccent(palette: Palette): Color {
    val seed = palette.vibrantSwatch?.rgb
        ?: palette.lightVibrantSwatch?.rgb
        ?: palette.lightMutedSwatch?.rgb
        ?: palette.dominantSwatch?.rgb
        ?: return AppColors.Accent

    val luminance = ColorUtils.calculateLuminance(seed)
    val adjusted = when {
        luminance < 0.18 -> ColorUtils.blendARGB(seed, android.graphics.Color.WHITE, 0.55f)
        luminance < 0.30 -> ColorUtils.blendARGB(seed, android.graphics.Color.WHITE, 0.30f)
        luminance > 0.85 -> ColorUtils.blendARGB(seed, android.graphics.Color.BLACK, 0.15f)
        else -> seed
    }
    return Color(adjusted)
}
