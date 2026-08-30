package com.lux032.musicbridge.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream

class AlbumLocalStore(context: Context) {
    private val helper = AlbumDatabaseHelper(context.applicationContext)

    data class ArtistInfo(
        val name: String,
        val coverUrl: String?,
        val albumCount: Int,
    )

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
        val selection = if (normalizedArtist == "未知艺人") {
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND (${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} IS NULL OR TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = '')"
        } else {
            "${AlbumDatabaseHelper.COLUMN_SOURCE_ID} = ? AND TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = ?"
        }
        val selectionArgs = if (normalizedArtist == "未知艺人") arrayOf(sourceId) else arrayOf(sourceId, normalizedArtist)

        return helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ALBUMS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "${AlbumDatabaseHelper.COLUMN_YEAR} DESC, ${AlbumDatabaseHelper.COLUMN_TITLE} COLLATE NOCASE ASC"
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
            null,
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
                    database.insertOrThrow(
                        AlbumDatabaseHelper.TABLE_ALBUM_TRACKS,
                        null,
                        track.toContentValues(),
                    )
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
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
            leafCount = nullableInt(AlbumDatabaseHelper.COLUMN_LEAF_COUNT),
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
            playbackMimeType = nullableString(AlbumDatabaseHelper.COLUMN_TRACK_PLAYBACK_MIME_TYPE),
            sourceId = string(AlbumDatabaseHelper.COLUMN_SOURCE_ID),
        )
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
            put(AlbumDatabaseHelper.COLUMN_TRACK_PLAYBACK_MIME_TYPE, playbackMimeType)
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

private class AlbumDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createAlbumsTable(db)
        createTracksTable(db)
        createAlbumTracksTable(db)
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
    }

    companion object {
        private const val DATABASE_NAME = "plex_album_cache.db"
        private const val DATABASE_VERSION = 8

        const val TABLE_ALBUMS = "albums"
        const val TABLE_TRACKS = "tracks"
        const val TABLE_ALBUM_TRACKS = "album_tracks"
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
        const val COLUMN_TRACK_BITRATE_KBPS = "bitrate_kbps"
        const val COLUMN_TRACK_PLAYBACK_MIME_TYPE = "playback_mime_type"
        const val COLUMN_LEAF_COUNT = "leaf_count"
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
                $COLUMN_TRACK_BITRATE_KBPS INTEGER,
                $COLUMN_TRACK_PLAYBACK_MIME_TYPE TEXT,
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
                $COLUMN_TRACK_BITRATE_KBPS INTEGER,
                $COLUMN_TRACK_PLAYBACK_MIME_TYPE TEXT,
                PRIMARY KEY ($COLUMN_SOURCE_ID, $COLUMN_TRACK_RATING_KEY)
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_album_tracks_album_key ON $TABLE_ALBUM_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_ALBUM_RATING_KEY)"
        )
    }

}
