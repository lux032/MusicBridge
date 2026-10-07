package com.lux032.musicbridge.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexArtist
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import org.json.JSONArray
import org.json.JSONObject

/** One release on a 032 artist page; [isAppearance] = the artist only sings some tracks. */
data class ArtistRelease(val album: PlexAlbum, val isAppearance: Boolean)

data class DownloadedAudioTrack(
    val sourceId: String,
    val ratingKey: String,
    val partKey: String,
    val title: String,
    val artistName: String?,
    val albumTitle: String?,
    val albumRatingKey: String?,
    val durationMillis: Long?,
    val sourceAudioCodec: String?,
    val sourceAudioBitrateKbps: Int?,
    val sourceAudioSampleRate: Int?,
    val bitDepth: Int?,
    val localAudioPath: String,
    val localThumbPath: String?,
    val localLyricPath: String?,
    val fileSize: Long,
    val downloadedAtEpochSeconds: Long,
    val discNumber: Int? = null,
    val trackNumber: Int? = null,
    val userRating: Float? = null,
) {
    fun toTrackStream(): PlexTrackStream = PlexTrackStream(
        ratingKey = ratingKey,
        title = title,
        streamUrl = "file://$localAudioPath",
        partKey = partKey,
        durationMillis = durationMillis,
        userRating = userRating,
        albumRatingKey = albumRatingKey,
        albumTitle = albumTitle,
        artistName = artistName,
        thumbUrl = localThumbPath?.let { "file://$it" },
        sourceAudioCodec = sourceAudioCodec,
        sourceAudioBitrateKbps = sourceAudioBitrateKbps,
        sourceAudioSampleRate = sourceAudioSampleRate,
        bitDepth = bitDepth,
        playbackMimeType = null,
        lyricUrl = localLyricPath?.let { "file://$it" },
        sourceId = sourceId,
        discNumber = discNumber,
        trackNumber = trackNumber,
    )
}

class AlbumLocalStore(context: Context) {
    private val helper = AlbumDatabaseHelper(context.applicationContext)

    data class ArtistInfo(
        val name: String,
        val coverUrl: String?,
        val albumCount: Int,
    )

    fun getDownloadedTrack(sourceId: String, ratingKey: String): DownloadedAudioTrack? {
        val track = helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(sourceId, ratingKey),
            null,
            null,
            null,
            "1",
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toDownloadedTrack() else null
        } ?: return null

        if (track.userRating != null) return track

        // Fallback for v13/v14 upgrade: check if track is recorded in TABLE_TRACKS with a favorite rating
        val fallbackRating = runCatching {
            helper.readableDatabase.query(
                AlbumDatabaseHelper.TABLE_TRACKS,
                arrayOf(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING),
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING} > 0",
                arrayOf(sourceId, ratingKey),
                null,
                null,
                null,
                "1",
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getFloat(0) else null
            }
        }.getOrNull()

        return if (fallbackRating != null) track.copy(userRating = fallbackRating) else track
    }

    fun getAllDownloadedTracks(): List<DownloadedAudioTrack> {
        val tracks = helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            null,
            null,
            null,
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_DOWNLOADED_AT_EPOCH_SECONDS} DESC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toDownloadedTrack())
                }
            }
        }

        if (tracks.isEmpty()) return emptyList()

        // Fallback for v13/v14 upgrade: find tracks missing userRating that exist in TABLE_TRACKS
        val missingRatingTracks = tracks.filter { it.userRating == null }
        if (missingRatingTracks.isEmpty()) return tracks

        val favoriteRatingMap = runCatching {
            helper.readableDatabase.query(
                AlbumDatabaseHelper.TABLE_TRACKS,
                arrayOf(AlbumDatabaseHelper.COLUMN_SOURCE_ID, AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY, AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING),
                "${AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING} > 0",
                null,
                null,
                null,
                null,
            ).use { cursor ->
                buildMap {
                    while (cursor.moveToNext()) {
                        val sId = cursor.getString(0)
                        val rKey = cursor.getString(1)
                        val rating = cursor.getFloat(2)
                        put("$sId:$rKey", rating)
                    }
                }
            }
        }.getOrDefault(emptyMap())

        if (favoriteRatingMap.isEmpty()) return tracks

        return tracks.map { track ->
            if (track.userRating == null) {
                val rating = favoriteRatingMap["${track.sourceId}:${track.ratingKey}"]
                if (rating != null) track.copy(userRating = rating) else track
            } else {
                track
            }
        }
    }

    fun getAllDownloadedTrackKeys(): Set<String> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            arrayOf(AlbumDatabaseHelper.COLUMN_SOURCE_ID, AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY),
            null,
            null,
            null,
            null,
            null,
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    val sId = cursor.getString(0)
                    val rKey = cursor.getString(1)
                    add("$sId:$rKey")
                    add(rKey)
                }
            }
        }

    fun insertDownloadedTrack(track: DownloadedAudioTrack) {
        helper.writableDatabase.insertWithOnConflict(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            null,
            track.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteDownloadedTrack(sourceId: String, ratingKey: String) {
        helper.writableDatabase.delete(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(sourceId, ratingKey),
        )
    }

    fun deleteDownloadedAlbum(sourceId: String, albumRatingKey: String): List<DownloadedAudioTrack> {
        val db = helper.writableDatabase
        db.beginTransaction()
        return try {
            val tracks = mutableListOf<DownloadedAudioTrack>()
            val selection = if (sourceId.isNotBlank()) {
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND (${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} = ? OR ${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE} = ?)"
            } else {
                "${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} = ? OR ${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE} = ?"
            }
            val selectionArgs = if (sourceId.isNotBlank()) {
                arrayOf(sourceId, albumRatingKey, albumRatingKey)
            } else {
                arrayOf(albumRatingKey, albumRatingKey)
            }

            db.query(
                AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
                null,
                selection,
                selectionArgs,
                null,
                null,
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    tracks.add(cursor.toDownloadedTrack())
                }
            }

            db.delete(AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS, selection, selectionArgs)
            db.setTransactionSuccessful()
            tracks
        } finally {
            db.endTransaction()
        }
    }

    fun clearAllDownloadedTracks() {
        helper.writableDatabase.delete(AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS, null, null)
    }

    fun totalDownloadedBytes(): Long =
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(${AlbumDatabaseHelper.COLUMN_DOWNLOAD_FILE_SIZE}), 0) FROM ${AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS}",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    fun getAllPlaylists(sourceId: String): List<PlexPlaylist> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_PLAYLISTS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_SORT_ORDER} ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toPlaylist())
            }
        }

    fun replaceAllPlaylists(sourceId: String, playlists: List<PlexPlaylist>) {
        val persistedPlaylists = playlists.asSequence()
            .filter { it.ratingKey.isNotBlank() && it.isPersistable() }
            .distinctBy(PlexPlaylist::ratingKey)
            .toList()
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_PLAYLISTS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            persistedPlaylists.forEachIndexed { index, playlist ->
                database.insertWithOnConflict(
                    AlbumDatabaseHelper.TABLE_PLAYLISTS,
                    null,
                    playlist.toContentValues(sourceId, index),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            if (persistedPlaylists.isEmpty()) {
                database.delete(
                    AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
                    "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?",
                    arrayOf(sourceId),
                )
            } else {
                val placeholders = persistedPlaylists.joinToString(",") { "?" }
                database.delete(
                    AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
                    "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_PLAYLIST_RATING_KEY} NOT IN ($placeholders)",
                    arrayOf(sourceId, *persistedPlaylists.map(PlexPlaylist::ratingKey).toTypedArray()),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun getPlaylistTracks(sourceId: String, playlistRatingKey: String): List<PlexTrackStream> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_PLAYLIST_RATING_KEY} = ?",
            arrayOf(sourceId, playlistRatingKey),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_PLAYLIST_ORDER} ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toTrack())
            }
        }

    fun savePlaylistTracks(sourceId: String, playlistRatingKey: String, tracks: List<PlexTrackStream>) {
        if (sourceId.isBlank() || playlistRatingKey.isBlank() || !isPersistablePlaylistKey(playlistRatingKey)) return
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_PLAYLIST_RATING_KEY} = ?",
                arrayOf(sourceId, playlistRatingKey),
            )
            tracks.asSequence().filter { it.ratingKey.isNotBlank() }.forEachIndexed { index, track ->
                database.insertWithOnConflict(
                    AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
                    null,
                    track.toContentValues().apply {
                        put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
                        put(AlbumDatabaseHelper.COLUMN_PLAYLIST_RATING_KEY, playlistRatingKey)
                        put(AlbumDatabaseHelper.COLUMN_PLAYLIST_ORDER, index)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun updatePlaylistTrackFavoriteStatus(sourceId: String, ratingKey: String, userRating: Float?) {
        val values = ContentValues().apply {
            if (userRating != null) put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
            else putNull(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING)
        }
        helper.writableDatabase.update(
            AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
            values,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(sourceId, ratingKey),
        )
    }

    fun isTrackAvailableLocally(sourceId: String, ratingKey: String): Boolean =
        getDownloadedTrack(sourceId, ratingKey)?.let { java.io.File(it.localAudioPath).exists() } == true

    fun getFavoriteTracks(sourceId: String): List<PlexTrackStream> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_TRACKS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING} > 0",
            arrayOf(sourceId),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_TRACK_TITLE} COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toTrack())
                }
            }
        }

    fun getArtistSummaries(sourceId: String): List<ArtistInfo> =
        helper.readableDatabase.rawQuery(
            """
            SELECT
                COALESCE(NULLIF(TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}), ''), '未知艺人') as artist,
                COUNT(*) as album_count,
                (SELECT ${AlbumDatabaseHelper.COLUMN_THUMB_URL} FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} t2
                 WHERE COALESCE(NULLIF(TRIM(t2.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}), ''), '未知艺人') =
                       COALESCE(NULLIF(TRIM(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}), ''), '未知艺人')
                 AND t2.${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = t1.${AlbumDatabaseHelper.COLUMN_SOURCE_ID}
                 AND t2.${AlbumDatabaseHelper.COLUMN_THUMB_URL} IS NOT NULL LIMIT 1) as cover_url
            FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} t1
            WHERE t1.${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?
            GROUP BY artist
            ORDER BY album_count DESC, artist COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(sourceId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ArtistInfo(
                            name = cursor.getString(0),
                            coverUrl = cursor.getString(2),
                            albumCount = cursor.getInt(1),
                        )
                    )
                }
            }
        }

    fun getAlbumsByArtist(sourceId: String, artistName: String): List<PlexAlbum> {
        val normalizedArtist = artistName.trim().ifBlank { "未知艺人" }
        val artistSelection = if (normalizedArtist == "未知艺人") {
            "(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} IS NULL OR TRIM(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = '')"
        } else {
            "TRIM(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = ?"
        }
        val selectionArgs = if (normalizedArtist == "未知艺人") arrayOf(sourceId) else arrayOf(sourceId, normalizedArtist)

        return helper.readableDatabase.rawQuery(
            """
            SELECT t1.*,
                   COALESCE(
                       t1.${AlbumDatabaseHelper.COLUMN_LEAF_COUNT},
                       NULLIF((
                           SELECT COUNT(*)
                           FROM ${AlbumDatabaseHelper.TABLE_ALBUM_TRACKS} t2
                           WHERE t2.${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} = t1.${AlbumDatabaseHelper.COLUMN_RATING_KEY}
                             AND t2.${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = t1.${AlbumDatabaseHelper.COLUMN_SOURCE_ID}
                       ), 0)
                   ) AS effective_leaf_count
            FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} t1
            WHERE t1.${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND $artistSelection
            ORDER BY t1.${AlbumDatabaseHelper.COLUMN_YEAR} DESC, t1.${AlbumDatabaseHelper.COLUMN_TITLE} COLLATE NOCASE ASC
            """.trimIndent(),
            selectionArgs,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }
    }

    fun getAllAlbums(sourceId: String): List<PlexAlbum> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ALBUMS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_TITLE} COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }

    fun searchAlbums(sourceId: String, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<PlexAlbum> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return getAllAlbums(sourceId)
        }

        val escapedQuery = normalizedQuery
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val likeArg = "%$escapedQuery%"

        return helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ALBUMS,
            null,
            """
            ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND (
                ${AlbumDatabaseHelper.COLUMN_TITLE} LIKE ? ESCAPE '\' OR
                ${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} LIKE ? ESCAPE '\'
            )
            """.trimIndent(),
            arrayOf(sourceId, likeArg, likeArg),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_TITLE} COLLATE NOCASE ASC",
            limit.coerceAtLeast(1).toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toAlbum())
                }
            }
        }
    }

    fun replaceAllAlbums(sourceId: String, albums: List<PlexAlbum>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_ALBUMS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            database.delete(AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            val links = ArtistLinkWriter(database)
            try {
                albums.forEach { album ->
                    database.insertOrThrow(
                        AlbumDatabaseHelper.TABLE_ALBUMS,
                        null,
                        album.toContentValues(),
                    )
                    links.album(album)
                }
            } finally {
                links.close()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun upsertAlbum(album: PlexAlbum) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.insertWithOnConflict(
                AlbumDatabaseHelper.TABLE_ALBUMS,
                null,
                album.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            database.delete(
                AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS,
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY} = ?",
                arrayOf(album.sourceId, album.ratingKey),
            )
            ArtistLinkWriter(database).run {
                try { album(album) } finally { close() }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    // ---- 032 artist sync model -------------------------------------------------------------

    fun replaceAllArtists(sourceId: String, artists: List<PlexArtist>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_ARTISTS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            artists.forEach { artist ->
                database.insertWithOnConflict(
                    AlbumDatabaseHelper.TABLE_ARTISTS,
                    null,
                    artist.toContentValues(sourceId),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun upsertArtist(sourceId: String, artist: PlexArtist) {
        helper.writableDatabase.insertWithOnConflict(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            null,
            artist.toContentValues(sourceId),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getArtists(sourceId: String): List<PlexArtist> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_ALBUM_COUNT} DESC, ${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} COLLATE NOCASE ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toArtist()) } }

    fun getArtist(sourceId: String, artistId: String): PlexArtist? =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_RATING_KEY} = ?",
            arrayOf(sourceId, artistId),
            null,
            null,
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toArtist() else null }

    /** Exact (case-insensitive, trimmed) display-name match for text-only links. */
    fun findArtistsByName(sourceId: String, name: String): List<PlexArtist> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = ? COLLATE NOCASE",
            arrayOf(sourceId, name.trim()),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_ALBUM_COUNT} DESC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toArtist()) } }

    fun updateArtistFavorite(sourceId: String, artistId: String, userRating: Float?) {
        val values = ContentValues().apply {
            if (userRating != null) put(AlbumDatabaseHelper.COLUMN_USER_RATING, userRating)
            else putNull(AlbumDatabaseHelper.COLUMN_USER_RATING)
        }
        helper.writableDatabase.update(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            values,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_RATING_KEY} = ?",
            arrayOf(sourceId, artistId),
        )
    }

    /**
     * Same release set as the server's ArtistDiscography: albums credited to the artist plus
     * albums where the artist is a primary track artist. Multi-artist compilations and
     * track-only credits are appearances (参与作品).
     */
    fun getArtistDiscography(sourceId: String, artistId: String): List<ArtistRelease> {
        val links = AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS
        val trackLinks = AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS
        val source = AlbumDatabaseHelper.COLUMN_SOURCE_ID
        val albumKey = AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY
        val artistKey = AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY
        return helper.readableDatabase.rawQuery(
            """
            SELECT t1.*,
                   COALESCE(
                       t1.${AlbumDatabaseHelper.COLUMN_LEAF_COUNT},
                       NULLIF((
                           SELECT COUNT(*)
                           FROM ${AlbumDatabaseHelper.TABLE_ALBUM_TRACKS} t2
                           WHERE t2.${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} = t1.${AlbumDatabaseHelper.COLUMN_RATING_KEY}
                             AND t2.$source = t1.$source
                       ), 0)
                   ) AS effective_leaf_count,
                   EXISTS(SELECT 1 FROM $links l WHERE l.$source = t1.$source AND l.$albumKey = t1.${AlbumDatabaseHelper.COLUMN_RATING_KEY} AND l.$artistKey = ?) AS is_credited,
                   (SELECT COUNT(*) FROM $links l WHERE l.$source = t1.$source AND l.$albumKey = t1.${AlbumDatabaseHelper.COLUMN_RATING_KEY}) AS credited_count
            FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} t1
            WHERE t1.$source = ? AND t1.${AlbumDatabaseHelper.COLUMN_RATING_KEY} IN (
                SELECT $albumKey FROM $links WHERE $source = ? AND $artistKey = ?
                UNION
                SELECT $albumKey FROM $trackLinks WHERE $source = ? AND $artistKey = ?
            )
            ORDER BY t1.${AlbumDatabaseHelper.COLUMN_YEAR} DESC, t1.${AlbumDatabaseHelper.COLUMN_TITLE} COLLATE NOCASE ASC
            """.trimIndent(),
            arrayOf(artistId, sourceId, sourceId, artistId, sourceId, artistId),
        ).use { cursor ->
            val creditedIndex = cursor.getColumnIndexOrThrow("is_credited")
            val countIndex = cursor.getColumnIndexOrThrow("credited_count")
            buildList {
                while (cursor.moveToNext()) {
                    val album = cursor.toAlbum()
                    val credited = cursor.getInt(creditedIndex) != 0
                    val compilationCollaboration = credited && cursor.getInt(countIndex) > 1 &&
                        album.subformat.orEmpty().contains("Compilation", ignoreCase = true)
                    add(ArtistRelease(album, isAppearance = !credited || compilationCollaboration))
                }
            }
        }
    }

    /** Performed tracks (own primary credits ∪ tracks of credited albums), for offline play. */
    fun getArtistTracks(sourceId: String, artistId: String): List<PlexTrackStream> {
        val source = AlbumDatabaseHelper.COLUMN_SOURCE_ID
        val artistKey = AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY
        return helper.readableDatabase.rawQuery(
            """
            SELECT t.* FROM ${AlbumDatabaseHelper.TABLE_ALBUM_TRACKS} t
            WHERE t.$source = ? AND (
                t.${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} IN (
                    SELECT ${AlbumDatabaseHelper.COLUMN_LINK_TRACK_RATING_KEY} FROM ${AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS}
                    WHERE $source = ? AND $artistKey = ?)
                OR t.${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} IN (
                    SELECT ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY} FROM ${AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS}
                    WHERE $source = ? AND $artistKey = ?)
            )
            ORDER BY COALESCE(t.${AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT}, 0) DESC,
                     t.${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY},
                     COALESCE(t.${AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER}, 1),
                     COALESCE(t.${AlbumDatabaseHelper.COLUMN_TRACK_NUMBER}, 2147483647)
            """.trimIndent(),
            arrayOf(sourceId, sourceId, artistId, sourceId, artistId),
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toTrack()) } }
    }

    /** Precompiled link writers: one statement per transaction instead of ContentValues per row. */
    private class ArtistLinkWriter(database: SQLiteDatabase) {
        private val albumLink = database.compileStatement(
            "INSERT OR IGNORE INTO ${AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS} " +
                "(${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY}, " +
                "${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY}, ${AlbumDatabaseHelper.COLUMN_LINK_POSITION}) VALUES (?, ?, ?, ?)"
        )
        private val trackLink = database.compileStatement(
            "INSERT OR IGNORE INTO ${AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS} " +
                "(${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_LINK_TRACK_RATING_KEY}, " +
                "${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY}, ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY}) VALUES (?, ?, ?, ?)"
        )

        fun album(album: PlexAlbum) {
            album.artists.forEachIndexed { index, ref ->
                albumLink.bindString(1, album.sourceId)
                albumLink.bindString(2, album.ratingKey)
                albumLink.bindString(3, ref.id)
                albumLink.bindLong(4, index.toLong())
                albumLink.executeInsert()
            }
        }

        fun track(track: PlexTrackStream) {
            val albumKey = track.albumRatingKey ?: return
            track.artists.forEach { ref ->
                trackLink.bindString(1, track.sourceId)
                trackLink.bindString(2, track.ratingKey)
                trackLink.bindString(3, albumKey)
                trackLink.bindString(4, ref.id)
                trackLink.executeInsert()
            }
        }

        fun close() {
            albumLink.close()
            trackLink.close()
        }
    }

    fun updateAlbumsMetadata(sourceId: String, albums: List<PlexAlbum>) {
        if (sourceId.isBlank() || albums.isEmpty()) return
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            albums.forEach { album ->
                database.execSQL(
                    """
                    UPDATE ${AlbumDatabaseHelper.TABLE_ALBUMS}
                    SET ${AlbumDatabaseHelper.COLUMN_FORMAT} = COALESCE(?, ${AlbumDatabaseHelper.COLUMN_FORMAT}),
                        ${AlbumDatabaseHelper.COLUMN_SUBFORMAT} = COALESCE(?, ${AlbumDatabaseHelper.COLUMN_SUBFORMAT}),
                        ${AlbumDatabaseHelper.COLUMN_LEAF_COUNT} = COALESCE(?, ${AlbumDatabaseHelper.COLUMN_LEAF_COUNT}),
                        ${AlbumDatabaseHelper.COLUMN_RELEASE_DATE} = COALESCE(?, ${AlbumDatabaseHelper.COLUMN_RELEASE_DATE})
                    WHERE ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?
                      AND ${AlbumDatabaseHelper.COLUMN_RATING_KEY} = ?
                    """.trimIndent(),
                    arrayOf<Any?>(album.format, album.subformat, album.leafCount, album.releaseDate, sourceId, album.ratingKey),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun deleteServerData(sourceId: String) {
        if (sourceId.isBlank()) return
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val args = arrayOf(sourceId)
            val where = "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?"
            database.delete(AlbumDatabaseHelper.TABLE_ALBUMS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_TRACKS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_ALBUM_TRACKS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_PLAYLISTS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_ARTISTS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS, where, args)
            database.delete(AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS, where, args)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun replaceAllFavoriteTracks(sourceId: String, tracks: List<PlexTrackStream>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_TRACKS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            tracks.forEach { track ->
                database.insertOrThrow(
                    AlbumDatabaseHelper.TABLE_TRACKS,
                    null,
                    track.toContentValues(),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun upsertFavoriteTrack(track: PlexTrackStream) {
        helper.writableDatabase.insertWithOnConflict(
            AlbumDatabaseHelper.TABLE_TRACKS,
            null,
            track.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun deleteFavoriteTrack(sourceId: String, ratingKey: String) {
        helper.writableDatabase.delete(
            AlbumDatabaseHelper.TABLE_TRACKS,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(sourceId, ratingKey),
        )
    }

    fun getAlbumTracks(sourceId: String, albumRatingKey: String): List<PlexTrackStream> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} = ?",
            arrayOf(sourceId, albumRatingKey),
            null,
            null,
            "COALESCE(${AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER}, 1) ASC, COALESCE(${AlbumDatabaseHelper.COLUMN_TRACK_NUMBER}, 2147483647) ASC, ${AlbumDatabaseHelper.COLUMN_TRACK_TITLE} COLLATE NOCASE ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.toTrack())
                }
            }
        }

    fun replaceAllAlbumTracks(sourceId: String, albumTracks: Map<String, List<PlexTrackStream>>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_ALBUM_TRACKS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            database.delete(AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            val links = ArtistLinkWriter(database)
            try {
                albumTracks.values.forEach { tracks ->
                    tracks.forEach { track ->
                        database.insertWithOnConflict(
                            AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                            null,
                            track.toContentValues(),
                            SQLiteDatabase.CONFLICT_REPLACE,
                        )
                        links.track(track)
                    }
                }
            } finally {
                links.close()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun saveAlbumTracks(sourceId: String, albumRatingKey: String, tracks: List<PlexTrackStream>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(
                AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY} = ?",
                arrayOf(sourceId, albumRatingKey),
            )
            database.delete(
                AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS,
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY} = ?",
                arrayOf(sourceId, albumRatingKey),
            )
            val links = ArtistLinkWriter(database)
            try {
                tracks.forEach { track ->
                    database.insertWithOnConflict(
                        AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                        null,
                        track.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                    links.track(track)
                }
            } finally {
                links.close()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun recordTrackPlayback(
        sourceId: String,
        ratingKey: String,
        playedAtEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            for (table in listOf(AlbumDatabaseHelper.TABLE_TRACKS, AlbumDatabaseHelper.TABLE_ALBUM_TRACKS, AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS)) {
                database.execSQL(
                    """
                    UPDATE $table
                    SET ${AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT} = COALESCE(${AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT}, 0) + 1,
                        ${AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS} = ?
                    WHERE ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?
                      AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?
                    """.trimIndent(),
                    arrayOf<Any>(playedAtEpochSeconds, sourceId, ratingKey),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun updateDownloadedTrackFavoriteStatus(sourceId: String, ratingKey: String, userRating: Float?) {
        val values = ContentValues().apply {
            if (userRating != null) put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
            else putNull(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING)
        }
        helper.writableDatabase.update(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            values,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(sourceId, ratingKey),
        )
    }

    fun updateAlbumTrackFavoriteStatus(sourceId: String, ratingKey: String, userRating: Float?) {
        val values = ContentValues().apply {
            if (userRating != null) put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
            else putNull(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING)
        }
        helper.writableDatabase.update(
            AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
            values,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(sourceId, ratingKey),
        )
    }

    private fun android.database.Cursor.toAlbum(): PlexAlbum {
        fun string(name: String): String = getString(getColumnIndexOrThrow(name))
        fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
        fun nullableInt(name: String): Int? =
            if (isNull(getColumnIndexOrThrow(name))) null else getInt(getColumnIndexOrThrow(name))
        fun nullableLong(name: String): Long? =
            if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
        fun nullableFloat(name: String): Float? =
            if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))

        return PlexAlbum(
            ratingKey = string(AlbumDatabaseHelper.COLUMN_RATING_KEY),
            title = string(AlbumDatabaseHelper.COLUMN_TITLE),
            artistName = nullableString(AlbumDatabaseHelper.COLUMN_ARTIST_NAME),
            year = nullableInt(AlbumDatabaseHelper.COLUMN_YEAR),
            thumbUrl = nullableString(AlbumDatabaseHelper.COLUMN_THUMB_URL),
            userRating = nullableFloat(AlbumDatabaseHelper.COLUMN_USER_RATING),
            addedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_ADDED_AT_EPOCH_SECONDS),
            lastViewedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS),
            section = PlexSection(
                key = string(AlbumDatabaseHelper.COLUMN_SECTION_KEY),
                title = string(AlbumDatabaseHelper.COLUMN_SECTION_TITLE),
                type = string(AlbumDatabaseHelper.COLUMN_SECTION_TYPE),
            ),
            sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
            leafCount = getColumnIndex("effective_leaf_count")
                .takeIf { it >= 0 && !isNull(it) }
                ?.let(::getInt)
                ?: nullableInt(AlbumDatabaseHelper.COLUMN_LEAF_COUNT),
            format = nullableString(AlbumDatabaseHelper.COLUMN_FORMAT),
            subformat = nullableString(AlbumDatabaseHelper.COLUMN_SUBFORMAT),
            releaseDate = getColumnIndex(AlbumDatabaseHelper.COLUMN_RELEASE_DATE)
                .takeIf { it >= 0 && !isNull(it) }
                ?.let(::getString),
            artists = artistRefsColumn(),
        )
    }

    private fun android.database.Cursor.artistRefsColumn(): List<ArtistRef> =
        getColumnIndex(AlbumDatabaseHelper.COLUMN_ARTIST_REFS)
            .takeIf { it >= 0 && !isNull(it) }
            ?.let { decodeArtistRefs(getString(it)) }
            .orEmpty()

    private fun android.database.Cursor.toArtist(): PlexArtist {
        fun nullableInt(name: String): Int? =
            getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
        return PlexArtist(
            ratingKey = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_RATING_KEY)),
            title = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_ARTIST_NAME)),
            thumbUrl = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_THUMB_URL)),
            userRating = getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_USER_RATING)
                .let { if (isNull(it)) null else getFloat(it) },
            sourceId = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_SOURCE_ID)),
            albumCount = nullableInt(AlbumDatabaseHelper.COLUMN_ALBUM_COUNT),
            trackCount = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_COUNT),
            sortName = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_SORT_NAME)),
        )
    }

    private fun PlexArtist.toContentValues(sourceId: String): ContentValues = ContentValues().apply {
        put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
        put(AlbumDatabaseHelper.COLUMN_RATING_KEY, ratingKey)
        put(AlbumDatabaseHelper.COLUMN_ARTIST_NAME, title)
        put(AlbumDatabaseHelper.COLUMN_SORT_NAME, sortName)
        put(AlbumDatabaseHelper.COLUMN_THUMB_URL, thumbUrl)
        put(AlbumDatabaseHelper.COLUMN_USER_RATING, userRating)
        put(AlbumDatabaseHelper.COLUMN_ALBUM_COUNT, albumCount)
        put(AlbumDatabaseHelper.COLUMN_TRACK_COUNT, trackCount)
    }

    private fun android.database.Cursor.toPlaylist(): PlexPlaylist = PlexPlaylist(
        ratingKey = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_RATING_KEY)),
        title = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_TITLE)),
        thumbUrl = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_THUMB_URL)),
        leafCount = getInt(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_LEAF_COUNT)),
        sourceId = getString(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_SOURCE_ID)),
    )

    private fun android.database.Cursor.toTrack(): PlexTrackStream {
        fun string(name: String): String = getString(getColumnIndexOrThrow(name))
        fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
        fun nullableLong(name: String): Long? =
            if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
        fun nullableFloat(name: String): Float? =
            if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))
        fun nullableInt(name: String): Int? =
            if (isNull(getColumnIndexOrThrow(name))) null else getInt(getColumnIndexOrThrow(name))

        return PlexTrackStream(
            ratingKey = string(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY),
            title = string(AlbumDatabaseHelper.COLUMN_TRACK_TITLE),
            streamUrl = string(AlbumDatabaseHelper.COLUMN_TRACK_STREAM_URL),
            partKey = string(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY),
            durationMillis = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS),
            userRating = nullableFloat(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING),
            albumRatingKey = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY),
            albumTitle = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE),
            artistName = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME),
            thumbUrl = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_THUMB_URL),
            sourceAudioCodec = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC),
            sourceAudioBitrateKbps = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS),
            sourceAudioSampleRate = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE),
            bitDepth = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH),
            playbackMimeType = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_PLAYBACK_MIME_TYPE),
            lyricUrl = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_LYRIC_URL),
            sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
            musicAnalysisVersion = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_MUSIC_ANALYSIS_VERSION),
            viewCount = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT),
            lastViewedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS),
            skipCount = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_SKIP_COUNT),
            discNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER),
            trackNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER),
            artists = artistRefsColumn(),
        )
    }

    private fun android.database.Cursor.toDownloadedTrack(): DownloadedAudioTrack {
        fun string(name: String): String = getString(getColumnIndexOrThrow(name))
        fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
        fun nullableLong(name: String): Long? =
            if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
        fun nullableFloat(name: String): Float? =
            if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))
        fun nullableInt(name: String): Int? =
            if (isNull(getColumnIndexOrThrow(name))) null else getInt(getColumnIndexOrThrow(name))

        return DownloadedAudioTrack(
            sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
            ratingKey = string(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY),
            partKey = string(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY),
            title = string(AlbumDatabaseHelper.COLUMN_TRACK_TITLE),
            artistName = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME),
            albumTitle = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE),
            albumRatingKey = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY),
            durationMillis = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS),
            sourceAudioCodec = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC),
            sourceAudioBitrateKbps = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS),
            sourceAudioSampleRate = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE),
            bitDepth = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH),
            localAudioPath = string(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_AUDIO_PATH),
            localThumbPath = nullableString(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_THUMB_PATH),
            localLyricPath = nullableString(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_LYRIC_PATH),
            fileSize = getLong(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_DOWNLOAD_FILE_SIZE)),
            downloadedAtEpochSeconds = getLong(getColumnIndexOrThrow(AlbumDatabaseHelper.COLUMN_DOWNLOADED_AT_EPOCH_SECONDS)),
            discNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER),
            trackNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER),
            userRating = nullableFloat(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING),
        )
    }

    private fun DownloadedAudioTrack.toContentValues(): ContentValues =
        ContentValues().apply {
            put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
            put(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY, ratingKey)
            put(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY, partKey)
            put(AlbumDatabaseHelper.COLUMN_TRACK_TITLE, title)
            put(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME, artistName)
            put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE, albumTitle)
            put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY, albumRatingKey)
            put(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS, durationMillis)
            put(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC, sourceAudioCodec)
            put(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS, sourceAudioBitrateKbps)
            put(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE, sourceAudioSampleRate)
            put(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH, bitDepth)
            put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_AUDIO_PATH, localAudioPath)
            put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_THUMB_PATH, localThumbPath)
            put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_LYRIC_PATH, localLyricPath)
            put(AlbumDatabaseHelper.COLUMN_DOWNLOAD_FILE_SIZE, fileSize)
            put(AlbumDatabaseHelper.COLUMN_DOWNLOADED_AT_EPOCH_SECONDS, downloadedAtEpochSeconds)
            put(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER, discNumber)
            put(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER, trackNumber)
            put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
        }


    private fun PlexAlbum.toContentValues(): ContentValues =
        ContentValues().apply {
            put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
            put(AlbumDatabaseHelper.COLUMN_RATING_KEY, ratingKey)
            put(AlbumDatabaseHelper.COLUMN_TITLE, title)
            put(AlbumDatabaseHelper.COLUMN_ARTIST_NAME, artistName)
            put(AlbumDatabaseHelper.COLUMN_YEAR, year)
            put(AlbumDatabaseHelper.COLUMN_THUMB_URL, thumbUrl)
            put(AlbumDatabaseHelper.COLUMN_USER_RATING, userRating)
            put(AlbumDatabaseHelper.COLUMN_ADDED_AT_EPOCH_SECONDS, addedAtEpochSeconds)
            put(AlbumDatabaseHelper.COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS, lastViewedAtEpochSeconds)
            put(AlbumDatabaseHelper.COLUMN_SECTION_KEY, section.key)
            put(AlbumDatabaseHelper.COLUMN_SECTION_TITLE, section.title)
            put(AlbumDatabaseHelper.COLUMN_SECTION_TYPE, section.type)
            put(AlbumDatabaseHelper.COLUMN_LEAF_COUNT, leafCount)
            put(AlbumDatabaseHelper.COLUMN_FORMAT, format)
            put(AlbumDatabaseHelper.COLUMN_SUBFORMAT, subformat)
            put(AlbumDatabaseHelper.COLUMN_RELEASE_DATE, releaseDate)
            put(AlbumDatabaseHelper.COLUMN_ARTIST_REFS, encodeArtistRefs(artists))
        }

    private fun PlexPlaylist.toContentValues(sourceId: String, sortOrder: Int): ContentValues = ContentValues().apply {
        put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
        put(AlbumDatabaseHelper.COLUMN_RATING_KEY, ratingKey)
        put(AlbumDatabaseHelper.COLUMN_TITLE, title)
        put(AlbumDatabaseHelper.COLUMN_THUMB_URL, thumbUrl)
        put(AlbumDatabaseHelper.COLUMN_LEAF_COUNT, leafCount)
        put(AlbumDatabaseHelper.COLUMN_SORT_ORDER, sortOrder)
    }

    private fun PlexPlaylist.isPersistable(): Boolean = isPersistablePlaylistKey(ratingKey)

    private fun isPersistablePlaylistKey(ratingKey: String): Boolean =
        !ratingKey.startsWith("ai:") && ratingKey != "favorites" &&
            !ratingKey.startsWith("artist:") && !ratingKey.startsWith("downloaded:")

    private fun PlexTrackStream.toContentValues(): ContentValues =
        ContentValues().apply {
            put(AlbumDatabaseHelper.COLUMN_SOURCE_ID, sourceId)
            put(AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY, ratingKey)
            put(AlbumDatabaseHelper.COLUMN_TRACK_TITLE, title)
            put(AlbumDatabaseHelper.COLUMN_TRACK_STREAM_URL, streamUrl)
            put(AlbumDatabaseHelper.COLUMN_TRACK_PART_KEY, partKey)
            put(AlbumDatabaseHelper.COLUMN_TRACK_DURATION_MILLIS, durationMillis)
            put(AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, userRating)
            put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY, albumRatingKey)
            put(AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_TITLE, albumTitle)
            put(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME, artistName)
            put(AlbumDatabaseHelper.COLUMN_TRACK_THUMB_URL, thumbUrl)
            put(AlbumDatabaseHelper.COLUMN_TRACK_SOURCE_AUDIO_CODEC, sourceAudioCodec)
            put(AlbumDatabaseHelper.COLUMN_TRACK_BITRATE_KBPS, sourceAudioBitrateKbps)
            put(AlbumDatabaseHelper.COLUMN_TRACK_SAMPLE_RATE, sourceAudioSampleRate)
            put(AlbumDatabaseHelper.COLUMN_TRACK_BIT_DEPTH, bitDepth)
            put(AlbumDatabaseHelper.COLUMN_TRACK_PLAYBACK_MIME_TYPE, playbackMimeType)
            put(AlbumDatabaseHelper.COLUMN_TRACK_LYRIC_URL, lyricUrl)
            put(AlbumDatabaseHelper.COLUMN_TRACK_MUSIC_ANALYSIS_VERSION, musicAnalysisVersion)
            put(AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT, viewCount)
            put(AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS, lastViewedAtEpochSeconds)
            put(AlbumDatabaseHelper.COLUMN_TRACK_SKIP_COUNT, skipCount)
            put(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER, discNumber)
            put(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER, trackNumber)
            put(AlbumDatabaseHelper.COLUMN_ARTIST_REFS, encodeArtistRefs(artists))
        }

    private companion object {
        const val DEFAULT_SEARCH_LIMIT = 200
    }
}

internal fun addColumnIfNotExists(
    db: SQLiteDatabase,
    tableName: String,
    columnName: String,
    columnType: String,
) {
    val exists = db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
        val nameIndex = cursor.getColumnIndex("name")
        if (nameIndex < 0) return@use false
        generateSequence { if (cursor.moveToNext()) cursor.getString(nameIndex) else null }
            .any { it.equals(columnName, ignoreCase = true) }
    }
    if (!exists) db.execSQL("ALTER TABLE $tableName ADD COLUMN $columnName $columnType")
}

internal fun ensureVersion8Columns(db: SQLiteDatabase) {
    addColumnIfNotExists(db, "albums", "leaf_count", "INTEGER")
    for (table in listOf("tracks", "album_tracks")) {
        addColumnIfNotExists(db, table, "sample_rate", "INTEGER")
        addColumnIfNotExists(db, table, "bitrate_kbps", "INTEGER")
    }
}

internal fun ensureVersion9Columns(db: SQLiteDatabase) {
    for (table in listOf("tracks", "album_tracks")) {
        addColumnIfNotExists(db, table, "view_count", "INTEGER")
        addColumnIfNotExists(db, table, "last_viewed_at_epoch_seconds", "INTEGER")
        addColumnIfNotExists(db, table, "skip_count", "INTEGER")
        addColumnIfNotExists(db, table, "music_analysis_version", "TEXT")
    }
}

internal fun ensureVersion10Columns(db: SQLiteDatabase) {
    for (table in listOf("tracks", "album_tracks")) {
        addColumnIfNotExists(db, table, "disc_number", "INTEGER")
        addColumnIfNotExists(db, table, "track_number", "INTEGER")
    }
}

internal fun ensureVersion11Columns(db: SQLiteDatabase) {
    addColumnIfNotExists(db, "albums", "format", "TEXT")
    addColumnIfNotExists(db, "albums", "subformat", "TEXT")
}

internal fun ensureVersion12Columns(db: SQLiteDatabase) {
    for (table in listOf("tracks", "album_tracks")) {
        addColumnIfNotExists(db, table, "bit_depth", "INTEGER")
    }
}

internal fun ensureVersion14Columns(db: SQLiteDatabase) {
    addColumnIfNotExists(db, "downloaded_tracks", "disc_number", "INTEGER")
    addColumnIfNotExists(db, "downloaded_tracks", "track_number", "INTEGER")
}

internal fun ensureVersion15Columns(db: SQLiteDatabase) {
    addColumnIfNotExists(db, "downloaded_tracks", AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING, "REAL")
}

internal fun ensureVersion16Columns(db: SQLiteDatabase) {
    for (table in listOf(AlbumDatabaseHelper.TABLE_TRACKS, AlbumDatabaseHelper.TABLE_ALBUM_TRACKS)) {
        addColumnIfNotExists(db, table, AlbumDatabaseHelper.COLUMN_TRACK_LYRIC_URL, "TEXT")
    }
}

internal fun ensureVersion17Columns(db: SQLiteDatabase) {
    val albumsExists = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(AlbumDatabaseHelper.TABLE_ALBUMS),
    ).use { it.moveToFirst() }
    // Nothing to migrate when the albums table is absent (partial or legacy schemas).
    if (!albumsExists) return
    addColumnIfNotExists(db, AlbumDatabaseHelper.TABLE_ALBUMS, AlbumDatabaseHelper.COLUMN_RELEASE_DATE, "TEXT")
}

/**
 * v18: 032 artist sync model. Albums/tracks keep their artist refs (JSON) for navigation, the
 * link tables make discography queries indexable, and `artists` holds the synced performers.
 * Existing rows have no refs until the next library sync; navigation falls back to names.
 */
internal fun ensureVersion18Schema(db: SQLiteDatabase) {
    val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }
    for (table in listOf(
        AlbumDatabaseHelper.TABLE_ALBUMS,
        AlbumDatabaseHelper.TABLE_TRACKS,
        AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
        AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS,
    )) {
        if (table in tables) addColumnIfNotExists(db, table, AlbumDatabaseHelper.COLUMN_ARTIST_REFS, "TEXT")
    }
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${AlbumDatabaseHelper.TABLE_ARTISTS} (
            ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_RATING_KEY} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_SORT_NAME} TEXT,
            ${AlbumDatabaseHelper.COLUMN_THUMB_URL} TEXT,
            ${AlbumDatabaseHelper.COLUMN_USER_RATING} REAL,
            ${AlbumDatabaseHelper.COLUMN_ALBUM_COUNT} INTEGER,
            ${AlbumDatabaseHelper.COLUMN_TRACK_COUNT} INTEGER,
            PRIMARY KEY (${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_RATING_KEY})
        )
        """.trimIndent()
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_artists_name ON ${AlbumDatabaseHelper.TABLE_ARTISTS}(${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} COLLATE NOCASE)"
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS} (
            ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_LINK_POSITION} INTEGER NOT NULL,
            PRIMARY KEY (${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY}, ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY})
        )
        """.trimIndent()
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_album_artist_links_artist ON ${AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS}(${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY})"
    )
    db.execSQL(
        """
        CREATE TABLE IF NOT EXISTS ${AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS} (
            ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_LINK_TRACK_RATING_KEY} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_LINK_ALBUM_RATING_KEY} TEXT NOT NULL,
            ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY} TEXT NOT NULL,
            PRIMARY KEY (${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_LINK_TRACK_RATING_KEY}, ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY})
        )
        """.trimIndent()
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_track_artist_links_artist ON ${AlbumDatabaseHelper.TABLE_TRACK_ARTIST_LINKS}(${AlbumDatabaseHelper.COLUMN_SOURCE_ID}, ${AlbumDatabaseHelper.COLUMN_LINK_ARTIST_RATING_KEY})"
    )
}

internal fun encodeArtistRefs(refs: List<ArtistRef>): String? {
    if (refs.isEmpty()) return null
    val array = JSONArray()
    refs.forEach { ref -> array.put(JSONObject().put("id", ref.id).put("name", ref.name)) }
    return array.toString()
}

internal fun decodeArtistRefs(text: String?): List<ArtistRef> {
    if (text.isNullOrBlank()) return emptyList()
    return runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("id").trim()
            val name = item.optString("name").trim()
            if (id.isEmpty() || name.isEmpty()) null else ArtistRef(id, name)
        }
    }.getOrDefault(emptyList())
}

private class AlbumDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        createAlbumsTable(db)
        createTracksTable(db)
        createAlbumTracksTable(db)
        createDownloadedTracksTable(db)
        createPlaylistTables(db)
        ensureVersion18Schema(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTracksTable(db)
        } else if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
            createTracksTable(db)
        }
        if (oldVersion < 4) {
            createAlbumTracksTable(db)
        }
        if (oldVersion < 5) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ALBUMS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ALBUM_TRACKS")
            createAlbumsTable(db)
            createTracksTable(db)
            createAlbumTracksTable(db)
        }
        if (oldVersion >= 5 && oldVersion < 6) {
            // Existing rows do not contain codec metadata, so keeping them would let cached
            // Opus tracks bypass the new Plex transcode path until the next full refresh.
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
            db.execSQL("DROP TABLE IF EXISTS $TABLE_ALBUM_TRACKS")
            createTracksTable(db)
            createAlbumTracksTable(db)
        }
        if (oldVersion < 8) {
            // Tables may have been preserved or recreated by an earlier migration branch.
            // Inspecting the actual schema makes this safe for every upgrade path, including v5.
            ensureVersion8Columns(db)
        }
        if (oldVersion < 9) ensureVersion9Columns(db)
        if (oldVersion < 10) ensureVersion10Columns(db)
        if (oldVersion < 11) ensureVersion11Columns(db)
        if (oldVersion < 12) ensureVersion12Columns(db)
        if (oldVersion < 13) createDownloadedTracksTable(db)
        if (oldVersion < 14) ensureVersion14Columns(db)
        if (oldVersion < 15) ensureVersion15Columns(db)
        if (oldVersion < 16) {
            ensureVersion16Columns(db)
            createPlaylistTables(db)
        }
        if (oldVersion < 17) ensureVersion17Columns(db)
        if (oldVersion < 18) ensureVersion18Schema(db)
    }

    companion object {
        private const val DATABASE_NAME = "plex_album_cache.db"
        private const val DATABASE_VERSION = 18

        const val TABLE_ALBUMS = "albums"
        const val TABLE_TRACKS = "tracks"
        const val TABLE_ALBUM_TRACKS = "album_tracks"
        const val TABLE_DOWNLOADED_TRACKS = "downloaded_tracks"
        const val TABLE_PLAYLISTS = "playlists"
        const val TABLE_PLAYLIST_TRACKS = "playlist_tracks"
        const val TABLE_ARTISTS = "artists"
        const val TABLE_ALBUM_ARTIST_LINKS = "album_artist_links"
        const val TABLE_TRACK_ARTIST_LINKS = "track_artist_links"
        const val COLUMN_ARTIST_REFS = "artist_refs"
        const val COLUMN_SORT_NAME = "sort_name"
        const val COLUMN_ALBUM_COUNT = "album_count"
        const val COLUMN_TRACK_COUNT = "track_count"
        const val COLUMN_LINK_ALBUM_RATING_KEY = "album_rating_key"
        const val COLUMN_LINK_TRACK_RATING_KEY = "track_rating_key"
        const val COLUMN_LINK_ARTIST_RATING_KEY = "artist_rating_key"
        const val COLUMN_LINK_POSITION = "position"
        const val COLUMN_SOURCE_ID = "source_id"
        const val COLUMN_RATING_KEY = "rating_key"
        const val COLUMN_TITLE = "title"
        const val COLUMN_ARTIST_NAME = "artist_name"
        const val COLUMN_YEAR = "year"
        const val COLUMN_THUMB_URL = "thumb_url"
        const val COLUMN_USER_RATING = "user_rating"
        const val COLUMN_ADDED_AT_EPOCH_SECONDS = "added_at_epoch_seconds"
        const val COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS = "last_viewed_at_epoch_seconds"
        const val COLUMN_SECTION_KEY = "section_key"
        const val COLUMN_SECTION_TITLE = "section_title"
        const val COLUMN_SECTION_TYPE = "section_type"
        const val COLUMN_RELEASE_DATE = "release_date"
        const val COLUMN_TRACK_RATING_KEY = "rating_key"
        const val COLUMN_TRACK_TITLE = "title"
        const val COLUMN_TRACK_STREAM_URL = "stream_url"
        const val COLUMN_TRACK_PART_KEY = "part_key"
        const val COLUMN_TRACK_DURATION_MILLIS = "duration_millis"
        const val COLUMN_TRACK_USER_RATING = "user_rating"
        const val COLUMN_TRACK_ALBUM_RATING_KEY = "album_rating_key"
        const val COLUMN_TRACK_ALBUM_TITLE = "album_title"
        const val COLUMN_TRACK_ARTIST_NAME = "artist_name"
        const val COLUMN_TRACK_THUMB_URL = "thumb_url"
        const val COLUMN_TRACK_SOURCE_AUDIO_CODEC = "source_audio_codec"
        const val COLUMN_TRACK_SAMPLE_RATE = "sample_rate"
        const val COLUMN_TRACK_BIT_DEPTH = "bit_depth"
        const val COLUMN_TRACK_BITRATE_KBPS = "bitrate_kbps"
        const val COLUMN_TRACK_PLAYBACK_MIME_TYPE = "playback_mime_type"
        const val COLUMN_TRACK_LYRIC_URL = "lyric_url"
        const val COLUMN_TRACK_VIEW_COUNT = "view_count"
        const val COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS = "last_viewed_at_epoch_seconds"
        const val COLUMN_TRACK_SKIP_COUNT = "skip_count"
        const val COLUMN_TRACK_DISC_NUMBER = "disc_number"
        const val COLUMN_TRACK_NUMBER = "track_number"
        const val COLUMN_TRACK_MUSIC_ANALYSIS_VERSION = "music_analysis_version"
        const val COLUMN_LEAF_COUNT = "leaf_count"
        const val COLUMN_FORMAT = "format"
        const val COLUMN_SUBFORMAT = "subformat"
        const val COLUMN_SORT_ORDER = "sort_order"
        const val COLUMN_PLAYLIST_RATING_KEY = "playlist_rating_key"
        const val COLUMN_PLAYLIST_ORDER = "playlist_order"

        const val COLUMN_DOWNLOAD_LOCAL_AUDIO_PATH = "local_audio_path"
        const val COLUMN_DOWNLOAD_LOCAL_THUMB_PATH = "local_thumb_path"
        const val COLUMN_DOWNLOAD_LOCAL_LYRIC_PATH = "local_lyric_path"
        const val COLUMN_DOWNLOAD_FILE_SIZE = "file_size"
        const val COLUMN_DOWNLOADED_AT_EPOCH_SECONDS = "downloaded_at_epoch_seconds"
    }


    private fun createAlbumsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALBUMS (
                $COLUMN_SOURCE_ID TEXT NOT NULL,
                $COLUMN_RATING_KEY TEXT NOT NULL,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_ARTIST_NAME TEXT,
                $COLUMN_YEAR INTEGER,
                $COLUMN_THUMB_URL TEXT,
                $COLUMN_USER_RATING REAL,
                $COLUMN_ADDED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_SECTION_KEY TEXT NOT NULL,
                $COLUMN_SECTION_TITLE TEXT NOT NULL,
                $COLUMN_SECTION_TYPE TEXT NOT NULL,
                $COLUMN_LEAF_COUNT INTEGER,
                $COLUMN_FORMAT TEXT,
                $COLUMN_SUBFORMAT TEXT,
                $COLUMN_RELEASE_DATE TEXT,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_RATING_KEY)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_albums_title ON $TABLE_ALBUMS($COLUMN_SOURCE_ID, $COLUMN_TITLE COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_albums_artist_name ON $TABLE_ALBUMS($COLUMN_SOURCE_ID, $COLUMN_ARTIST_NAME COLLATE NOCASE)"
        )
    }

    private fun createTracksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRACKS (
                $COLUMN_SOURCE_ID TEXT NOT NULL,
                $COLUMN_TRACK_RATING_KEY TEXT NOT NULL,
                $COLUMN_TRACK_TITLE TEXT NOT NULL,
                $COLUMN_TRACK_STREAM_URL TEXT NOT NULL,
                $COLUMN_TRACK_PART_KEY TEXT NOT NULL,
                $COLUMN_TRACK_DURATION_MILLIS INTEGER,
                $COLUMN_TRACK_USER_RATING REAL,
                $COLUMN_TRACK_ALBUM_RATING_KEY TEXT,
                $COLUMN_TRACK_ALBUM_TITLE TEXT,
                $COLUMN_TRACK_ARTIST_NAME TEXT,
                $COLUMN_TRACK_THUMB_URL TEXT,
                $COLUMN_TRACK_SOURCE_AUDIO_CODEC TEXT,
                $COLUMN_TRACK_SAMPLE_RATE INTEGER,
                $COLUMN_TRACK_BIT_DEPTH INTEGER,
                $COLUMN_TRACK_BITRATE_KBPS INTEGER,
                $COLUMN_TRACK_PLAYBACK_MIME_TYPE TEXT,
                $COLUMN_TRACK_LYRIC_URL TEXT,
                $COLUMN_TRACK_VIEW_COUNT INTEGER,
                $COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_TRACK_SKIP_COUNT INTEGER,
                $COLUMN_TRACK_MUSIC_ANALYSIS_VERSION TEXT,
                $COLUMN_TRACK_DISC_NUMBER INTEGER,
                $COLUMN_TRACK_NUMBER INTEGER,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_TRACK_RATING_KEY)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_title ON $TABLE_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_TITLE COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_user_rating ON $TABLE_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_USER_RATING)"
        )
    }

    private fun createAlbumTracksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALBUM_TRACKS (
                $COLUMN_SOURCE_ID TEXT NOT NULL,
                $COLUMN_TRACK_RATING_KEY TEXT NOT NULL,
                $COLUMN_TRACK_TITLE TEXT NOT NULL,
                $COLUMN_TRACK_STREAM_URL TEXT NOT NULL,
                $COLUMN_TRACK_PART_KEY TEXT NOT NULL,
                $COLUMN_TRACK_DURATION_MILLIS INTEGER,
                $COLUMN_TRACK_USER_RATING REAL,
                $COLUMN_TRACK_ALBUM_RATING_KEY TEXT,
                $COLUMN_TRACK_ALBUM_TITLE TEXT,
                $COLUMN_TRACK_ARTIST_NAME TEXT,
                $COLUMN_TRACK_THUMB_URL TEXT,
                $COLUMN_TRACK_SOURCE_AUDIO_CODEC TEXT,
                $COLUMN_TRACK_SAMPLE_RATE INTEGER,
                $COLUMN_TRACK_BIT_DEPTH INTEGER,
                $COLUMN_TRACK_BITRATE_KBPS INTEGER,
                $COLUMN_TRACK_PLAYBACK_MIME_TYPE TEXT,
                $COLUMN_TRACK_LYRIC_URL TEXT,
                $COLUMN_TRACK_VIEW_COUNT INTEGER,
                $COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_TRACK_SKIP_COUNT INTEGER,
                $COLUMN_TRACK_MUSIC_ANALYSIS_VERSION TEXT,
                $COLUMN_TRACK_DISC_NUMBER INTEGER,
                $COLUMN_TRACK_NUMBER INTEGER,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_TRACK_RATING_KEY)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_album_tracks_album_key ON $TABLE_ALBUM_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_ALBUM_RATING_KEY)"
        )
    }

    private fun createPlaylistTables(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLISTS (
                $COLUMN_SOURCE_ID TEXT NOT NULL,
                $COLUMN_RATING_KEY TEXT NOT NULL,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_THUMB_URL TEXT,
                $COLUMN_LEAF_COUNT INTEGER NOT NULL,
                $COLUMN_SORT_ORDER INTEGER NOT NULL,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_RATING_KEY)
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PLAYLIST_TRACKS (
                $COLUMN_SOURCE_ID TEXT NOT NULL,
                $COLUMN_PLAYLIST_RATING_KEY TEXT NOT NULL,
                $COLUMN_PLAYLIST_ORDER INTEGER NOT NULL,
                $COLUMN_TRACK_RATING_KEY TEXT NOT NULL,
                $COLUMN_TRACK_TITLE TEXT NOT NULL,
                $COLUMN_TRACK_STREAM_URL TEXT NOT NULL,
                $COLUMN_TRACK_PART_KEY TEXT NOT NULL,
                $COLUMN_TRACK_DURATION_MILLIS INTEGER,
                $COLUMN_TRACK_USER_RATING REAL,
                $COLUMN_TRACK_ALBUM_RATING_KEY TEXT,
                $COLUMN_TRACK_ALBUM_TITLE TEXT,
                $COLUMN_TRACK_ARTIST_NAME TEXT,
                $COLUMN_TRACK_THUMB_URL TEXT,
                $COLUMN_TRACK_SOURCE_AUDIO_CODEC TEXT,
                $COLUMN_TRACK_SAMPLE_RATE INTEGER,
                $COLUMN_TRACK_BIT_DEPTH INTEGER,
                $COLUMN_TRACK_BITRATE_KBPS INTEGER,
                $COLUMN_TRACK_PLAYBACK_MIME_TYPE TEXT,
                $COLUMN_TRACK_LYRIC_URL TEXT,
                $COLUMN_TRACK_VIEW_COUNT INTEGER,
                $COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_TRACK_SKIP_COUNT INTEGER,
                $COLUMN_TRACK_MUSIC_ANALYSIS_VERSION TEXT,
                $COLUMN_TRACK_DISC_NUMBER INTEGER,
                $COLUMN_TRACK_NUMBER INTEGER,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_PLAYLIST_RATING_KEY, $COLUMN_PLAYLIST_ORDER)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_playlist_tracks_ref ON $TABLE_PLAYLIST_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_RATING_KEY)"
        )
    }

    private fun createDownloadedTracksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_DOWNLOADED_TRACKS (
                $COLUMN_SOURCE_ID TEXT NOT NULL,
                $COLUMN_TRACK_RATING_KEY TEXT NOT NULL,
                $COLUMN_TRACK_PART_KEY TEXT NOT NULL,
                $COLUMN_TRACK_TITLE TEXT NOT NULL,
                $COLUMN_TRACK_ARTIST_NAME TEXT,
                $COLUMN_TRACK_ALBUM_TITLE TEXT,
                $COLUMN_TRACK_ALBUM_RATING_KEY TEXT,
                $COLUMN_TRACK_DURATION_MILLIS INTEGER,
                $COLUMN_TRACK_SOURCE_AUDIO_CODEC TEXT,
                $COLUMN_TRACK_BITRATE_KBPS INTEGER,
                $COLUMN_TRACK_SAMPLE_RATE INTEGER,
                $COLUMN_TRACK_BIT_DEPTH INTEGER,
                $COLUMN_DOWNLOAD_LOCAL_AUDIO_PATH TEXT NOT NULL,
                $COLUMN_DOWNLOAD_LOCAL_THUMB_PATH TEXT,
                $COLUMN_DOWNLOAD_LOCAL_LYRIC_PATH TEXT,
                $COLUMN_DOWNLOAD_FILE_SIZE INTEGER NOT NULL,
                $COLUMN_DOWNLOADED_AT_EPOCH_SECONDS INTEGER NOT NULL,
                $COLUMN_TRACK_DISC_NUMBER INTEGER,
                $COLUMN_TRACK_NUMBER INTEGER,
                $COLUMN_TRACK_USER_RATING REAL,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_TRACK_RATING_KEY)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_downloaded_tracks_time ON $TABLE_DOWNLOADED_TRACKS($COLUMN_DOWNLOADED_AT_EPOCH_SECONDS DESC)"
        )
    }


}
