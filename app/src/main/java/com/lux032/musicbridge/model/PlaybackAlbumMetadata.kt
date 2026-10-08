package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.PlexSection

internal fun PlexTrackStream.resolvePlaybackAlbum(
    albums: List<PlexAlbum>,
    fallback: PlexAlbum?,
): PlexAlbum = albums.firstOrNull {
    !albumRatingKey.isNullOrBlank() && it.ratingKey == albumRatingKey && it.sourceId == sourceId
} ?: PlexAlbum(
    ratingKey = albumRatingKey ?: fallback?.ratingKey ?: ratingKey,
    title = albumTitle ?: fallback?.title ?: title,
    artistName = artistName ?: fallback?.artistName,
    year = albumYearFrom(fallback),
    thumbUrl = thumbUrl ?: fallback?.thumbUrl,
    userRating = fallback?.userRating,
    addedAtEpochSeconds = fallback?.addedAtEpochSeconds,
    lastViewedAtEpochSeconds = fallback?.lastViewedAtEpochSeconds,
    section = fallback?.section ?: PlexSection("", "", ""),
    sourceId = sourceId.ifBlank { fallback?.sourceId.orEmpty() },
)

/** A mixed queue's fallback album may belong to the previous track. */
internal fun PlexTrackStream.albumYearFrom(fallbackAlbum: PlexAlbum?): Int? =
    fallbackAlbum?.year?.takeIf {
        !albumRatingKey.isNullOrBlank() &&
            albumRatingKey == fallbackAlbum.ratingKey &&
            sourceId == fallbackAlbum.sourceId
    }
