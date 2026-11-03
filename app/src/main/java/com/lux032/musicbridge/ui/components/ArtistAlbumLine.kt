package com.lux032.musicbridge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

/**
 * Widths for the "Artist · Album · Year" line, after the year has been reserved.
 *
 * Whoever is short keeps its natural width and the other takes the rest; only when both
 * overflow their share is the line split by [artistShare] (artist) vs the remainder (album).
 */
internal fun allocateArtistAlbumWidths(
    available: Int,
    artistWidth: Int,
    albumWidth: Int,
    artistShare: Float = 0.5f,
): Pair<Int, Int> {
    val space = available.coerceAtLeast(0)
    if (artistWidth + albumWidth <= space) return artistWidth to albumWidth
    val artistCap = (space * artistShare).toInt()
    val albumCap = space - artistCap
    return when {
        artistWidth <= artistCap -> artistWidth to (space - artistWidth)
        albumWidth <= albumCap -> (space - albumWidth) to albumWidth
        else -> artistCap to albumCap
    }
}

/**
 * Single-line "Artist · Album · Year" row. The year (if any) is never truncated; artist and
 * album share the remaining width via [allocateArtistAlbumWidths], so a short part never
 * wastes space the long part could use. Children are expected to ellipsize themselves.
 */
@Composable
internal fun ArtistAlbumYearLine(
    artist: @Composable () -> Unit,
    album: (@Composable () -> Unit)?,
    year: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
    artistShare: Float = 0.5f,
) {
    Layout(
        modifier = modifier,
        content = {
            artist()
            album?.invoke()
            year?.invoke()
        },
    ) { measurables, constraints ->
        val artistM = measurables[0]
        val albumM = if (album != null) measurables[1] else null
        val yearM = if (year != null) measurables.last() else null
        val maxWidth = constraints.maxWidth
        val bounded = maxWidth != Constraints.Infinity

        val yearPlaceable = yearM?.measure(Constraints(maxWidth = if (bounded) maxWidth else Constraints.Infinity))
        val yearWidth = yearPlaceable?.width ?: 0

        val (artistWidth, albumWidth) = if (bounded) {
            val remaining = (maxWidth - yearWidth).coerceAtLeast(0)
            if (albumM == null) {
                remaining to 0
            } else {
                allocateArtistAlbumWidths(
                    available = remaining,
                    artistWidth = artistM.maxIntrinsicWidth(Constraints.Infinity),
                    albumWidth = albumM.maxIntrinsicWidth(Constraints.Infinity),
                    artistShare = artistShare,
                )
            }
        } else {
            Constraints.Infinity to Constraints.Infinity
        }

        val artistPlaceable = artistM.measure(Constraints(maxWidth = artistWidth))
        val albumPlaceable = albumM?.measure(Constraints(maxWidth = albumWidth))
        val placeables = listOfNotNull(artistPlaceable, albumPlaceable, yearPlaceable)

        val width = placeables.sumOf { it.width }.coerceIn(constraints.minWidth, maxWidth)
        val height = placeables.maxOf { it.height }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            var x = 0
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width
            }
        }
    }
}
