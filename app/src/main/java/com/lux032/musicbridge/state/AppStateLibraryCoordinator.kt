package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032Client
import com.lux032.musicbridge.plex.ArtistRef
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexArtist
import com.lux032.musicbridge.plex.PlexClient
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.storage.ArtistRelease
import com.lux032.musicbridge.storage.DownloadedAlbumGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class AppStateLibraryCoordinator(
    private val state: AppState,
) {
    private var artistAlbumsJob: Job? = null
    private var offlineHydrationJob: Job? = null
    private var playlistDetailJob: Job? = null
    private var playlistDetailGeneration = 0L
    private var playlistSyncJob: Job? = null
    private var playlistSyncGeneration = 0L

    suspend fun loadAlbumsFromLocalStore() {
        val sourceId = state.connectionPreferences.id
        val albums = withContext(Dispatchers.IO) {
            state.albumLocalStore.getAllAlbums(sourceId)
        }
        val favorites = withContext(Dispatchers.IO) {
            state.albumLocalStore.getFavoriteTracks(sourceId)
        }
        if (state.connectionPreferences.id != sourceId) return
        val playlists = withContext(Dispatchers.IO) {
            state.albumLocalStore.getAllPlaylists(sourceId)
        }
        state.allAlbums = albums
        state.favoriteTracks = favorites
        state.playlists = playlists.distinctBy(PlexPlaylist::ratingKey)
        state.hasLoadedLocalAlbums = true
    }

    suspend fun searchAlbums(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            state.albumSearchResults = emptyList()
            state.isAlbumSearchLoading = false
            return
        }

        state.isAlbumSearchLoading = true
        val sourceId = state.connectionPreferences.id
        val results = withContext(Dispatchers.IO) {
            state.albumLocalStore.searchAlbums(sourceId, normalizedQuery)
        }
        if (state.connectionPreferences.id != sourceId) return
        state.albumSearchResults = results
        state.isAlbumSearchLoading = false
    }

    suspend fun refreshAlbums(showLoading: Boolean) {
        val profile = state.connectionPreferences
        val sourceId = profile.id
        if (!profile.hasCredentials()) {
            state.selectedAlbum = null
            state.trackResult = null
            return
        }

        if (showLoading) {
            state.isLoading = true
        }
        state.errorMessage = null
        state.actionMessage = null
        try {
            val client = state.client(profile)
            val fetched = client.fetchAlbums()
            withContext(Dispatchers.IO) {
                state.albumLocalStore.replaceAllAlbums(sourceId, fetched.albums)
            }
            if (state.connectionPreferences.id != sourceId) return
            state.allAlbums = fetched.albums
            if (profile.type == MusicServerType.Music032) {
                // Artists are part of the 032 sync model: one cursor sync, persisted locally.
                runCatching { client.fetchArtists().artists }
                    .onSuccess { artists ->
                        withContext(Dispatchers.IO) { state.albumLocalStore.replaceAllArtists(sourceId, artists) }
                    }
                    .onFailure { if (it is CancellationException) throw it }
                if (state.connectionPreferences.id != sourceId) return
                state.artistAlbumsRevision++
            }
            loadArtists()
            state.lastAlbumSyncEpochMillis = System.currentTimeMillis()
            state.appPreferences.saveLastAlbumSyncEpochMillis(sourceId, state.lastAlbumSyncEpochMillis!!)
            if (state.selectedAlbum?.let { selected ->
                    fetched.albums.none { album -> sameAlbum(album, selected) }
                } == true
            ) {
                state.selectedAlbum = null
                state.trackResult = null
            }
            if (showLoading) state.isLoading = false
            state.actionMessage = "已同步 ${fetched.albums.size} 张专辑，正在批量同步曲目..."
            runCatching {
                val allTracks = client.fetchAllAlbumTracks(fetched.albums) { current, total ->
                    if (state.connectionPreferences.id == sourceId) {
                        state.actionMessage = "正在批量同步曲目 $current/$total"
                    }
                }
                if (allTracks.isEmpty() && fetched.albums.isNotEmpty()) {
                    error("曲目同步未返回数据，已保留本地曲目缓存。")
                }
                withContext(Dispatchers.IO) {
                    state.albumLocalStore.replaceAllAlbumTracks(sourceId, allTracks)
                }
                allTracks
            }.onSuccess { allTracks ->
                if (state.connectionPreferences.id != sourceId) return
                // Track artist links feed artist discographies (参与作品).
                state.artistAlbumsRevision++
                val totalTrackCount = allTracks.values.sumOf { tracks -> tracks.size }
                state.actionMessage = "已同步 ${fetched.albums.size} 张专辑、${totalTrackCount} 首曲目"
            }.onFailure { error ->
                if (error is CancellationException) throw error
                if (state.connectionPreferences.id == sourceId) {
                    state.actionMessage = "已同步 ${fetched.albums.size} 张专辑；曲目同步失败，已保留本地缓存"
                    state.errorMessage = error.message ?: "曲目同步失败"
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            state.actionMessage = null
            state.errorMessage = error.message ?: "未知错误"
        } finally {
            if (showLoading) {
                state.isLoading = false
            }
        }
    }

    fun openAlbumDetail(requestedAlbum: PlexAlbum) {
        state.errorMessage = null
        state.actionMessage = null
        // 播放页等入口传入的是由曲目拼出来的展示用专辑（artistName 取的是曲目艺术家），
        // 合辑里单曲艺术家与专辑艺术家不同，直接使用会把专辑页的艺术家显示错。
        // 优先使用曲库中同 sourceId + ratingKey 的真实专辑元数据。
        val album = state.allAlbums.firstOrNull { candidate ->
            candidate.sourceId == requestedAlbum.sourceId && candidate.ratingKey == requestedAlbum.ratingKey
        } ?: requestedAlbum
        state.selectedAlbum = album
        state.scope.launch {
            val cachedTracks = withContext(Dispatchers.IO) {
                state.albumLocalStore.getAlbumTracks(album.sourceId, album.ratingKey)
            }
            if (cachedTracks.isNotEmpty()) {
                state.trackResult = PlexAlbumTracksResult("", album, cachedTracks)
                state.navigateTo(AppSection.AlbumDetail)
            } else {
                state.isLoading = true
                runCatching {
                    state.clientFor(album.sourceId).fetchAlbumTrackStreams(album)
                }.onSuccess {
                    state.trackResult = it
                    state.navigateTo(AppSection.AlbumDetail)
                }.onFailure {
                    state.errorMessage = it.message ?: "未知错误"
                }
                state.isLoading = false
            }
        }
    }

    private val isMusic032Active: Boolean
        get() = state.connectionPreferences.type == MusicServerType.Music032

    fun openArtistAlbums(artist: ArtistSummary) {
        val key = artist.ratingKey?.takeIf { isMusic032Active }
        showArtistPage(artist.name, key)
        if (key != null) ensureArtistLoaded(key)
    }

    fun openArtistRef(ref: ArtistRef) {
        state.artistChoices = emptyList()
        if (!isMusic032Active) {
            openArtistAlbumsByName(ref.name)
            return
        }
        showArtistPage(ref.name, ref.id)
        ensureArtistLoaded(ref.id)
    }

    fun openArtistLink(artistName: String?, refs: List<ArtistRef>) {
        state.artistChoices = emptyList()
        val distinct = refs.distinctBy(ArtistRef::id)
        when {
            distinct.size == 1 -> openArtistRef(distinct.single())
            distinct.size > 1 && isMusic032Active -> state.artistChoices = distinct
            else -> openArtistAlbumsByName(artistName)
        }
    }

    /**
     * Text-only artist link. 032 resolves the name to an artist id: local sync model first,
     * then joined credit text ("A, B"), finally the server's artist search. Plex keeps the
     * name-based page.
     */
    fun openArtistAlbumsByName(artistName: String?) {
        state.artistChoices = emptyList()
        val normalizedName = artistName?.trim().orEmpty().ifBlank { "未知艺人" }
        if (!isMusic032Active) {
            showArtistPage(normalizedName, key = null)
            return
        }
        val local = state.artists.filter { it.ratingKey != null && it.name.trim().equals(normalizedName, ignoreCase = true) }
        if (local.isNotEmpty()) {
            openArtistLink(normalizedName, local.map { ArtistRef(it.ratingKey!!, it.name) })
            return
        }
        val parts = splitArtistCredit(normalizedName)
        if (parts.size > 1) {
            val refs = parts.mapNotNull { part ->
                state.artists.firstOrNull { it.ratingKey != null && it.name.trim().equals(part, ignoreCase = true) }
                    ?.let { ArtistRef(it.ratingKey!!, it.name) }
            }
            if (refs.size == parts.size) {
                openArtistLink(normalizedName, refs)
                return
            }
        }
        showArtistPage(normalizedName, key = null)
        resolveArtistByName(normalizedName)
    }

    private fun showArtistPage(name: String, key: String?) {
        val changed = state.selectedArtistName != name || state.selectedArtistKey != key
        state.selectedArtistName = name
        state.selectedArtistKey = key
        if (changed) {
            state.resetLazyScrollPosition(AppSection.ArtistAlbums)
            artistResolveJob?.cancel()
            state.isResolvingArtist = false
            if (state.remoteArtist?.ratingKey != key) state.remoteArtist = null
        }
        if (state.activeSection != AppSection.ArtistAlbums) state.navigateTo(AppSection.ArtistAlbums)
    }

    private var artistResolveJob: Job? = null
    private var artistResolveGeneration = 0L

    /** Loads the server artist page when the local sync model has nothing for [artistId]. */
    private fun ensureArtistLoaded(artistId: String) {
        val sourceId = state.connectionPreferences.id
        val client = runCatching { state.clientFor(sourceId) as? Music032Client }.getOrNull() ?: return
        artistResolveJob?.cancel()
        val generation = ++artistResolveGeneration
        artistResolveJob = state.scope.launch {
            val local = withContext(Dispatchers.IO) { localArtistSummary(sourceId, artistId, state.artists) }
            if (local != null && local.albums.isNotEmpty()) return@launch
            state.isResolvingArtist = local == null
            try {
                val detail = client.fetchArtistDetail(artistId)
                if (generation != artistResolveGeneration || state.selectedArtistKey != artistId) return@launch
                val canonical = detail.artist
                state.remoteArtist = ArtistSummary(
                    name = canonical.title,
                    coverUrl = canonical.thumbUrl,
                    albumCount = detail.albums.size,
                    albums = detail.albums,
                    ratingKey = canonical.ratingKey,
                    sourceId = sourceId,
                    userRating = canonical.userRating,
                    trackCount = canonical.trackCount,
                    appearanceAlbumKeys = detail.appearanceAlbumKeys,
                )
                // A merged (stale) id resolves to the canonical artist; follow it.
                state.selectedArtistName = canonical.title
                state.selectedArtistKey = canonical.ratingKey
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Offline or removed: the page shows local data or the not-found card.
            } finally {
                if (generation == artistResolveGeneration) state.isResolvingArtist = false
            }
        }
    }

    private fun resolveArtistByName(name: String) {
        val sourceId = state.connectionPreferences.id
        val client = runCatching { state.clientFor(sourceId) as? Music032Client }.getOrNull() ?: return
        artistResolveJob?.cancel()
        val generation = ++artistResolveGeneration
        state.isResolvingArtist = true
        artistResolveJob = state.scope.launch {
            var resolved: List<ArtistRef> = emptyList()
            try {
                val candidates = client.searchArtists(name)
                resolved = candidates
                    .filter { it.title.trim().equals(name, ignoreCase = true) }
                    .map { ArtistRef(it.ratingKey, it.title) }
                if (resolved.isEmpty()) {
                    val parts = splitArtistCredit(name)
                    if (parts.size > 1) {
                        resolved = parts.mapNotNull { part ->
                            client.searchArtists(part)
                                .firstOrNull { it.title.trim().equals(part, ignoreCase = true) }
                                ?.let { ArtistRef(it.ratingKey, it.title) }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                // Fall through to the not-found card.
            } finally {
                if (generation == artistResolveGeneration) state.isResolvingArtist = false
            }
            if (generation != artistResolveGeneration || state.selectedArtistName != name || state.selectedArtistKey != null) {
                return@launch
            }
            if (resolved.isNotEmpty()) openArtistLink(name, resolved)
        }
    }

    /** Artist page data from the local 032 sync model (artist row + discography links). */
    internal fun localArtistSummary(sourceId: String, artistId: String, artists: List<ArtistSummary>): ArtistSummary? {
        val known = artists.firstOrNull { it.ratingKey == artistId && (it.sourceId.isBlank() || it.sourceId == sourceId) }
            ?: state.albumLocalStore.getArtist(sourceId, artistId)?.toArtistSummary()
            ?: return null
        val releases = state.albumLocalStore.getArtistDiscography(sourceId, artistId)
        return known.copy(
            albums = releases.map(ArtistRelease::album),
            albumCount = releases.size.takeIf { it > 0 } ?: known.albumCount,
            appearanceAlbumKeys = releases.filter(ArtistRelease::isAppearance).map { it.album.ratingKey }.toSet(),
        )
    }

    private fun splitArtistCredit(text: String): List<String> =
        text.split(", ", "、", " / ", "; ").map(String::trim).filter(String::isNotEmpty).distinct()

    fun shuffleArtist(artist: ArtistSummary) {
        if (state.isPlaybackCommandLoading || artist.albums.isEmpty()) return

        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.actionMessage = "正在整理 ${artist.name} 的歌曲..."
        state.scope.launch {
            runCatching {
                val artistKey = artist.ratingKey
                val performedTracks = if (isMusic032Active && artistKey != null) {
                    // 032: only what the artist performs — not every track of a compilation
                    // they merely appear on.
                    withContext(Dispatchers.IO) {
                        state.albumLocalStore.getArtistTracks(artist.sourceId, artistKey)
                    }.ifEmpty {
                        runCatching {
                            state.clientFor(artist.sourceId).fetchArtistTracks(
                                PlexArtist(ratingKey = artistKey, title = artist.name, thumbUrl = artist.coverUrl, sourceId = artist.sourceId)
                            )
                        }.getOrDefault(emptyList())
                    }
                } else {
                    emptyList()
                }
                val tracks = performedTracks.ifEmpty { artist.albums.flatMap { album ->
                    val cachedTracks = withContext(Dispatchers.IO) {
                        state.albumLocalStore.getAlbumTracks(album.sourceId, album.ratingKey)
                    }
                    cachedTracks.ifEmpty {
                        runCatching {
                            state.clientFor(album.sourceId).fetchAlbumTrackStreams(album).tracks
                        }.getOrDefault(emptyList())
                    }
                } }
                    .distinctBy(PlexTrackStream::ratingKey)
                    .shuffled()

                check(tracks.isNotEmpty()) { "未找到 ${artist.name} 的可播放歌曲" }
                PlexPlaylistTracksResult(
                    serverName = "",
                    playlist = PlexPlaylist(
                        ratingKey = "artist:${artist.name}",
                        title = artist.name,
                        thumbUrl = artist.coverUrl,
                        leafCount = tracks.size,
                        sourceId = tracks.firstOrNull()?.sourceId ?: state.connectionPreferences.id,
                    ),
                    tracks = tracks,
                )
            }.onSuccess { result ->
                state.startPlaylistPlayback(result, shuffle = false)
                state.actionMessage = "正在随机播放 ${artist.name} 的 ${result.tracks.size} 首歌曲"
            }.onFailure {
                state.errorMessage = it.message ?: "加载歌手歌曲失败"
                state.isPlaybackCommandLoading = false
            }
        }
    }

    fun loadPlaylists() {
        val profile = state.connectionPreferences
        val sourceId = profile.id
        state.scope.launch {
            val cached = withContext(Dispatchers.IO) { state.albumLocalStore.getAllPlaylists(sourceId) }
                .distinctBy(PlexPlaylist::ratingKey)
            if (state.connectionPreferences.id != sourceId) return@launch
            if (cached.isNotEmpty()) state.playlists = cached
            if (!profile.hasCredentials()) {
                if (cached.isEmpty()) state.playlists = emptyList()
                return@launch
            }
            runCatching { withContext(Dispatchers.IO) { state.client(profile).fetchPlaylists() } }
                .onSuccess { result ->
                    val playlists = result.playlists.filter { it.ratingKey.isNotBlank() }.distinctBy(PlexPlaylist::ratingKey)
                    withContext(Dispatchers.IO) { state.albumLocalStore.replaceAllPlaylists(sourceId, playlists) }
                    if (state.connectionPreferences.id == sourceId) state.playlists = playlists
                }
                .onFailure {
                    if (cached.isEmpty() && state.connectionPreferences.id == sourceId) {
                        state.errorMessage = it.message ?: "加载歌单失败"
                    }
                }
        }
    }

    fun syncAllPlaylistsTracks(onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        val profile = state.connectionPreferences
        if (!profile.hasCredentials() || state.playlists.isEmpty()) return
        playlistSyncJob?.cancel()
        val syncGeneration = ++playlistSyncGeneration
        playlistSyncJob = state.scope.launch {
            val playlists = state.playlists.filter { it.ratingKey.isNotBlank() }
            state.isPlaylistSyncing = true
            state.playlistSyncProgress = 0 to playlists.size
            state.errorMessage = null
            var failures = 0
            try {
                playlists.forEachIndexed { index, playlist ->
                    coroutineContext.ensureActive()
                    val sourceId = playlist.sourceId.ifBlank { profile.id }
                    try {
                        val result = withContext(Dispatchers.IO) {
                            state.clientFor(sourceId).fetchPlaylistTracks(playlist)
                        }
                        coroutineContext.ensureActive()
                        withContext(Dispatchers.IO) {
                            state.albumLocalStore.savePlaylistTracks(sourceId, playlist.ratingKey, result.tracks)
                        }
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        failures++
                    }
                    val completed = index + 1
                    state.playlistSyncProgress = completed to playlists.size
                    onProgress(completed, playlists.size)
                }
                state.actionMessage = if (failures == 0) "已同步 ${playlists.size} 个歌单" else "歌单同步完成，${failures} 个失败"
            } finally {
                if (playlistSyncGeneration == syncGeneration) state.isPlaylistSyncing = false
            }
        }
    }

    fun loadFavoriteTracks() {
        val profile = state.connectionPreferences
        state.scope.launch {
            runCatching {
                loadFavoriteTracksFromCacheOrRemote(profile)
            }.onSuccess { tracks ->
                if (state.connectionPreferences.id == profile.id) state.favoriteTracks = tracks
            }.onFailure {
                if (state.connectionPreferences.id == profile.id) {
                    state.errorMessage = it.message ?: "加载收藏单曲失败"
                }
            }
        }
    }

    fun openDownloadedTracks() {
        state.navigateTo(AppSection.Downloads)
    }

    fun downloadAlbum(album: PlexAlbum, tracks: List<PlexTrackStream>) {
        val unDownloaded = tracks.filterNot {
            state.offlineDownloadManager.isTrackDownloaded(it.sourceId, it.ratingKey)
        }
        if (unDownloaded.isEmpty()) {
            state.actionMessage = "整张专辑已在本地"
            return
        }
        unDownloaded.forEach { state.offlineDownloadManager.enqueueDownload(it) }
        state.actionMessage = Strings.albumDownloadQueued(unDownloaded.size)
    }

    fun deleteDownloadedAlbum(group: DownloadedAlbumGroup) {
        state.offlineDownloadManager.deleteAlbumDownload(group.sourceId, group.albumRatingKey)
        state.actionMessage = "已删除专辑离线原档: ${group.albumTitle}"
    }

    fun deleteDownloadedAlbum(album: PlexAlbum) {
        state.offlineDownloadManager.deleteAlbumDownload(album.sourceId, album.ratingKey)
        state.actionMessage = "已删除专辑离线原档: ${album.title}"
    }

    /**
     * Plays an offline album while honouring the active output target.
     *
     * Tapping an offline album is a content choice, not an output switch, so the Sonos
     * session must survive it: on Sonos the offline entry only identifies which album to
     * push, and the authoritative server streams are re-hydrated for the speaker.
     */
    fun playDownloadedAlbum(group: DownloadedAlbumGroup, startIndex: Int = 0) {
        if (group.tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, group.tracks.lastIndex)
        when (offlinePlaybackRoute(state.selectedPlaybackTarget)) {
            OfflinePlaybackRoute.LocalOriginal -> {
                // startLocalPlayback owns the target switch; never hard-set it here.
                state.localPlaybackCoordinator.startLocalPlayback(
                    album = downloadedGroupAlbum(group),
                    tracks = group.tracks.map { it.toTrackStream() },
                    startIndex = safeIndex,
                    initialPositionMillis = 0L,
                )
            }
            OfflinePlaybackRoute.SonosHydrated -> playDownloadedAlbumOnSonos(group, safeIndex)
        }
    }

    private fun playDownloadedAlbumOnSonos(group: DownloadedAlbumGroup, startIndex: Int) {
        val requestedRatingKey = group.tracks.getOrNull(startIndex)?.ratingKey
        launchOfflineSonosHydration(
            preparingMessage = "正在为 Sonos 准备《${group.albumTitle}》...",
            hydrate = { hydrateDownloadedGroupForSonos(group) },
            dispatch = { hydrated ->
                state.startAlbumPlayback(
                    albumTrackResult = hydrated,
                    startIndex = resolveHydratedStartIndex(hydrated.tracks, requestedRatingKey),
                    initialPositionMillis = 0L,
                )
            },
        )
    }

    /** Cancels an in-flight offline hydration so its result cannot reach playback. */
    fun cancelOfflineHydration() {
        offlineHydrationJob?.cancel()
        offlineHydrationJob = null
    }

    /**
     * Runs an offline hydration for Sonos and dispatches the result only while the session
     * it was started for is still the live one.
     *
     * Hydration is a network round trip: the user can switch output or start other
     * playback before it returns. Both the captured target and the playback generation are
     * re-checked so a late success cannot overwrite the queue that replaced it, and a late
     * failure cannot raise a Sonos error over an output the user already left.
     */
    private fun <T> launchOfflineSonosHydration(
        preparingMessage: String,
        hydrate: suspend () -> T,
        dispatch: (T) -> Unit,
    ) {
        if (state.isPlaybackCommandLoading) return
        val requestedTarget = state.selectedPlaybackTarget
        val requestedGeneration = state.currentPlaybackRequestGeneration
        offlineHydrationJob?.cancel()
        state.isPlaybackCommandLoading = true
        state.errorMessage = null
        state.actionMessage = preparingMessage
        offlineHydrationJob = state.scope.launch {
            var didHandOffToPlayback = false
            try {
                val hydrated = runCatching { hydrate() }
                if (!isActive) return@launch
                if (!isOfflineHydrationStillCurrent(
                        requestedTarget = requestedTarget,
                        currentTarget = state.selectedPlaybackTarget,
                        requestedGeneration = requestedGeneration,
                        currentGeneration = state.currentPlaybackRequestGeneration,
                    )
                ) {
                    return@launch
                }
                hydrated
                    .onSuccess { result ->
                        // The playback pipeline owns the loading flag from here on.
                        didHandOffToPlayback = true
                        state.isPlaybackCommandLoading = false
                        dispatch(result)
                    }
                    .onFailure { error ->
                        if (error is CancellationException) return@onFailure
                        // The user never asked to change output: stay on Sonos and surface
                        // the failure instead of dropping back to the phone speaker.
                        state.actionMessage = null
                        state.errorMessage = Strings.sonosOfflineHydrationFailed(error.message)
                    }
            } finally {
                // Release the flag on every exit path, cancellation included. A latched
                // "true" would silently swallow every later offline tap. Sessions that are
                // no longer current are skipped: whoever superseded us owns the flag.
                val stillOwnsLoadingFlag = !didHandOffToPlayback && isOfflineHydrationStillCurrent(
                    requestedTarget = requestedTarget,
                    currentTarget = state.selectedPlaybackTarget,
                    requestedGeneration = requestedGeneration,
                    currentGeneration = state.currentPlaybackRequestGeneration,
                )
                if (stillOwnsLoadingFlag) state.isPlaybackCommandLoading = false
            }
        }
    }

    /**
     * Resolves server-side streams for a downloaded album. Sonos cannot read the phone's
     * private `file://` originals, and high-resolution originals still need the server's
     * transcode path, so the speaker always plays the authoritative Plex streams.
     */
    private suspend fun hydrateDownloadedGroupForSonos(group: DownloadedAlbumGroup): PlexAlbumTracksResult {
        val client = state.clientFor(group.sourceId)
        val albumRequest = downloadedGroupAlbum(group)
        val remote = if (group.albumRatingKey.isNotBlank()) {
            runCatching { client.fetchAlbumTrackStreams(albumRequest) }.getOrNull()
        } else {
            null
        }
        val remoteByRatingKey = remote?.tracks.orEmpty().associateBy(PlexTrackStream::ratingKey)
        val hydrated = group.tracks.mapNotNull { downloaded ->
            remoteByRatingKey[downloaded.ratingKey]
                ?: client.fetchTrackStream(downloaded.ratingKey)
        }
        check(hydrated.isNotEmpty()) { "服务器未返回可推送的曲目" }
        return PlexAlbumTracksResult(
            serverName = remote?.serverName.orEmpty(),
            album = remote?.album ?: albumRequest,
            tracks = hydrated,
        )
    }

    private fun downloadedGroupAlbum(group: DownloadedAlbumGroup): PlexAlbum = PlexAlbum(
        ratingKey = group.albumRatingKey.ifBlank { "downloaded:${group.albumTitle}" },
        title = group.albumTitle,
        artistName = group.artistName,
        year = null,
        thumbUrl = group.thumbPath?.let { "file://$it" },
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = PlexSection("", "", ""),
        sourceId = group.sourceId,
    )

    fun shuffleAllDownloadedTracks() {
        val groups = state.offlineDownloadManager.downloadedAlbums.value
        if (groups.all { it.tracks.isEmpty() }) return
        when (offlinePlaybackRoute(state.selectedPlaybackTarget)) {
            OfflinePlaybackRoute.LocalOriginal -> {
                val allDownloaded = groups
                    .flatMap { it.tracks }
                    .map { it.toTrackStream() }
                    .shuffled()
                if (allDownloaded.isEmpty()) return
                // startLocalPlayback owns the target switch; never hard-set it here.
                state.localPlaybackCoordinator.startLocalPlayback(
                    album = allDownloadedAlbum(allDownloaded.firstNotNullOfOrNull { it.thumbUrl }, allDownloaded.firstOrNull()?.sourceId),
                    tracks = allDownloaded,
                    startIndex = 0,
                    initialPositionMillis = 0L,
                )
            }
            OfflinePlaybackRoute.SonosHydrated -> shuffleAllDownloadedTracksOnSonos(groups)
        }
    }

    private fun shuffleAllDownloadedTracksOnSonos(groups: List<DownloadedAlbumGroup>) {
        launchOfflineSonosHydration(
            preparingMessage = "正在为 Sonos 准备离线曲目...",
            hydrate = {
                val hydrated = groups
                    .filter { it.tracks.isNotEmpty() }
                    .flatMap { group ->
                        runCatching { hydrateDownloadedGroupForSonos(group).tracks }.getOrDefault(emptyList())
                    }
                    .distinctBy(PlexTrackStream::ratingKey)
                    .shuffled()
                check(hydrated.isNotEmpty()) { "服务器未返回可推送的曲目" }
                PlexPlaylistTracksResult(
                    serverName = "",
                    playlist = PlexPlaylist(
                        ratingKey = "downloaded:all",
                        title = Strings.allDownloadedTracks,
                        thumbUrl = hydrated.firstNotNullOfOrNull { it.thumbUrl },
                        leafCount = hydrated.size,
                        sourceId = hydrated.firstOrNull()?.sourceId.orEmpty(),
                    ),
                    tracks = hydrated,
                )
            },
            dispatch = { result -> state.startPlaylistPlayback(result, shuffle = false) },
        )
    }

    private fun allDownloadedAlbum(thumbUrl: String?, sourceId: String?): PlexAlbum = PlexAlbum(
        ratingKey = "downloaded:all",
        title = Strings.allDownloadedTracks,
        artistName = Strings.offlineMusic,
        year = null,
        thumbUrl = thumbUrl,
        userRating = null,
        addedAtEpochSeconds = null,
        lastViewedAtEpochSeconds = null,
        section = PlexSection("", "", ""),
        sourceId = sourceId.orEmpty(),
    )


    fun openFavoriteTracks() {
        val profile = state.connectionPreferences
        state.isLoading = true
        state.errorMessage = null
        state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksFromCacheOrRemote(profile)
                val fakePlaylist = PlexPlaylist(
                    ratingKey = "favorites",
                    title = Strings.myFavoriteTracks,
                    thumbUrl = null,
                    leafCount = tracks.size,
                    sourceId = profile.id,
                )
                PlexPlaylistTracksResult("", fakePlaylist, tracks)
            }.onSuccess { result ->
                if (state.connectionPreferences.id == profile.id) {
                    state.favoriteTracks = result.tracks
                    state.playlistTrackResult = result
                    state.navigateTo(AppSection.PlaylistDetail)
                }
            }.onFailure {
                if (state.connectionPreferences.id == profile.id) {
                    state.errorMessage = it.message ?: "加载收藏单曲失败"
                }
            }
            state.isLoading = false
        }
    }

    fun openPlaylistDetail(playlist: PlexPlaylist) {
        playlistDetailJob?.cancel()
        val detailGeneration = ++playlistDetailGeneration
        state.aiPlaylistDescription = null
        state.selectedPlaylist = playlist
        state.errorMessage = null
        val sourceId = playlist.sourceId.ifBlank { state.connectionPreferences.id }
        val playlistKey = playlist.ratingKey
        playlistDetailJob = state.scope.launch {
            var hasCachedTracks = false
            try {
                val cachedTracks = withContext(Dispatchers.IO) {
                    state.albumLocalStore.getPlaylistTracks(sourceId, playlistKey)
                }
                hasCachedTracks = cachedTracks.isNotEmpty()
                if (!isPlaylistDetailCurrent(sourceId, playlistKey, detailGeneration)) return@launch
                if (hasCachedTracks) {
                    state.playlistTrackResult = PlexPlaylistTracksResult("", playlist, cachedTracks)
                    state.navigateTo(AppSection.PlaylistDetail)
                } else {
                    state.playlistTrackResult = null
                    state.isLoading = true
                }

                val result = withContext(Dispatchers.IO) {
                    state.clientFor(sourceId).fetchPlaylistTracks(playlist)
                }
                if (!isPlaylistDetailCurrent(sourceId, playlistKey, detailGeneration)) return@launch
                withContext(Dispatchers.IO) {
                    state.albumLocalStore.savePlaylistTracks(sourceId, playlistKey, result.tracks)
                }
                if (!isPlaylistDetailCurrent(sourceId, playlistKey, detailGeneration)) return@launch
                state.playlistTrackResult = result
                if (!hasCachedTracks) state.navigateTo(AppSection.PlaylistDetail)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                if (!hasCachedTracks && isPlaylistDetailCurrent(sourceId, playlistKey, detailGeneration)) {
                    state.errorMessage = error.message ?: "加载歌单详情失败"
                }
            } finally {
                if (isPlaylistDetailCurrent(sourceId, playlistKey, detailGeneration)) state.isLoading = false
            }
        }
    }

    private fun isPlaylistDetailCurrent(sourceId: String, playlistKey: String, generation: Long): Boolean =
        playlistDetailGeneration == generation && state.connectionPreferences.id == sourceId &&
            state.selectedPlaylist?.ratingKey == playlistKey

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) {
        state.allAlbums = state.allAlbums.map { album ->
            if (sameAlbum(album, updatedAlbum)) updatedAlbum else album
        }
        state.scope.launch(Dispatchers.IO) {
            state.albumLocalStore.upsertAlbum(updatedAlbum)
        }
        if (state.selectedAlbum?.let { sameAlbum(it, updatedAlbum) } == true) {
            state.selectedAlbum = updatedAlbum
        }
        state.trackResult = state.trackResult?.let { current ->
            if (sameAlbum(current.album, updatedAlbum)) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
        state.miniPlayerState = state.miniPlayerState?.let { current ->
            if (sameAlbum(current.album, updatedAlbum)) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
    }

    fun replaceTrackInState(updatedTrack: PlexTrackStream) {
        state.trackResult = state.trackResult?.let { current ->
            if (current.tracks.none { sameTrack(it, updatedTrack) }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (sameTrack(track, updatedTrack)) updatedTrack else track
                    }
                )
            }
        }
        state.playlistTrackResult = state.playlistTrackResult?.let { current ->
            if (current.tracks.none { sameTrack(it, updatedTrack) }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (sameTrack(track, updatedTrack)) updatedTrack else track
                    }
                )
            }
        }
        state.favoriteTracks = state.favoriteTracks.mapNotNull { track ->
            when {
                !sameTrack(track, updatedTrack) -> track
                updatedTrack.isFavorite -> updatedTrack
                else -> null
            }
        }
        state.scope.launch(Dispatchers.IO) {
            if (updatedTrack.isFavorite) {
                state.albumLocalStore.upsertFavoriteTrack(updatedTrack)
            } else {
                state.albumLocalStore.deleteFavoriteTrack(updatedTrack.sourceId, updatedTrack.ratingKey)
            }
            state.albumLocalStore.updateAlbumTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
            state.albumLocalStore.updatePlaylistTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
            state.albumLocalStore.updateDownloadedTrackFavoriteStatus(
                updatedTrack.sourceId,
                updatedTrack.ratingKey,
                updatedTrack.userRating,
            )
        }
        state.miniPlayerState = state.miniPlayerState?.let { current ->
            if (current.tracks.none { sameTrack(it, updatedTrack) }) {
                current
            } else {
                current.copy(
                    album = current.tracks
                        .getOrNull(current.currentIndex)
                        ?.takeIf { sameTrack(it, updatedTrack) }
                        ?.let { resolveAlbumForTrack(updatedTrack, current.album) }
                        ?: current.album,
                    tracks = current.tracks.map { track ->
                        if (sameTrack(track, updatedTrack)) updatedTrack else track
                    }
                )
            }
        }
    }

    fun toggleAlbumFavorite(album: PlexAlbum) {
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !album.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                state.clientFor(album.sourceId).setAlbumFavorite(album.ratingKey, targetFavorite)
            }.onSuccess {
                replaceAlbumInState(album.copy(userRating = if (targetFavorite) 10f else null))
                state.actionMessage = if (targetFavorite) {
                    "已收藏专辑：${album.title}"
                } else {
                    "已取消收藏专辑：${album.title}"
                }
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isFavoriteMutationLoading = false
        }
    }

    fun toggleArtistFavorite(artist: ArtistSummary) {
        val ratingKey = artist.ratingKey ?: return
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !artist.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null
        state.scope.launch {
            runCatching {
                state.clientFor(artist.sourceId).setArtistFavorite(ratingKey, targetFavorite)
            }.onSuccess {
                val newRating = if (targetFavorite) 10f else null
                state.artists = state.artists.map { current ->
                    if (current.ratingKey == ratingKey && current.sourceId == artist.sourceId) {
                        current.copy(userRating = newRating)
                    } else {
                        current
                    }
                }
                state.remoteArtist?.takeIf { it.ratingKey == ratingKey }?.let {
                    state.remoteArtist = it.copy(userRating = newRating)
                }
                if (isMusic032Active) {
                    withContext(Dispatchers.IO) {
                        state.albumLocalStore.updateArtistFavorite(artist.sourceId, ratingKey, newRating)
                    }
                }
                state.actionMessage = if (targetFavorite) {
                    "已收藏歌手：${artist.name}"
                } else {
                    "已取消收藏歌手：${artist.name}"
                }
            }.onFailure {
                state.errorMessage = it.message ?: "未知错误"
            }
            state.isFavoriteMutationLoading = false
        }
    }

    fun loadArtistTracks(artist: ArtistSummary) {
        val ratingKey = artist.ratingKey
        state.artistTracks = emptyList()
        if (ratingKey == null) {
            state.isArtistTracksLoading = false
            return
        }
        state.isArtistTracksLoading = true
        state.scope.launch {
            if (isMusic032Active) {
                // Synced performed tracks show immediately (and offline); the server list
                // below refreshes them with play statistics.
                val local = withContext(Dispatchers.IO) {
                    state.albumLocalStore.getArtistTracks(artist.sourceId, ratingKey)
                }
                if (local.isNotEmpty() && state.selectedArtistName == artist.name) {
                    state.artistTracks = local
                    state.isArtistTracksLoading = false
                }
            }
            runCatching {
                state.clientFor(artist.sourceId).fetchArtistTracks(
                    PlexArtist(
                        ratingKey = ratingKey,
                        title = artist.name,
                        thumbUrl = artist.coverUrl,
                        sourceId = artist.sourceId,
                    )
                )
            }.onSuccess { tracks ->
                if (state.selectedArtistName == artist.name) {
                    state.artistTracks = tracks
                }
            }
            if (state.selectedArtistName == artist.name) {
                state.isArtistTracksLoading = false
            }
        }
    }

    fun loadArtistAlbums(artist: ArtistSummary) {
        val ratingKey = artist.ratingKey?.trim().orEmpty()
        if (ratingKey.isEmpty()) return

        artistAlbumsJob?.cancel()
        artistAlbumsJob = state.scope.launch {
            runCatching {
                state.clientFor(artist.sourceId).fetchArtistAlbums(ratingKey)
            }.onSuccess { freshAlbums ->
                if (freshAlbums.isEmpty()) return@onSuccess
                withContext(Dispatchers.IO) {
                    state.albumLocalStore.updateAlbumsMetadata(artist.sourceId, freshAlbums)
                }
                if (state.selectedArtistName == artist.name) {
                    state.artistAlbumsRevision++
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
            }
        }
    }

    private fun Throwable.isNetworkOfflineError(): Boolean {
        var cause: Throwable? = this
        while (cause != null) {
            if (cause is java.net.UnknownHostException ||
                cause is java.net.ConnectException ||
                cause is java.net.SocketTimeoutException ||
                cause is java.net.SocketException
            ) return true
            cause = cause.cause
        }
        return false
    }

    fun toggleTrackFavorite(track: PlexTrackStream) {
        if (state.isFavoriteMutationLoading) return

        val targetFavorite = !track.isFavorite
        state.isFavoriteMutationLoading = true
        state.errorMessage = null
        state.actionMessage = null

        // Update local memory and local database immediately (optimistic update)
        val updatedTrack = track.copy(userRating = if (targetFavorite) 10f else null)
        replaceTrackInState(updatedTrack)

        state.scope.launch {
            runCatching {
                state.clientFor(track.sourceId).setTrackFavorite(track.ratingKey, targetFavorite)
            }.onSuccess {
                state.actionMessage = if (targetFavorite) {
                    "已收藏单曲：${track.title}"
                } else {
                    "已取消收藏单曲：${track.title}"
                }
            }.onFailure { error ->
                if (error.isNetworkOfflineError()) {
                    state.actionMessage = if (targetFavorite) {
                        "已在本地收藏单曲（离线）"
                    } else {
                        "已在本地取消收藏（离线）"
                    }
                } else {
                    state.errorMessage = error.message ?: "未知错误"
                }
            }
            state.isFavoriteMutationLoading = false
        }
    }

    private var artistLoadGeneration = 0L

    /**
     * Several callers (startup, server switch, library sync) may overlap; only the newest call
     * may publish, so an older snapshot can never overwrite artists from a newer sync.
     */
    suspend fun loadArtists() {
        val generation = ++artistLoadGeneration
        val profile = state.connectionPreferences
        fun isStale() = generation != artistLoadGeneration || state.connectionPreferences.id != profile.id
        if (profile.type == MusicServerType.Music032) {
            loadMusic032Artists(profile, ::isStale)
            return
        }
        val cachedArtists = withContext(Dispatchers.IO) {
            state.albumLocalStore.getArtistSummaries(profile.id)
        }
        if (isStale()) return
        if (cachedArtists.isNotEmpty()) {
            state.artists = cachedArtists.map { info ->
                ArtistSummary(
                    name = info.name,
                    coverUrl = info.coverUrl,
                    albumCount = info.albumCount,
                )
            }
        }

        if (!profile.hasCredentials()) return
        try {
            val plexArtists = state.client(profile).fetchArtists().artists
            if (isStale()) return
            // Re-read after the network call: a sync may have finished meanwhile.
            val freshArtists = withContext(Dispatchers.IO) {
                state.albumLocalStore.getArtistSummaries(profile.id)
            }
            if (isStale()) return
            val artistInfoMap = freshArtists.associateBy { it.name }
            state.artists = plexArtists.map { plexArtist ->
                val info = artistInfoMap[plexArtist.title]
                ArtistSummary(
                    name = plexArtist.title,
                    coverUrl = plexArtist.thumbUrl,
                    albumCount = info?.albumCount ?: 0,
                    ratingKey = plexArtist.ratingKey,
                    sourceId = plexArtist.sourceId,
                    userRating = plexArtist.userRating,
                )
            }.filter { it.albumCount > 0 }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Cached data is already visible.
        }
    }

    private suspend fun loadMusic032Artists(profile: MusicServerProfile, isStale: () -> Boolean) {
        val cached = withContext(Dispatchers.IO) { state.albumLocalStore.getArtists(profile.id) }
        if (isStale()) return
        if (cached.isNotEmpty()) {
            state.artists = cached.map { it.toArtistSummary() }
            return
        }
        // No artist sync yet (fresh install or upgraded cache): fetch the performer list once.
        if (!profile.hasCredentials()) return
        try {
            val remote = state.client(profile).fetchArtists().artists
            withContext(Dispatchers.IO) { state.albumLocalStore.replaceAllArtists(profile.id, remote) }
            if (isStale()) return
            state.artists = remote
                .sortedWith(compareByDescending<PlexArtist> { it.albumCount ?: 0 }.thenBy { it.title.lowercase() })
                .map { it.toArtistSummary() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            // Offline without a cached artist list: the page stays empty until a sync.
        }
    }

    private suspend fun loadFavoriteTracksFromCacheOrRemote(profile: MusicServerProfile): List<PlexTrackStream> {
        val cachedTracks = withContext(Dispatchers.IO) {
            state.albumLocalStore.getFavoriteTracks(profile.id)
        }
        if (cachedTracks.isNotEmpty()) {
            refreshFavoriteTracksInBackground(profile)
            return cachedTracks
        }

        check(profile.hasCredentials()) { "请先完善服务器连接信息。" }
        val remoteTracks = loadFavoriteTracksInternal(profile)
        withContext(Dispatchers.IO) {
            state.albumLocalStore.replaceAllFavoriteTracks(profile.id, remoteTracks)
        }
        return remoteTracks
    }

    private suspend fun loadFavoriteTracksInternal(profile: MusicServerProfile): List<PlexTrackStream> = withContext(Dispatchers.IO) {
        state.client(profile).fetchFavoriteTracks()
    }

    private fun refreshFavoriteTracksInBackground(profile: MusicServerProfile) {
        state.favoriteTrackSyncJob?.cancel()
        state.favoriteTrackSyncJob = state.scope.launch {
            runCatching {
                val tracks = loadFavoriteTracksInternal(profile)
                withContext(Dispatchers.IO) {
                    state.albumLocalStore.replaceAllFavoriteTracks(profile.id, tracks)
                }
                if (state.connectionPreferences.id == profile.id) state.favoriteTracks = tracks
            }
        }
    }

    private fun resolveAlbumForTrack(
        track: PlexTrackStream,
        fallbackAlbum: PlexAlbum? = null,
    ): PlexAlbum {
        val matchedAlbum = track.albumRatingKey?.let { ratingKey ->
            state.allAlbums.firstOrNull { album -> album.ratingKey == ratingKey && album.sourceId == track.sourceId }
        }
        if (matchedAlbum != null) {
            return matchedAlbum
        }

        val fallback = fallbackAlbum ?: state.selectedAlbum
        return PlexAlbum(
            ratingKey = track.albumRatingKey ?: fallback?.ratingKey ?: track.ratingKey,
            title = track.albumTitle ?: fallback?.title ?: track.title,
            artistName = track.artistName ?: fallback?.artistName,
            year = track.albumYearFrom(fallback),
            thumbUrl = track.thumbUrl ?: fallback?.thumbUrl,
            userRating = fallback?.userRating,
            addedAtEpochSeconds = fallback?.addedAtEpochSeconds,
            lastViewedAtEpochSeconds = fallback?.lastViewedAtEpochSeconds,
            section = fallback?.section ?: PlexSection("", "", ""),
            sourceId = track.sourceId.ifBlank { fallback?.sourceId.orEmpty() },
        )
    }

    private fun sameAlbum(first: PlexAlbum, second: PlexAlbum): Boolean =
        first.sourceId == second.sourceId && first.ratingKey == second.ratingKey

    private fun sameTrack(first: PlexTrackStream, second: PlexTrackStream): Boolean =
        first.sourceId == second.sourceId && first.ratingKey == second.ratingKey
}

internal fun PlexArtist.toArtistSummary(): ArtistSummary = ArtistSummary(
    name = title,
    coverUrl = thumbUrl,
    albumCount = albumCount ?: 0,
    ratingKey = ratingKey,
    sourceId = sourceId,
    userRating = userRating,
    trackCount = trackCount,
)
