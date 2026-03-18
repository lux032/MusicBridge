package com.lux032.plextosonosplayer

import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbum
import com.lux032.plextosonosplayer.plex.PlexAlbumTracksResult
import com.lux032.plextosonosplayer.plex.PlexAuthConfig
import com.lux032.plextosonosplayer.plex.PlexClient
import com.lux032.plextosonosplayer.plex.PlexTrackStream
import com.lux032.plextosonosplayer.plex.isFavorite
import com.lux032.plextosonosplayer.sonos.SonosController
import com.lux032.plextosonosplayer.sonos.SonosDiscovery
import com.lux032.plextosonosplayer.sonos.SonosRoom
import com.lux032.plextosonosplayer.storage.AlbumLocalStore
import com.lux032.plextosonosplayer.ui.theme.PlexToSonosPlayerTheme
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.Normalizer

class MainActivity : ComponentActivity() {
    var onHardwareVolumeStep: ((Int) -> Boolean)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}
@Composable
fun PlexAlbumScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val appPreferences = remember(context) { AppPreferences(context) }
    val albumLocalStore = remember(context) { AlbumLocalStore(context) }
    val sonosController = remember { SonosController() }
    val initialPreferences = remember(appPreferences) { appPreferences.loadPlexConnectionPreferences() }

    val navigationStack = remember { mutableStateListOf(AppSection.Home) }
    var primarySection by remember { mutableStateOf(AppSection.Home) }
    var connectionPreferences by remember { mutableStateOf(initialPreferences) }
    var username by rememberSaveable { mutableStateOf(initialPreferences.username) }
    var password by rememberSaveable { mutableStateOf(initialPreferences.password) }
    var token by rememberSaveable { mutableStateOf(initialPreferences.token) }
    var server by rememberSaveable { mutableStateOf(initialPreferences.server) }
    var baseUrl by rememberSaveable { mutableStateOf(initialPreferences.baseUrl) }

    var isLoading by remember { mutableStateOf(false) }
    var isSonosLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var allAlbums by remember { mutableStateOf<List<PlexAlbum>>(emptyList()) }
    var hasLoadedLocalAlbums by remember { mutableStateOf(false) }
    var lastAlbumSyncEpochMillis by remember { mutableStateOf(appPreferences.loadLastAlbumSyncEpochMillis()) }
    var albumSearchQuery by rememberSaveable { mutableStateOf("") }
    var albumSearchResults by remember { mutableStateOf<List<PlexAlbum>>(emptyList()) }
    var isAlbumSearchLoading by remember { mutableStateOf(false) }
    var selectedAlbum by remember { mutableStateOf<PlexAlbum?>(null) }
    var selectedArtistName by remember { mutableStateOf<String?>(null) }
    var artistPresentation by remember { mutableStateOf(ArtistPresentation.Covers) }
    var trackResult by remember { mutableStateOf<PlexAlbumTracksResult?>(null) }
    var sonosRooms by remember { mutableStateOf<List<SonosRoom>>(emptyList()) }
    var selectedSonosRoom by remember { mutableStateOf<SonosRoom?>(null) }
    var sonosDiscoveryAttempted by remember { mutableStateOf(false) }
    var sonosVolume by remember { mutableFloatStateOf(0f) }
    var hasLoadedSonosVolume by remember { mutableStateOf(false) }
    var isVolumeLoading by remember { mutableStateOf(false) }
    var isVolumeChanging by remember { mutableStateOf(false) }
    var isPlaybackCommandLoading by remember { mutableStateOf(false) }
    var isFavoriteMutationLoading by remember { mutableStateOf(false) }
    var volumeSyncKey by remember { mutableIntStateOf(0) }
    var volumeChangeJob by remember { mutableStateOf<Job?>(null) }
    var albumPlaybackJob by remember { mutableStateOf<Job?>(null) }
    var miniPlayerState by remember { mutableStateOf<MiniPlayerState?>(null) }
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    var recentPlayedAlbumKeys by remember { mutableStateOf(appPreferences.loadRecentPlayedAlbumKeys()) }

    val activeSection = navigationStack.lastOrNull() ?: AppSection.Home
    val bottomNavigationHeight = 84.dp
    val contentBottomPadding = with(density) {
        if (miniPlayerState != null && activeSection != AppSection.PlaybackDetail) {
            miniPlayerHeightPx.toDp() + bottomNavigationHeight + 28.dp
        } else {
            bottomNavigationHeight + 20.dp
        }
    }

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

    fun client(preferences: PlexConnectionPreferences = connectionPreferences) = PlexClient(
        PlexAuthConfig(
            username = preferences.username,
            password = preferences.password,
            preferredServer = preferences.server,
            baseUrl = preferences.baseUrl,
            token = preferences.token,
        )
    )

    suspend fun loadAlbumsFromLocalStore() {
        allAlbums = withContext(Dispatchers.IO) {
            albumLocalStore.getAllAlbums()
        }
        hasLoadedLocalAlbums = true
    }

    fun startSingleTrackPlayback(
        album: PlexAlbum,
        tracks: List<PlexTrackStream>,
        trackIndex: Int,
        room: SonosRoom,
    ) {
        val targetTrack = tracks.getOrNull(trackIndex) ?: return
        albumPlaybackJob?.cancel()
        appPreferences.markAlbumPlayed(album.ratingKey)
        recentPlayedAlbumKeys = appPreferences.loadRecentPlayedAlbumKeys()
        selectedSonosRoom = room
        volumeSyncKey += 1
        miniPlayerState = MiniPlayerState(
            album = album,
            tracks = tracks,
            currentIndex = trackIndex,
            room = room,
            isPaused = false,
        )
        isPlaybackCommandLoading = true
        errorMessage = null
        actionMessage = null
        scope.launch {
            runCatching {
                sonosController.playTrack(
                    room = room,
                    trackUrl = targetTrack.streamUrl,
                    title = targetTrack.title,
                    albumTitle = album.title,
                )
            }.onSuccess {
                actionMessage = "已将 ${targetTrack.title} 推送到 ${room.roomName}"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isPlaybackCommandLoading = false
        }
    }

    fun startAlbumPlayback(
        albumTrackResult: PlexAlbumTracksResult,
        room: SonosRoom,
        startIndex: Int = 0,
    ) {
        albumPlaybackJob?.cancel()
        appPreferences.markAlbumPlayed(albumTrackResult.album.ratingKey)
        recentPlayedAlbumKeys = appPreferences.loadRecentPlayedAlbumKeys()
        selectedSonosRoom = room
        volumeSyncKey += 1
        miniPlayerState = MiniPlayerState(
            album = albumTrackResult.album,
            tracks = albumTrackResult.tracks,
            currentIndex = startIndex,
            room = room,
            isPaused = false,
        )
        isPlaybackCommandLoading = true
        errorMessage = null
        actionMessage = null
        albumPlaybackJob = scope.launch {
            runCatching<Unit> {
                var didStartFirstTrack = false
                playAlbumSequentially(
                    sonosController = sonosController,
                    room = room,
                    trackResult = albumTrackResult,
                    startIndex = startIndex,
                    onTrackChanged = { index: Int, track: PlexTrackStream ->
                        if (!didStartFirstTrack) {
                            didStartFirstTrack = true
                            isPlaybackCommandLoading = false
                        }
                        miniPlayerState = miniPlayerState?.copy(
                            currentIndex = index,
                            isPaused = false,
                        )
                        actionMessage = "正在连续播放：${track.title}"
                    },
                )
            }.onSuccess { _: Unit ->
                actionMessage = "专辑播放结束：${albumTrackResult.album.title}"
            }.onFailure { e: Throwable ->
                errorMessage = e.message ?: "未知错误"
                isPlaybackCommandLoading = false
            }
        }
    }

    suspend fun refreshAlbums(showLoading: Boolean = true) {
        if (!connectionPreferences.hasCredentials()) {
            selectedAlbum = null
            trackResult = null
            return
        }

        if (showLoading) {
            isLoading = true
        }
        errorMessage = null
        actionMessage = null
        runCatching {
            client().fetchAlbums()
        }.onSuccess {
            allAlbums = it.albums
            withContext(Dispatchers.IO) {
                albumLocalStore.replaceAllAlbums(it.albums)
            }
            lastAlbumSyncEpochMillis = System.currentTimeMillis()
            appPreferences.saveLastAlbumSyncEpochMillis(lastAlbumSyncEpochMillis!!)
            if (selectedAlbum?.ratingKey?.let { key -> it.albums.none { album -> album.ratingKey == key } } == true) {
                selectedAlbum = null
                trackResult = null
            }
            actionMessage = "已同步 ${it.albums.size} 张专辑到本地"
        }.onFailure {
            selectedAlbum = null
            trackResult = null
            errorMessage = it.message ?: "未知错误"
        }
        if (showLoading) {
            isLoading = false
        }
    }

    fun saveSettings(refreshAfterSave: Boolean) {
        val newPreferences = PlexConnectionPreferences(
            username = username.trim(),
            password = password,
            token = token.trim(),
            server = server.trim(),
            baseUrl = baseUrl.trim(),
        )
        connectionPreferences = newPreferences
        appPreferences.savePlexConnectionPreferences(newPreferences)
        actionMessage = "Plex 连接信息已保存到设置"
        errorMessage = null
        if (refreshAfterSave) {
            scope.launch {
                refreshAlbums()
                if (allAlbums.isNotEmpty()) {
                    switchPrimarySection(AppSection.Home)
                }
            }
        }
    }

    fun refreshSonosRooms() {
        isSonosLoading = true
        errorMessage = null
        actionMessage = null
        sonosDiscoveryAttempted = true
        scope.launch {
            runCatching {
                SonosDiscovery(context).discoverRooms()
            }.onSuccess { rooms ->
                sonosRooms = rooms
                selectedSonosRoom = selectedSonosRoom
                    ?.let { current -> rooms.firstOrNull { room -> room.coordinatorUuid == current.coordinatorUuid } }
                    ?: rooms.firstOrNull()
                actionMessage = if (rooms.isEmpty()) {
                    "当前未发现 Sonos 房间"
                } else {
                    "已同步 ${rooms.size} 个 Sonos 房间"
                }
                volumeSyncKey += 1
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isSonosLoading = false
        }
    }

    LaunchedEffect(Unit) {
        if (!hasLoadedLocalAlbums) {
            loadAlbumsFromLocalStore()
        }
    }

    LaunchedEffect(albumSearchQuery, allAlbums.size) {
        val normalizedQuery = albumSearchQuery.trim()
        if (normalizedQuery.isEmpty()) {
            albumSearchResults = emptyList()
            isAlbumSearchLoading = false
            return@LaunchedEffect
        }

        isAlbumSearchLoading = true
        albumSearchResults = withContext(Dispatchers.IO) {
            albumLocalStore.searchAlbums(normalizedQuery)
        }
        isAlbumSearchLoading = false
    }

    LaunchedEffect(
        selectedSonosRoom?.coordinatorUuid,
        selectedSonosRoom?.coordinatorBaseUrl,
        volumeSyncKey,
    ) {
        val room = selectedSonosRoom ?: return@LaunchedEffect
        isVolumeLoading = true
        hasLoadedSonosVolume = false
        errorMessage = null
        runCatching {
            sonosController.getVolume(room)
        }.onSuccess {
            sonosVolume = it.toFloat()
            hasLoadedSonosVolume = true
        }.onFailure {
            errorMessage = it.message ?: "未知错误"
        }
        isVolumeLoading = false
    }

    fun applyVolumeChange(room: SonosRoom, newValue: Float) {
        sonosVolume = newValue
        hasLoadedSonosVolume = true
        errorMessage = null
        volumeChangeJob?.cancel()
        volumeChangeJob = scope.launch {
            delay(180)
            val targetVolume = sonosVolume.toInt().coerceIn(0, 100)
            isVolumeChanging = true
            runCatching {
                sonosController.setVolume(room, targetVolume)
            }.onSuccess {
                actionMessage = "已将 ${room.roomName} 音量设置为 $targetVolume"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
                volumeSyncKey += 1
            }
            isVolumeChanging = false
        }
    }

    DisposableEffect(activity, selectedSonosRoom, sonosVolume, hasLoadedSonosVolume) {
        activity?.onHardwareVolumeStep = { step ->
            val room = selectedSonosRoom
            if (room == null) {
                false
            } else {
            val baseVolume = if (hasLoadedSonosVolume) sonosVolume else 0f
            val targetVolume = (baseVolume + step).coerceIn(0f, 100f)
            applyVolumeChange(room, targetVolume)
            true
            }
        }

        onDispose {
            if (activity?.onHardwareVolumeStep != null) {
                activity.onHardwareVolumeStep = null
            }
        }
    }

    fun openAlbumDetail(album: PlexAlbum) {
        isLoading = true
        errorMessage = null
        actionMessage = null
        selectedAlbum = album
        scope.launch {
            runCatching {
                client().fetchAlbumTrackStreams(album)
            }.onSuccess {
                trackResult = it
                navigateTo(AppSection.AlbumDetail)
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isLoading = false
        }
    }

    fun openArtistAlbums(artist: ArtistSummary) {
        selectedArtistName = artist.name
        if (activeSection == AppSection.ArtistAlbums) return
        navigateTo(AppSection.ArtistAlbums)
    }

    fun openArtistAlbumsByName(artistName: String?) {
        val normalizedName = artistName?.trim().orEmpty().ifBlank { "未知艺人" }
        selectedArtistName = normalizedName
        navigateTo(AppSection.ArtistAlbums)
    }

    fun replaceAlbumInState(updatedAlbum: PlexAlbum) {
        allAlbums = allAlbums.map { album ->
            if (album.ratingKey == updatedAlbum.ratingKey) updatedAlbum else album
        }
        scope.launch(Dispatchers.IO) {
            albumLocalStore.upsertAlbum(updatedAlbum)
        }
        if (selectedAlbum?.ratingKey == updatedAlbum.ratingKey) {
            selectedAlbum = updatedAlbum
        }
        trackResult = trackResult?.let { current ->
            if (current.album.ratingKey == updatedAlbum.ratingKey) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
        miniPlayerState = miniPlayerState?.let { current ->
            if (current.album.ratingKey == updatedAlbum.ratingKey) {
                current.copy(album = updatedAlbum)
            } else {
                current
            }
        }
    }

    fun replaceTrackInState(updatedTrack: PlexTrackStream) {
        trackResult = trackResult?.let { current ->
            if (current.tracks.none { it.ratingKey == updatedTrack.ratingKey }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (track.ratingKey == updatedTrack.ratingKey) updatedTrack else track
                    }
                )
            }
        }
        miniPlayerState = miniPlayerState?.let { current ->
            if (current.tracks.none { it.ratingKey == updatedTrack.ratingKey }) {
                current
            } else {
                current.copy(
                    tracks = current.tracks.map { track ->
                        if (track.ratingKey == updatedTrack.ratingKey) updatedTrack else track
                    }
                )
            }
        }
    }

    fun toggleAlbumFavorite(album: PlexAlbum) {
        if (isFavoriteMutationLoading) return

        val targetFavorite = !album.isFavorite
        isFavoriteMutationLoading = true
        errorMessage = null
        actionMessage = null
        scope.launch {
            runCatching {
                client().setFavorite(album.ratingKey, targetFavorite)
            }.onSuccess {
                replaceAlbumInState(
                    album.copy(userRating = if (targetFavorite) 10f else null)
                )
                actionMessage = if (targetFavorite) {
                    "已收藏专辑：${album.title}"
                } else {
                    "已取消收藏专辑：${album.title}"
                }
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isFavoriteMutationLoading = false
        }
    }

    fun toggleTrackFavorite(track: PlexTrackStream) {
        if (isFavoriteMutationLoading) return

        val targetFavorite = !track.isFavorite
        isFavoriteMutationLoading = true
        errorMessage = null
        actionMessage = null
        scope.launch {
            runCatching {
                client().setFavorite(track.ratingKey, targetFavorite)
            }.onSuccess {
                replaceTrackInState(
                    track.copy(userRating = if (targetFavorite) 10f else null)
                )
                actionMessage = if (targetFavorite) {
                    "已收藏单曲：${track.title}"
                } else {
                    "已取消收藏单曲：${track.title}"
                }
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
            }
            isFavoriteMutationLoading = false
        }
    }

    val favoriteAlbums = allAlbums
        .filter(PlexAlbum::isFavorite)
        .sortedWith(
            compareBy<PlexAlbum> { album ->
                recentPlayedAlbumKeys.indexOf(album.ratingKey).takeIf { it >= 0 } ?: Int.MAX_VALUE
            }
                .thenByDescending { it.lastViewedAtEpochSeconds ?: 0L }
                .thenBy { it.title.lowercase() }
        )
    val recentAddedAlbums = allAlbums
        .sortedWith(
            compareByDescending<PlexAlbum> { it.addedAtEpochSeconds ?: 0L }
                .thenBy { it.title.lowercase() }
        )
    val recentAddedTopHundred = recentAddedAlbums.take(100)
    var artists by remember { mutableStateOf<List<ArtistSummary>>(emptyList()) }

    LaunchedEffect(connectionPreferences) {
        if (connectionPreferences.token.isNullOrBlank()) return@LaunchedEffect
        try {
            val plexArtists = client().fetchArtists().artists
            val artistInfoMap = albumLocalStore.getArtistSummaries().associateBy { it.name }
            artists = plexArtists.map { plexArtist ->
                val info = artistInfoMap[plexArtist.title]
                ArtistSummary(
                    name = plexArtist.title,
                    coverUrl = plexArtist.thumbUrl,
                    albumCount = info?.albumCount ?: 0,
                )
            }.filter { it.albumCount > 0 }
        } catch (e: Exception) {
            artists = albumLocalStore.getArtistSummaries().map { info ->
                ArtistSummary(
                    name = info.name,
                    coverUrl = info.coverUrl,
                    albumCount = info.albumCount,
                )
            }
        }
    }
    val selectedArtist = selectedArtistName?.let { selectedName ->
        remember(selectedName, allAlbums) {
            val albums = albumLocalStore.getAlbumsByArtist(selectedName)
            val summary = artists.firstOrNull { it.name == selectedName }
            summary?.let {
                ArtistSummary(
                    name = it.name,
                    coverUrl = it.coverUrl,
                    albumCount = it.albumCount,
                    albums = albums
                )
            }
        }
    }

    BackHandler(enabled = navigationStack.size > 1) {
        when (activeSection) {
            AppSection.AlbumDetail,
            AppSection.ArtistAlbums,
            AppSection.PlaybackDetail,
            AppSection.FavoriteCollection,
            AppSection.RecentAdded,
            AppSection.AllAlbums,
            AppSection.Artists,
            AppSection.Settings -> navigateBack()
            AppSection.Home -> Unit
        }
    }

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
            if (activeSection == AppSection.AllAlbums) {
                AllAlbumsSection(
                    albums = if (albumSearchQuery.isBlank()) allAlbums else albumSearchResults,
                    selectedAlbum = selectedAlbum,
                    searchQuery = albumSearchQuery,
                    isSearchLoading = isAlbumSearchLoading,
                    bottomContentPadding = contentBottomPadding,
                    onSearchQueryChange = { albumSearchQuery = it },
                    onBack = ::navigateBack,
                    onAlbumClick = ::openAlbumDetail,
                )
            } else {
                if (activeSection == AppSection.Artists) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                    ) {
                        ArtistsSection(
                            artists = artists,
                            presentation = artistPresentation,
                            onPresentationChange = { artistPresentation = it },
                            onGoHome = { switchPrimarySection(AppSection.Home) },
                            onArtistClick = ::openArtistAlbums,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (activeSection) {
                            AppSection.Home -> HomeSection(
                                connectionPreferences = connectionPreferences,
                                allAlbums = allAlbums,
                                favoriteAlbums = favoriteAlbums,
                                recentAddedAlbums = recentAddedTopHundred,
                                selectedAlbum = selectedAlbum,
                                selectedSonosRoom = selectedSonosRoom,
                                lastAlbumSyncEpochMillis = lastAlbumSyncEpochMillis,
                                isLoading = isLoading,
                                errorMessage = errorMessage,
                                actionMessage = actionMessage,
                                onRefreshFavorites = { scope.launch { refreshAlbums() } },
                                onAlbumClick = ::openAlbumDetail,
                                onOpenAllAlbums = {
                                    albumSearchQuery = ""
                                    navigateTo(AppSection.AllAlbums)
                                },
                                onOpenFavorites = { navigateTo(AppSection.FavoriteCollection) },
                                onOpenRecentAdded = { navigateTo(AppSection.RecentAdded) },
                            )
                            AppSection.AlbumDetail -> AlbumDetailSection(
                            trackResult = trackResult,
                            selectedRoom = selectedSonosRoom,
                            isPlaybackLoading = isPlaybackCommandLoading,
                            isFavoriteLoading = isFavoriteMutationLoading,
                            onReturnHome = ::navigateBack,
                            onArtistClick = ::openArtistAlbumsByName,
                            onToggleAlbumFavorite = ::toggleAlbumFavorite,
                            onToggleTrackFavorite = ::toggleTrackFavorite,
                            onPlayAlbum = { albumTrackResult: PlexAlbumTracksResult, room: SonosRoom ->
                                startAlbumPlayback(albumTrackResult, room)
                            },
                            onPlayTrack = { album: PlexAlbum, track: PlexTrackStream, room: SonosRoom ->
                                val currentTrackResult = trackResult
                                val playlist = if (currentTrackResult?.album?.ratingKey == album.ratingKey) {
                                    currentTrackResult.tracks
                                } else {
                                    listOf(track)
                                }
                                val trackIndex = playlist.indexOfFirst { it.ratingKey == track.ratingKey }.coerceAtLeast(0)
                                startSingleTrackPlayback(album, playlist, trackIndex, room)
                            },
                        )
                        AppSection.PlaybackDetail -> PlaybackDetailSection(
                            state = miniPlayerState,
                            hasLoadedVolume = hasLoadedSonosVolume && selectedSonosRoom?.coordinatorUuid == miniPlayerState?.room?.coordinatorUuid,
                            isVolumeLoading = isVolumeLoading,
                            isVolumeChanging = isVolumeChanging,
                            volume = sonosVolume,
                            isLoading = isPlaybackCommandLoading,
                            onBack = ::navigateBack,
                            onPrevious = {
                                val state = miniPlayerState ?: return@PlaybackDetailSection
                                val newIndex = (state.currentIndex - 1).coerceAtLeast(0)
                                if (newIndex != state.currentIndex) {
                                    val currentTrackResult = trackResult?.takeIf { it.album.ratingKey == state.album.ratingKey }
                                    if (currentTrackResult != null) {
                                        startAlbumPlayback(currentTrackResult, state.room, newIndex)
                                    } else {
                                        startSingleTrackPlayback(state.album, state.tracks, newIndex, state.room)
                                    }
                                }
                            },
                            onNext = {
                                val state = miniPlayerState ?: return@PlaybackDetailSection
                                val newIndex = (state.currentIndex + 1).coerceAtMost(state.tracks.lastIndex)
                                if (newIndex != state.currentIndex) {
                                    val currentTrackResult = trackResult?.takeIf { it.album.ratingKey == state.album.ratingKey }
                                    if (currentTrackResult != null) {
                                        startAlbumPlayback(currentTrackResult, state.room, newIndex)
                                    } else {
                                        startSingleTrackPlayback(state.album, state.tracks, newIndex, state.room)
                                    }
                                }
                            },
                            onVolumeChange = { state: MiniPlayerState, newValue: Float ->
                                selectedSonosRoom = state.room
                                applyVolumeChange(state.room, newValue)
                            },
                            onTogglePause = { state: MiniPlayerState ->
                                isPlaybackCommandLoading = true
                                scope.launch {
                                    runCatching {
                                        if (state.isPaused) sonosController.resume(state.room) else sonosController.pause(state.room)
                                    }.onSuccess {
                                        miniPlayerState = miniPlayerState?.copy(isPaused = !state.isPaused)
                                        actionMessage = if (state.isPaused) "已继续播放" else "已暂停播放"
                                    }.onFailure {
                                        errorMessage = it.message ?: "未知错误"
                                    }
                                    isPlaybackCommandLoading = false
                                }
                            },
                        )
                        AppSection.ArtistAlbums -> ArtistAlbumsSection(
                            artist = selectedArtist,
                            selectedAlbum = selectedAlbum,
                            onBack = ::navigateBack,
                            onAlbumClick = ::openAlbumDetail,
                        )
                        AppSection.FavoriteCollection -> AlbumCollectionSection(
                            title = "收藏专辑",
                            subtitle = "按最近播放顺序优先展示全部收藏专辑，点击封面可以继续进入专辑详情。",
                            albums = favoriteAlbums,
                            selectedAlbum = selectedAlbum,
                            onBack = ::navigateBack,
                            onAlbumClick = ::openAlbumDetail,
                        )
                        AppSection.RecentAdded -> AlbumCollectionSection(
                            title = "最近添加的 100 张专辑",
                            subtitle = "按 Plex 最近添加时间倒序展示，点击封面可直接进入专辑详情。",
                            albums = recentAddedTopHundred,
                            selectedAlbum = selectedAlbum,
                            onBack = ::navigateBack,
                            onAlbumClick = ::openAlbumDetail,
                        )
                        AppSection.Settings -> SettingsSection(
                            rooms = sonosRooms,
                            selectedRoom = selectedSonosRoom,
                            isSonosLoading = isSonosLoading,
                            discoveryAttempted = sonosDiscoveryAttempted,
                            username = username,
                            password = password,
                            token = token,
                            server = server,
                            baseUrl = baseUrl,
                            onDiscoverSonos = ::refreshSonosRooms,
                            onSelectRoom = {
                                selectedSonosRoom = it
                                actionMessage = "已选择 Sonos 房间: ${it.roomName}"
                                volumeSyncKey += 1
                            },
                            onUsernameChange = { username = it },
                            onPasswordChange = { password = it },
                            onTokenChange = { token = it },
                            onServerChange = { server = it },
                            onBaseUrlChange = { baseUrl = it },
                            onSave = { saveSettings(refreshAfterSave = false) },
                            onSaveAndRefresh = { saveSettings(refreshAfterSave = true) },
                        )
                        AppSection.Artists -> Unit
                        AppSection.AllAlbums -> Unit
                    }
                }
            }
        }

        BottomNavigationBar(
            primarySection = primarySection,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            onSectionChange = { section: AppSection ->
                when (section) {
                    AppSection.Home,
                    AppSection.Artists,
                    AppSection.Settings -> switchPrimarySection(section)
                    else -> Unit
                }
            },
        )

        if (activeSection != AppSection.PlaybackDetail) {
            miniPlayerState?.let { state ->
            BottomMiniPlayer(
                state = state,
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
                        navigateTo(AppSection.PlaybackDetail)
                    }
                },
                onTogglePause = {
                    isPlaybackCommandLoading = true
                    scope.launch {
                        runCatching {
                            if (state.isPaused) sonosController.resume(state.room) else sonosController.pause(state.room)
                        }.onSuccess {
                            miniPlayerState = miniPlayerState?.copy(isPaused = !state.isPaused)
                            actionMessage = if (state.isPaused) "已继续播放" else "已暂停播放"
                        }.onFailure {
                            errorMessage = it.message ?: "未知错误"
                        }
                        isPlaybackCommandLoading = false
                    }
                },
            )
            }
        }
    }
}
}

