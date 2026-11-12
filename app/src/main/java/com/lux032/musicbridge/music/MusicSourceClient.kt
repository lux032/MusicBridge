package com.lux032.musicbridge.music

import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.AlbumTracksResult
import com.lux032.musicbridge.music.AlbumsResult
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.ArtistsResult
import com.lux032.musicbridge.music.MusicPlaylist
import com.lux032.musicbridge.music.PlaylistTracksResult
import com.lux032.musicbridge.music.PlaylistsResult
import com.lux032.musicbridge.music.MusicTrack

interface MusicSourceClient {
    val sourceId: String

    suspend fun fetchAlbums(): AlbumsResult
    suspend fun fetchArtists(): ArtistsResult
    suspend fun fetchFavoriteTracks(): List<MusicTrack>
    suspend fun fetchAlbumTrackStreams(album: MusicAlbum): AlbumTracksResult
    suspend fun fetchAllAlbumTracks(
        albums: List<MusicAlbum>,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, List<MusicTrack>>
    suspend fun fetchPlaylists(): PlaylistsResult
    suspend fun fetchPlaylistTracks(playlist: MusicPlaylist): PlaylistTracksResult
    suspend fun setAlbumFavorite(ratingKey: String, isFavorite: Boolean)
    suspend fun setTrackFavorite(ratingKey: String, isFavorite: Boolean)
    suspend fun setArtistFavorite(ratingKey: String, isFavorite: Boolean)
    suspend fun fetchTrackStream(ratingKey: String): MusicTrack? = null
    suspend fun fetchArtistTracks(artist: MusicArtist): List<MusicTrack>
    suspend fun fetchArtistAlbums(artistRatingKey: String): List<MusicAlbum> = emptyList()
    suspend fun reportTimeline(
        ratingKey: String,
        state: String,
        timeMillis: Long,
        durationMillis: Long?,
        continuing: Boolean = false,
    )
    suspend fun scrobble(ratingKey: String, positionMillis: Long = 0L, durationMillis: Long? = null)
}
