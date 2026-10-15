package com.lux032.musicbridge.music

import com.lux032.musicbridge.MusicServerProfile
import com.lux032.musicbridge.MusicServerType
import com.lux032.musicbridge.plex.PlexAlbum
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Wire-level check of the playlist write API against a real 032 server. Skipped unless
 * `MUSIC032_E2E_URL`, `MUSIC032_E2E_TOKEN` and `MUSIC032_E2E_MEDIA_TOKEN` are set; needs a
 * library with at least two albums of two or more tracks. Creates and deletes its own playlist.
 */
class Music032PlaylistLiveTest {
    private val url = System.getenv("MUSIC032_E2E_URL").orEmpty()
    private val token = System.getenv("MUSIC032_E2E_TOKEN").orEmpty()
    private val mediaToken = System.getenv("MUSIC032_E2E_MEDIA_TOKEN").orEmpty()

    @Test fun fullPlaylistLifecycle() = runBlocking {
        assumeTrue("set MUSIC032_E2E_URL to run", url.isNotBlank() && token.isNotBlank())
        val client = Music032Client(
            MusicServerProfile(id = "e2e", type = MusicServerType.Music032, token = token, mediaToken = mediaToken, baseUrl = url),
        )
        val albums: List<PlexAlbum> = client.fetchAlbums().albums
        val first = client.fetchAlbumTrackStreams(albums[0]).tracks
        val second = albums[1]

        // Create with tracks (skip policy) — an unknown id is skipped, not fatal.
        val created = client.createPlaylist("E2E 歌单", "desc", listOf(first[0].ratingKey, "999999"))
        val id = created.playlist.ratingKey
        try {
            assertEquals(1, created.stats!!.added)
            assertEquals(1, created.stats!!.skippedInvalid)

            // Empty create stays on the strict path (no unknown JSON fields).
            val empty = client.createPlaylist("E2E empty", "", emptyList())
            assertEquals(0, empty.playlist.leafCount)
            client.deletePlaylist(empty.playlist.ratingKey)

            // Append a track, then the same track again → duplicate is reported, not added.
            val appended = client.appendPlaylistTracks(id, listOf(first[1].ratingKey))
            assertEquals(1, appended.added)
            val duplicate = client.appendPlaylistTracks(id, listOf(first[1].ratingKey))
            assertEquals(0, duplicate.added)
            assertEquals(1, duplicate.skippedDuplicate)

            // Whole album.
            val album = client.appendPlaylistAlbum(id, second.ratingKey)
            assertTrue(album.added >= 2)

            // Remove with a stale revision → 409 playlist_conflict, nothing removed.
            val detail = client.fetchPlaylistTracks(created.playlist)
            val revision = detail.playlist.revision!!
            val stale = runCatching { client.removePlaylistTracks(id, listOf(first[1].ratingKey), revision - 1) }
            val error = stale.exceptionOrNull() as Music032HttpException
            assertEquals(409, error.status)
            assertEquals("playlist_conflict", error.code)

            val removed = client.removePlaylistTracks(id, listOf(first[1].ratingKey), revision)
            assertEquals(1, removed.removed)
            assertFalse(client.fetchPlaylistTracks(created.playlist).tracks.any { it.ratingKey == first[1].ratingKey })

            // Undo puts it back at its old index.
            client.insertPlaylistTrack(id, first[1].ratingKey, 1)
            assertEquals(first[1].ratingKey, client.fetchPlaylistTracks(created.playlist).tracks[1].ratingKey)

            // Rename + description. The desktop JDK's HttpURLConnection rejects PATCH (Android's
            // OkHttp-backed implementation accepts it), so this step only runs where supported.
            val renamed = try {
                client.updatePlaylist(id, "E2E 改名", "新的简介")
            } catch (unsupported: java.net.ProtocolException) {
                null
            }
            if (renamed != null) {
                assertEquals("E2E 改名", renamed.title)
                assertEquals("新的简介", renamed.description)
                assertTrue(client.fetchPlaylists().playlists.any { it.ratingKey == id && it.title == "E2E 改名" })
            }
        } finally {
            client.deletePlaylist(id)
        }
        assertFalse(client.fetchPlaylists().playlists.any { it.ratingKey == id })
        val gone = runCatching { client.deletePlaylist(id) }.exceptionOrNull() as Music032HttpException
        assertEquals(404, gone.status)
    }
}
