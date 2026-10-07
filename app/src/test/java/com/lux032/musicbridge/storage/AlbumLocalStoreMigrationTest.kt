package com.lux032.musicbridge.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexArtist
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun version11To12AddsTrackBitDepthColumnsIdempotently() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE tracks (source_id TEXT, rating_key TEXT)")
            db.execSQL("CREATE TABLE album_tracks (source_id TEXT, rating_key TEXT)")
            ensureVersion12Columns(db)
            ensureVersion12Columns(db)
            assertTrue(db.columnNames("tracks").contains("bit_depth"))
            assertTrue(db.columnNames("album_tracks").contains("bit_depth"))
        }

        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val track = track("source", "depth-track", 0, 0).copy(
            bitDepth = 24,
            sourceAudioSampleRate = 48_000,
            userRating = 10f,
        )
        store.replaceAllFavoriteTracks("source", listOf(track))
        val stored = store.getFavoriteTracks("source").single()
        assertEquals(24, stored.bitDepth)
        assertEquals(48_000, stored.sourceAudioSampleRate)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version10To11AddsAlbumFormatColumnsAndRoundTripsValues() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL("CREATE TABLE albums (source_id TEXT, rating_key TEXT, title TEXT)")
            ensureVersion11Columns(db)
            ensureVersion11Columns(db)
            assertTrue(db.columnNames("albums").containsAll(listOf("format", "subformat")))
        }

        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val album = PlexAlbum(
            ratingKey = "album",
            title = "Live Release",
            artistName = "Artist",
            year = 2024,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("1", "Music", "artist"),
            sourceId = "source",
            leafCount = 10,
            format = "Album",
            subformat = "Live,Remix",
        )
        store.replaceAllAlbums("source", listOf(album))
        val stored = store.getAllAlbums("source").single()
        assertEquals("Album", stored.format)
        assertEquals("Live,Remix", stored.subformat)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version10To11AddsFormatAndSubformatAndReadsSuccessfully() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE albums (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist_name TEXT,
                    year INTEGER,
                    thumb_url TEXT,
                    user_rating REAL,
                    added_at_epoch_seconds INTEGER,
                    last_viewed_at_epoch_seconds INTEGER,
                    section_key TEXT NOT NULL,
                    section_title TEXT NOT NULL,
                    section_type TEXT NOT NULL,
                    leaf_count INTEGER,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """INSERT INTO albums VALUES
                    ('source', 'legacy-album', 'Legacy Album', 'Legacy Artist', 2020, NULL, NULL,
                     NULL, NULL, '1', 'Music', 'artist', 9)"""
            )
            db.execSQL(
                """
                CREATE TABLE tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE album_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    album_rating_key TEXT,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.version = 10
        }

        val albums = AlbumLocalStore(context).getAlbumsByArtist("source", "Legacy Artist")

        assertEquals(1, albums.size)
        assertEquals("legacy-album", albums.single().ratingKey)
        assertEquals(null, albums.single().format)
        assertEquals(null, albums.single().subformat)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            assertTrue(db.columnNames("albums").containsAll(listOf("format", "subformat")))
            assertTrue(db.columnNames("tracks").contains("bit_depth"))
            assertTrue(db.columnNames("album_tracks").contains("bit_depth"))
            assertTrue(db.columnNames("downloaded_tracks").containsAll(
                listOf(
                    "source_id",
                    "rating_key",
                    "part_key",
                    "title",
                    "local_audio_path",
                    "local_thumb_path",
                    "local_lyric_path",
                    "file_size",
                    "downloaded_at_epoch_seconds",
                    "disc_number",
                    "track_number",
                    "user_rating",
                )
            ))
        }


        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version12To13CreatesDownloadedTracksTableAndIndices() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE albums (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    section_key TEXT NOT NULL,
                    section_title TEXT NOT NULL,
                    section_type TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE album_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    album_rating_key TEXT,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.version = 12
        }

        // Access store to trigger SQLiteOpenHelper upgrade from 12 to 13
        val store = AlbumLocalStore(context)
        assertEquals(emptyList<DownloadedAudioTrack>(), store.getAllDownloadedTracks())

        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            val expectedColumns = setOf(
                "source_id",
                "rating_key",
                "part_key",
                "title",
                "artist_name",
                "album_title",
                "album_rating_key",
                "duration_millis",
                "source_audio_codec",
                "bitrate_kbps",
                "sample_rate",
                "bit_depth",
                "local_audio_path",
                "local_thumb_path",
                "local_lyric_path",
                "file_size",
                "downloaded_at_epoch_seconds",
                "disc_number",
                "track_number",
                "user_rating",
            )
            val actualColumns = db.columnNames("downloaded_tracks")
            assertEquals(expectedColumns, actualColumns)
            assertTrue(db.indexNames("downloaded_tracks").contains("index_downloaded_tracks_time"))
        }

        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version13To14AddsDiscAndTrackNumberColumnsToDownloadedTracks() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL(
                """
                CREATE TABLE downloaded_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist_name TEXT,
                    album_title TEXT,
                    album_rating_key TEXT,
                    duration_millis INTEGER,
                    source_audio_codec TEXT,
                    bitrate_kbps INTEGER,
                    sample_rate INTEGER,
                    bit_depth INTEGER,
                    local_audio_path TEXT NOT NULL,
                    local_thumb_path TEXT,
                    local_lyric_path TEXT,
                    file_size INTEGER NOT NULL,
                    downloaded_at_epoch_seconds INTEGER NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            ensureVersion14Columns(db)
            ensureVersion14Columns(db) // test idempotency
            assertTrue(db.columnNames("downloaded_tracks").containsAll(listOf("disc_number", "track_number")))
        }

        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE albums (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    section_key TEXT NOT NULL,
                    section_title TEXT NOT NULL,
                    section_type TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE album_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE downloaded_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist_name TEXT,
                    album_title TEXT,
                    album_rating_key TEXT,
                    duration_millis INTEGER,
                    source_audio_codec TEXT,
                    bitrate_kbps INTEGER,
                    sample_rate INTEGER,
                    bit_depth INTEGER,
                    local_audio_path TEXT NOT NULL,
                    local_thumb_path TEXT,
                    local_lyric_path TEXT,
                    file_size INTEGER NOT NULL,
                    downloaded_at_epoch_seconds INTEGER NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO downloaded_tracks VALUES ('src', 'trk1', '/part', 'Title', 'Artist', 'Album', 'alb1', 1000, 'flac', 1000, 44100, 16, '/local/path', null, null, 5000, 12345)")
            db.version = 13
        }

        val store = AlbumLocalStore(context)
        val loaded = store.getAllDownloadedTracks().single()
        assertEquals("trk1", loaded.ratingKey)
        assertEquals(null, loaded.discNumber)
        assertEquals(null, loaded.trackNumber)

        val newTrack = loaded.copy(
            ratingKey = "trk2",
            discNumber = 2,
            trackNumber = 5,
            userRating = null,
        )
        store.insertDownloadedTrack(newTrack)
        val storedTrack = store.getDownloadedTrack("src", "trk2")
        assertEquals(2, storedTrack?.discNumber)
        assertEquals(5, storedTrack?.trackNumber)

        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            assertTrue(db.columnNames("downloaded_tracks").containsAll(listOf("disc_number", "track_number", "user_rating")))
        }
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version14To15AddsUserRatingColumnAndPreservesFavoriteStatus() {
        SQLiteDatabase.create(null).use { db ->
            db.execSQL(
                """
                CREATE TABLE downloaded_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist_name TEXT,
                    album_title TEXT,
                    album_rating_key TEXT,
                    duration_millis INTEGER,
                    source_audio_codec TEXT,
                    bitrate_kbps INTEGER,
                    sample_rate INTEGER,
                    bit_depth INTEGER,
                    local_audio_path TEXT NOT NULL,
                    local_thumb_path TEXT,
                    local_lyric_path TEXT,
                    file_size INTEGER NOT NULL,
                    downloaded_at_epoch_seconds INTEGER NOT NULL,
                    disc_number INTEGER,
                    track_number INTEGER,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            ensureVersion15Columns(db)
            ensureVersion15Columns(db) // test idempotency
            assertTrue(db.columnNames("downloaded_tracks").contains("user_rating"))
        }

        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE albums (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    section_key TEXT NOT NULL,
                    section_title TEXT NOT NULL,
                    section_type TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    user_rating REAL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE album_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    stream_url TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE downloaded_tracks (
                    source_id TEXT NOT NULL,
                    rating_key TEXT NOT NULL,
                    part_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    artist_name TEXT,
                    album_title TEXT,
                    album_rating_key TEXT,
                    duration_millis INTEGER,
                    source_audio_codec TEXT,
                    bitrate_kbps INTEGER,
                    sample_rate INTEGER,
                    bit_depth INTEGER,
                    local_audio_path TEXT NOT NULL,
                    local_thumb_path TEXT,
                    local_lyric_path TEXT,
                    file_size INTEGER NOT NULL,
                    downloaded_at_epoch_seconds INTEGER NOT NULL,
                    disc_number INTEGER,
                    track_number INTEGER,
                    PRIMARY KEY (source_id, rating_key)
                )
                """.trimIndent()
            )
            // Insert track1 with existing favorite in tracks table (legacy v14 migration scenario)
            db.execSQL("INSERT INTO tracks VALUES ('src', 'trk1', 'Track 1', 'url', '/part', 10.0)")
            db.execSQL("INSERT INTO downloaded_tracks VALUES ('src', 'trk1', '/part', 'Title 1', 'Artist', 'Album', 'alb1', 1000, 'flac', 1000, 44100, 16, '/local/path1', null, null, 5000, 12345, 1, 1)")
            // Insert track2 without favorite in tracks table
            db.execSQL("INSERT INTO downloaded_tracks VALUES ('src', 'trk2', '/part', 'Title 2', 'Artist', 'Album', 'alb1', 1000, 'flac', 1000, 44100, 16, '/local/path2', null, null, 5000, 12346, 1, 2)")
            db.version = 14
        }

        val store = AlbumLocalStore(context)
        val loaded1 = store.getDownloadedTrack("src", "trk1")
        assertNotNull(loaded1)
        assertEquals(10f, loaded1?.userRating)

        val loaded2 = store.getDownloadedTrack("src", "trk2")
        assertNotNull(loaded2)
        assertNull(loaded2?.userRating)

        // Test updateDownloadedTrackFavoriteStatus
        store.updateDownloadedTrackFavoriteStatus("src", "trk2", 10f)
        val updated2 = store.getDownloadedTrack("src", "trk2")
        assertEquals(10f, updated2?.userRating)

        store.updateDownloadedTrackFavoriteStatus("src", "trk1", null)
        // trk1 now has explicit null in downloaded_tracks, but fallback will check tracks table if needed or return null
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            assertTrue(db.columnNames("downloaded_tracks").contains("user_rating"))
        }
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version15To16AddsPlaylistTablesAndLyricColumns() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE tracks (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, stream_url TEXT NOT NULL, part_key TEXT NOT NULL, PRIMARY KEY (source_id, rating_key))")
            db.execSQL("CREATE TABLE album_tracks (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, stream_url TEXT NOT NULL, part_key TEXT NOT NULL, PRIMARY KEY (source_id, rating_key))")
            db.version = 15
        }

        AlbumLocalStore(context).getAllPlaylists("source")

        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            assertTrue(db.columnNames("tracks").contains("lyric_url"))
            assertTrue(db.columnNames("album_tracks").contains("lyric_url"))
            assertTrue(db.columnNames("playlists").containsAll(listOf("sort_order", "rating_key", "source_id")))
            assertTrue(db.columnNames("playlist_tracks").containsAll(listOf("playlist_rating_key", "playlist_order", "lyric_url")))
            assertEquals(1, db.columnNotNull("playlist_tracks", "playlist_order"))
            assertTrue(db.indexNames("playlist_tracks").contains("index_playlist_tracks_ref"))
        }
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version16To17AddsAlbumReleaseDateAndRoundTripsIt() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE albums (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, artist_name TEXT, year INTEGER, thumb_url TEXT, user_rating REAL, added_at_epoch_seconds INTEGER, last_viewed_at_epoch_seconds INTEGER, section_key TEXT NOT NULL, section_title TEXT NOT NULL, section_type TEXT NOT NULL, leaf_count INTEGER, format TEXT, subformat TEXT, PRIMARY KEY (source_id, rating_key))")
            db.execSQL("INSERT INTO albums (source_id, rating_key, title, year, section_key, section_title, section_type) VALUES ('source', 'old', 'Old', 2001, '1', 'Music', 'artist')")
            db.version = 16
        }

        val store = AlbumLocalStore(context)
        assertEquals(null, store.getAllAlbums("source").single().releaseDate)
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            assertTrue(db.columnNames("albums").contains("release_date"))
        }

        val album = PlexAlbum(
            ratingKey = "new",
            title = "New",
            artistName = "Artist",
            year = 2004,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("1", "Music", "artist"),
            sourceId = "source",
            releaseDate = "2004-12-01",
        )
        store.replaceAllAlbums("source", listOf(album))
        assertEquals("2004-12-01", store.getAllAlbums("source").single().releaseDate)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun version17To18AddsArtistSyncModel() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE albums (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, artist_name TEXT, year INTEGER, thumb_url TEXT, user_rating REAL, added_at_epoch_seconds INTEGER, last_viewed_at_epoch_seconds INTEGER, section_key TEXT NOT NULL, section_title TEXT NOT NULL, section_type TEXT NOT NULL, leaf_count INTEGER, format TEXT, subformat TEXT, release_date TEXT, PRIMARY KEY (source_id, rating_key))")
            db.execSQL("INSERT INTO albums (source_id, rating_key, title, artist_name, section_key, section_title, section_type) VALUES ('source', 'old', 'Old', 'Someone', '1', 'Music', 'artist')")
            db.version = 17
        }

        val store = AlbumLocalStore(context)
        val legacy = store.getAllAlbums("source").single()
        assertTrue(legacy.artists.isEmpty())
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(18, db.version)
            assertTrue(db.columnNames("albums").contains("artist_refs"))
            assertTrue(db.columnNames("artists").containsAll(listOf("rating_key", "artist_name", "album_count", "track_count")))
            assertTrue(db.indexNames("album_artist_links").contains("index_album_artist_links_artist"))
            assertTrue(db.indexNames("track_artist_links").contains("index_track_artist_links_artist"))
        }
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun artistSyncModelBuildsDiscographyFromIdLinks() {
        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val team = ArtistRef("1", "アトラスサウンドチーム")
        val singerA = ArtistRef("2", "高橋あず美")
        val singerB = ArtistRef("3", "Lotus Juice")
        val guest = ArtistRef("4", "目黒将司")
        fun album(key: String, artists: List<ArtistRef>, subformat: String? = null) = PlexAlbum(
            ratingKey = key,
            title = key,
            artistName = artists.joinToString(", ") { it.name },
            year = 2024,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("032", "032", "artist"),
            sourceId = "source",
            subformat = subformat,
            artists = artists,
        )
        store.replaceAllAlbums(
            "source",
            listOf(
                album("ost", listOf(team)),
                album("duet", listOf(singerA, singerB)),
                album("best", listOf(team, singerA), subformat = "Compilation"),
            ),
        )
        store.replaceAllAlbumTracks(
            "source",
            mapOf(
                "ost" to listOf(
                    track("source", "guest-track", 0, 0).copy(albumRatingKey = "ost", artists = listOf(guest)),
                    track("source", "plain-track", 0, 0).copy(albumRatingKey = "ost"),
                ),
                "duet" to listOf(track("source", "duet-track", 0, 0).copy(albumRatingKey = "duet")),
            ),
        )
        store.replaceAllArtists(
            "source",
            listOf(
                PlexArtist(team.id, team.name, null, sourceId = "source", albumCount = 2),
                PlexArtist(guest.id, guest.name, null, userRating = 10f, sourceId = "source", albumCount = 1),
            ),
        )

        // Refs round-trip on albums and tracks.
        assertEquals(listOf(singerA, singerB), store.getAllAlbums("source").first { it.ratingKey == "duet" }.artists)
        assertEquals(listOf(guest), store.getAlbumTracks("source", "ost").first { it.ratingKey == "guest-track" }.artists)

        // A track-only singer gets the OST as an appearance, and only their own track.
        val guestReleases = store.getArtistDiscography("source", guest.id)
        assertEquals(listOf("ost"), guestReleases.map { it.album.ratingKey })
        assertTrue(guestReleases.single().isAppearance)
        assertEquals(listOf("guest-track"), store.getArtistTracks("source", guest.id).map { it.ratingKey })

        // Joint album is the singer's own release; a shared compilation is an appearance.
        val singerReleases = store.getArtistDiscography("source", singerA.id).associate { it.album.ratingKey to it.isAppearance }
        assertEquals(mapOf("duet" to false, "best" to true), singerReleases)
        assertEquals(setOf("duet-track"), store.getArtistTracks("source", singerB.id).map { it.ratingKey }.toSet())

        assertEquals(guest.id, store.findArtistsByName("source", " 目黒将司 ").single().ratingKey)
        assertEquals(10f, store.getArtist("source", guest.id)?.userRating)
        store.updateArtistFavorite("source", guest.id, null)
        assertNull(store.getArtist("source", guest.id)?.userRating)
        assertEquals(listOf(team.id, guest.id), store.getArtists("source").map { it.ratingKey })

        // Re-syncing tracks of one album drops stale links.
        store.saveAlbumTracks("source", "ost", listOf(track("source", "plain-track", 0, 0).copy(albumRatingKey = "ost")))
        assertTrue(store.getArtistDiscography("source", guest.id).isEmpty())

        store.deleteServerData("source")
        assertTrue(store.getArtists("source").isEmpty())
        assertTrue(store.getArtistDiscography("source", team.id).isEmpty())
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun playlistCachePreservesOrderDuplicatesLyricsAndFiltersInternalPlaylists() {
        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        store.replaceAllPlaylists(
            "source",
            listOf(
                PlexPlaylist("playlist-2", "Second", null, 2, "source"),
                PlexPlaylist("ai:temp", "AI", null, 1, "source"),
                PlexPlaylist("playlist-1", "First", null, 1, "source"),
            ),
        )
        assertEquals(listOf("playlist-2", "playlist-1"), store.getAllPlaylists("source").map { it.ratingKey })

        val first = track("source", "track-1", 0, 0).copy(lyricUrl = "https://lyrics/1")
        val second = track("source", "track-2", 0, 0)
        store.savePlaylistTracks("source", "playlist-2", listOf(first, second, first))
        val stored = store.getPlaylistTracks("source", "playlist-2")
        assertEquals(listOf("track-1", "track-2", "track-1"), stored.map { it.ratingKey })
        assertEquals("https://lyrics/1", stored.first().lyricUrl)

        store.savePlaylistTracks("source", "favorites", listOf(first))
        assertTrue(store.getPlaylistTracks("source", "favorites").isEmpty())
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun replacingPlaylistListRemovesOrphanedPlaylistTracks() {
        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val first = PlexPlaylist("playlist-1", "First", null, 1, "source")
        val second = PlexPlaylist("playlist-2", "Second", null, 1, "source")
        val cachedTrack = track("source", "track-1", 0, 0)
        store.replaceAllPlaylists("source", listOf(first, second))
        store.savePlaylistTracks("source", first.ratingKey, listOf(cachedTrack))
        store.savePlaylistTracks("source", second.ratingKey, listOf(cachedTrack))

        store.replaceAllPlaylists("source", listOf(second))

        assertTrue(store.getPlaylistTracks("source", first.ratingKey).isEmpty())
        assertEquals(1, store.getPlaylistTracks("source", second.ratingKey).size)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun upgradedAndFreshDatabasesHaveMatchingPlaylistSchemas() {
        val context = cleanDatabaseContext()
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("CREATE TABLE tracks (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, stream_url TEXT NOT NULL, part_key TEXT NOT NULL, PRIMARY KEY (source_id, rating_key))")
            db.execSQL("CREATE TABLE album_tracks (source_id TEXT NOT NULL, rating_key TEXT NOT NULL, title TEXT NOT NULL, stream_url TEXT NOT NULL, part_key TEXT NOT NULL, PRIMARY KEY (source_id, rating_key))")
            db.version = 15
        }
        AlbumLocalStore(context).getAllPlaylists("source")
        val upgraded = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            mapOf("playlists" to db.columnNames("playlists"), "playlist_tracks" to db.columnNames("playlist_tracks"))
        }
        context.deleteDatabase(DATABASE_NAME)
        AlbumLocalStore(context).getAllPlaylists("source")
        val fresh = context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            mapOf("playlists" to db.columnNames("playlists"), "playlist_tracks" to db.columnNames("playlist_tracks"))
        }
        assertEquals(fresh, upgraded)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun artistAlbumsUseStoredTrackCountWhenLeafCountIsMissing() {
        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val album = PlexAlbum(
            ratingKey = "album",
            title = "Release",
            artistName = "Artist",
            year = 2024,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("1", "Music", "artist"),
            sourceId = "source",
            leafCount = null,
        )
        store.replaceAllAlbums("source", listOf(album))
        store.replaceAllAlbumTracks(
            "source",
            mapOf("album" to listOf(
                track("source", "track-1", viewCount = 0, lastViewedAt = 0),
                track("source", "track-2", viewCount = 0, lastViewedAt = 0),
            )),
        )

        assertEquals(2, store.getAlbumsByArtist("source", "Artist").single().leafCount)
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun updateAlbumsMetadataUpdatesOnlyNonNullValues() {
        val context = cleanDatabaseContext()
        val store = AlbumLocalStore(context)
        val original = PlexAlbum(
            ratingKey = "album",
            title = "Release",
            artistName = "Artist",
            year = 2024,
            thumbUrl = null,
            userRating = null,
            addedAtEpochSeconds = null,
            lastViewedAtEpochSeconds = null,
            section = PlexSection("1", "Music", "artist"),
            sourceId = "source",
            leafCount = 8,
            format = "Album",
            subformat = "Remix",
        )
        store.replaceAllAlbums("source", listOf(original))

        store.updateAlbumsMetadata(
            "source",
            listOf(original.copy(leafCount = 2, format = "Single", subformat = null)),
        )

        val updated = store.getAllAlbums("source").single()
        assertEquals(2, updated.leafCount)
        assertEquals("Single", updated.format)
        assertEquals("Remix", updated.subformat)
        context.deleteDatabase(DATABASE_NAME)
    }

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

    private fun SQLiteDatabase.columnNotNull(table: String, column: String): Int =
        rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val notNullIndex = cursor.getColumnIndex("notnull")
            while (cursor.moveToNext()) if (cursor.getString(nameIndex) == column) return@use cursor.getInt(notNullIndex)
            error("Column $column not found in $table")
        }

    private fun SQLiteDatabase.indexNames(table: String): Set<String> =
        rawQuery("PRAGMA index_list($table)", null).use { cursor ->
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
