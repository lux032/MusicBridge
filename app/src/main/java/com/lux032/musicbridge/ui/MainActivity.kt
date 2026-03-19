package com.lux032.musicbridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexAlbumTracksResult
import com.lux032.musicbridge.plex.PlexSection
import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.ui.theme.PlexToSonosPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class MainActivity : ComponentActivity() {
    var onHardwareVolumeStep: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.init(this)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            PlexToSonosPlayerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PlexAlbumScreen(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    if (onHardwareVolumeStep?.invoke(+5) == true) return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    if (onHardwareVolumeStep?.invoke(-5) == true) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
    }
}
@Composable
fun PlexAlbumScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(context, scope) { AppState(context, scope) }

    var username by rememberSaveable { mutableStateOf(state.connectionPreferences.username) }
    var password by rememberSaveable { mutableStateOf(state.connectionPreferences.password) }
    var token by rememberSaveable { mutableStateOf(state.connectionPreferences.token) }
    var server by rememberSaveable { mutableStateOf(state.connectionPreferences.server) }
    var baseUrl by rememberSaveable { mutableStateOf(state.connectionPreferences.baseUrl) }
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    var isVolumeOverlayVisible by remember { mutableStateOf(false) }
    var volumeOverlayNonce by remember { mutableIntStateOf(0) }

    val activeSection = state.activeSection
    val bottomNavigationHeight = 84.dp
    val contentBottomPadding = with(density) {
        if (activeSection == AppSection.PlaybackDetail) {
            20.dp
        } else if (state.miniPlayerState != null) {
            miniPlayerHeightPx.toDp() + bottomNavigationHeight + 28.dp
        } else {
            bottomNavigationHeight + 20.dp
        }
    }
    val volumeOverlayBottomPadding = with(density) {
        if (activeSection == AppSection.PlaybackDetail) {
            24.dp
        } else if (state.miniPlayerState != null) {
            miniPlayerHeightPx.toDp() + bottomNavigationHeight + 32.dp
        } else {
            bottomNavigationHeight + 24.dp
        }
    }

    fun showVolumeOverlay() {
        isVolumeOverlayVisible = true
        volumeOverlayNonce += 1
    }

    LaunchedEffect(Unit) {
        if (!state.hasLoadedLocalAlbums) {
            state.loadAlbumsFromLocalStore()
        }
    }

    LaunchedEffect(state.albumSearchQuery, state.allAlbums.size) {
        state.searchAlbums(state.albumSearchQuery)
    }

    LaunchedEffect(
        state.selectedSonosRoom?.coordinatorUuid,
        state.selectedSonosRoom?.coordinatorBaseUrl,
        state.volumeSyncKey,
    ) {
        val room = state.selectedSonosRoom ?: return@LaunchedEffect
        state.loadVolume(room)
    }

    LaunchedEffect(state.connectionPreferences) {
        launch { state.loadArtists() }
        launch { state.loadPlaylists() }
    }

    DisposableEffect(activity, state.selectedSonosRoom, state.sonosVolume, state.hasLoadedSonosVolume) {
        activity?.onHardwareVolumeStep = { step ->
            val room = state.selectedSonosRoom
            if (room == null) {
                false
            } else {
                val baseVolume = if (state.hasLoadedSonosVolume) state.sonosVolume else 0f
                val targetVolume = (baseVolume + step).coerceIn(0f, 100f)
                state.applyVolumeChange(room, targetVolume)
                showVolumeOverlay()
                true
            }
        }

        onDispose {
            if (activity?.onHardwareVolumeStep != null) {
                activity.onHardwareVolumeStep = null
            }
        }
    }

    LaunchedEffect(volumeOverlayNonce) {
        if (volumeOverlayNonce == 0) return@LaunchedEffect
        delay(2_000)
        isVolumeOverlayVisible = false
    }

    BackHandler(enabled = state.navigationStack.size > 1) {
        when (activeSection) {
            AppSection.AlbumDetail,
            AppSection.ArtistAlbums,
            AppSection.PlaylistDetail,
            AppSection.PlaybackDetail,
            AppSection.FavoriteCollection,
            AppSection.RecentAdded,
            AppSection.AllAlbums,
            AppSection.Artists,
            AppSection.Playlists,
            AppSection.Settings -> state.navigateBack()
            AppSection.Home -> Unit
        }
    }
    
    val isBottomBarVisible = activeSection != AppSection.PlaybackDetail
    val isMiniPlayerVisible = activeSection != AppSection.PlaybackDetail && state.miniPlayerState != null
    val overlayRoom = state.selectedSonosRoom

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(AppColors.BackgroundTop, AppColors.BackgroundBottom),
                )
            )
    ) {
        key(activeSection) {
            val currentSection = activeSection

            if (currentSection == AppSection.AllAlbums) {
                val savedPos = state.getSavedLazyScrollPosition(AppSection.AllAlbums)
                val allAlbumsGridState = rememberLazyGridState(
                    initialFirstVisibleItemIndex = savedPos.index,
                    initialFirstVisibleItemScrollOffset = savedPos.offset,
                )
                DisposableEffect(Unit) {
                    onDispose {
                        state.saveLazyScrollPosition(
                            AppSection.AllAlbums,
                            allAlbumsGridState.firstVisibleItemIndex,
                            allAlbumsGridState.firstVisibleItemScrollOffset,
                        )
                    }
                }
                AllAlbumsSection(
                    albums = if (state.albumSearchQuery.isBlank()) state.allAlbums else state.albumSearchResults,
                    selectedAlbum = state.selectedAlbum,
                    searchQuery = state.albumSearchQuery,
                    isSearchLoading = state.isAlbumSearchLoading,
                    bottomContentPadding = contentBottomPadding,
                    gridState = allAlbumsGridState,
                    onSearchQueryChange = { state.albumSearchQuery = it },
                    onBack = state::navigateBack,
                    onAlbumClick = state::openAlbumDetail,
                )
            } else {
                if (currentSection == AppSection.Artists) {
                    val savedPos = state.getSavedLazyScrollPosition(AppSection.Artists)
                    val artistsGridState = rememberLazyGridState(
                        initialFirstVisibleItemIndex = savedPos.index,
                        initialFirstVisibleItemScrollOffset = savedPos.offset,
                    )
                    val artistsListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = savedPos.index,
                        initialFirstVisibleItemScrollOffset = savedPos.offset,
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            val (index, offset) = if (state.artistPresentation == ArtistPresentation.Covers) {
                                artistsGridState.firstVisibleItemIndex to artistsGridState.firstVisibleItemScrollOffset
                            } else {
                                artistsListState.firstVisibleItemIndex to artistsListState.firstVisibleItemScrollOffset
                            }
                            state.saveLazyScrollPosition(AppSection.Artists, index, offset)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                    ) {
                        ArtistsSection(
                            artists = state.artists,
                            presentation = state.artistPresentation,
                            gridState = artistsGridState,
                            listState = artistsListState,
                            onPresentationChange = { state.artistPresentation = it },
                            onGoHome = { state.switchPrimarySection(AppSection.Home) },
                            onArtistClick = state::openArtistAlbums,
                        )
                    }
                } else if (activeSection == AppSection.Playlists) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                    ) {
                        PlaylistsSection(
                            playlists = state.playlists,
                            onGoHome = { state.switchPrimarySection(AppSection.Home) },
                            onPlaylistClick = state::openPlaylistDetail,
                            onOpenFavorites = state::openFavoriteTracks,
                        )
                    }
                } else if (activeSection == AppSection.PlaylistDetail) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                    ) {
                        PlaylistDetailSection(
                            playlistResult = state.playlistTrackResult,
                            selectedRoom = state.selectedSonosRoom,
                            isPlaybackLoading = state.isPlaybackCommandLoading,
                            isFavoriteLoading = state.isFavoriteMutationLoading,
                            onBack = state::navigateBack,
                            onToggleTrackFavorite = state::toggleTrackFavorite,
                            onPlayPlaylist = { playlistResult, room ->
                                state.startPlaylistPlayback(playlistResult, room, shuffle = false)
                            },
                            onShufflePlaylist = { playlistResult, room ->
                                state.startPlaylistPlayback(playlistResult, room, shuffle = true)
                            },
                            onPlayTrack = { playlist, track, room, index ->
                                val currentResult = state.playlistTrackResult
                                if (currentResult != null) {
                                    val tracksFromIndex = currentResult.tracks.drop(index)
                                    val fakeAlbum = PlexAlbum(
                                        ratingKey = playlist.ratingKey,
                                        title = playlist.title,
                                        artistName = null,
                                        year = null,
                                        thumbUrl = playlist.thumbUrl,
                                        userRating = null,
                                        addedAtEpochSeconds = null,
                                        lastViewedAtEpochSeconds = null,
                                        section = PlexSection("", "", ""),
                                    )
                                    state.startSingleTrackPlayback(fakeAlbum, tracksFromIndex, 0, room)
                                }
                            },
                        )
                    }
                } else {
                    val scrollState = rememberScrollState(
                        initial = state.getSavedScrollValue(currentSection)
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            state.saveScrollValue(currentSection, scrollState.value)
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (activeSection) {
                            AppSection.Home -> HomeSection(
                                connectionPreferences = state.connectionPreferences,
                                allAlbums = state.allAlbums,
                                recentPlayedAlbums = state.recentPlayedAlbums.take(9),
                                favoriteAlbums = state.favoriteAlbums,
                                recentAddedAlbums = state.recentAddedAlbums.take(100),
                                selectedAlbum = state.selectedAlbum,
                                selectedSonosRoom = state.selectedSonosRoom,
                                lastAlbumSyncEpochMillis = state.lastAlbumSyncEpochMillis,
                                isLoading = state.isLoading,
                                errorMessage = state.errorMessage,
                                actionMessage = state.actionMessage,
                                onRefreshFavorites = { scope.launch { state.refreshAlbums() } },
                                onAlbumClick = state::openAlbumDetail,
                                onOpenAllAlbums = {
                                    state.albumSearchQuery = ""
                                    state.navigateTo(AppSection.AllAlbums)
                                },
                                onOpenFavorites = { state.navigateTo(AppSection.FavoriteCollection) },
                                onOpenRecentAdded = { state.navigateTo(AppSection.RecentAdded) },
                            )
                            AppSection.AlbumDetail -> AlbumDetailSection(
                                trackResult = state.trackResult,
                                selectedRoom = state.selectedSonosRoom,
                                isPlaybackLoading = state.isPlaybackCommandLoading,
                                isFavoriteLoading = state.isFavoriteMutationLoading,
                                onReturnHome = state::navigateBack,
                                onArtistClick = state::openArtistAlbumsByName,
                                onToggleAlbumFavorite = state::toggleAlbumFavorite,
                                onToggleTrackFavorite = state::toggleTrackFavorite,
                                onPlayAlbum = { albumTrackResult: PlexAlbumTracksResult, room: SonosRoom ->
                                    state.startAlbumPlayback(albumTrackResult, room)
                                },
                                onPlayTrack = { album: PlexAlbum, track: PlexTrackStream, room: SonosRoom ->
                                    val currentTrackResult = state.trackResult
                                    if (currentTrackResult?.album?.ratingKey == album.ratingKey) {
                                        val trackIndex = currentTrackResult.tracks.indexOfFirst { it.ratingKey == track.ratingKey }.coerceAtLeast(0)
                                        val playlist = currentTrackResult.tracks.drop(trackIndex)
                                        state.startSingleTrackPlayback(album, playlist, 0, room)
                                    } else {
                                        state.startSingleTrackPlayback(album, listOf(track), 0, room)
                                    }
                                },
                            )
                            AppSection.PlaybackDetail -> PlaybackDetailSection(
                                state = state.miniPlayerState,
                                rooms = state.sonosRooms,
                                playbackMode = state.playbackMode,
                                isLoading = state.isPlaybackCommandLoading,
                                isFavoriteLoading = state.isFavoriteMutationLoading,
                                onBack = state::navigateBack,
                                onPrevious = {
                                    val playerState = state.miniPlayerState ?: return@PlaybackDetailSection
                                    val newIndex = (playerState.currentIndex - 1).coerceAtLeast(0)
                                    if (newIndex != playerState.currentIndex) {
                                        state.playQueueIndex(newIndex, room = playerState.room)
                                    }
                                },
                                onNext = {
                                    val playerState = state.miniPlayerState ?: return@PlaybackDetailSection
                                    val newIndex = (playerState.currentIndex + 1).coerceAtMost(playerState.tracks.lastIndex)
                                    if (newIndex != playerState.currentIndex) {
                                        state.playQueueIndex(newIndex, room = playerState.room)
                                    }
                                },
                                onTogglePause = state::togglePause,
                                onSeek = state::seekPlayback,
                                onAlbumClick = state::openAlbumDetail,
                                onArtistClick = state::openArtistAlbumsByName,
                                onToggleTrackFavorite = state::toggleTrackFavorite,
                                onSelectTrack = { index -> state.playQueueIndex(index) },
                                onSelectPlaybackMode = state::updatePlaybackMode,
                                onSelectRoom = state::switchPlaybackRoom,
                            )
                            AppSection.ArtistAlbums -> ArtistAlbumsSection(
                                artist = state.selectedArtist,
                                selectedAlbum = state.selectedAlbum,
                                onBack = state::navigateBack,
                                onAlbumClick = state::openAlbumDetail,
                            )
                            AppSection.FavoriteCollection -> AlbumCollectionSection(
                                title = Strings.favoriteAlbumsCollection,
                                subtitle = Strings.favoriteAlbumsCollectionDesc,
                                albums = state.favoriteAlbums,
                                selectedAlbum = state.selectedAlbum,
                                onBack = state::navigateBack,
                                onAlbumClick = state::openAlbumDetail,
                            )
                            AppSection.RecentAdded -> AlbumCollectionSection(
                                title = Strings.recentAdded100,
                                subtitle = Strings.recentAdded100Desc,
                                albums = state.recentAddedAlbums.take(100),
                                selectedAlbum = state.selectedAlbum,
                                onBack = state::navigateBack,
                                onAlbumClick = state::openAlbumDetail,
                            )
                            AppSection.Settings -> SettingsSection(
                                rooms = state.sonosRooms,
                                selectedRoom = state.selectedSonosRoom,
                                isSonosLoading = state.isSonosLoading,
                                discoveryAttempted = state.sonosDiscoveryAttempted,
                                token = token,
                                baseUrl = baseUrl,
                                onDiscoverSonos = state::refreshSonosRooms,
                                onSelectRoom = state::selectSonosRoom,
                                onTokenChange = { token = it },
                                onBaseUrlChange = { baseUrl = it },
                                onSave = { state.saveSettings(username, password, token, server, baseUrl, false) },
                                onSaveAndRefresh = { state.saveSettings(username, password, token, server, baseUrl, true) },
                                onRefreshHome = { scope.launch { state.refreshAlbums() } },
                            )
                        AppSection.Artists -> Unit
                        AppSection.Playlists -> Unit
                        AppSection.AllAlbums -> Unit
                        AppSection.PlaylistDetail -> Unit
                    }
                }
            }
        }

        if (isBottomBarVisible) {
            BottomNavigationBar(
                primarySection = state.primarySection,
                modifier = Modifier
                    .align(Alignment.BottomCenter),
                onSectionChange = { section: AppSection ->
                    when (section) {
                        AppSection.Home,
                        AppSection.Artists,
                        AppSection.Playlists,
                        AppSection.Settings -> state.switchPrimarySection(section)
                        else -> Unit
                    }
                }
            )
        }

        if (isMiniPlayerVisible) {
            state.miniPlayerState?.let { playerState ->
                BottomMiniPlayer(
                    state = playerState,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = bottomNavigationHeight + 12.dp,
                        )
                        .onSizeChanged { miniPlayerHeightPx = it.height },
                    onArtworkClick = {
                        if (activeSection != AppSection.PlaybackDetail) {
                            state.navigateTo(AppSection.PlaybackDetail)
                        }
                    },
                    onTogglePause = { state.togglePause(playerState) },
                )
            }
        }

        if (isVolumeOverlayVisible && overlayRoom != null) {
            GlobalVolumeOverlay(
                room = overlayRoom,
                volume = state.sonosVolume,
                hasLoadedVolume = state.hasLoadedSonosVolume,
                isVolumeLoading = state.isVolumeLoading,
                isVolumeChanging = state.isVolumeChanging,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = volumeOverlayBottomPadding),
                onVolumeChange = { newValue ->
                    state.applyVolumeChange(overlayRoom, newValue)
                    showVolumeOverlay()
                },
            )
        }

    }
}
}
