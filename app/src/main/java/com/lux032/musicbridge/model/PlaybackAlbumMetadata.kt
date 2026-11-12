package com.lux032.musicbridge

import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.LibrarySection

internal fun MusicTrack.resolvePlaybackAlbum(
    albums: List<MusicAlbum>,
    fallback: MusicAlbum?,
): MusicAlbum = albums.firstOrNull {
    !albumRatingKey.isNullOrBlank() && it.ratingKey == albumRatingKey && it.sourceId == sourceId
} ?: MusicAlbum(
    ratingKey = albumRatingKey ?: fallback?.ratingKey ?: ratingKey,
    title = albumTitle ?: fallback?.title ?: title,
    artistName = artistName ?: fallback?.artistName,
    year = albumYearFrom(fallback),
    thumbUrl = thumbUrl ?: fallback?.thumbUrl,
    userRating = fallback?.userRating,
    addedAtEpochSeconds = fallback?.addedAtEpochSeconds,
    lastViewedAtEpochSeconds = fallback?.lastViewedAtEpochSeconds,
    section = fallback?.section ?: LibrarySection("", "", ""),
    sourceId = sourceId.ifBlank { fallback?.sourceId.orEmpty() },
)

/** A mixed queue's fallback album may belong to the previous track. */
internal fun MusicTrack.albumYearFrom(fallbackAlbum: MusicAlbum?): Int? =
    fallbackAlbum?.year?.takeIf {
        !albumRatingKey.isNullOrBlank() &&
            albumRatingKey == fallbackAlbum.ratingKey &&
            sourceId == fallbackAlbum.sourceId
    }
