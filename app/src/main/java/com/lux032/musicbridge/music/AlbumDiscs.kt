package com.lux032.musicbridge.music

import java.util.Locale

/** One disc slice inside a multi-disc (or single-disc) album track list. */
data class AlbumDiscGroup<T>(
    val discNumber: Int,
    val tracks: List<T>,
    val totalDurationMillis: Long,
)

/** Normalize missing or non-positive disc numbers to disc 1, matching 032player-rs. */
fun normalizedDiscNumber(discNumber: Int?): Int = (discNumber ?: 1).coerceAtLeast(1)

/**
 * Detect whether a track list spans multiple discs by inspecting normalized track disc numbers.
 * Mirrors 032player-rs commit 79e9b17 so an album with only "Disc 2" tracks or a stale
 * `discCount > 1` with one actual disc is not split into a lone disc header.
 */
fun <T> hasMultipleDiscs(items: List<T>, discNumberOf: (T) -> Int?): Boolean {
    if (items.isEmpty()) return false
    val firstDisc = normalizedDiscNumber(discNumberOf(items[0]))
    return items.any { normalizedDiscNumber(discNumberOf(it)) != firstDisc }
}

fun List<MusicTrack>.hasMultipleDiscs(): Boolean = hasMultipleDiscs(this) { it.discNumber }

fun isMultiDiscAlbum(album: MusicAlbum, tracks: List<MusicTrack>): Boolean =
    if (tracks.isEmpty()) (album.discCount ?: 1) > 1 else tracks.hasMultipleDiscs()

fun effectiveDiscCount(album: MusicAlbum, tracks: List<MusicTrack>): Int =
    if (tracks.isEmpty()) {
        (album.discCount ?: 1).coerceAtLeast(1)
    } else {
        tracks.map { normalizedDiscNumber(it.discNumber) }.distinct().size.coerceAtLeast(1)
    }

/** Sort album tracks by normalized disc, track number, and case-folded title. */
fun List<MusicTrack>.sortedInDiscOrder(): List<MusicTrack> =
    sortedWith(
        compareBy<MusicTrack> { normalizedDiscNumber(it.discNumber) }
            .thenBy { it.trackNumber ?: Int.MAX_VALUE }
            .thenBy { it.title.lowercase(Locale.ROOT) }
    )

/** Group tracks by normalized disc number in ascending disc order. */
fun <T> groupTracksByDisc(
    items: List<T>,
    discNumberOf: (T) -> Int?,
    durationOf: (T) -> Long?,
): List<AlbumDiscGroup<T>> =
    items.groupBy { normalizedDiscNumber(discNumberOf(it)) }
        .toSortedMap()
        .map { (disc, discTracks) ->
            AlbumDiscGroup(
                discNumber = disc,
                tracks = discTracks,
                totalDurationMillis = discTracks.sumOf { (durationOf(it) ?: 0L).coerceAtLeast(0L) },
            )
        }

fun List<MusicTrack>.groupByDisc(): List<AlbumDiscGroup<MusicTrack>> =
    groupTracksByDisc(this, { it.discNumber }, { it.durationMillis })
