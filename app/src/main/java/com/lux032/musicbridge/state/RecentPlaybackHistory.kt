package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexAlbum

/** Keep the current album first without rewriting history on every reporting tick. */
internal fun AppPreferences.recordPlayedAlbum(album: PlexAlbum): List<String> {
    val keys = loadRecentPlayedAlbumKeys(album.sourceId)
    val ratingKey = album.ratingKey.trim()
    if (album.sourceId.isBlank() || ratingKey.isEmpty() || keys.firstOrNull() == ratingKey) return keys
    markAlbumPlayed(album.sourceId, ratingKey)
    return loadRecentPlayedAlbumKeys(album.sourceId)
}
