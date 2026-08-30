package com.lux032.musicbridge.storage

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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

    private fun SQLiteDatabase.columnNames(table: String): Set<String> =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val index = cursor.getColumnIndex("name")
            check(index >= 0)
            buildSet { while (cursor.moveToNext()) add(cursor.getString(index)) }
        }

    private fun SQLiteDatabase.rowCount(table: String): Int = scalarInt("SELECT COUNT(*) FROM $table")

    private fun SQLiteDatabase.scalarInt(sql: String): Int =
        rawQuery(sql, null).use { cursor -> check(cursor.moveToFirst()); cursor.getInt(0) }
}
