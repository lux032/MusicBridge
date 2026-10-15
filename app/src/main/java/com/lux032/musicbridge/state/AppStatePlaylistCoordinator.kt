package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.music.Music032HttpException
import com.lux032.musicbridge.music.Music032PlaylistItemResult
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** What the user asked to put into a playlist: some tracks, or a whole album. */
sealed interface PlaylistAddition {
    val sourceId: String

    data class Tracks(val tracks: List<PlexTrackStream>) : PlaylistAddition {
        override val sourceId: String get() = tracks.firstOrNull()?.sourceId.orEmpty()
    }

    data class Album(val album: PlexAlbum, val trackCount: Int? = null) : PlaylistAddition {
        override val sourceId: String get() = album.sourceId
    }
}

/** Transient playlist feedback for the global snackbar; [id] makes repeats distinct. */
data class PlaylistNotice(
    val id: Long,
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
    val isError: Boolean = false,
)

/** Real server playlists have numeric 032 ids; AI / favourites / artist mixes do not. */
internal fun isEditableServerPlaylistKey(ratingKey: String): Boolean =
    ratingKey.toLongOrNull()?.let { it > 0 } == true

/** User-facing copy for a failed playlist write: names the problem and the recovery. */
internal fun playlistErrorMessage(error: Throwable): String = when {
    error is Music032HttpException && error.code == "playlist_conflict" -> Strings.playlistConflict
    error is Music032HttpException && error.code == "playlist_capacity" -> Strings.playlistCapacity
    error is Music032HttpException && error.status == 404 -> Strings.playlistNotFound
    error is Music032HttpException && error.status == 503 -> Strings.playlistServerBusy
    error is Music032HttpException && (error.status == 401 || error.status == 403) -> Strings.playlistUnauthorized
    error is IOException -> Strings.playlistNetworkError
    else -> Strings.playlistActionFailed(error.message)
}

/** Snackbar copy for an append; reports duplicates and vanished tracks honestly. */
internal fun playlistAdditionMessage(playlistName: String, result: Music032PlaylistItemResult): String {
    val base = when {
        result.added == 0 && result.skippedDuplicate > 0 -> Strings.alreadyInPlaylist(playlistName, result.skippedDuplicate)
        result.added == 0 -> Strings.nothingAddedToPlaylist(playlistName)
        else -> Strings.addedToPlaylist(playlistName, result.added)
    }
    val extras = buildList {
        if (result.added > 0 && result.skippedDuplicate > 0) add(Strings.skippedDuplicates(result.skippedDuplicate))
        if (result.skippedInvalid > 0) add(Strings.skippedInvalid(result.skippedInvalid))
    }
    return if (extras.isEmpty()) base else base + Strings.listSeparator + extras.joinToString(Strings.listSeparator)
}

/**
 * Writes to 032 server playlists: create, rename, delete, add tracks / albums, remove tracks
 * with undo. The server is the source of truth; local state is updated optimistically only
 * where a rollback is cheap (track removal), and the cached list is re-synced afterwards.
 */
internal class AppStatePlaylistCoordinator(
    private val state: AppState,
) {
    private var noticeSequence = 0L

    fun canEdit(sourceId: String): Boolean =
        sourceId.isNotBlank() && state.serverProfiles.any { it.id == sourceId && it.type == MusicServerType.Music032 }

    /** Adding needs the active server: the picker lists the active server's playlists. */
    fun canAddFrom(sourceId: String): Boolean =
        canEdit(sourceId) && sourceId == state.connectionPreferences.id

    fun canEditPlaylist(playlist: PlexPlaylist): Boolean =
        canEdit(playlist.sourceId.ifBlank { state.connectionPreferences.id }) && isEditableServerPlaylistKey(playlist.ratingKey)

    fun notify(message: String, actionLabel: String? = null, isError: Boolean = false, onAction: (() -> Unit)? = null) {
        state.playlistNotice = PlaylistNotice(++noticeSequence, message, actionLabel, onAction, isError)
    }

    private fun client(sourceId: String): Music032Client =
        state.clientFor(sourceId) as? Music032Client ?: error(Strings.playlistEditUnsupported)

    fun requestAdd(addition: PlaylistAddition) {
        if (!canAddFrom(addition.sourceId)) return
        if (addition is PlaylistAddition.Tracks && addition.tracks.isEmpty()) return
        state.playlistAddRequest = addition
        // The picker should list what the server has now, not a stale cache.
        state.loadPlaylists()
    }

    /** Shows a just-created (empty) playlist without a round trip. */
    fun openCreated(playlist: PlexPlaylist) {
        state.aiPlaylistDescription = null
        state.selectedPlaylist = playlist
        state.playlistTrackResult = PlexPlaylistTracksResult("", playlist, emptyList())
        state.navigateTo(AppSection.PlaylistDetail)
    }

    suspend fun create(name: String, description: String, addition: PlaylistAddition?): Result<PlexPlaylist> {
        val sourceId = addition?.sourceId ?: state.connectionPreferences.id
        if (!canEdit(sourceId)) return Result.failure(IllegalStateException(Strings.playlistEditUnsupported))
        if (name.isBlank()) return Result.failure(IllegalArgumentException(Strings.playlistNameRequired))
        state.isPlaylistMutating = true
        return try {
            val created = withContext(Dispatchers.IO) {
                val client = client(sourceId)
                val trackIds = (addition as? PlaylistAddition.Tracks)?.tracks?.map { it.ratingKey }.orEmpty()
                val result = client.createPlaylist(name, description, trackIds)
                val albumStats = (addition as? PlaylistAddition.Album)?.let {
                    client.appendPlaylistAlbum(result.playlist.ratingKey, it.album.ratingKey)
                }
                val stats = albumStats ?: result.stats
                result.playlist.copy(
                    leafCount = stats?.total ?: result.playlist.leafCount,
                    revision = stats?.revision ?: result.playlist.revision,
                ) to stats
            }
            val (playlist, stats) = created
            if (state.connectionPreferences.id == sourceId) {
                state.playlists = listOf(playlist) + state.playlists.filterNot { it.ratingKey == playlist.ratingKey }
                persistPlaylistList(sourceId)
            }
            notify(
                if (addition != null && stats != null) Strings.playlistCreatedWith(playlist.title, stats.added)
                else Strings.playlistCreated(playlist.title)
            )
            // Re-sync: artwork is derived server-side from the first track.
            state.loadPlaylists()
            Result.success(playlist)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(IllegalStateException(playlistErrorMessage(error), error))
        } finally {
            state.isPlaylistMutating = false
        }
    }

    fun addTo(playlist: PlexPlaylist, addition: PlaylistAddition) {
        val sourceId = playlist.sourceId.ifBlank { addition.sourceId }
        if (!canEdit(sourceId) || !isEditableServerPlaylistKey(playlist.ratingKey)) return
        state.isPlaylistMutating = true
        state.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = client(sourceId)
                    when (addition) {
                        is PlaylistAddition.Album -> client.appendPlaylistAlbum(playlist.ratingKey, addition.album.ratingKey)
                        is PlaylistAddition.Tracks -> client.appendPlaylistTracks(playlist.ratingKey, addition.tracks.map { it.ratingKey })
                    }
                }
                applyItemResult(sourceId, playlist.ratingKey, result)
                notify(playlistAdditionMessage(playlist.title, result))
                if (result.added > 0) refreshPlaylistTracks(sourceId, playlist)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                notify(playlistErrorMessage(error), isError = true)
            } finally {
                state.isPlaylistMutating = false
            }
        }
    }

    suspend fun update(playlist: PlexPlaylist, name: String, description: String): Result<PlexPlaylist> {
        val sourceId = playlist.sourceId.ifBlank { state.connectionPreferences.id }
        if (!canEditPlaylist(playlist)) return Result.failure(IllegalStateException(Strings.playlistEditUnsupported))
        if (name.isBlank()) return Result.failure(IllegalArgumentException(Strings.playlistNameRequired))
        state.isPlaylistMutating = true
        return try {
            val updated = withContext(Dispatchers.IO) { client(sourceId).updatePlaylist(playlist.ratingKey, name, description) }
            replacePlaylistEverywhere(sourceId, updated)
            persistPlaylistList(sourceId)
            notify(Strings.playlistSaved)
            Result.success(updated)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            Result.failure(IllegalStateException(playlistErrorMessage(error), error))
        } finally {
            state.isPlaylistMutating = false
        }
    }

    suspend fun delete(playlist: PlexPlaylist): Result<Unit> {
        val sourceId = playlist.sourceId.ifBlank { state.connectionPreferences.id }
        if (!canEditPlaylist(playlist)) return Result.failure(IllegalStateException(Strings.playlistEditUnsupported))
        state.isPlaylistMutating = true
        return try {
            withContext(Dispatchers.IO) { client(sourceId).deletePlaylist(playlist.ratingKey) }
            onPlaylistGone(sourceId, playlist)
            notify(Strings.playlistDeleted(playlist.title))
            Result.success(Unit)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            if (error is Music032HttpException && error.status == 404) {
                // Already gone on the server: converge instead of reporting a failure.
                onPlaylistGone(sourceId, playlist)
                notify(Strings.playlistDeleted(playlist.title))
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException(playlistErrorMessage(error), error))
            }
        } finally {
            state.isPlaylistMutating = false
        }
    }

    /** Removes [track] from the open playlist: optimistic, with undo and rollback. */
    fun removeTrack(track: PlexTrackStream) {
        val current = state.playlistTrackResult ?: return
        val playlist = current.playlist
        if (!canEditPlaylist(playlist)) return
        val sourceId = playlist.sourceId.ifBlank { state.connectionPreferences.id }
        val index = current.tracks.indexOfFirst { it.ratingKey == track.ratingKey }
        if (index < 0) return
        val remaining = current.tracks.filterIndexed { position, _ -> position != index }
        state.playlistTrackResult = current.copy(
            playlist = playlist.copy(leafCount = remaining.size),
            tracks = remaining,
        )
        state.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val client = client(sourceId)
                    val revision = state.playlistTrackResult?.playlist?.takeIf { it.ratingKey == playlist.ratingKey }?.revision
                        ?: playlist.revision
                        ?: client.fetchPlaylistTracks(playlist).playlist.revision
                        ?: 0L
                    try {
                        client.removePlaylistTracks(playlist.ratingKey, listOf(track.ratingKey), revision)
                    } catch (conflict: Music032HttpException) {
                        if (conflict.code != "playlist_conflict" && conflict.status != 409) throw conflict
                        // Someone else edited it meanwhile: retry once on the fresh revision.
                        val fresh = client.fetchPlaylistTracks(playlist)
                        if (fresh.tracks.none { it.ratingKey == track.ratingKey }) {
                            Music032PlaylistItemResult(total = fresh.tracks.size, revision = fresh.playlist.revision)
                        } else {
                            client.removePlaylistTracks(playlist.ratingKey, listOf(track.ratingKey), fresh.playlist.revision ?: 0L)
                        }
                    }
                }
                applyItemResult(sourceId, playlist.ratingKey, result)
                persistOpenPlaylistTracks(sourceId, playlist.ratingKey)
                notify(Strings.removedFromPlaylist(track.title, playlist.title), actionLabel = Strings.undo) {
                    undoRemove(sourceId, playlist, track, index)
                }
                // The first track supplies the automatic artwork.
                if (index == 0) state.loadPlaylists()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                restoreTrack(playlist.ratingKey, track, index)
                notify(playlistErrorMessage(error), isError = true)
            }
        }
    }

    private fun undoRemove(sourceId: String, playlist: PlexPlaylist, track: PlexTrackStream, index: Int) {
        state.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    client(sourceId).insertPlaylistTrack(playlist.ratingKey, track.ratingKey, index)
                }
                restoreTrack(playlist.ratingKey, track, index)
                applyItemResult(sourceId, playlist.ratingKey, result)
                notify(Strings.restoredToPlaylist(track.title))
                refreshPlaylistTracks(sourceId, playlist)
                if (index == 0) state.loadPlaylists()
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                notify(playlistErrorMessage(error), isError = true)
            }
        }
    }

    private fun restoreTrack(playlistKey: String, track: PlexTrackStream, index: Int) {
        val current = state.playlistTrackResult ?: return
        if (current.playlist.ratingKey != playlistKey) return
        if (current.tracks.any { it.ratingKey == track.ratingKey }) return
        val tracks = current.tracks.toMutableList().apply { add(index.coerceIn(0, size), track) }
        state.playlistTrackResult = current.copy(playlist = current.playlist.copy(leafCount = tracks.size), tracks = tracks)
    }

    /** Folds an item result (count, revision) into the list row and the open detail. */
    private fun applyItemResult(sourceId: String, playlistKey: String, result: Music032PlaylistItemResult) {
        if (state.connectionPreferences.id == sourceId) {
            state.playlists = state.playlists.map {
                if (it.ratingKey == playlistKey) it.copy(leafCount = result.total, revision = result.revision ?: it.revision) else it
            }
        }
        state.playlistTrackResult?.takeIf { it.playlist.ratingKey == playlistKey }?.let { open ->
            state.playlistTrackResult = open.copy(playlist = open.playlist.copy(revision = result.revision ?: open.playlist.revision))
        }
        if (state.selectedPlaylist?.ratingKey == playlistKey) {
            state.selectedPlaylist = state.selectedPlaylist?.let { it.copy(leafCount = result.total, revision = result.revision ?: it.revision) }
        }
    }

    /** Pulls the server's track list so the cache and an open detail match the server. */
    private suspend fun refreshPlaylistTracks(sourceId: String, playlist: PlexPlaylist) {
        val fresh = runCatching {
            withContext(Dispatchers.IO) { client(sourceId).fetchPlaylistTracks(playlist) }
        }.getOrElse { if (it is CancellationException) throw it; return }
        withContext(Dispatchers.IO) { state.albumLocalStore.savePlaylistTracks(sourceId, playlist.ratingKey, fresh.tracks) }
        if (state.playlistTrackResult?.playlist?.ratingKey == playlist.ratingKey && state.connectionPreferences.id == sourceId) {
            state.playlistTrackResult = fresh
        }
        replacePlaylistEverywhere(sourceId, fresh.playlist.copy(leafCount = fresh.tracks.size))
    }

    private fun replacePlaylistEverywhere(sourceId: String, updated: PlexPlaylist) {
        if (state.connectionPreferences.id != sourceId) return
        state.playlists = state.playlists.map { if (it.ratingKey == updated.ratingKey) updated else it }
        if (state.selectedPlaylist?.ratingKey == updated.ratingKey) state.selectedPlaylist = updated
        state.playlistTrackResult?.takeIf { it.playlist.ratingKey == updated.ratingKey }?.let { open ->
            state.playlistTrackResult = open.copy(playlist = updated.copy(leafCount = open.tracks.size))
        }
    }

    private suspend fun onPlaylistGone(sourceId: String, playlist: PlexPlaylist) {
        if (state.connectionPreferences.id == sourceId) {
            state.playlists = state.playlists.filterNot { it.ratingKey == playlist.ratingKey }
            persistPlaylistList(sourceId)
        }
        val showingIt = state.activeSection == AppSection.PlaylistDetail &&
            state.playlistTrackResult?.playlist?.ratingKey == playlist.ratingKey
        if (state.selectedPlaylist?.ratingKey == playlist.ratingKey) state.selectedPlaylist = null
        if (showingIt) state.navigateBack()
    }

    private suspend fun persistPlaylistList(sourceId: String) {
        val snapshot = state.playlists
        withContext(Dispatchers.IO) { state.albumLocalStore.replaceAllPlaylists(sourceId, snapshot) }
    }

    private suspend fun persistOpenPlaylistTracks(sourceId: String, playlistKey: String) {
        val open = state.playlistTrackResult?.takeIf { it.playlist.ratingKey == playlistKey } ?: return
        withContext(Dispatchers.IO) { state.albumLocalStore.savePlaylistTracks(sourceId, playlistKey, open.tracks) }
    }
}
