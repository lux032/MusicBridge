package com.lux032.musicbridge.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lux032.musicbridge.music.MusicAlbum
import com.lux032.musicbridge.music.MusicArtist
import com.lux032.musicbridge.music.MusicPlaylist
import com.lux032.musicbridge.music.MusicTrack
import com.lux032.musicbridge.music.normalizedDiscNumber

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

    fun getAllDownloadedTracks(sourceId: String? = null): List<DownloadedAudioTrack> {
        val selection = sourceId?.takeIf(String::isNotBlank)?.let { "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?" }
        val selectionArgs = sourceId?.takeIf(String::isNotBlank)?.let { arrayOf(it) }
        val tracks = helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS,
            null,
            selection,
            selectionArgs,
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
                    add(downloadedTrackKey(sId, rKey))
                    if (sId.isBlank()) {
                        add(rKey)
                    }
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

    fun countDownloadedTracksByThumbPath(localThumbPath: String): Int {
        if (localThumbPath.isBlank()) return 0
        return helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM ${AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS} WHERE ${AlbumDatabaseHelper.COLUMN_DOWNLOAD_LOCAL_THUMB_PATH} = ?",
            arrayOf(localThumbPath),
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun totalDownloadedBytes(): Long =
        helper.readableDatabase.rawQuery(
            "SELECT COALESCE(SUM(${AlbumDatabaseHelper.COLUMN_DOWNLOAD_FILE_SIZE}), 0) FROM ${AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS}",
            null,
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else 0L
        }

    fun getAllPlaylists(sourceId: String): List<MusicPlaylist> =
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

    fun replaceAllPlaylists(sourceId: String, playlists: List<MusicPlaylist>) {
        val persistedPlaylists = playlists.asSequence()
            .filter { it.ratingKey.isNotBlank() && it.isPersistable() }
            .distinctBy(MusicPlaylist::ratingKey)
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
                    arrayOf(sourceId, *persistedPlaylists.map(MusicPlaylist::ratingKey).toTypedArray()),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun getPlaylistTracks(sourceId: String, playlistRatingKey: String): List<MusicTrack> =
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

    fun savePlaylistTracks(sourceId: String, playlistRatingKey: String, tracks: List<MusicTrack>) {
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

    fun getFavoriteTracks(sourceId: String): List<MusicTrack> =
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

    private fun queryDerivedDiscCounts(database: SQLiteDatabase, sourceId: String): Map<String, Int> {
        val hasDiscNumberColumn = database.rawQuery(
            "PRAGMA table_info(${AlbumDatabaseHelper.TABLE_ALBUM_TRACKS})",
            null,
        ).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            var found = false
            while (cursor.moveToNext()) {
                if (nameIndex >= 0 && cursor.getString(nameIndex) == AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER) {
                    found = true
                    break
                }
            }
            found
        }
        if (!hasDiscNumberColumn) return emptyMap()
        return buildMap {
            database.rawQuery(
                """
                SELECT ${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY},
                       COUNT(DISTINCT MAX(1, COALESCE(${AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER}, 1)))
                FROM ${AlbumDatabaseHelper.TABLE_ALBUM_TRACKS}
                WHERE ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?
                GROUP BY ${AlbumDatabaseHelper.COLUMN_TRACK_ALBUM_RATING_KEY}
                """.trimIndent(),
                arrayOf(sourceId),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val albumKey = cursor.getString(0)
                    val discCount = cursor.getInt(1)
                    if (!albumKey.isNullOrBlank() && discCount > 0) {
                        put(albumKey, discCount)
                    }
                }
            }
        }
    }

    private fun applyDerivedDiscCounts(
        database: SQLiteDatabase,
        sourceId: String,
        albums: List<MusicAlbum>,
    ): List<MusicAlbum> {
        if (albums.isEmpty()) return albums
        val derived = queryDerivedDiscCounts(database, sourceId)
        if (derived.isEmpty()) return albums
        return albums.map { album ->
            derived[album.ratingKey]?.let { count ->
                if (album.discCount == count) album else album.copy(discCount = count)
            } ?: album
        }
    }

    fun getAlbumsByArtist(sourceId: String, artistName: String): List<MusicAlbum> {
        val normalizedArtist = artistName.trim().ifBlank { "未知艺人" }
        val artistSelection = if (normalizedArtist == "未知艺人") {
            "(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} IS NULL OR TRIM(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = '')"
        } else {
            "TRIM(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = ?"
        }
        val selectionArgs = if (normalizedArtist == "未知艺人") arrayOf(sourceId) else arrayOf(sourceId, normalizedArtist)

        val db = helper.readableDatabase
        val albums = db.rawQuery(
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
        return applyDerivedDiscCounts(db, sourceId, albums)
    }

    fun getAllAlbums(sourceId: String): List<MusicAlbum> {
        val db = helper.readableDatabase
        val albums = db.query(
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
        return applyDerivedDiscCounts(db, sourceId, albums)
    }

    fun searchAlbums(sourceId: String, query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<MusicAlbum> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return getAllAlbums(sourceId)
        }

        val escapedQuery = normalizedQuery
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        val likeArg = "%$escapedQuery%"

        val db = helper.readableDatabase
        val albums = db.query(
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
        return applyDerivedDiscCounts(db, sourceId, albums)
    }

    internal fun getLocalSearchSnapshot(
        sourceId: String,
        downloadedOnly: Boolean,
    ): LocalLibrarySearchSnapshot {
        val downloaded = getAllDownloadedTracks(sourceId.takeIf(String::isNotBlank)).filter {
            val file = java.io.File(it.localAudioPath)
            file.exists() && file.length() > 0L
        }
        val effectiveSourceIds = if (sourceId.isNotBlank()) {
            listOf(sourceId)
        } else {
            downloaded.map(DownloadedAudioTrack::sourceId).filter(String::isNotBlank).distinct()
        }
        val albums = effectiveSourceIds.flatMap(::getAllAlbums)
        val artists = effectiveSourceIds.flatMap(::getArtists)
        if (downloadedOnly) {
            return LocalLibrarySearchSnapshot(
                artists = artists,
                albums = albums,
                tracks = emptyList(),
                downloadedTracks = downloaded,
            )
        }
        val db = helper.readableDatabase
        val catalogTracks = buildList {
            for (src in effectiveSourceIds) {
                for (table in listOf(AlbumDatabaseHelper.TABLE_ALBUM_TRACKS, AlbumDatabaseHelper.TABLE_TRACKS)) {
                    db.query(
                        table,
                        null,
                        "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?",
                        arrayOf(src),
                        null,
                        null,
                        null,
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            add(cursor.toTrack())
                        }
                    }
                }
            }
        }
        return LocalLibrarySearchSnapshot(
            artists = artists,
            albums = albums,
            tracks = catalogTracks,
            downloadedTracks = downloaded,
        )
    }

    fun replaceAllAlbums(sourceId: String, albums: List<MusicAlbum>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            val existingDiscCounts = buildMap {
                database.rawQuery(
                    "SELECT ${AlbumDatabaseHelper.COLUMN_RATING_KEY}, ${AlbumDatabaseHelper.COLUMN_DISC_COUNT} " +
                        "FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} WHERE ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? " +
                        "AND ${AlbumDatabaseHelper.COLUMN_DISC_COUNT} IS NOT NULL",
                    arrayOf(sourceId),
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        put(cursor.getString(0), cursor.getInt(1))
                    }
                }
            }
            database.delete(AlbumDatabaseHelper.TABLE_ALBUMS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            database.delete(AlbumDatabaseHelper.TABLE_ALBUM_ARTIST_LINKS, "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?", arrayOf(sourceId))
            val links = ArtistLinkWriter(database)
            try {
                albums.forEach { album ->
                    val albumWithDiscCount = if (album.discCount != null) {
                        album
                    } else {
                        existingDiscCounts[album.ratingKey]?.let { album.copy(discCount = it) } ?: album
                    }
                    database.insertOrThrow(
                        AlbumDatabaseHelper.TABLE_ALBUMS,
                        null,
                        albumWithDiscCount.toContentValues(),
                    )
                    links.album(albumWithDiscCount)
                }
            } finally {
                links.close()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun upsertAlbum(album: MusicAlbum) {
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

    fun replaceAllArtists(sourceId: String, artists: List<MusicArtist>) {
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

    fun upsertArtist(sourceId: String, artist: MusicArtist) {
        helper.writableDatabase.insertWithOnConflict(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            null,
            artist.toContentValues(sourceId),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun getArtists(sourceId: String): List<MusicArtist> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ARTISTS,
            null,
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?",
            arrayOf(sourceId),
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_ALBUM_COUNT} DESC, ${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} COLLATE NOCASE ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toArtist()) } }

    fun getArtist(sourceId: String, artistId: String): MusicArtist? =
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
    fun findArtistsByName(sourceId: String, name: String): List<MusicArtist> =
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
        val db = helper.readableDatabase
        val derivedDiscCounts = queryDerivedDiscCounts(db, sourceId)
        return db.rawQuery(
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
                    val rawAlbum = cursor.toAlbum()
                    val album = derivedDiscCounts[rawAlbum.ratingKey]?.let { count ->
                        if (rawAlbum.discCount == count) rawAlbum else rawAlbum.copy(discCount = count)
                    } ?: rawAlbum
                    val credited = cursor.getInt(creditedIndex) != 0
                    val compilationCollaboration = credited && cursor.getInt(countIndex) > 1 &&
                        album.subformat.orEmpty().contains("Compilation", ignoreCase = true)
                    add(ArtistRelease(album, isAppearance = !credited || compilationCollaboration))
                }
            }
        }
    }

    /** Performed tracks (own primary credits ∪ tracks of credited albums), for offline play. */
    fun getArtistTracks(sourceId: String, artistId: String): List<MusicTrack> {
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

        fun album(album: MusicAlbum) {
            album.artists.forEachIndexed { index, ref ->
                albumLink.bindString(1, album.sourceId)
                albumLink.bindString(2, album.ratingKey)
                albumLink.bindString(3, ref.id)
                albumLink.bindLong(4, index.toLong())
                albumLink.executeInsert()
            }
        }

        fun track(track: MusicTrack) {
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

    fun updateAlbumsMetadata(sourceId: String, albums: List<MusicAlbum>) {
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

    fun replaceAllFavoriteTracks(sourceId: String, tracks: List<MusicTrack>) {
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

    fun upsertFavoriteTrack(track: MusicTrack) {
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

    fun getAlbumTracks(sourceId: String, albumRatingKey: String): List<MusicTrack> =
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

    fun replaceAllAlbumTracks(sourceId: String, albumTracks: Map<String, List<MusicTrack>>) {
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
            refreshArtistMetadata(sourceId, albumTracks.values.flatten())
            syncAlbumDiscCounts(database, sourceId, albumTracks)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun saveAlbumTracks(sourceId: String, albumRatingKey: String, tracks: List<MusicTrack>) {
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
            refreshArtistMetadata(sourceId, tracks)
            syncAlbumDiscCounts(database, sourceId, mapOf(albumRatingKey to tracks))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun syncAlbumDiscCounts(
        database: SQLiteDatabase,
        sourceId: String,
        albumTracks: Map<String, List<MusicTrack>>,
    ) {
        val values = ContentValues()
        albumTracks.forEach { (albumKey, tracks) ->
            if (albumKey.isBlank() || tracks.isEmpty()) return@forEach
            val discCount = tracks.map { normalizedDiscNumber(it.discNumber) }.distinct().size.coerceAtLeast(1)
            values.clear()
            values.put(AlbumDatabaseHelper.COLUMN_DISC_COUNT, discCount)
            database.update(
                AlbumDatabaseHelper.TABLE_ALBUMS,
                values,
                "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_RATING_KEY} = ?",
                arrayOf(sourceId, albumKey),
            )
        }
    }

    /** Refresh identity metadata in cached copies without touching audio paths or ratings. */
    fun refreshArtistMetadata(sourceId: String, tracks: List<MusicTrack>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            tracks.filter { it.sourceId == sourceId }.forEach { track ->
                val values = ContentValues().apply {
                    put(AlbumDatabaseHelper.COLUMN_TRACK_ARTIST_NAME, track.artistName)
                    put(AlbumDatabaseHelper.COLUMN_ARTIST_REFS, encodeArtistRefs(track.artists))
                }
                listOf(AlbumDatabaseHelper.TABLE_TRACKS, AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                    AlbumDatabaseHelper.TABLE_PLAYLIST_TRACKS, AlbumDatabaseHelper.TABLE_DOWNLOADED_TRACKS).forEach { table ->
                    database.update(table, values,
                        "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND ${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
                        arrayOf(sourceId, track.ratingKey))
                }
            }
            database.setTransactionSuccessful()
        } finally { database.endTransaction() }
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

    private companion object {
        const val DEFAULT_SEARCH_LIMIT = 200
    }
}
