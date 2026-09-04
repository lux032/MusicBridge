package com.lux032.musicbridge.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream

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
            albums.forEach { album ->
                database.insertOrThrow(
                    AlbumDatabaseHelper.TABLE_ALBUMS,
                    null,
                    album.toContentValues(),
                )
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun upsertAlbum(album: PlexAlbum) {
        helper.writableDatabase.insertWithOnConflict(
            AlbumDatabaseHelper.TABLE_ALBUMS,
            null,
            album.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
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
                        ${AlbumDatabaseHelper.COLUMN_LEAF_COUNT} = COALESCE(?, ${AlbumDatabaseHelper.COLUMN_LEAF_COUNT})
                    WHERE ${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ?
                      AND ${AlbumDatabaseHelper.COLUMN_RATING_KEY} = ?
                    """.trimIndent(),
                    arrayOf<Any?>(album.format, album.subformat, album.leafCount, sourceId, album.ratingKey),
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
            albumTracks.values.forEach { tracks ->
                tracks.forEach { track ->
                    database.insertWithOnConflict(
                        AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                        null,
                        track.toContentValues(),
                        SQLiteDatabase.CONFLICT_REPLACE,
                    )
                }
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
            tracks.forEach { track ->
                database.insertWithOnConflict(
                    AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                    null,
                    track.toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
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
            for (table in listOf(AlbumDatabaseHelper.TABLE_TRACKS, AlbumDatabaseHelper.TABLE_ALBUM_TRACKS)) {
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
        )
    }

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
            sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
            musicAnalysisVersion = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_MUSIC_ANALYSIS_VERSION),
            viewCount = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT),
            lastViewedAtEpochSeconds = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS),
            skipCount = nullableLong(AlbumDatabaseHelper.COLUMN_TRACK_SKIP_COUNT),
            discNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER),
            trackNumber = nullableInt(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER),
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
        }

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
            put(AlbumDatabaseHelper.COLUMN_TRACK_MUSIC_ANALYSIS_VERSION, musicAnalysisVersion)
            put(AlbumDatabaseHelper.COLUMN_TRACK_VIEW_COUNT, viewCount)
            put(AlbumDatabaseHelper.COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS, lastViewedAtEpochSeconds)
            put(AlbumDatabaseHelper.COLUMN_TRACK_SKIP_COUNT, skipCount)
            put(AlbumDatabaseHelper.COLUMN_TRACK_DISC_NUMBER, discNumber)
            put(AlbumDatabaseHelper.COLUMN_TRACK_NUMBER, trackNumber)
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
    }

    companion object {
        private const val DATABASE_NAME = "plex_album_cache.db"
        private const val DATABASE_VERSION = 15

        const val TABLE_ALBUMS = "albums"
        const val TABLE_TRACKS = "tracks"
        const val TABLE_ALBUM_TRACKS = "album_tracks"
        const val TABLE_DOWNLOADED_TRACKS = "downloaded_tracks"
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
        const val COLUMN_TRACK_VIEW_COUNT = "view_count"
        const val COLUMN_TRACK_LAST_VIEWED_AT_EPOCH_SECONDS = "last_viewed_at_epoch_seconds"
        const val COLUMN_TRACK_SKIP_COUNT = "skip_count"
        const val COLUMN_TRACK_DISC_NUMBER = "disc_number"
        const val COLUMN_TRACK_NUMBER = "track_number"
        const val COLUMN_TRACK_MUSIC_ANALYSIS_VERSION = "music_analysis_version"
        const val COLUMN_LEAF_COUNT = "leaf_count"
        const val COLUMN_FORMAT = "format"
        const val COLUMN_SUBFORMAT = "subformat"

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
