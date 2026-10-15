package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032HttpException
import com.lux032.musicbridge.music.Music032PlaylistItemResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class PlaylistEditingTest {
    @Test fun onlyNumericServerPlaylistsAreEditable() {
        assertTrue(isEditableServerPlaylistKey("12"))
        assertFalse(isEditableServerPlaylistKey("0"))
        assertFalse(isEditableServerPlaylistKey("ai:5f1"))
        assertFalse(isEditableServerPlaylistKey("favorites"))
        assertFalse(isEditableServerPlaylistKey("artist:Aimer"))
        assertFalse(isEditableServerPlaylistKey("downloaded:all"))
    }

    @Test fun additionMessageReportsWhatTheServerDid() {
        assertEquals(Strings.addedToPlaylist("P", 1), playlistAdditionMessage("P", Music032PlaylistItemResult(added = 1, total = 3)))
        assertEquals(
            Strings.alreadyInPlaylist("P", 1),
            playlistAdditionMessage("P", Music032PlaylistItemResult(skippedDuplicate = 1, total = 3)),
        )
        assertEquals(
            Strings.addedToPlaylist("P", 8) + Strings.listSeparator + Strings.skippedDuplicates(2) +
                Strings.listSeparator + Strings.skippedInvalid(1),
            playlistAdditionMessage("P", Music032PlaylistItemResult(added = 8, skippedDuplicate = 2, skippedInvalid = 1)),
        )
        assertEquals(
            Strings.nothingAddedToPlaylist("P") + Strings.listSeparator + Strings.skippedInvalid(2),
            playlistAdditionMessage("P", Music032PlaylistItemResult(skippedInvalid = 2)),
        )
    }

    @Test fun errorsMapToRecoverableCopy() {
        assertEquals(Strings.playlistConflict, playlistErrorMessage(Music032HttpException(409, "x", "playlist_conflict")))
        assertEquals(Strings.playlistCapacity, playlistErrorMessage(Music032HttpException(400, "x", "playlist_capacity")))
        assertEquals(Strings.playlistNotFound, playlistErrorMessage(Music032HttpException(404, "x", "not_found")))
        assertEquals(Strings.playlistServerBusy, playlistErrorMessage(Music032HttpException(503, "x", "database_busy")))
        assertEquals(Strings.playlistUnauthorized, playlistErrorMessage(Music032HttpException(401, "x")))
        assertEquals(Strings.playlistNetworkError, playlistErrorMessage(IOException("reset")))
        assertEquals(Strings.playlistActionFailed("boom"), playlistErrorMessage(IllegalStateException("boom")))
    }
}
