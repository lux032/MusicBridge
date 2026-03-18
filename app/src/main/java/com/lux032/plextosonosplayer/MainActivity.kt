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

private enum class AppSection {
    Home,
    Artists,
    AlbumDetail,
    ArtistAlbums,
    PlaybackDetail,
    FavoriteCollection,
    RecentAdded,
    AllAlbums,
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

private enum class FavoriteIconStyle {
    Outline,
    Filled,
}

private enum class ArtistPresentation {
    Covers,
    List,
}

private enum class NavIcon {
    Home,
    Artists,
    Settings,
    Covers,
    List,
}

private data class ArtistSummary(
    val name: String,
    val coverUrl: String?,
    val albumCount: Int,
    val albums: List<PlexAlbum> = emptyList(),
)

private data class IndexedGroup<T>(
    val label: String,
    val items: List<T>,
)

private val KanaIndexOrder = listOf("あ", "か", "さ", "た", "な", "は", "ま", "や", "ら", "わ")
private val SupportedIndexOrder = listOf("0-9") + ('A'..'Z').map(Char::toString) + KanaIndexOrder + "#"

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

@Composable
private fun HomeSection(
    connectionPreferences: PlexConnectionPreferences,
    allAlbums: List<PlexAlbum>,
    favoriteAlbums: List<PlexAlbum>,
    recentAddedAlbums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    selectedSonosRoom: SonosRoom?,
    lastAlbumSyncEpochMillis: Long?,
    isLoading: Boolean,
    errorMessage: String?,
    actionMessage: String?,
    onRefreshFavorites: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
    onOpenAllAlbums: () -> Unit,
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
        lastAlbumSyncEpochMillis = lastAlbumSyncEpochMillis,
        isLoading = isLoading,
        onRefresh = onRefreshFavorites,
    )

    errorMessage?.let { MessageCard(label = "错误", message = it, tone = MessageTone.Error) }
    actionMessage?.let { MessageCard(label = "状态", message = it, tone = MessageTone.Info) }

    if (isLoading) {
        LoadingCard(message = "正在从 Plex 同步专辑")
    }

    if (!connectionPreferences.hasCredentials() && allAlbums.isEmpty()) {
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
            body = "当前按 Plex 返回的 userRating 识别收藏专辑。你也可以先打开任意专辑，在详情页直接点击收藏。",
            actionLabel = "重新获取",
            onAction = onRefreshFavorites,
        )
    }

    if (allAlbums.isNotEmpty()) {
        EntryActionCard(
            title = "全部专辑",
            body = "已同步 ${allAlbums.size} 张专辑",
            actionLabel = "查看全部并搜索",
            onAction = onOpenAllAlbums,
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
private fun EntryActionCard(
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 4.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
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
                Text(
                    text = body,
                    color = AppColors.TextSecondary,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            OutlinedButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
private fun ArtistsSection(
    artists: List<ArtistSummary>,
    presentation: ArtistPresentation,
    onPresentationChange: (ArtistPresentation) -> Unit,
    onGoHome: () -> Unit,
    onArtistClick: (ArtistSummary) -> Unit,
) {
    if (artists.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EmptyStateCard(
                title = "还没有可展示的歌手",
                body = "先到首页同步 Plex 音乐库，之后这里会按专辑自动聚合出歌手。",
                actionLabel = "去首页",
                onAction = onGoHome,
            )
        }
        return
    }

    val artistGroups = remember(artists) {
        buildIndexedGroups(artists) { it.name }
    }
    val scope = rememberCoroutineScope()

    when (presentation) {
        ArtistPresentation.Covers -> {
            val gridState = rememberLazyGridState()
            val jumpTargets = remember(artistGroups) {
                buildGridJumpTargets(
                    leadingItemCount = 1,
                    groups = artistGroups,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = 42.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(span = { GridItemSpan(2) }) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "歌手",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = AppColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "按索引分组展示，右侧可直接跳转到字母或五十音分组。",
                                    color = AppColors.TextSecondary,
                                )
                            }
                            ArtistPresentationToggle(
                                presentation = presentation,
                                onPresentationChange = onPresentationChange,
                            )
                        }
                    }
                    artistGroups.forEach { group ->
                        item(
                            key = "artist-grid-header-${group.label}",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            IndexSectionHeader(label = group.label)
                        }
                        items(
                            items = group.items,
                            key = { artist -> "artist-${artist.name}" },
                        ) { artist ->
                            ArtistCoverCard(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                            )
                        }
                    }
                }

                if (jumpTargets.size > 1) {
                    VerticalIndexBar(
                        labels = jumpTargets.map { it.first },
                        modifier = Modifier.padding(top = 56.dp, bottom = 12.dp),
                        onSelect = { label ->
                            jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                                scope.launch { gridState.animateScrollToItem(index) }
                            }
                        },
                    )
                }
            }
        }
        ArtistPresentation.List -> {
            val listState = rememberLazyListState()
            val jumpTargets = remember(artistGroups) {
                buildListJumpTargets(
                    leadingItemCount = 1,
                    groups = artistGroups,
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(end = 42.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "歌手",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = AppColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "按索引分组展示，右侧可直接跳转到字母或五十音分组。",
                                    color = AppColors.TextSecondary,
                                )
                            }
                            ArtistPresentationToggle(
                                presentation = presentation,
                                onPresentationChange = onPresentationChange,
                            )
                        }
                    }
                    artistGroups.forEach { group ->
                        item(key = "artist-list-header-${group.label}") {
                            IndexSectionHeader(label = group.label)
                        }
                        items(
                            items = group.items,
                            key = { artist -> "artist-list-${artist.name}" },
                        ) { artist ->
                            ArtistListRow(
                                artist = artist,
                                onClick = { onArtistClick(artist) },
                            )
                        }
                    }
                }

                if (jumpTargets.size > 1) {
                    VerticalIndexBar(
                        labels = jumpTargets.map { it.first },
                        modifier = Modifier.padding(top = 56.dp, bottom = 12.dp),
                        onSelect = { label ->
                            jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistCoverCard(
    artist: ArtistSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
        shadowElevation = 4.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (artist.coverUrl != null) {
                AsyncAlbumArtwork(
                    imageUrl = artist.coverUrl,
                    title = artist.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f)
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.05f)
                        .background(AppColors.SurfaceMuted, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
            Column(
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = artist.name,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${artist.albumCount} 张专辑",
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ArtistListRow(
    artist: ArtistSummary,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncAlbumArtwork(
                imageUrl = artist.coverUrl,
                title = artist.name,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = artist.name,
                    color = AppColors.TextPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${artist.albumCount} 张专辑",
                    color = AppColors.TextSecondary,
                )
            }
            NavIconGraphic(
                icon = NavIcon.Artists,
                tint = AppColors.TextTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun IndexSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        color = AppColors.TextSecondary,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun BoxScope.VerticalIndexBar(
    labels: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (labels.isEmpty()) return

    var barHeightPx by remember { mutableIntStateOf(0) }
    var activeLabel by remember { mutableStateOf<String?>(null) }

    fun selectLabelAt(y: Float) {
        if (barHeightPx <= 0 || labels.isEmpty()) return
        val slotHeight = barHeightPx.toFloat() / labels.size
        val index = (y / slotHeight).toInt().coerceIn(0, labels.lastIndex)
        val label = labels[index]
        if (activeLabel == label) return
        activeLabel = label
        onSelect(label)
    }

    Surface(
        modifier = modifier
            .align(Alignment.CenterEnd)
            .onSizeChanged { barHeightPx = it.height }
            .pointerInput(labels) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        selectLabelAt(offset.y)
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        selectLabelAt(change.position.y)
                    },
                    onDragEnd = { activeLabel = null },
                    onDragCancel = { activeLabel = null },
                )
            },
        shape = RoundedCornerShape(20.dp),
        color = AppColors.SurfaceStrong.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier
                        .width(26.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelect(label) }
                        .padding(vertical = 2.dp),
                    color = if (label == activeLabel) AppColors.SurfaceStrong else AppColors.TextPrimary,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun <T> buildIndexedGroups(
    items: List<T>,
    labelOf: (T) -> String?,
): List<IndexedGroup<T>> {
    return items
        .sortedWith(
            compareBy<T> { indexOrderOf(indexLabelForText(labelOf(it))) }
                .thenBy { normalizedIndexValue(labelOf(it)) }
        )
        .groupBy { indexLabelForText(labelOf(it)) }
        .entries
        .sortedBy { indexOrderOf(it.key) }
        .map { IndexedGroup(label = it.key, items = it.value) }
}

private fun <T> buildListJumpTargets(
    leadingItemCount: Int,
    groups: List<IndexedGroup<T>>,
): List<Pair<String, Int>> {
    var index = leadingItemCount
    return buildList {
        groups.forEach { group ->
            add(group.label to index)
            index += 1 + group.items.size
        }
    }
}

private fun <T> buildGridJumpTargets(
    leadingItemCount: Int,
    groups: List<IndexedGroup<T>>,
): List<Pair<String, Int>> {
    var index = leadingItemCount
    return buildList {
        groups.forEach { group ->
            add(group.label to index)
            index += 1 + group.items.size
        }
    }
}

private fun indexOrderOf(label: String): Int =
    SupportedIndexOrder.indexOf(label).takeIf { it >= 0 } ?: Int.MAX_VALUE

private fun normalizedIndexValue(value: String?): String {
    val normalized = Normalizer.normalize(value.orEmpty().trim(), Normalizer.Form.NFKC)
    return buildString(normalized.length) {
        normalized.forEach { char ->
            append(char.normalizeForIndex())
        }
    }.lowercase()
}

private fun indexLabelForText(value: String?): String {
    val firstChar = normalizedIndexValue(value)
        .firstOrNull { !it.isWhitespace() && !it.isIgnoredIndexPrefix() }
        ?: return "#"

    if (firstChar.isDigit()) return "0-9"
    if (firstChar in 'a'..'z') return firstChar.uppercaseChar().toString()

    return when (firstChar) {
        in "あいうえおぁぃぅぇぉ" -> "あ"
        in "かきくけこがぎぐげごゕゖ" -> "か"
        in "さしすせそざじずぜぞ" -> "さ"
        in "たちつてとだぢづでどっ" -> "た"
        in "なにぬねの" -> "な"
        in "はひふへほばびぶべぼぱぴぷぺぽゔ" -> "は"
        in "まみむめも" -> "ま"
        in "やゆよゃゅょ" -> "や"
        in "らりるれろ" -> "ら"
        in "わをんゎ" -> "わ"
        else -> "#"
    }
}

private fun Char.normalizeForIndex(): Char {
    val codePoint = code
    return when {
        codePoint in 0x30A1..0x30F6 -> (codePoint - 0x60).toChar()
        else -> this
    }
}

private fun Char.isIgnoredIndexPrefix(): Boolean =
    this in setOf('\'', '"', '‘', '’', '“', '”', '・', '･', 'ー', '-', '_', '(', ')', '[', ']', '【', '】', '「', '」', '『', '』')

@Composable
private fun ArtistAlbumsSection(
    artist: ArtistSummary?,
    selectedAlbum: PlexAlbum?,
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
    val currentArtist = artist
    if (currentArtist == null) {
        EmptyStateCard(
            title = "歌手不存在或还未同步",
            body = "先回到歌手页重新选择，或者先同步一次本地音乐库。",
            actionLabel = "返回歌手页",
            onAction = onBack,
        )
        return
    }

    val heroAlbum = currentArtist.albums.firstOrNull()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        OutlinedButton(onClick = onBack) {
            Text("返回歌手页")
        }
        heroAlbum?.let { album ->
            AsyncAlbumArtwork(
                imageUrl = currentArtist.coverUrl ?: album.thumbUrl,
                title = currentArtist.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.25f)
                    .clip(RoundedCornerShape(24.dp)),
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = currentArtist.name,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "共 ${currentArtist.albumCount} 张专辑，点击封面进入专辑详情并继续播放。",
                color = AppColors.TextSecondary,
            )
        }
        AlbumGrid(
            albums = currentArtist.albums,
            columns = 2,
            selectedAlbum = selectedAlbum,
            onAlbumClick = onAlbumClick,
            compact = false,
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
private fun AllAlbumsSection(
    albums: List<PlexAlbum>,
    selectedAlbum: PlexAlbum?,
    searchQuery: String,
    isSearchLoading: Boolean,
    bottomContentPadding: androidx.compose.ui.unit.Dp,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAlbumClick: (PlexAlbum) -> Unit,
) {
    val albumGroups = remember(albums) {
        buildIndexedGroups(albums) { it.title }
    }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val jumpTargets = remember(albumGroups) {
        buildGridJumpTargets(
            leadingItemCount = 3,
            groups = albumGroups,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = 16.dp,
                end = 42.dp,
                bottom = bottomContentPadding,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedButton(onClick = onBack) {
                    Text("返回首页")
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "全部专辑",
                        style = MaterialTheme.typography.headlineMedium,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (searchQuery.isBlank()) {
                            "当前展示本地已同步的全部 ${albums.size} 张专辑，可从右侧索引快速跳转。"
                        } else {
                            "搜索 \"$searchQuery\" 命中 ${albums.size} 张专辑，仍可按索引分组跳转。"
                        },
                        color = AppColors.TextSecondary,
                    )
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜索专辑或艺人") },
                    singleLine = true,
                )
            }
            if (isSearchLoading) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LoadingCard(message = "正在查询本地专辑库")
                }
            } else if (albums.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyStateCard(
                        title = if (searchQuery.isBlank()) "本地还没有专辑" else "没有匹配结果",
                        body = if (searchQuery.isBlank()) {
                            "先回首页同步一次 Plex 音乐库，之后这里会显示全部专辑。"
                        } else {
                            "换个关键词试试，搜索会匹配专辑名和艺人名。"
                        },
                        actionLabel = "返回首页",
                        onAction = onBack,
                    )
                }
            } else {
                albumGroups.forEach { group ->
                    item(
                        key = "album-header-${group.label}",
                        span = { GridItemSpan(maxLineSpan) },
                    ) {
                        IndexSectionHeader(label = group.label)
                    }
                    items(
                        items = group.items,
                        key = { album -> album.ratingKey },
                    ) { album ->
                        AlbumCoverCard(
                            album = album,
                            selected = selectedAlbum?.ratingKey == album.ratingKey,
                            onClick = { onAlbumClick(album) },
                            compact = false,
                        )
                    }
                }
            }
        }

        if (!isSearchLoading && jumpTargets.size > 1) {
            VerticalIndexBar(
                labels = jumpTargets.map { it.first },
                modifier = Modifier.padding(top = 100.dp, end = 4.dp, bottom = 12.dp),
                onSelect = { label ->
                    jumpTargets.firstOrNull { it.first == label }?.let { (_, index) ->
                        scope.launch { gridState.animateScrollToItem(index) }
                    }
                },
            )
        }
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
    isPlaybackLoading: Boolean,
    isFavoriteLoading: Boolean,
    onReturnHome: () -> Unit,
    onArtistClick: (String?) -> Unit,
    onToggleAlbumFavorite: (PlexAlbum) -> Unit,
    onToggleTrackFavorite: (PlexTrackStream) -> Unit,
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
            val artistName = currentTrackResult.album.artistName ?: "未知艺人"
            Text(
                currentTrackResult.album.title,
                style = MaterialTheme.typography.headlineMedium,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = artistName,
                    color = AppColors.Accent,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onArtistClick(currentTrackResult.album.artistName) },
                )
                Text(
                    text = buildString {
                        currentTrackResult.album.year?.let {
                            append("· ")
                            append(it)
                            append(' ')
                        }
                        append("· ${currentTrackResult.tracks.size} 首")
                    },
                    color = AppColors.TextSecondary,
                )
            }
            Text(
                text = selectedRoom?.let { "当前将推送到 ${it.roomName}" } ?: "尚未选择 Sonos 房间，请先到设置页选择房间。",
                color = AppColors.TextTertiary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FavoriteIconButton(
                    isFavorite = currentTrackResult.album.isFavorite,
                    isLoading = isFavoriteLoading,
                    onClick = { onToggleAlbumFavorite(currentTrackResult.album) },
                )
                IconCircleButton(
                    onClick = { selectedRoom?.let { onPlayAlbum(currentTrackResult, it) } },
                    enabled = selectedRoom != null && !isPlaybackLoading,
                    highlighted = true,
                ) {
                    PlayerIconGraphic(
                        icon = PlayerIcon.Play,
                        tint = if (selectedRoom != null) AppColors.TextPrimary else AppColors.TextTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            currentTrackResult.tracks.forEachIndexed { index, track ->
                Surface(
                    modifier = Modifier.clickable(
                        enabled = selectedRoom != null && !isPlaybackLoading,
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
                        Column(
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                FavoriteIconButton(
                                    isFavorite = track.isFavorite,
                                    isLoading = isFavoriteLoading,
                                    onClick = { onToggleTrackFavorite(track) },
                                )
                                IconCircleButton(
                                    onClick = { selectedRoom?.let { onPlayTrack(currentTrackResult.album, track, it) } },
                                    enabled = selectedRoom != null && !isPlaybackLoading,
                                ) {
                                    PlayerIconGraphic(
                                        icon = PlayerIcon.Play,
                                        tint = if (selectedRoom != null) AppColors.TextPrimary else AppColors.TextTertiary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteIconButton(
    isFavorite: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconCircleButton(
        modifier = modifier,
        onClick = onClick,
        enabled = !isLoading,
        highlighted = isFavorite,
    ) {
        FavoriteIconGraphic(
            style = if (isFavorite) FavoriteIconStyle.Filled else FavoriteIconStyle.Outline,
            tint = if (isLoading) {
                AppColors.TextTertiary
            } else if (isFavorite) {
                AppColors.TextPrimary
            } else {
                AppColors.TextSecondary
            },
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun IconCircleButton(
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .size(42.dp)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (highlighted) AppColors.SurfaceStrong else AppColors.SurfaceMuted,
        border = BorderStroke(
            1.dp,
            if (highlighted) AppColors.BorderStrong else AppColors.Border,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun BottomNavigationBar(
    primarySection: AppSection,
    modifier: Modifier = Modifier,
    onSectionChange: (AppSection) -> Unit,
) {
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            BottomNavButton(
                icon = NavIcon.Home,
                selected = primarySection == AppSection.Home,
                onClick = { onSectionChange(AppSection.Home) },
                modifier = Modifier.weight(1f),
            )
            BottomNavButton(
                icon = NavIcon.Artists,
                selected = primarySection == AppSection.Artists,
                onClick = { onSectionChange(AppSection.Artists) },
                modifier = Modifier.weight(1f),
            )
            BottomNavButton(
                icon = NavIcon.Settings,
                selected = primarySection == AppSection.Settings,
                onClick = { onSectionChange(AppSection.Settings) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BottomNavButton(
    icon: NavIcon,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) AppColors.Accent else Color.Transparent
    val contentColor = if (selected) AppColors.SurfaceStrong else AppColors.TextSecondary
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = background,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, if (selected) Color.Transparent else AppColors.Border),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NavIconGraphic(
                icon = icon,
                tint = contentColor,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.size(6.dp))
            Box(
                modifier = Modifier
                    .size(width = 16.dp, height = 3.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) contentColor.copy(alpha = 0.88f) else Color.Transparent)
            )
        }
    }
}

@Composable
private fun ArtistPresentationToggle(
    presentation: ArtistPresentation,
    onPresentationChange: (ArtistPresentation) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        IconCircleButton(
            onClick = { onPresentationChange(ArtistPresentation.Covers) },
            enabled = presentation != ArtistPresentation.Covers,
            highlighted = presentation == ArtistPresentation.Covers,
        ) {
            NavIconGraphic(
                icon = NavIcon.Covers,
                tint = if (presentation == ArtistPresentation.Covers) AppColors.SurfaceStrong else AppColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconCircleButton(
            onClick = { onPresentationChange(ArtistPresentation.List) },
            enabled = presentation != ArtistPresentation.List,
            highlighted = presentation == ArtistPresentation.List,
        ) {
            NavIconGraphic(
                icon = NavIcon.List,
                tint = if (presentation == ArtistPresentation.List) AppColors.SurfaceStrong else AppColors.TextPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun HomeStatusCard(
    selectedRoom: SonosRoom?,
    lastAlbumSyncEpochMillis: Long?,
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
                Text(
                    text = formatSyncStatus(lastAlbumSyncEpochMillis),
                    color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
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
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spacing),
    ) {
        albums.chunked(columns).forEach { rowAlbums ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing),
            ) {
                rowAlbums.forEach { album ->
                    Box(modifier = Modifier.weight(1f)) {
                        AlbumCoverCard(
                            album = album,
                            selected = selectedAlbum?.ratingKey == album.ratingKey,
                            onClick = { onAlbumClick(album) },
                            compact = compact,
                        )
                    }
                }

                repeat(columns - rowAlbums.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
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
private fun NavIconGraphic(
    icon: NavIcon,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val imageVector = when (icon) {
        NavIcon.Home -> Icons.Filled.Home
        NavIcon.Artists -> Icons.Filled.Person
        NavIcon.Settings -> Icons.Filled.Settings
        NavIcon.Covers -> Icons.Filled.GridView
        NavIcon.List -> Icons.Filled.List
    }

    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = tint,
        modifier = modifier
    )
}

@Composable
private fun FavoriteIconGraphic(
    style: FavoriteIconStyle,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        val heart = Path().apply {
            moveTo(w * 0.50f, h * 0.86f)
            cubicTo(w * 0.18f, h * 0.62f, w * 0.02f, h * 0.38f, w * 0.20f, h * 0.20f)
            cubicTo(w * 0.34f, h * 0.06f, w * 0.50f, h * 0.16f, w * 0.50f, h * 0.28f)
            cubicTo(w * 0.50f, h * 0.16f, w * 0.66f, h * 0.06f, w * 0.80f, h * 0.20f)
            cubicTo(w * 0.98f, h * 0.38f, w * 0.82f, h * 0.62f, w * 0.50f, h * 0.86f)
            close()
        }

        when (style) {
            FavoriteIconStyle.Filled -> drawPath(path = heart, color = tint)
            FavoriteIconStyle.Outline -> drawPath(
                path = heart,
                color = tint,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = size.minDimension * 0.10f),
            )
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
            .listener(
                onSuccess = { _, result ->
                    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                        Log.d("AlbumArtwork", "Loaded $imageUrl from ${result.dataSource}")
                    }
                },
            )
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

private fun formatSyncStatus(epochMillis: Long?): String {
    if (epochMillis == null) return "尚未同步"

    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    return "最近同步 ${formatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))}"
}

private fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1_000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Preview(showBackground = true)
@Composable
private fun GreetingPreview() {
    PlexToSonosPlayerTheme {
        PlexAlbumScreen()
    }
}
