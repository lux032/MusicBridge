package com.lux032.musicbridge.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

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

/** v19 keeps performer identities when the downloaded audio is played offline. */
internal fun ensureVersion19Columns(db: SQLiteDatabase) {
    val downloadsExist = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf("downloaded_tracks"),
    ).use { it.moveToFirst() }
    // Partial legacy caches may have no downloads table, just like albums in v17.
    if (!downloadsExist) return
    addColumnIfNotExists(db, "downloaded_tracks", "artist_refs", "TEXT")
}

/** v20 persists album disc counts for multi-disc album card badges across syncs. */
internal fun ensureVersion20Columns(db: SQLiteDatabase) {
    val albumsExist = db.rawQuery(
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        arrayOf(AlbumDatabaseHelper.TABLE_ALBUMS),
    ).use { it.moveToFirst() }
    if (!albumsExist) return
    addColumnIfNotExists(db, AlbumDatabaseHelper.TABLE_ALBUMS, AlbumDatabaseHelper.COLUMN_DISC_COUNT, "INTEGER")
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


internal class AlbumDatabaseHelper(context: Context) :
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
        ensureVersion19Columns(db)
        ensureVersion20Columns(db)
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
            // Opus tracks bypass the Opus transcode path until the next full refresh.
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
        if (oldVersion < 19) ensureVersion19Columns(db)
        if (oldVersion < 20) ensureVersion20Columns(db)
    }

    companion object {
        // Historical file name, kept so existing installs keep their library cache.
        private const val DATABASE_NAME = "plex_album_cache.db"
        private const val DATABASE_VERSION = 20

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
        const val COLUMN_DISC_COUNT = "disc_count"
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

    /**
     * Shared track table DDL used by both [TABLE_TRACKS] (synced favorites snapshot) and
     * [TABLE_ALBUM_TRACKS] (synced album track catalog). The two tables intentionally remain
     * separate because `replaceAllFavoriteTracks` and `replaceAllAlbumTracks` have independent
     * full-replacement sync lifecycles; unifying their DDL and mappers avoids schema drift
     * without introducing destructive v20 -> v21 migrations or sync-order coupling.
     */
    private fun createTrackCatalogTable(db: SQLiteDatabase, tableName: String) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $tableName (
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
    }

    private fun createTracksTable(db: SQLiteDatabase) {
        createTrackCatalogTable(db, TABLE_TRACKS)
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_title ON $TABLE_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_TITLE COLLATE NOCASE)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_tracks_user_rating ON $TABLE_TRACKS($COLUMN_SOURCE_ID, $COLUMN_TRACK_USER_RATING)"
        )
    }

    private fun createAlbumTracksTable(db: SQLiteDatabase) {
        createTrackCatalogTable(db, TABLE_ALBUM_TRACKS)
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
