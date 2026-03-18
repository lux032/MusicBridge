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

    fun getFavoriteTracks(): List<PlexTrackStream> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_TRACKS,
            null,
            "${AlbumDatabaseHelper.COLUMN_TRACK_USER_RATING} > 0",
            null,
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

    fun getArtistSummaries(): List<ArtistInfo> =
        helper.readableDatabase.rawQuery(
            """
            SELECT
                COALESCE(NULLIF(TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}), ''), '未知艺人') as artist,
                COUNT(*) as album_count,
                (SELECT ${AlbumDatabaseHelper.COLUMN_THUMB_URL} FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} t2
                 WHERE COALESCE(NULLIF(TRIM(t2.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}), ''), '未知艺人') =
                       COALESCE(NULLIF(TRIM(t1.${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}), ''), '未知艺人')
                 AND t2.${AlbumDatabaseHelper.COLUMN_THUMB_URL} IS NOT NULL LIMIT 1) as cover_url
            FROM ${AlbumDatabaseHelper.TABLE_ALBUMS} t1
            GROUP BY artist
            ORDER BY album_count DESC, artist COLLATE NOCASE ASC
            """.trimIndent(),
            null
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

    fun getAlbumsByArtist(artistName: String): List<PlexAlbum> {
        val normalizedArtist = artistName.trim().ifBlank { "未知艺人" }
        val selection = if (normalizedArtist == "未知艺人") {
            "(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} IS NULL OR TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = '')"
        } else {
            "TRIM(${AlbumDatabaseHelper.COLUMN_ARTIST_NAME}) = ?"
        }
        val selectionArgs = if (normalizedArtist == "未知艺人") null else arrayOf(normalizedArtist)

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

    fun getAllAlbums(): List<PlexAlbum> =
        helper.readableDatabase.query(
            AlbumDatabaseHelper.TABLE_ALBUMS,
            null,
            null,
            null,
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

    fun searchAlbums(query: String, limit: Int = DEFAULT_SEARCH_LIMIT): List<PlexAlbum> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return getAllAlbums()
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
            ${AlbumDatabaseHelper.COLUMN_TITLE} LIKE ? ESCAPE '\' OR
            ${AlbumDatabaseHelper.COLUMN_ARTIST_NAME} LIKE ? ESCAPE '\'
            """.trimIndent(),
            arrayOf(likeArg, likeArg),
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

    fun replaceAllAlbums(albums: List<PlexAlbum>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_ALBUMS, null, null)
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

    fun replaceAllFavoriteTracks(tracks: List<PlexTrackStream>) {
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            database.delete(AlbumDatabaseHelper.TABLE_TRACKS, null, null)
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

    fun deleteFavoriteTrack(ratingKey: String) {
        helper.writableDatabase.delete(
            AlbumDatabaseHelper.TABLE_TRACKS,
            "${AlbumDatabaseHelper.COLUMN_TRACK_RATING_KEY} = ?",
            arrayOf(ratingKey),
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
        )
    }

    private fun android.database.Cursor.toTrack(): PlexTrackStream {
        fun string(name: String): String = getString(getColumnIndexOrThrow(name))
        fun nullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
        fun nullableLong(name: String): Long? =
            if (isNull(getColumnIndexOrThrow(name))) null else getLong(getColumnIndexOrThrow(name))
        fun nullableFloat(name: String): Float? =
            if (isNull(getColumnIndexOrThrow(name))) null else getFloat(getColumnIndexOrThrow(name))

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
        )
    }

    private fun PlexAlbum.toContentValues(): ContentValues =
        ContentValues().apply {
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
        }

    private fun PlexTrackStream.toContentValues(): ContentValues =
        ContentValues().apply {
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
        }

    private companion object {
        const val DEFAULT_SEARCH_LIMIT = 200
    }
}

private class AlbumDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        createAlbumsTable(db)
        createTracksTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createTracksTable(db)
        } else if (oldVersion < 3) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_TRACKS")
            createTracksTable(db)
        }
    }

    companion object {
        private const val DATABASE_NAME = "plex_album_cache.db"
        private const val DATABASE_VERSION = 3

        const val TABLE_ALBUMS = "albums"
        const val TABLE_TRACKS = "tracks"
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
    }

    private fun createAlbumsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ALBUMS (
                $COLUMN_RATING_KEY TEXT PRIMARY KEY,
                $COLUMN_TITLE TEXT NOT NULL,
                $COLUMN_ARTIST_NAME TEXT,
                $COLUMN_YEAR INTEGER,
                $COLUMN_THUMB_URL TEXT,
                $COLUMN_USER_RATING REAL,
                $COLUMN_ADDED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_LAST_VIEWED_AT_EPOCH_SECONDS INTEGER,
                $COLUMN_SECTION_KEY TEXT NOT NULL,
                $COLUMN_SECTION_TITLE TEXT NOT NULL,
                $COLUMN_SECTION_TYPE TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_albums_title ON $TABLE_ALBUMS($COLUMN_TITLE COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_albums_artist_name ON $TABLE_ALBUMS($COLUMN_ARTIST_NAME COLLATE NOCASE)"
        )
    }

    private fun createTracksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_TRACKS (
                $COLUMN_TRACK_RATING_KEY TEXT PRIMARY KEY,
                $COLUMN_TRACK_TITLE TEXT NOT NULL,
                $COLUMN_TRACK_STREAM_URL TEXT NOT NULL,
                $COLUMN_TRACK_PART_KEY TEXT NOT NULL,
                $COLUMN_TRACK_DURATION_MILLIS INTEGER,
                $COLUMN_TRACK_USER_RATING REAL,
                $COLUMN_TRACK_ALBUM_RATING_KEY TEXT,
                $COLUMN_TRACK_ALBUM_TITLE TEXT,
                $COLUMN_TRACK_ARTIST_NAME TEXT,
                $COLUMN_TRACK_THUMB_URL TEXT
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_title ON $TABLE_TRACKS($COLUMN_TRACK_TITLE COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_user_rating ON $TABLE_TRACKS($COLUMN_TRACK_USER_RATING)"
        )
    }
}
