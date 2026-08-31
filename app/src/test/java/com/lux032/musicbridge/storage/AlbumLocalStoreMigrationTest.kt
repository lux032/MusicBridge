package com.lux032.musicbridge.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lux032.musicbridge.plex.PlexTrackStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlbumLocalStoreMigrationTest {
    @Test
    fun version5To8AddsMissingColumnsAndPreservesRows() = verifyMigration(fromVersion = 5)

    @Test
    fun version6To8AddsMissingColumnsAndPreservesRows() = verifyMigration(fromVersion = 6)

    @Test
    fun version7To8AddsOnlyMissingColumnsAndPreservesRows() = verifyMigration(fromVersion = 7)

    @Test
    fun recordTrackPlaybackUpdatesBothTablesAtomicallyAndIsolatesSources() {
        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val sourceOne = track("source-1", "track", viewCount = 2, lastViewedAt = 10)
        val sourceTwo = track("source-2", "track", viewCount = 9, lastViewedAt = 20)
        store.replaceAllFavoriteTracks("source-1", listOf(sourceOne))
        store.replaceAllFavoriteTracks("source-2", listOf(sourceTwo))
        store.replaceAllAlbumTracks("source-1", mapOf("album" to listOf(sourceOne)))
        store.replaceAllAlbumTracks("source-2", mapOf("album" to listOf(sourceTwo)))

        store.recordTrackPlayback("source-1", "track", playedAtEpochSeconds = 1234)

        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            for (table in listOf("tracks", "album_tracks")) {
                assertEquals(3, db.scalarInt("SELECT view_count FROM $table WHERE source_id = 'source-1' AND rating_key = 'track'"))
                assertEquals(1234, db.scalarInt("SELECT last_viewed_at_epoch_seconds FROM $table WHERE source_id = 'source-1' AND rating_key = 'track'"))
                assertEquals(9, db.scalarInt("SELECT view_count FROM $table WHERE source_id = 'source-2' AND rating_key = 'track'"))
                assertEquals(20, db.scalarInt("SELECT last_viewed_at_epoch_seconds FROM $table WHERE source_id = 'source-2' AND rating_key = 'track'"))
            }
        }
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun upgradedAndFreshDatabasesHaveMatchingTrackSchemas() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            for (table in listOf("tracks", "album_tracks")) {
                db.execSQL(
                    """
                    CREATE TABLE $table (
                        source_id TEXT NOT NULL,
                        rating_key TEXT NOT NULL,
                        title TEXT NOT NULL,
                        stream_url TEXT NOT NULL,
                        part_key TEXT NOT NULL,
                        duration_millis INTEGER,
                        user_rating REAL,
                        album_rating_key TEXT,
                        album_title TEXT,
                        artist_name TEXT,
                        thumb_url TEXT,
                        source_audio_codec TEXT,
                        sample_rate INTEGER,
                        bitrate_kbps INTEGER,
                        playback_mime_type TEXT,
                        PRIMARY KEY (source_id, rating_key)
                    )
                    """.trimIndent()
                )
            }
            db.execSQL("CREATE TABLE albums (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, artist_name TEXT, year INTEGER, thumb_url TEXT, user_rating REAL, added_at_epoch_seconds INTEGER, last_viewed_at_epoch_seconds INTEGER, section_key TEXT NOT NULL, section_title TEXT NOT NULL, section_type TEXT NOT NULL, leaf_count INTEGER, PRIMARY KEY (source_id, rating_key))")
            db.version = 8
        }
        AlbumLocalStore(context).getFavoriteTracks("source")
        val upgradedSchemas = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            mapOf("tracks" to db.columnNames("tracks"), "album_tracks" to db.columnNames("album_tracks"))
        }

        context.deleteDatabase(DATABASE_NAME)
        AlbumLocalStore(context).getFavoriteTracks("source")
        val freshSchemas = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            mapOf("tracks" to db.columnNames("tracks"), "album_tracks" to db.columnNames("album_tracks"))
        }

        assertEquals(freshSchemas, upgradedSchemas)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version8To9AddsBehaviorColumnsAndPreservesRows() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE tracks (source_id TEXT, rating_key TEXT, title TEXT)")
            db.execSQL("CREATE TABLE album_tracks (source_id TEXT, rating_key TEXT, title TEXT)")
            db.execSQL("INSERT INTO tracks VALUES ('source', 'track', 'Track')")
            db.execSQL("INSERT INTO album_tracks VALUES ('source', 'track', 'Track')")

            ensureVersion9Columns(db)
            ensureVersion9Columns(db)

            val expected = listOf("view_count", "last_viewed_at_epoch_seconds", "skip_count", "music_analysis_version")
            assertTrue(db.columnNames("tracks").containsAll(expected))
            assertTrue(db.columnNames("album_tracks").containsAll(expected))
            assertEquals(1, db.rowCount("tracks"))
            assertEquals(1, db.rowCount("album_tracks"))
            db.execSQL("UPDATE tracks SET view_count = 7, last_viewed_at_epoch_seconds = 123 WHERE rating_key = 'track'")
            assertEquals(7, db.scalarInt("SELECT view_count FROM tracks WHERE rating_key = 'track'"))
        }
    }

    private fun verifyMigration(fromVersion: Int) {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE albums (source_id TEXT, rating_key TEXT, title TEXT)")
            db.execSQL("CREATE TABLE tracks (source_id TEXT, rating_key TEXT, title TEXT)")
            db.execSQL("CREATE TABLE album_tracks (source_id TEXT, rating_key TEXT, title TEXT)")
            if (fromVersion >= 7) db.execSQL("ALTER TABLE albums ADD COLUMN leaf_count INTEGER")
            db.execSQL("INSERT INTO albums VALUES ('source', 'album', 'Album'${if (fromVersion >= 7) ", 12" else ""})")
            db.execSQL("INSERT INTO tracks VALUES ('source', 'track', 'Track')")
            db.execSQL("INSERT INTO album_tracks VALUES ('source', 'track', 'Track')")

            ensureVersion8Columns(db)
            ensureVersion8Columns(db)

            assertTrue(db.columnNames("albums").contains("leaf_count"))
            assertTrue(db.columnNames("tracks").containsAll(listOf("sample_rate", "bitrate_kbps")))
            assertTrue(db.columnNames("album_tracks").containsAll(listOf("sample_rate", "bitrate_kbps")))
            assertEquals(1, db.rowCount("albums"))
            assertEquals(1, db.rowCount("tracks"))
            assertEquals(1, db.rowCount("album_tracks"))
            db.execSQL("UPDATE albums SET leaf_count = 9 WHERE rating_key = 'album'")
            db.execSQL("UPDATE tracks SET sample_rate = 96000, bitrate_kbps = 4608 WHERE rating_key = 'track'")
            assertEquals(9, db.scalarInt("SELECT leaf_count FROM albums WHERE rating_key = 'album'"))
            assertEquals(96000, db.scalarInt("SELECT sample_rate FROM tracks WHERE rating_key = 'track'"))
        }
    }

    private fun cleanDatabaseContext(): Context =
        RuntimeEnvironment.getApplication().also { it.deleteDatabase(DATABASE_NAME) }

    private fun track(sourceId: String, ratingKey: String, viewCount: Long, lastViewedAt: Long) = PlexTrackStream(
        ratingKey = ratingKey,
        title = "Track",
        streamUrl = "url",
        partKey = "/part",
        durationMillis = 1000,
        userRating = null,
        albumRatingKey = "album",
        sourceId = sourceId,
        viewCount = viewCount,
        lastViewedAtEpochSeconds = lastViewedAt,
    )

    private fun SQLiteDatabase.columnNames(table: String): Set<String> =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val index = cursor.getColumnIndex("name")
            check(index >= 0)
            buildSet { while (cursor.moveToNext()) add(cursor.getString(index)) }
        }

    private fun SQLiteDatabase.rowCount(table: String): Int = scalarInt("SELECT COUNT(*) FROM $table")

    private fun SQLiteDatabase.scalarInt(sql: String): Int =
        rawQuery(sql, null).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }

    private companion object {
        const val DATABASE_NAME = "plex_album_cache.db"
    }
}
