package com.lux032.musicbridge.plex

data class SonicTrackMatch(val ratingKey: String, val distance: Double?, val track: PlexTrackStream)

interface SonicCapableSource {
    suspend fun fetchNearestTracks(ratingKey: String, limit: Int = 30): List<SonicTrackMatch>
    suspend fun computeSonicPath(sectionKey: String, startRatingKey: String, endRatingKey: String): List<PlexTrackStream>
    suspend fun createPlaylist(title: String, trackRatingKeys: List<String>): PlexPlaylist
}
