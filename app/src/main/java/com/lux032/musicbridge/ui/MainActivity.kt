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
import androidx.activity.SystemBarStyle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
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
    internal val appState: AppState by lazy { ViewModelProvider(this)[AppState::class.java] }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Strings.init(this)
        requestNotificationPermissionIfNeeded()
        // App is always dark, so force light system-bar icons (transparent bars) regardless of
        // the device's light/dark setting — otherwise dark icons can be invisible on our background.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            PlexToSonosPlayerTheme {
                // Content draws its own full-screen gradient; a transparent scaffold avoids
                // painting a solid background underneath it every frame.
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = androidx.compose.ui.graphics.Color.Transparent,
                ) { innerPadding ->
                    PlexAlbumScreen(
                        // Applied per-section inside: the playback screen goes edge-to-edge so
                        // its artwork backdrop paints behind the transparent system bars.
                        contentPadding = innerPadding,
                        systemBarTopPadding = innerPadding.calculateTopPadding(),
                        systemBarBottomPadding = innerPadding.calculateBottomPadding(),
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
fun PlexAlbumScreen(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    systemBarTopPadding: androidx.compose.ui.unit.Dp = 0.dp,
    systemBarBottomPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val state = remember(activity) { requireNotNull(activity).appState }
    val layoutInfo = rememberAppLayoutInfo()

    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    var isVolumeOverlayVisible by remember { mutableStateOf(false) }
    var volumeOverlayNonce by remember { mutableIntStateOf(0) }
    val hasMiniPlayer by remember(state) {
        derivedStateOf { state.miniPlayerState != null }
    }

    // Global Plexamp-style tint: the whole app background and accent highlights follow the
    // now-playing artwork. Null when nothing is playing -> DefaultArtworkPalette, i.e. the
    // original neutral dark look. Every page (including detail pages) shares this one tint.
    val nowPlayingArtworkUrl by remember(state) {
        derivedStateOf {
            val playerState = state.miniPlayerState ?: return@derivedStateOf null
            playerState.tracks.getOrNull(playerState.currentIndex)?.thumbUrl
                ?: playerState.album.thumbUrl
        }
    }
    val nowPlayingPalette = rememberAnimatedArtworkPalette(nowPlayingArtworkUrl)

    val activeSection = state.activeSection
    val bottomNavigationHeight = 84.dp
    val isNavigationRailVisible = layoutInfo.useNavigationRail && activeSection != AppSection.PlaybackDetail
    val navigationBottomPadding = if (isNavigationRailVisible) 0.dp else bottomNavigationHeight
    val contentBottomPadding = with(density) {
        if (activeSection == AppSection.PlaybackDetail) {
            20.dp
        } else if (hasMiniPlayer) {
            miniPlayerHeightPx.toDp() + navigationBottomPadding + 28.dp
        } else {
            navigationBottomPadding + 20.dp
        }
    }
    val volumeOverlayBottomPadding = with(density) {
        if (activeSection == AppSection.PlaybackDetail) {
            24.dp
        } else if (hasMiniPlayer) {
            miniPlayerHeightPx.toDp() + navigationBottomPadding + 32.dp
        } else {
            navigationBottomPadding + 24.dp
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
    val isMiniPlayerVisible = activeSection != AppSection.PlaybackDetail && hasMiniPlayer
    val overlayRoom = state.selectedSonosRoom

    fun shouldPersistVerticalScroll(section: AppSection): Boolean =
        when (section) {
            AppSection.AlbumDetail,
            AppSection.ArtistAlbums,
            AppSection.FavoriteCollection,
            AppSection.RecentAdded,
            AppSection.PlaylistDetail -> false
            else -> true
        }

    // Only the player lays out edge-to-edge (it applies the system bar insets itself);
    // every other section sits inside the scaffold's safe padding while the root gradient
    // below paints full-bleed behind the transparent system bars.
    val isEdgeToEdgeSection = activeSection == AppSection.PlaybackDetail

    CompositionLocalProvider(
        LocalAppLayoutInfo provides layoutInfo,
        LocalOverscrollFactory provides null,
        LocalArtworkPalette provides nowPlayingPalette,
    ) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(nowPlayingPalette.backgroundTop, nowPlayingPalette.backgroundBottom),
                )
            ),
    ) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (isEdgeToEdgeSection) Modifier
                else Modifier.padding(contentPadding)
            )
    ) {
        if (isNavigationRailVisible) {
            AppNavigationRail(
                primarySection = state.primarySection,
                onSectionChange = state::switchPrimarySection,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
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
                    onAlbumQueueAction = state::enqueueAlbum,
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
                            .padding(start = layoutInfo.horizontalPadding, top = 16.dp, end = layoutInfo.horizontalPadding, bottom = contentBottomPadding),
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
                } else if (currentSection == AppSection.Playlists) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = layoutInfo.horizontalPadding, top = 16.dp, end = layoutInfo.horizontalPadding, bottom = contentBottomPadding),
                    ) {
                        PlaylistsSection(
                            playlists = state.playlists,
                            onGoHome = { state.switchPrimarySection(AppSection.Home) },
                            onPlaylistClick = state::openPlaylistDetail,
                            onOpenFavorites = state::openFavoriteTracks,
                        )
                    }
                } else if (currentSection == AppSection.PlaylistDetail) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PlaylistDetailSection(
                            playlistResult = state.playlistTrackResult,
                            selectedRoom = state.selectedSonosRoom,
                            isPlaybackLoading = state.isPlaybackCommandLoading,
                            isFavoriteLoading = state.isFavoriteMutationLoading,
                            bottomContentPadding = contentBottomPadding,
                            onBack = state::navigateBack,
                            onToggleTrackFavorite = state::toggleTrackFavorite,
                            onQueueTrack = state::enqueueTrack,
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
                                        sourceId = playlist.sourceId,
                                    )
                                    state.startSingleTrackPlayback(fakeAlbum, tracksFromIndex, 0, room)
                                }
                            },
                        )
                    }
                } else if (currentSection == AppSection.PlaybackDetail) {
                    val sleepTimerState by state.sleepTimerState.collectAsState()
                    // No outer padding: the player draws its own full-bleed artwork-tinted
                    // backdrop and applies content padding internally.
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PlaybackDetailSection(
                            state = state.miniPlayerState,
                            rooms = state.sonosRooms,
                            topSystemBarInset = systemBarTopPadding,
                            bottomSystemBarInset = systemBarBottomPadding,
                            playbackMode = state.playbackMode,
                            sleepTimerState = sleepTimerState,
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
                            onStartSleepTimer = { hours, minutes ->
                                val room = state.miniPlayerState?.room ?: state.selectedSonosRoom
                                if (room != null) {
                                    state.startSleepTimer(hours, minutes, room)
                                }
                            },
                            onCancelSleepTimer = state::cancelSleepTimer,
                        )
                    }
                } else if (currentSection == AppSection.ArtistAlbums) {
                    val savedPos = state.getSavedLazyScrollPosition(AppSection.ArtistAlbums)
                    val artistAlbumsGridState = rememberLazyGridState(
                        initialFirstVisibleItemIndex = savedPos.index,
                        initialFirstVisibleItemScrollOffset = savedPos.offset,
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            state.saveLazyScrollPosition(
                                AppSection.ArtistAlbums,
                                artistAlbumsGridState.firstVisibleItemIndex,
                                artistAlbumsGridState.firstVisibleItemScrollOffset,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        ArtistAlbumsSection(
                            artist = state.selectedArtist,
                            selectedAlbum = state.selectedAlbum,
                            selectedRoom = state.selectedSonosRoom,
                            isPlaybackLoading = state.isPlaybackCommandLoading,
                            bottomContentPadding = contentBottomPadding,
                            gridState = artistAlbumsGridState,
                            onBack = state::navigateBack,
                            onAlbumClick = state::openAlbumDetail,
                            onAlbumQueueAction = state::enqueueAlbum,
                            onShuffleArtist = state::shuffleArtist,
                        )
                    }
                } else if (currentSection == AppSection.FavoriteCollection) {
                    val savedPos = state.getSavedLazyScrollPosition(AppSection.FavoriteCollection)
                    val favoriteGridState = rememberLazyGridState(
                        initialFirstVisibleItemIndex = savedPos.index,
                        initialFirstVisibleItemScrollOffset = savedPos.offset,
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            state.saveLazyScrollPosition(
                                AppSection.FavoriteCollection,
                                favoriteGridState.firstVisibleItemIndex,
                                favoriteGridState.firstVisibleItemScrollOffset,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AlbumCollectionSection(
                            title = Strings.favoriteAlbumsCollection,
                            subtitle = Strings.favoriteAlbumsCollectionDesc,
                            albums = state.favoriteAlbums,
                            selectedAlbum = state.selectedAlbum,
                            bottomContentPadding = contentBottomPadding,
                            gridState = favoriteGridState,
                            onBack = state::navigateBack,
                            onAlbumClick = state::openAlbumDetail,
                            onAlbumQueueAction = state::enqueueAlbum,
                        )
                    }
                } else if (currentSection == AppSection.RecentAdded) {
                    val savedPos = state.getSavedLazyScrollPosition(AppSection.RecentAdded)
                    val recentAddedGridState = rememberLazyGridState(
                        initialFirstVisibleItemIndex = savedPos.index,
                        initialFirstVisibleItemScrollOffset = savedPos.offset,
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            state.saveLazyScrollPosition(
                                AppSection.RecentAdded,
                                recentAddedGridState.firstVisibleItemIndex,
                                recentAddedGridState.firstVisibleItemScrollOffset,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AlbumCollectionSection(
                            title = Strings.recentAdded100,
                            subtitle = Strings.recentAdded100Desc,
                            albums = state.recentAddedAlbums.take(100),
                            selectedAlbum = state.selectedAlbum,
                            bottomContentPadding = contentBottomPadding,
                            gridState = recentAddedGridState,
                            onBack = state::navigateBack,
                            onAlbumClick = state::openAlbumDetail,
                            onAlbumQueueAction = state::enqueueAlbum,
                        )
                    }
                } else if (currentSection == AppSection.AlbumDetail) {
                    val albumTrackListState = rememberLazyListState()
                    Box(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        AlbumDetailSection(
                            trackResult = state.trackResult,
                            selectedRoom = state.selectedSonosRoom,
                            isPlaybackLoading = state.isPlaybackCommandLoading,
                            isFavoriteLoading = state.isFavoriteMutationLoading,
                            bottomContentPadding = contentBottomPadding,
                            trackListState = albumTrackListState,
                            onReturnHome = state::navigateBack,
                            onArtistClick = state::openArtistAlbumsByName,
                            onToggleAlbumFavorite = state::toggleAlbumFavorite,
                            onToggleTrackFavorite = state::toggleTrackFavorite,
                            onQueueAlbum = state::enqueueAlbum,
                            onQueueTrack = state::enqueueTrack,
                            onPlayAlbum = { albumTrackResult: PlexAlbumTracksResult, room: SonosRoom ->
                                state.startAlbumPlayback(albumTrackResult, room)
                            },
                            onPlayTrack = { album: PlexAlbum, track: PlexTrackStream, room: SonosRoom ->
                                val currentTrackResult = state.trackResult
                                if (
                                    currentTrackResult != null &&
                                    currentTrackResult.album.ratingKey == album.ratingKey &&
                                    currentTrackResult.album.sourceId == album.sourceId
                                ) {
                                    val trackIndex = currentTrackResult.tracks
                                        .indexOfFirst {
                                            it.ratingKey == track.ratingKey && it.sourceId == track.sourceId
                                        }
                                        .coerceAtLeast(0)
                                    val playlist = currentTrackResult.tracks.drop(trackIndex)
                                    state.startSingleTrackPlayback(album, playlist, 0, room)
                                } else {
                                    state.startSingleTrackPlayback(album, listOf(track), 0, room)
                                }
                            },
                        )
                    }
                } else if (currentSection == AppSection.Home) {
                    val savedPos = state.getSavedLazyScrollPosition(AppSection.Home)
                    val homeListState = rememberLazyListState(
                        initialFirstVisibleItemIndex = savedPos.index,
                        initialFirstVisibleItemScrollOffset = savedPos.offset,
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            state.saveLazyScrollPosition(
                                AppSection.Home,
                                homeListState.firstVisibleItemIndex,
                                homeListState.firstVisibleItemScrollOffset,
                            )
                        }
                    }
                    HomeSection(
                        connectionPreferences = state.connectionPreferences,
                        allAlbums = state.allAlbums,
                        recentPlayedAlbums = state.recentPlayedAlbums,
                        favoriteAlbums = state.favoriteAlbums,
                        recentAddedAlbums = state.recentAddedAlbums.take(100),
                        selectedAlbum = state.selectedAlbum,
                        selectedSonosRoom = state.selectedSonosRoom,
                        lastAlbumSyncEpochMillis = state.lastAlbumSyncEpochMillis,
                        isLoading = state.isLoading,
                        errorMessage = state.errorMessage,
                        actionMessage = state.actionMessage,
                        bottomContentPadding = contentBottomPadding,
                        listState = homeListState,
                        onRefreshFavorites = { scope.launch { state.refreshAlbums() } },
                        onAlbumClick = state::openAlbumDetail,
                        onAlbumQueueAction = state::enqueueAlbum,
                        onOpenAllAlbums = {
                            state.albumSearchQuery = ""
                            state.navigateTo(AppSection.AllAlbums)
                        },
                        onOpenFavorites = { state.navigateTo(AppSection.FavoriteCollection) },
                        onOpenRecentAdded = { state.navigateTo(AppSection.RecentAdded) },
                    )
                } else {
                    val scrollState = rememberScrollState(
                        initial = if (shouldPersistVerticalScroll(currentSection)) {
                            state.getSavedScrollValue(currentSection)
                        } else {
                            0
                        }
                    )
                    DisposableEffect(Unit) {
                        onDispose {
                            if (shouldPersistVerticalScroll(currentSection)) {
                                state.saveScrollValue(currentSection, scrollState.value)
                            }
                        }
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(start = layoutInfo.horizontalPadding, top = 16.dp, end = layoutInfo.horizontalPadding, bottom = contentBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (currentSection) {
                            AppSection.Home -> Unit
                            AppSection.AlbumDetail -> Unit
                            AppSection.PlaybackDetail -> Unit
                            AppSection.ArtistAlbums -> Unit
                            AppSection.FavoriteCollection -> Unit
                            AppSection.RecentAdded -> Unit
                            AppSection.Settings -> SettingsSection(
                                rooms = state.sonosRooms,
                                selectedRoom = state.selectedSonosRoom,
                                isSonosLoading = state.isSonosLoading,
                                discoveryAttempted = state.sonosDiscoveryAttempted,
                                serverProfiles = state.serverProfiles,
                                activeServerId = state.connectionPreferences.id,
                                opusTranscodeMode = state.opusTranscodeMode,
                                onOpusTranscodeModeChange = state::updateOpusTranscodeMode,
                                onDiscoverSonos = state::refreshSonosRooms,
                                onSelectRoom = state::selectSonosRoom,
                                onSaveServer = state::saveServerProfile,
                                onSwitchServer = state::switchServer,
                                onDeleteServer = state::deleteServer,
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

        if (isBottomBarVisible && !isNavigationRailVisible) {
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
            BottomMiniPlayerHost(
                appState = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = layoutInfo.horizontalPadding,
                        end = layoutInfo.horizontalPadding,
                        top = 12.dp,
                        bottom = navigationBottomPadding + 12.dp,
                    )
                    .onSizeChanged { miniPlayerHeightPx = it.height },
                onOpenPlayback = { state.navigateTo(AppSection.PlaybackDetail) },
            )
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
                    .padding(
                        start = layoutInfo.horizontalPadding,
                        end = layoutInfo.horizontalPadding,
                        bottom = volumeOverlayBottomPadding,
                    ),
                onVolumeChange = { newValue ->
                    state.applyVolumeChange(overlayRoom, newValue)
                    showVolumeOverlay()
                },
            )
        }

        }
    }
    }
    }
}
}
