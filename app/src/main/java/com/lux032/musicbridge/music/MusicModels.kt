package com.lux032.musicbridge.music

/** A library section of a music source (032 servers expose a single music library). */
data class LibrarySection(val key: String, val title: String, val type: String)

/** Server-side artist identity (032 artist id + display name). Links that only carry text
 *  (old caches) leave refs empty and keep navigating by name. */
data class ArtistRef(val id: String, val name: String)

/** Backstage credit of a track: one role (`lyricist`, `composer`, `arranger`, `producer`) and
 *  its people. A blank [ArtistRef.id] is a name with no artist page to open. */
data class TrackCredit(val role: String, val artists: List<ArtistRef>)

data class MusicAlbum(
    val ratingKey: String,
    val title: String,
    val artistName: String?,
    val year: Int?,
    val thumbUrl: String?,
    val userRating: Float?,
    val addedAtEpochSeconds: Long?,
    val lastViewedAtEpochSeconds: Long?,
    val section: LibrarySection,
    val sourceId: String = "",
    val leafCount: Int? = null,
    val musicAnalysisVersion: String? = null,
    val format: String? = null,
    val subformat: String? = null,
    /** Full release date as served (032 releaseDate); may be only a year or year-month.
     *  Use [releaseSortKey] for ordering. */
    val releaseDate: String? = null,
    /** Credited album artists in credit order; [artistName] is only their joined text. */
    val artists: List<ArtistRef> = emptyList(),
) {
    val isFavorite: Boolean
        get() = (userRating ?: 0f) > 0f
}

data class MusicTrack(
    val ratingKey: String,
    val title: String,
    val streamUrl: String,
    val partKey: String,
    val durationMillis: Long?,
    val userRating: Float?,
    val albumRatingKey: String? = null,
    val albumTitle: String? = null,
    val artistName: String? = null,
    val thumbUrl: String? = null,
    val sourceAudioCodec: String? = null,
    val sourceAudioBitrateKbps: Int? = null,
    val sourceAudioSampleRate: Int? = null,
    val bitDepth: Int? = null,
    val playbackMimeType: String? = null,
    /** Lyric file for this track (a downloaded `file:` copy or a server URL), when known. */
    val lyricUrl: String? = null,
    val sourceId: String = "",
    val musicAnalysisVersion: String? = null,
    val viewCount: Long? = null,
    val lastViewedAtEpochSeconds: Long? = null,
    val skipCount: Long? = null,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    /** Performing track artists; empty means the album artists apply. */
    val artists: List<ArtistRef> = emptyList(),
    /** The daily mix this queue entry came from (reported on session start). */
    val playbackContext: Music032PlaybackContext? = null,
)

/** Replace only artist metadata; stream URLs, offline files and session context stay intact. */
internal fun MusicTrack.withArtistMetadataFrom(fresh: MusicTrack): MusicTrack =
    if (sourceId == fresh.sourceId && ratingKey == fresh.ratingKey) {
        copy(artistName = fresh.artistName, artists = fresh.artists)
    } else this

val MusicTrack.isFavorite: Boolean
    get() = (userRating ?: 0f) > 0f

data class AlbumsResult(
    val serverName: String,
    val sections: List<LibrarySection>,
    val albums: List<MusicAlbum>,
)

data class MusicArtist(
    val ratingKey: String,
    val title: String,
    val thumbUrl: String?,
    val userRating: Float? = null,
    val sourceId: String = "",
    /** 032 sync model counts (discography albums / performed tracks); null when unknown. */
    val albumCount: Int? = null,
    val trackCount: Int? = null,
    val sortName: String? = null,
) {
    val isFavorite: Boolean
        get() = (userRating ?: 0f) > 0f
}

data class ArtistsResult(
    val serverName: String,
    val artists: List<MusicArtist>,
)

data class AlbumTracksResult(
    val serverName: String,
    val album: MusicAlbum,
    val tracks: List<MusicTrack>,
)

data class MusicPlaylist(
    val ratingKey: String,
    val title: String,
    val thumbUrl: String?,
    val leafCount: Int,
    val sourceId: String = "",
    /** Free-text note. */
    val description: String = "",
    /** 032 optimistic-concurrency revision; null when unknown (local cache rows). */
    val revision: Long? = null,
)

data class PlaylistsResult(
    val serverName: String,
    val playlists: List<MusicPlaylist>,
)

data class PlaylistTracksResult(
    val serverName: String,
    val playlist: MusicPlaylist,
    val tracks: List<MusicTrack>,
)

/**
 * Identity of a playback URL as Sonos reports it back: the URL without its query string,
 * so a re-signed or re-parameterised URL of the same media still matches.
 */
internal fun playbackUrlIdentity(url: String): String = url.substringBefore('?')
