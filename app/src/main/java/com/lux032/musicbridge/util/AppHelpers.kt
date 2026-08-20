package com.lux032.musicbridge

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import androidx.compose.ui.graphics.Color
import com.lux032.musicbridge.plex.PlexAlbum
import java.text.Normalizer

data class ArtistSummary(
    val name: String,
    val coverUrl: String?,
    val albumCount: Int,
    val albums: List<PlexAlbum> = emptyList(),
    val ratingKey: String? = null,
    val sourceId: String = "",
    val userRating: Float? = null,
) {
    // Plex has no artist "follow"; a 10-star rating is what Plexamp shows as a liked artist.
    val isFavorite: Boolean
        get() = (userRating ?: 0f) > 0f
}

data class IndexedGroup<T>(
    val label: String,
    val items: List<T>,
)

private data class IndexKeyed<T>(
    val item: T,
    val indexLabel: String,
    val sortKey: String,
)

val KanaIndexOrder = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")
val SupportedIndexOrder = listOf("0-9") + ('A'..'Z').map(Char::toString) + KanaIndexOrder + "#"

object AppColors {
    val BackgroundTop = Color(0xFF050505)
    val BackgroundBottom = Color(0xFF151515)
    val Surface = Color(0xFF141414)
    val SurfaceAlt = Color(0xFF1B1B1B)
    val SurfaceMuted = Color(0xFF222222)
    val SurfaceStrong = Color(0xFF0D0D0D)
    val Border = Color.White.copy(alpha = 0.10f)
    val BorderStrong = Color.White.copy(alpha = 0.18f)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB7B7B7)
    val TextTertiary = Color(0xFF8E8E8E)
    val Accent = Color(0xFFF1F1F1)
    val AccentMuted = Color(0xFF2A2A2A)
    // Glassy card chrome: a light veil that lets the artwork-tinted background gradient
    // show through, matching the player action bar / floating nav pill.
    val CardSurface = Color.White.copy(alpha = 0.07f)
    val CardSurfaceStrong = Color.White.copy(alpha = 0.12f)
    val ErrorBg = Color(0xFF2A1616)
    val ErrorText = Color(0xFFFFB4AB)
}

object AlbumArtworkImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: Context): ImageLoader {
        instance?.let { return it }

        return synchronized(this) {
            instance?.let { return@synchronized it }

            val imageLoader = ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context.applicationContext, 0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.applicationContext.cacheDir.resolve("coil_album_artwork").toOkioPath())
                        .maxSizePercent(0.08)
                        .build()
                }
                .build()

            instance = imageLoader
            imageLoader
        }
    }
}

// Buckets keep the set of distinct transcode sizes small so Coil's memory/disk cache
// can be shared across screens that show the same artwork at similar sizes.
private val ThumbnailSizeBuckets = intArrayOf(256, 512, 768, 1024, 1536)

/**
 * Rewrites a full-resolution Plex artwork URL ("{server}{thumbPath}?X-Plex-Token=…") into a
 * server-side transcode request sized for the actual on-screen target. Original artwork is
 * frequently 1000–3000 px / multi-MB, which is what makes cover-heavy lists stutter; the
 * transcoded variant is a few tens of KB. Falls back to the original URL if it cannot be parsed.
 */
fun plexThumbnailUrl(originalUrl: String, targetPx: Int): String {
    if (targetPx <= 0) return originalUrl
    val uri = android.net.Uri.parse(originalUrl)
    val scheme = uri.scheme ?: return originalUrl
    val authority = uri.authority ?: return originalUrl
    val mediaPath = uri.path?.takeIf { it.isNotBlank() } ?: return originalUrl
    if (mediaPath.contains("/photo/:/transcode")) return originalUrl
    val token = uri.getQueryParameter("X-Plex-Token")?.takeIf { it.isNotBlank() } ?: return originalUrl
    val bucket = ThumbnailSizeBuckets.firstOrNull { it >= targetPx } ?: ThumbnailSizeBuckets.last()
    return android.net.Uri.Builder()
        .scheme(scheme)
        .encodedAuthority(authority)
        .encodedPath("/photo/:/transcode")
        .appendQueryParameter("width", bucket.toString())
        .appendQueryParameter("height", bucket.toString())
        .appendQueryParameter("minSize", "1")
        .appendQueryParameter("upscale", "1")
        .appendQueryParameter("url", mediaPath)
        .appendQueryParameter("X-Plex-Token", token)
        .build()
        .toString()
}

fun indexLabelForText(text: String?): String {
    if (text.isNullOrBlank()) return "#"
    val normalized = normalizedIndexValue(text)
    val firstChar = normalized.firstOrNull() ?: return "#"

    if (firstChar.isDigit()) return "0-9"
    if (firstChar in 'A'..'Z') return firstChar.toString()

    val kanaBlock = when (firstChar) {
        in 'あ'..'お' -> "あ"
        in 'か'..'ご' -> "か"
        in 'さ'..'ぞ' -> "さ"
        in 'た'..'ど' -> "た"
        in 'な'..'の' -> "な"
        in 'は'..'ぽ' -> "は"
        in 'ま'..'も' -> "ま"
        in 'や'..'よ' -> "や"
        in 'ら'..'ろ' -> "ら"
        in 'わ'..'ん' -> "わ"
        else -> null
    }
    return kanaBlock ?: "#"
}

fun normalizedIndexValue(text: String?): String {
    if (text.isNullOrBlank()) return ""
    return Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .uppercase()
}

fun indexOrderOf(label: String): Int {
    return SupportedIndexOrder.indexOf(label).takeIf { it >= 0 } ?: SupportedIndexOrder.size
}

fun <T> buildIndexedGroups(
    items: List<T>,
    labelOf: (T) -> String?,
): List<IndexedGroup<T>> {
    // Precompute the index label and normalized sort key once per item so the
    // comparator below does not re-run Normalizer/regex on every comparison.
    val keyed = items.map { item ->
        val label = labelOf(item)
        IndexKeyed(item, indexLabelForText(label), normalizedIndexValue(label))
    }
    return keyed
        .sortedWith(
            compareBy<IndexKeyed<T>> { indexOrderOf(it.indexLabel) }
                .thenBy { it.sortKey }
        )
        .groupBy { it.indexLabel }
        .entries
        .sortedBy { indexOrderOf(it.key) }
        .map { entry -> IndexedGroup(label = entry.key, items = entry.value.map { it.item }) }
}

fun <T> buildListJumpTargets(
    leadingItemCount: Int,
    groups: List<IndexedGroup<T>>,
): List<Pair<String, Int>> {
    var index = leadingItemCount
    return buildList {
        groups.forEach { group ->
            add(group.label to index)
            index += 1 + group.items.size
        }
    }
}

fun <T> buildGridJumpTargets(
    leadingItemCount: Int,
    groups: List<IndexedGroup<T>>,
): List<Pair<String, Int>> {
    var index = leadingItemCount
    return buildList {
        groups.forEach { group ->
            add(group.label to index)
            index += 1 + group.items.size
        }
    }
}
