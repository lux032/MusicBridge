package com.lux032.musicbridge

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexAuthConfig
import com.lux032.musicbridge.plex.PlexClient
import com.lux032.musicbridge.plex.PlexPlaylist
import com.lux032.musicbridge.plex.PlexPlaylistTracksResult
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.plex.isFavorite
import com.lux032.musicbridge.sonos.SonosController
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.storage.AlbumLocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

@Stable
class AppState(
    context: Context,
) {
    internal val context: Context = context.applicationContext
    internal val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    internal val appPreferences = AppPreferences(context)
    internal val albumLocalStore = AlbumLocalStore(context)
    internal val sonosController = SonosController()

    private val initialPreferences = appPreferences.loadPlexConnectionPreferences()
    private val libraryCoordinator = AppStateLibraryCoordinator(this)
    private val playbackCoordinator = AppStatePlaybackCoordinator(this)

    val navigationStack = mutableStateListOf(AppSection.Home)
    var primarySection by mutableStateOf(AppSection.Home)
    var connectionPreferences by mutableStateOf(initialPreferences)

    var isLoading by mutableStateOf(false)
    var isSonosLoading by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var actionMessage by mutableStateOf<String?>(null)
    var allAlbums by mutableStateOf<List<PlexAlbum>>(emptyList())
    var hasLoadedLocalAlbums by mutableStateOf(false)
    var lastAlbumSyncEpochMillis by mutableStateOf(appPreferences.loadLastAlbumSyncEpochMillis())
    var albumSearchQuery by mutableStateOf("")
    var albumSearchResults by mutableStateOf<List<PlexAlbum>>(emptyList())
    var isAlbumSearchLoading by mutableStateOf(false)
    var selectedAlbum by mutableStateOf<PlexAlbum?>(null)
    var selectedArtistName by mutableStateOf<String?>(null)
    var artistPresentation by mutableStateOf(ArtistPresentation.Covers)
    var trackResult by mutableStateOf<PlexAlbumTracksResult?>(null)
    var sonosRooms by mutableStateOf<List<SonosRoom>>(emptyList())
    var selectedSonosRoom by mutableStateOf<SonosRoom?>(null)
    var sonosDiscoveryAttempted by mutableStateOf(false)
    var sonosVolume by mutableFloatStateOf(0f)
    var hasLoadedSonosVolume by mutableStateOf(false)
    var isVolumeLoading by mutableStateOf(false)
    var isVolumeChanging by mutableStateOf(false)
    var isPlaybackCommandLoading by mutableStateOf(false)
    var isFavoriteMutationLoading by mutableStateOf(false)
    var volumeSyncKey by mutableIntStateOf(0)
    var volumeChangeJob by mutableStateOf<Job?>(null)
    var albumPlaybackJob by mutableStateOf<Job?>(null)
    var playbackReportingJob by mutableStateOf<Job?>(null)
    var favoriteTrackSyncJob by mutableStateOf<Job?>(null)
    var miniPlayerState by mutableStateOf<MiniPlayerState?>(null)
    var playbackMode by mutableStateOf(PlaybackMode.Sequential)
    var recentPlayedAlbumKeys by mutableStateOf(appPreferences.loadRecentPlayedAlbumKeys())
    var artists by mutableStateOf<List<ArtistSummary>>(emptyList())
    var playlists by mutableStateOf<List<PlexPlaylist>>(emptyList())
    var selectedPlaylist by mutableStateOf<PlexPlaylist?>(null)
    var playlistTrackResult by mutableStateOf<PlexPlaylistTracksResult?>(null)
    var favoriteTracks by mutableStateOf<List<PlexTrackStream>>(emptyList())

    val activeSection: AppSection
        get() = navigationStack.lastOrNull() ?: AppSection.Home

    data class SavedScrollPosition(val index: Int = 0, val offset: Int = 0)

    private val savedScrollValues = mutableMapOf<AppSection, Int>()
    private val savedLazyPositions = mutableMapOf<AppSection, SavedScrollPosition>()

    val favoriteAlbums: List<PlexAlbum>
        get() = allAlbums
            .filter(PlexAlbum::isFavorite)
            .sortedWith(
                compareBy<PlexAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

    val recentPlayedAlbums: List<PlexAlbum>
        get() = allAlbums
            .filter { album ->
                album.ratingKey in recentPlayedAlbumKeys || (album.lastViewedAtEpochSeconds ?: 0L) > 0L
            }
            .sortedWith(
                compareBy<PlexAlbum> { album ->
                    recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                    .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

    val recentAddedAlbums: List<PlexAlbum>
        get() = allAlbums
            .sortedWith(
                compareByDescending<PlexAlbum> { it.addedAtEpochSeconds ?: 0L }
                    .thenBy { it.title.lowercase() }
            )

    val selectedArtist: ArtistSummary?
        get() = selectedArtistName?.let { selectedName ->
            val albums = albumLocalStore.getAlbumsByArtist(selectedName)
            val summary = artists.firstOrNull { it.name == selectedName }
            summary?.let {
                ArtistSummary(
                    name = it.name,
                    coverUrl = it.coverUrl,
                    albumCount = it.albumCount,
                    albums = albums,
                )
            }
        }

    fun saveScrollValue(section: AppSection, value: Int) {
        savedScrollValues[section] = value
    }

    fun getSavedScrollValue(section: AppSection): Int = savedScrollValues[section] ?: 0

    fun saveLazyScrollPosition(section: AppSection, index: Int, offset: Int) {
        savedLazyPositions[section] = SavedScrollPosition(index, offset)
    }

    fun getSavedLazyScrollPosition(section: AppSection): SavedScrollPosition =
        savedLazyPositions[section] ?: SavedScrollPosition()

    fun navigateTo(section: AppSection) {
        if (navigationStack.lastOrNull() == section) return
        navigationStack.add(section)
    }

    fun replaceWith(section: AppSection) {
        navigationStack.clear()
        navigationStack.add(section)
    }

    fun switchPrimarySection(section: AppSection) {
        primarySection = section
        replaceWith(section)
    }

    fun navigateBack() {
        if (navigationStack.size > 1) {
            navigationStack.removeAt(navigationStack.lastIndex)
        }
    }

    internal fun client(preferences: PlexConnectionPreferences = connectionPreferences) = PlexClient(
        PlexAuthConfig(
            username = preferences.username,
            password = preferences.password,
            preferredServer = preferences.server,
            baseUrl = preferences.baseUrl,
            token = preferences.token,
        )
    )

    suspend fun loadAlbumsFromLocalStore() = libraryCoordinator.loadAlbumsFromLocalStore()

    suspend fun searchAlbums(query: String) = libraryCoordinator.searchAlbums(query)

    suspend fun refreshAlbums(showLoading: Boolean = true) = libraryCoordinator.refreshAlbums(showLoading)

    fun saveSettings(
        username: String,
        password: String,
        token: String,
        server: String,
        baseUrl: String,
        refreshAfterSave: Boolean,
    ) = libraryCoordinator.saveSettings(username, password, token, server, baseUrl, refreshAfterSave)

    fun openAlbumDetail(album: PlexAlbum) = libraryCoordinator.openAlbumDetail(album)

    fun openArtistAlbums(artist: ArtistSummary) = libraryCoordinator.openArtistAlbums(artist)

    fun openArtistAlbumsByName(artistName: String?) = libraryCoordinator.openArtistAlbumsByName(artistName)

    fun loadPlaylists() = libraryCoordinator.loadPlaylists()

    fun loadFavoriteTracks() = libraryCoordinator.loadFavoriteTracks()

    fun openFavoriteTracks() = libraryCoordinator.openFavoriteTracks()

    fun openPlaylistDetail(playlist: PlexPlaylist) = libraryCoordinator.openPlaylistDetail(playlist)

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) = libraryCoordinator.replaceAlbumInState(updatedAlbum)

    fun replaceTrackInState(updatedTrack: PlexTrackStream) = libraryCoordinator.replaceTrackInState(updatedTrack)

    fun toggleAlbumFavorite(album: PlexAlbum) = libraryCoordinator.toggleAlbumFavorite(album)

    fun toggleTrackFavorite(track: PlexTrackStream) = libraryCoordinator.toggleTrackFavorite(track)

    suspend fun loadArtists() = libraryCoordinator.loadArtists()

    fun startSingleTrackPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        trackIndex: Int,
        room: SonosRoom,
        initialPositionMillis: Long = 0L,
    ) = playbackCoordinator.startSingleTrackPlayback(album, tracks, trackIndex, room, initialPositionMillis)

    fun startAlbumPlayback(
        albumTrackResult: PlexAlbumTracksResult,
        room: SonosRoom,
        startIndex: Int = 0,
        initialPositionMillis: Long = 0L,
    ) = playbackCoordinator.startAlbumPlayback(albumTrackResult, room, startIndex, initialPositionMillis)

    fun refreshSonosRooms() = playbackCoordinator.refreshSonosRooms()

    suspend fun loadVolume(room: SonosRoom) = playbackCoordinator.loadVolume(room)

    fun applyVolumeChange(room: SonosRoom, newValue: Float) = playbackCoordinator.applyVolumeChange(room, newValue)

    fun startPlaylistPlayback(
        playlistResult: PlexPlaylistTracksResult,
        room: SonosRoom,
        shuffle: Boolean = false,
    ) = playbackCoordinator.startPlaylistPlayback(playlistResult, room, shuffle)

    fun togglePause(state: MiniPlayerState) = playbackCoordinator.togglePause(state)

    fun selectSonosRoom(room: SonosRoom) = playbackCoordinator.selectSonosRoom(room)

    fun updatePlaybackMode(mode: PlaybackMode) = playbackCoordinator.updatePlaybackMode(mode)

    fun playQueueIndex(index: Int, positionMillis: Long = 0L, room: SonosRoom? = null) =
        playbackCoordinator.playQueueIndex(index, positionMillis, room)

    fun switchPlaybackRoom(room: SonosRoom) = playbackCoordinator.switchPlaybackRoom(room)

    fun seekPlayback(state: MiniPlayerState, positionMillis: Long) =
        playbackCoordinator.seekPlayback(state, positionMillis)
}
