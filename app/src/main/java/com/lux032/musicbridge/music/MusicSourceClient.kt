package com.lux032.musicbridge.music

import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexAlbumsResult
import com.lux032.musicbridge.plex.PlexArtist
import com.lux032.musicbridge.plex.PlexArtistsResult
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexPlaylistsResult
import com.lux032.musicbridge.plex.PlexTrackStream

interface MusicSourceClient {
    val sourceId: String

    suspend fun fetchAlbums(): PlexAlbumsResult
    suspend fun fetchArtists(): PlexArtistsResult
    suspend fun fetchFavoriteTracks(): List<PlexTrackStream>
    suspend fun fetchAlbumTrackStreams(album: PlexAlbum): PlexAlbumTracksResult
    suspend fun fetchAllAlbumTracks(
        albums: List<PlexAlbum>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, List<PlexTrackStream>>
    suspend fun fetchPlaylists(): PlexPlaylistsResult
    suspend fun fetchPlaylistTracks(playlist: PlexPlaylist): PlexPlaylistTracksResult
    suspend fun setAlbumFavorite(ratingKey: String, isFavorite: Boolean)
    suspend fun setTrackFavorite(ratingKey: String, isFavorite: Boolean)
    suspend fun setArtistFavorite(ratingKey: String, isFavorite: Boolean)
    suspend fun fetchTrackStream(ratingKey: String): PlexTrackStream? = null
    suspend fun fetchArtistTracks(artist: PlexArtist): List<PlexTrackStream>
    suspend fun fetchArtistAlbums(artistRatingKey: String): List<PlexAlbum> = emptyList()
    suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean = false,
    )
    suspend fun scrobble(ratingKey: String, positionMillis: Long = 0L, durationMillis: Long? = null)
}
