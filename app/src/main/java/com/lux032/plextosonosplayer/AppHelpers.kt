package com.lux032.plextosonosplayer

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import okio.Path.Companion.toOkioPath
import androidx.compose.ui.graphics.Color
import com.lux032.plextosonosplayer.plex.PlexAlbum
import java.text.Normalizer

data class ArtistSummary(
    val name: String,
    val coverUrl: String?,
    val albumCount: Int,
    val albums: List<PlexAlbum> = emptyList(),
)

data class IndexedGroup<T>(
    val label: String,
    val items: List<T>,
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
    return items
        .sortedWith(
            compareBy<T> { indexOrderOf(indexLabelForText(labelOf(it))) }
                .thenBy { normalizedIndexValue(labelOf(it)) }
        )
        .groupBy { indexLabelForText(labelOf(it)) }
        .entries
        .sortedBy { indexOrderOf(it.key) }
        .map { IndexedGroup(label = it.key, items = it.value) }
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
