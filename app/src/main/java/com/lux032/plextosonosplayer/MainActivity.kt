package com.lux032.plextosonosplayer

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbum
import com.lux032.plextosonosplayer.plex.PlexAlbumTracksResult
import com.lux032.plextosonosplayer.plex.PlexAlbumsResult
import com.lux032.plextosonosplayer.plex.PlexAuthConfig
import com.lux032.plextosonosplayer.plex.PlexClient
import com.lux032.plextosonosplayer.plex.PlexTrackStream
import com.lux032.plextosonosplayer.sonos.SonosController
import com.lux032.plextosonosplayer.sonos.SonosDiscovery
import com.lux032.plextosonosplayer.sonos.SonosRoom
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

private enum class AppSection {
    Home,
    AlbumDetail,
    PlaybackDetail,
    FavoriteCollection,
    RecentAdded,
    Settings,
}

private enum class MessageTone {
    Info,
    Error,
}

private data class MiniPlayerState(
    val album: PlexAlbum,
    val tracks: List<PlexTrackStream>,
    val currentIndex: Int,
    val room: SonosRoom,
    val isPaused: Boolean,
)

private enum class PlayerIcon {
    Back,
    Previous,
    Play,
    Pause,
    Next,
}

private object AppColors {
    val BackgroundTop = Color(0xFF050505)
    val BackgroundBottom = Color(0xFF151515)
    val Surface = Color(0xFF141414)
    val SurfaceAlt = Color(0xFF1B1B1B)
    val SurfaceMuted = Color(0xFF222222)
    val SurfaceStrong = Color(0xFF0D0D0D)
    val Border = Color.White.copy(alpha = 0.10f)
    val BorderStrong = Color.White.copy(alpha = 0.18f)
    val TextPrimary = Color(0xFFF5F5F5)
    val TextSecondary = Color(0xFFB7B7B7)
    val TextTertiary = Color(0xFF8E8E8E)
    val Accent = Color(0xFFF1F1F1)
    val AccentMuted = Color(0xFF2A2A2A)
    val ErrorBg = Color(0xFF2A1616)
    val ErrorText = Color(0xFFFFB4AB)
}

private object AlbumArtworkImageLoader {
    @Volatile
    private var instance: ImageLoader? = null

    fun get(context: android.content.Context): ImageLoader {
        instance?.let { return it }

        return synchronized(this) {
            instance?.let { return@synchronized it }

            val imageLoader = ImageLoader.Builder(context.applicationContext)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context.applicationContext, 0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(context.applicationContext.cacheDir.resolve("coil_album_artwork").toOkioPath())
                        .maxSizePercent(0.08)
                        .build()
                }
                .build()

            instance = imageLoader
            imageLoader
        }
    }
}

@Composable
fun PlexAlbumScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val appPreferences = remember(context) { AppPreferences(context) }
    val sonosController = remember { SonosController() }
    val initialPreferences = remember(appPreferences) { appPreferences.loadPlexConnectionPreferences() }

    val navigationStack = remember { mutableStateListOf(AppSection.Home) }
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
    var libraryAlbumsResult by remember { mutableStateOf<PlexAlbumsResult?>(null) }
    var selectedAlbum by remember { mutableStateOf<PlexAlbum?>(null) }
    var trackResult by remember { mutableStateOf<PlexAlbumTracksResult?>(null) }
    var sonosRooms by remember { mutableStateOf<List<SonosRoom>>(emptyList()) }
    var selectedSonosRoom by remember { mutableStateOf<SonosRoom?>(null) }
    var sonosDiscoveryAttempted by remember { mutableStateOf(false) }
    var sonosVolume by remember { mutableFloatStateOf(0f) }
    var hasLoadedSonosVolume by remember { mutableStateOf(false) }
    var isVolumeLoading by remember { mutableStateOf(false) }
    var isVolumeChanging by remember { mutableStateOf(false) }
    var isPlaybackCommandLoading by remember { mutableStateOf(false) }
    var volumeSyncKey by remember { mutableIntStateOf(0) }
    var volumeChangeJob by remember { mutableStateOf<Job?>(null) }
    var albumPlaybackJob by remember { mutableStateOf<Job?>(null) }
    var miniPlayerState by remember { mutableStateOf<MiniPlayerState?>(null) }
    var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
    var recentPlayedAlbumKeys by remember { mutableStateOf(appPreferences.loadRecentPlayedAlbumKeys()) }
    var hasAttemptedInitialAlbumSync by rememberSaveable { mutableStateOf(false) }

    val activeSection = navigationStack.lastOrNull() ?: AppSection.Home
    val contentBottomPadding = with(density) {
        if (miniPlayerState != null && activeSection != AppSection.PlaybackDetail) {
            miniPlayerHeightPx.toDp() + 32.dp
        } else {
            16.dp
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
            runCatching {
                var didStartFirstTrack = false
                playAlbumSequentially(
                    sonosController = sonosController,
                    room = room,
                    trackResult = albumTrackResult,
                    startIndex = startIndex,
                    onTrackChanged = { index, track ->
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
            }.onSuccess {
                actionMessage = "专辑播放结束：${albumTrackResult.album.title}"
            }.onFailure {
                errorMessage = it.message ?: "未知错误"
                isPlaybackCommandLoading = false
            }
        }
    }

    suspend fun refreshAlbums(showLoading: Boolean = true) {
        if (!connectionPreferences.hasCredentials()) {
            libraryAlbumsResult = null
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
            libraryAlbumsResult = it
            if (selectedAlbum?.ratingKey?.let { key -> it.albums.none { album -> album.ratingKey == key } } == true) {
                selectedAlbum = null
                trackResult = null
            }
        }.onFailure {
            libraryAlbumsResult = null
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
                if (libraryAlbumsResult != null) {
                    replaceWith(AppSection.Home)
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
        if (!hasAttemptedInitialAlbumSync) {
            hasAttemptedInitialAlbumSync = true
            if (connectionPreferences.hasCredentials() && libraryAlbumsResult == null && !isLoading) {
                refreshAlbums()
            }
        }
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

    val allAlbums = libraryAlbumsResult?.albums.orEmpty()
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

    BackHandler(enabled = activeSection != AppSection.Home) {
        when (activeSection) {
            AppSection.AlbumDetail -> replaceWith(AppSection.Home)
            AppSection.PlaybackDetail,
            AppSection.FavoriteCollection,
            AppSection.RecentAdded,
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = contentBottomPadding),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (activeSection == AppSection.Home || activeSection == AppSection.Settings) {
                    TopNavigation(
                        activeSection = activeSection,
                        onSectionChange = { section ->
                            when (section) {
                                AppSection.Home -> replaceWith(AppSection.Home)
                                AppSection.Settings -> {
                                    if (activeSection != AppSection.Settings) {
                                        navigateTo(AppSection.Settings)
                                    }
                                }
                                else -> Unit
                            }
                        },
                    )
                }

                when (activeSection) {
                    AppSection.Home -> HomeSection(
                        connectionPreferences = connectionPreferences,
                        favoriteAlbums = favoriteAlbums,
                        recentAddedAlbums = recentAddedTopHundred,
                        selectedAlbum = selectedAlbum,
                        selectedSonosRoom = selectedSonosRoom,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        actionMessage = actionMessage,
                        onRefreshFavorites = { scope.launch { refreshAlbums() } },
                        onAlbumClick = ::openAlbumDetail,
                        onOpenFavorites = { navigateTo(AppSection.FavoriteCollection) },
                        onOpenRecentAdded = { navigateTo(AppSection.RecentAdded) },
                    )
                    AppSection.AlbumDetail -> AlbumDetailSection(
                        trackResult = trackResult,
                        selectedRoom = selectedSonosRoom,
                        isLoading = isPlaybackCommandLoading,
                        onReturnHome = { replaceWith(AppSection.Home) },
                        onPlayAlbum = { albumTrackResult, room ->
                            startAlbumPlayback(albumTrackResult, room)
                        },
                        onPlayTrack = { album, track, room ->
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
                        onVolumeChange = { state, newValue ->
                            selectedSonosRoom = state.room
                            applyVolumeChange(state.room, newValue)
                        },
                        onTogglePause = { state ->
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
                }
            }
        }

        if (activeSection != AppSection.PlaybackDetail) {
            miniPlayerState?.let { state ->
            BottomMiniPlayer(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .navigationBarsPadding()
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

@Composable
private fun HomeSection(
    connectionPreferences: PlexConnectionPreferences,
    favoriteAlbums: List<PlexAlbum>,
    recentAddedAlbums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    selectedSonosRoom: SonosRoom?,
    isLoading: Boolean,
    errorMessage: String?,
    actionMessage: String?,
    onRefreshFavorites: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenRecentAdded: () -> Unit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }

    LaunchedEffect(favoriteAlbums, recentAddedAlbums) {
        (favoriteAlbums.take(9) + recentAddedAlbums.take(9))
            .mapNotNull(PlexAlbum::thumbUrl)
            .distinct()
            .forEach { imageUrl ->
                imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(imageUrl)
                        .build()
                )
            }
    }

    HomeStatusCard(
        selectedRoom = selectedSonosRoom,
        isLoading = isLoading,
        onRefresh = onRefreshFavorites,
    )

    errorMessage?.let { MessageCard(label = "错误", message = it, tone = MessageTone.Error) }
    actionMessage?.let { MessageCard(label = "状态", message = it, tone = MessageTone.Info) }

    if (isLoading) {
        LoadingCard(message = "正在从 Plex 同步专辑")
    }

    if (!connectionPreferences.hasCredentials()) {
        EmptyStateCard(
            title = "先在设置里填写 Plex 服务器",
            body = "首页现在只保留封面展示和播放链路，连接信息已移到设置页并支持持久化。",
            actionLabel = "刷新首页",
            onAction = onRefreshFavorites,
        )
        return
    }

    if (!isLoading && favoriteAlbums.isEmpty() && errorMessage == null) {
        EmptyStateCard(
            title = "还没有检测到已收藏专辑",
            body = "当前按 Plex 返回的 userRating 识别收藏专辑。你可以先在 Plex 里标记后再刷新首页。",
            actionLabel = "重新获取",
            onAction = onRefreshFavorites,
        )
    }

    if (favoriteAlbums.isNotEmpty()) {
        AlbumPreviewGridSection(
            title = "收藏专辑",
            subtitle = "按最近播放顺序优先展示，首页预览前 9 张，进入后可查看全部收藏。",
            albums = favoriteAlbums.take(9),
            selectedAlbum = selectedAlbum,
            actionLabel = "查看全部",
            onAction = onOpenFavorites,
            onAlbumClick = onAlbumClick,
        )
    }
    if (recentAddedAlbums.isNotEmpty()) {
        AlbumPreviewGridSection(
            title = "最近添加",
            subtitle = "这里预览最新 9 张，进入后可查看最近添加的前 100 张。",
            albums = recentAddedAlbums.take(9),
            selectedAlbum = selectedAlbum,
            actionLabel = "查看前 100 张",
            onAction = onOpenRecentAdded,
            onAlbumClick = onAlbumClick,
        )
    }
}

@Composable
private fun AlbumPreviewGridSection(
    title: String,
    subtitle: String,
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    onAlbumClick: (PlexAlbum) -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, color = AppColors.TextSecondary)
                }
            }
            if (actionLabel != null && onAction != null) {
                OutlinedButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
        AlbumGrid(
            albums = albums,
            columns = 3,
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
            compact = true,
        )
    }
}

@Composable
private fun AlbumCollectionSection(
    title: String,
    subtitle: String,
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
    val heroAlbum = albums.firstOrNull()
    if (heroAlbum == null) {
        EmptyStateCard(
            title = title,
            body = "这里还没有可展示的专辑。",
            actionLabel = "返回首页",
            onAction = onBack,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text("返回首页")
        }
        AsyncAlbumArtwork(
            imageUrl = heroAlbum.thumbUrl,
            title = heroAlbum.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .clip(RoundedCornerShape(16.dp)),
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "$subtitle 共 ${albums.size} 张。",
                color = AppColors.TextSecondary,
            )
            selectedAlbum?.let {
                Text(
                    text = "当前高亮：${it.title}",
                    color = AppColors.TextTertiary,
                )
            }
        }
        AlbumGrid(
            albums = albums,
            columns = 2,
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
            compact = false,
        )
    }
}

@Composable
private fun PlaybackDetailSection(
    state: MiniPlayerState?,
    hasLoadedVolume: Boolean,
    isVolumeLoading: Boolean,
    isVolumeChanging: Boolean,
    volume: Float,
    isLoading: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onVolumeChange: (MiniPlayerState, Float) -> Unit,
    onTogglePause: (MiniPlayerState) -> Unit,
) {
    val currentState = state
    val currentTrack = currentState?.tracks?.getOrNull(currentState.currentIndex)
    if (currentState == null || currentTrack == null) {
        EmptyStateCard(
            title = "当前没有播放中的专辑",
            body = "先从专辑详情里播放内容，底部栏出现后再进入播放详情。",
            actionLabel = "返回首页",
            onAction = onBack,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AsyncAlbumArtwork(
            imageUrl = currentState.album.thumbUrl,
            title = currentState.album.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp)),
            contentScale = ContentScale.Crop,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                currentTrack.title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${currentState.album.title} · ${currentState.album.artistName ?: "未知艺人"}",
                color = AppColors.TextSecondary,
            )
            Text(
                text = "${currentState.room.roomName} · ${currentState.currentIndex + 1}/${currentState.tracks.size}",
                color = AppColors.TextTertiary,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerControlButton(
                icon = PlayerIcon.Previous,
                onClick = onPrevious,
                enabled = currentState.currentIndex > 0 && !isLoading,
            )
            Spacer(modifier = Modifier.width(32.dp))
            PlayerControlButton(
                icon = if (currentState.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                onClick = { onTogglePause(currentState) },
                enabled = !isLoading,
                highlighted = true,
            )
            Spacer(modifier = Modifier.width(32.dp))
            PlayerControlButton(
                icon = PlayerIcon.Next,
                onClick = onNext,
                enabled = currentState.currentIndex < currentState.tracks.lastIndex && !isLoading,
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = when {
                    isVolumeLoading -> "音量读取中"
                    isVolumeChanging -> "正在设置音量 ${volume.toInt()}"
                    hasLoadedVolume -> "音量 ${volume.toInt()}"
                    else -> "音量未读取"
                },
                color = AppColors.TextSecondary,
            )
            Slider(
                value = volume,
                onValueChange = { onVolumeChange(currentState, it) },
                valueRange = 0f..100f,
                enabled = hasLoadedVolume && !isVolumeLoading,
            )
        }
    }
}

@Composable
private fun AlbumDetailSection(
    trackResult: PlexAlbumTracksResult?,
    selectedRoom: SonosRoom?,
    isLoading: Boolean,
    onReturnHome: () -> Unit,
    onPlayAlbum: (PlexAlbumTracksResult, SonosRoom) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream, SonosRoom) -> Unit,
) {
    val currentTrackResult = trackResult
    if (currentTrackResult == null) {
        EmptyStateCard(
            title = "还没有打开专辑",
            body = "先从首页选择一张收藏专辑，再进入专辑详情页。",
            actionLabel = "返回首页",
            onAction = onReturnHome,
        )
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AsyncAlbumArtwork(
            imageUrl = currentTrackResult.album.thumbUrl,
            title = currentTrackResult.album.title,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(16.dp)),
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                currentTrackResult.album.title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = buildString {
                    append(currentTrackResult.album.artistName ?: "未知艺人")
                    currentTrackResult.album.year?.let {
                        append(" · ")
                        append(it)
                    }
                    append(" · ${currentTrackResult.tracks.size} 首")
                },
                color = AppColors.TextSecondary,
            )
            Text(
                text = selectedRoom?.let { "当前将推送到 ${it.roomName}" } ?: "尚未选择 Sonos 房间，请先到设置页选择房间。",
                color = AppColors.TextTertiary,
            )
        }

        if (selectedRoom != null) {
            Button(
                onClick = { onPlayAlbum(currentTrackResult, selectedRoom) },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.SurfaceStrong,
                ),
            ) {
                Text("连续播放整张专辑")
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            currentTrackResult.tracks.forEachIndexed { index, track ->
                Surface(
                    modifier = Modifier.clickable(
                        enabled = selectedRoom != null && !isLoading,
                    ) {
                        onPlayTrack(currentTrackResult.album, track, selectedRoom!!)
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = AppColors.Surface,
                    border = BorderStroke(1.dp, AppColors.Border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("${index + 1}. ${track.title}", color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                            track.durationMillis?.let {
                                Text(formatDuration(it), color = AppColors.TextTertiary)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (selectedRoom != null) {
                            Button(
                                onClick = { onPlayTrack(currentTrackResult.album, track, selectedRoom) },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.AccentMuted,
                                    contentColor = AppColors.TextPrimary,
                                ),
                            ) {
                                Text("播放")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopNavigation(
    activeSection: AppSection,
    onSectionChange: (AppSection) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavigationChip(
                label = "首页",
                selected = activeSection == AppSection.Home,
                onClick = { onSectionChange(AppSection.Home) },
                modifier = Modifier.weight(1f),
            )
            NavigationChip(
                label = "设置",
                selected = activeSection == AppSection.Settings,
                onClick = { onSectionChange(AppSection.Settings) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun NavigationChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) AppColors.Accent else Color.Transparent
    val contentColor = if (selected) AppColors.SurfaceStrong else AppColors.TextSecondary
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, if (selected) Color.Transparent else AppColors.Border),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = label, color = contentColor, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun HomeStatusCard(
    selectedRoom: SonosRoom?,
    isLoading: Boolean,
    onRefresh: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = "当前输出房间",
                    color = AppColors.TextTertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = selectedRoom?.roomName ?: "还没有选定 Sonos 房间",
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Button(
                onClick = onRefresh,
                enabled = !isLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.SurfaceStrong,
                ),
            ) {
                Text("刷新首页")
            }
        }
    }
}

@Composable
private fun MessageCard(label: String, message: String, tone: MessageTone) {
    val container = if (tone == MessageTone.Error) AppColors.ErrorBg else AppColors.Surface
    val content = if (tone == MessageTone.Error) AppColors.ErrorText else AppColors.TextPrimary
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = container,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(label, color = content, fontWeight = FontWeight.Bold)
            Text(message, color = content)
        }
    }
}

@Composable
private fun LoadingCard(message: String) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.4.dp,
                color = AppColors.Accent,
            )
            Text(message, color = AppColors.TextPrimary)
        }
    }
}

@Composable
private fun EmptyStateCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            Text(body, color = AppColors.TextSecondary)
            Button(
                onClick = onAction,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.SurfaceStrong,
                ),
            ) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun SonosSettingsCard(
    rooms: List<SonosRoom>,
    selectedRoom: SonosRoom?,
    isLoading: Boolean,
    discoveryAttempted: Boolean,
    onDiscover: () -> Unit,
    onSelectRoom: (SonosRoom) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Sonos 房间", style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
            Text("先在这里选择默认输出房间，专辑详情和播放详情都会直接使用它。", color = AppColors.TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onDiscover,
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = AppColors.SurfaceStrong,
                    ),
                ) {
                    Text("发现设备")
                }
            }

            if (discoveryAttempted && rooms.isEmpty() && !isLoading) {
                Text("当前未发现 Sonos 房间，请确认手机和音箱在同一局域网。", color = AppColors.ErrorText)
            }

            rooms.forEach { room ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) AppColors.SurfaceMuted else AppColors.SurfaceAlt,
                    border = BorderStroke(1.dp, if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) AppColors.BorderStrong else AppColors.Border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectRoom(room) }
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(room.roomName, fontWeight = FontWeight.SemiBold, color = AppColors.TextPrimary)
                            Text(
                                if (room.memberCount > 1) "${room.memberCount} 个成员" else "单房间",
                                color = AppColors.TextSecondary,
                            )
                        }
                        Text(
                            if (room.coordinatorUuid == selectedRoom?.coordinatorUuid) "当前房间" else "选择",
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<PlexAlbum>,
    columns: Int,
    selectedAlbum: PlexAlbum?,
    onAlbumClick: (PlexAlbum) -> Unit,
    compact: Boolean,
) {
    val spacing = if (compact) 10.dp else 14.dp
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val rows = (albums.size + columns - 1) / columns
        val cellSize = (maxWidth - spacing * (columns - 1)) / columns
        val gridHeight = if (rows > 0) {
            val cardHeight = if (compact) {
                cellSize
            } else {
                cellSize + 78.dp
            }
            (cardHeight * rows) + (spacing * (rows - 1))
        } else {
            0.dp
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight),
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(spacing),
            horizontalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(albums, key = { it.ratingKey }) { album ->
                AlbumCoverCard(
                    album = album,
                    selected = selectedAlbum?.ratingKey == album.ratingKey,
                    onClick = { onAlbumClick(album) },
                    compact = compact,
                )
            }
        }
    }
}

@Composable
private fun AlbumCoverCard(
    album: PlexAlbum,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(if (compact) 18.dp else 16.dp),
        color = if (compact) Color.Transparent else Color.Transparent,
        border = BorderStroke(0.dp, Color.Transparent),
        shadowElevation = 0.dp,
    ) {
        if (compact) {
            AsyncAlbumArtwork(
                imageUrl = album.thumbUrl,
                title = album.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(18.dp)),
            )
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncAlbumArtwork(
                    imageUrl = album.thumbUrl,
                    title = album.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = album.title,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = album.artistName ?: "未知艺人",
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Text(
                            text = "当前浏览",
                            color = AppColors.TextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomMiniPlayer(
    state: MiniPlayerState,
    modifier: Modifier = Modifier,
    onArtworkClick: () -> Unit,
    onTogglePause: () -> Unit,
) {
    val currentTrack = state.tracks.getOrNull(state.currentIndex) ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = AppColors.SurfaceStrong,
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onArtworkClick),
            ) {
                AsyncAlbumArtwork(
                    imageUrl = state.album.thumbUrl,
                    title = state.album.title,
                    modifier = Modifier.size(52.dp),
                )
            }
            Text(
                text = currentTrack.title,
                modifier = Modifier.weight(1f),
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PlayerControlButton(
                icon = if (state.isPaused) PlayerIcon.Play else PlayerIcon.Pause,
                onClick = onTogglePause,
                enabled = true,
                highlighted = true,
                sizeOverride = 54.dp,
                iconSizeOverride = 24.dp,
            )
        }
    }
}

@Composable
private fun PlayerControlButton(
    icon: PlayerIcon,
    onClick: () -> Unit,
    enabled: Boolean,
    highlighted: Boolean = false,
    sizeOverride: androidx.compose.ui.unit.Dp? = null,
    iconSizeOverride: androidx.compose.ui.unit.Dp? = null,
) {
    val containerColor = if (highlighted) AppColors.Accent else AppColors.SurfaceMuted
    val contentColor = if (highlighted) AppColors.SurfaceStrong else AppColors.TextPrimary
    val borderColor = if (highlighted) Color.Transparent else AppColors.Border
    val buttonSize = sizeOverride ?: if (highlighted) 64.dp else 52.dp
    val iconSize = iconSizeOverride ?: if (highlighted) 26.dp else 22.dp

    Surface(
        modifier = Modifier
            .size(buttonSize)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = containerColor,
        border = BorderStroke(1.dp, borderColor.copy(alpha = if (enabled) 1f else 0.35f)),
        shadowElevation = if (highlighted) 2.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            PlayerIconGraphic(
                icon = icon,
                tint = contentColor.copy(alpha = if (enabled) 1f else 0.35f),
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

@Composable
private fun TrackSection(
    trackResult: PlexAlbumTracksResult?,
    selectedRoom: SonosRoom?,
    isLoading: Boolean,
    onPlayAlbum: (PlexAlbumTracksResult, SonosRoom) -> Unit,
    onPlayTrack: (PlexAlbum, PlexTrackStream, SonosRoom) -> Unit,
) {
    val currentTrackResult = trackResult ?: return

    Surface(
        shape = RoundedCornerShape(26.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(currentTrackResult.album.title, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
            Text(
                text = "${currentTrackResult.album.artistName ?: "未知艺人"} · ${currentTrackResult.tracks.size} 首",
                color = AppColors.TextSecondary,
            )
            Text(
                text = selectedRoom?.let { "当前将推送到 ${it.roomName}" } ?: "尚未选择 Sonos 房间，先到上方控制台选择房间。",
                color = AppColors.TextTertiary,
            )
            if (selectedRoom != null) {
                Button(
                    onClick = { onPlayAlbum(currentTrackResult, selectedRoom) },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AppColors.Accent,
                        contentColor = AppColors.SurfaceStrong,
                    ),
                ) {
                    Text("连续播放整张专辑")
                }
            }
            currentTrackResult.tracks.forEachIndexed { index, track ->
                Surface(
                    modifier = Modifier.clickable(
                        enabled = selectedRoom != null && !isLoading,
                    ) {
                        onPlayTrack(currentTrackResult.album, track, selectedRoom!!)
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = AppColors.SurfaceAlt,
                    border = BorderStroke(1.dp, AppColors.Border),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Text("${index + 1}. ${track.title}", color = AppColors.TextPrimary, fontWeight = FontWeight.Medium)
                            track.durationMillis?.let {
                                Text(formatDuration(it), color = AppColors.TextTertiary)
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        if (selectedRoom != null) {
                            Button(
                                onClick = { onPlayTrack(currentTrackResult.album, track, selectedRoom) },
                                enabled = !isLoading,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AppColors.AccentMuted,
                                    contentColor = AppColors.TextPrimary,
                                ),
                            ) {
                                Text("推送")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    rooms: List<SonosRoom>,
    selectedRoom: SonosRoom?,
    isSonosLoading: Boolean,
    discoveryAttempted: Boolean,
    username: String,
    password: String,
    token: String,
    server: String,
    baseUrl: String,
    onDiscoverSonos: () -> Unit,
    onSelectRoom: (SonosRoom) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    onBaseUrlChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAndRefresh: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SonosSettingsCard(
            rooms = rooms,
            selectedRoom = selectedRoom,
            isLoading = isSonosLoading,
            discoveryAttempted = discoveryAttempted,
            onDiscover = onDiscoverSonos,
            onSelectRoom = onSelectRoom,
        )
        HorizontalDivider(color = AppColors.Border)
        Text("Plex 设置", style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
        Text(
            "Plex 连接信息放在 Sonos 设置下面。保存后会持久化到本地，下次启动会自动读取。",
            color = AppColors.TextSecondary,
        )
        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex 用户名") },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex 密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Token（可选，优先）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = server,
            onValueChange = onServerChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器名称（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = onBaseUrlChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Base URL（可选）") },
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.Accent,
                    contentColor = AppColors.SurfaceStrong,
                ),
            ) {
                Text("保存")
            }
            OutlinedButton(
                onClick = onSaveAndRefresh,
                modifier = Modifier.weight(1f),
                border = BorderStroke(1.dp, AppColors.BorderStrong),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AppColors.TextPrimary),
            ) {
                Text("保存并刷新首页")
            }
        }
    }
}

@Composable
private fun PlayerIconGraphic(
    icon: PlayerIcon,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        when (icon) {
            PlayerIcon.Play -> {
                val path = Path().apply {
                    moveTo(w * 0.28f, h * 0.18f)
                    lineTo(w * 0.28f, h * 0.82f)
                    lineTo(w * 0.80f, h * 0.50f)
                    close()
                }
                drawPath(path = path, color = tint)
            }
            PlayerIcon.Pause -> {
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.26f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.64f),
                )
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.58f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.16f, h * 0.64f),
                )
            }
            PlayerIcon.Previous -> {
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.16f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.64f),
                )
                val first = Path().apply {
                    moveTo(w * 0.74f, h * 0.18f)
                    lineTo(w * 0.40f, h * 0.50f)
                    lineTo(w * 0.74f, h * 0.82f)
                    close()
                }
                drawPath(path = first, color = tint)
            }
            PlayerIcon.Next -> {
                drawRect(
                    color = tint,
                    topLeft = androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.18f),
                    size = androidx.compose.ui.geometry.Size(w * 0.10f, h * 0.64f),
                )
                val first = Path().apply {
                    moveTo(w * 0.26f, h * 0.18f)
                    lineTo(w * 0.60f, h * 0.50f)
                    lineTo(w * 0.26f, h * 0.82f)
                    close()
                }
                drawPath(path = first, color = tint)
            }
            PlayerIcon.Back -> {
                val path = Path().apply {
                    moveTo(w * 0.68f, h * 0.16f)
                    lineTo(w * 0.34f, h * 0.50f)
                    lineTo(w * 0.68f, h * 0.84f)
                    lineTo(w * 0.68f, h * 0.66f)
                    lineTo(w * 0.86f, h * 0.66f)
                    lineTo(w * 0.86f, h * 0.34f)
                    lineTo(w * 0.68f, h * 0.34f)
                    close()
                }
                drawPath(path = path, color = tint)
            }
        }
    }
}

@Composable
private fun AsyncAlbumArtwork(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }

    if (imageUrl.isNullOrBlank()) {
        Box(
            modifier = modifier.background(
                brush = Brush.linearGradient(
                    colors = listOf(AppColors.SurfaceMuted, AppColors.Surface, AppColors.SurfaceStrong),
                )
            ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = title.take(1).ifBlank { "A" },
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        return
    }

    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(imageUrl)
            .memoryCacheKey(imageUrl)
            .diskCacheKey(imageUrl)
            .build(),
        imageLoader = imageLoader,
        contentDescription = title,
        modifier = modifier,
        contentScale = contentScale,
    )
}

private suspend fun playAlbumSequentially(
    sonosController: SonosController,
    room: SonosRoom,
    trackResult: PlexAlbumTracksResult,
    startIndex: Int = 0,
    onTrackChanged: (Int, PlexTrackStream) -> Unit,
) {
    val tracks = trackResult.tracks
    require(tracks.isNotEmpty()) { "专辑里没有可播放的单曲。" }

    tracks.drop(startIndex).forEachIndexed { relativeIndex, track ->
        val index = startIndex + relativeIndex
        sonosController.playTrack(
            room = room,
            trackUrl = track.streamUrl,
            title = track.title,
            albumTitle = trackResult.album.title,
        )
        onTrackChanged(index, track)

        if (index == tracks.lastIndex) {
            return
        }

        waitForTrackToFinish(
            sonosController = sonosController,
            room = room,
            expectedTrackUrl = track.streamUrl,
            expectedDurationMillis = track.durationMillis,
        )
    }
}

private suspend fun waitForTrackToFinish(
    sonosController: SonosController,
    room: SonosRoom,
    expectedTrackUrl: String,
    expectedDurationMillis: Long?,
) {
    val expectedDurationSeconds = expectedDurationMillis?.div(1_000)?.toInt()
    var stableProgressSeen = false

    while (true) {
        delay(1_000)
        val status = sonosController.getPlaybackStatus(room)
        val currentUri = status.currentTrackUri.orEmpty()
        val normalizedExpected = expectedTrackUrl.substringBefore('?')
        val normalizedCurrent = currentUri.substringBefore('?')

        if (normalizedCurrent.isNotBlank() && normalizedCurrent != normalizedExpected) {
            return
        }

        val relTimeSeconds = status.relTimeSeconds
        val trackDurationSeconds = status.trackDurationSeconds ?: expectedDurationSeconds

        if (relTimeSeconds != null && relTimeSeconds > 0) {
            stableProgressSeen = true
        }

        if (status.transportState.equals("STOPPED", ignoreCase = true) && stableProgressSeen) {
            return
        }

        if (relTimeSeconds != null && trackDurationSeconds != null && relTimeSeconds >= trackDurationSeconds - 1) {
            return
        }
    }
}

private fun PlexConnectionPreferences.hasCredentials(): Boolean =
    token.isNotBlank() || (username.isNotBlank() && password.isNotBlank())

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PlexToSonosPlayerTheme {
        PlexAlbumScreen()
    }
}
