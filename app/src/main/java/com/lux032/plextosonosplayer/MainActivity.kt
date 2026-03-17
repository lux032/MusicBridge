package com.lux032.plextosonosplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbum
import com.lux032.plextosonosplayer.plex.PlexAlbumsResult
import com.lux032.plextosonosplayer.plex.PlexAlbumTracksResult
import com.lux032.plextosonosplayer.plex.PlexAuthConfig
import com.lux032.plextosonosplayer.plex.PlexClient
import com.lux032.plextosonosplayer.plex.PlexTrackStream
import com.lux032.plextosonosplayer.sonos.SonosController
import com.lux032.plextosonosplayer.sonos.SonosDiscovery
import com.lux032.plextosonosplayer.sonos.SonosRoom
import com.lux032.plextosonosplayer.ui.theme.PlexToSonosPlayerTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
}

private enum class BrowserStage {
    Albums,
    Tracks,
    UrlDetail,
}

private const val AlbumsPerPage = 100

@Composable
fun PlexAlbumScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val appPreferences = remember(context) { AppPreferences(context) }
    val savedPreferences = remember(appPreferences) { appPreferences.loadPlexConnectionPreferences() }
    var username by rememberSaveable { mutableStateOf(savedPreferences.username) }
    var password by rememberSaveable { mutableStateOf(savedPreferences.password) }
    var token by rememberSaveable { mutableStateOf(savedPreferences.token) }
    var server by rememberSaveable { mutableStateOf(savedPreferences.server) }
    var baseUrl by rememberSaveable { mutableStateOf(savedPreferences.baseUrl) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var albumsResult by remember { mutableStateOf<PlexAlbumsResult?>(null) }
    var trackResult by remember { mutableStateOf<PlexAlbumTracksResult?>(null) }
    var selectedAlbum by remember { mutableStateOf<PlexAlbum?>(null) }
    var selectedTrack by remember { mutableStateOf<PlexTrackStream?>(null) }
    var stage by remember { mutableStateOf(BrowserStage.Albums) }
    var albumPage by remember { mutableStateOf(0) }
    var sonosRooms by remember { mutableStateOf<List<SonosRoom>>(emptyList()) }
    var selectedSonosRoom by remember { mutableStateOf<SonosRoom?>(null) }
    var sonosDiscoveryAttempted by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }
    var sonosVolume by remember { mutableStateOf(0f) }
    var hasLoadedSonosVolume by remember { mutableStateOf(false) }
    var isVolumeLoading by remember { mutableStateOf(false) }
    var volumeSyncKey by remember { mutableStateOf(0) }
    var volumeChangeJob by remember { mutableStateOf<Job?>(null) }
    var isVolumeChanging by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sonosController = remember { SonosController() }

    fun client() = PlexClient(
        PlexAuthConfig(
            username = username,
            password = password,
            preferredServer = server,
            baseUrl = baseUrl,
            token = token,
        )
    )

    SideEffect {
        appPreferences.savePlexConnectionPreferences(
            PlexConnectionPreferences(
                username = username,
                password = password,
                token = token,
                server = server,
                baseUrl = baseUrl,
            )
        )
    }

    LaunchedEffect(selectedSonosRoom?.coordinatorUuid, selectedSonosRoom?.coordinatorBaseUrl, volumeSyncKey) {
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Plex 专辑与单曲串流地址测试")
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex 用户名") },
            singleLine = true,
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex 密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Token（可选，优先）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = server,
            onValueChange = { server = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("服务器名称（可选）") },
            singleLine = true,
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Plex Base URL（可选）") },
            singleLine = true,
        )
        Button(
            onClick = {
                isLoading = true
                errorMessage = null
                actionMessage = null
                albumsResult = null
                trackResult = null
                selectedAlbum = null
                selectedTrack = null
                stage = BrowserStage.Albums
                albumPage = 0
                scope.launch {
                    runCatching {
                        client().fetchAlbums()
                    }.onSuccess {
                        albumsResult = it
                    }.onFailure {
                        errorMessage = it.message ?: "未知错误"
                    }
                    isLoading = false
                }
            },
            enabled = !isLoading,
        ) {
            Text("获取专辑列表")
        }
        Button(
            onClick = {
                isLoading = true
                errorMessage = null
                actionMessage = null
                sonosDiscoveryAttempted = true
                scope.launch {
                    runCatching {
                        SonosDiscovery(context).discoverRooms()
                    }.onSuccess {
                        sonosRooms = it
                        selectedSonosRoom = selectedSonosRoom
                            ?.let { current -> it.firstOrNull { room -> room.coordinatorUuid == current.coordinatorUuid } }
                            ?: it.firstOrNull()
                        volumeSyncKey += 1
                    }.onFailure {
                        errorMessage = it.message ?: "未知错误"
                    }
                    isLoading = false
                }
            },
            enabled = !isLoading,
        ) {
            Text("发现局域网 Sonos 设备")
        }

        if (isLoading) {
            CircularProgressIndicator()
        }

        errorMessage?.let {
            Text("错误: $it")
        }
        actionMessage?.let {
            Text(it)
        }

        Text("Sonos 房间数: ${sonosRooms.size}")
        if (sonosDiscoveryAttempted && sonosRooms.isEmpty() && !isLoading) {
            Text("当前未发现 Sonos 房间。请确认手机和音箱在同一局域网。")
        }
        sonosRooms.forEach { room ->
            HorizontalDivider()
            Button(
                onClick = {
                    selectedSonosRoom = room
                    actionMessage = "已选择 Sonos 房间: ${room.roomName}"
                    volumeSyncKey += 1
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading,
            ) {
                Text(
                    if (room.memberCount > 1) {
                        "${room.roomName} (${room.memberCount} 成员，可能是立体声/组合房间)"
                    } else {
                        room.roomName
                    }
                )
            }
            Text("Coordinator: ${room.rawCoordinatorName}")
            Text("成员数: ${room.memberCount}")
            Text("控制地址: ${room.coordinatorBaseUrl}")
        }
        selectedSonosRoom?.let { room ->
            Text("当前 Sonos 目标: ${room.roomName}")
            Text(
                if (isVolumeLoading) {
                    "当前音量: 读取中"
                } else if (!hasLoadedSonosVolume) {
                    "当前音量: 未读取到"
                } else if (isVolumeChanging) {
                    "当前音量: ${sonosVolume.toInt()}（设置中）"
                } else {
                    "当前音量: ${sonosVolume.toInt()}"
                }
            )
            if (hasLoadedSonosVolume) {
                Slider(
                    value = sonosVolume,
                    onValueChange = { newValue ->
                        sonosVolume = newValue
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
                    },
                    valueRange = 0f..100f,
                    enabled = !isVolumeLoading,
                )
            } else {
                Button(
                    onClick = { volumeSyncKey += 1 },
                    enabled = !isVolumeLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("读取当前音量")
                }
            }
            Button(
                onClick = { volumeSyncKey += 1 },
                enabled = !isVolumeLoading,
            ) {
                Text("刷新当前音量")
            }
            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    actionMessage = null
                    scope.launch {
                        runCatching {
                            sonosController.pause(room)
                        }.onSuccess {
                            actionMessage = "已暂停播放"
                        }.onFailure {
                            errorMessage = it.message ?: "未知错误"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("暂停播放")
            }
            Button(
                onClick = {
                    isLoading = true
                    errorMessage = null
                    actionMessage = null
                    scope.launch {
                        runCatching {
                            sonosController.resume(room)
                        }.onSuccess {
                            actionMessage = "已继续播放"
                        }.onFailure {
                            errorMessage = it.message ?: "未知错误"
                        }
                        isLoading = false
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("继续播放")
            }
        }

        albumsResult?.let { result ->
            Text("服务器: ${result.serverName}")
            Text("音乐库: ${result.sections.joinToString { it.title }}")
            Text("当前层级: ${stageLabel(stage)}")

            when (stage) {
                BrowserStage.Albums -> {
                    val totalPages = result.albums.pageCount(AlbumsPerPage)
                    val currentPage = albumPage.coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                    val pageAlbums = result.albums.pageSlice(currentPage, AlbumsPerPage)
                    Text("专辑数: ${result.albums.size}")
                    Text("分页: ${currentPage + 1} / $totalPages，每页 $AlbumsPerPage 条")
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { if (currentPage > 0) albumPage = currentPage - 1 },
                            enabled = !isLoading && currentPage > 0,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("上一页")
                        }
                        Button(
                            onClick = { if (currentPage < totalPages - 1) albumPage = currentPage + 1 },
                            enabled = !isLoading && currentPage < totalPages - 1,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("下一页")
                        }
                    }
                    pageAlbums.forEach { album ->
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                selectedAlbum = album
                                selectedTrack = null
                                scope.launch {
                                    runCatching {
                                        client().fetchAlbumTrackStreams(album)
                                    }.onSuccess {
                                        trackResult = it
                                        stage = BrowserStage.Tracks
                                    }.onFailure {
                                        errorMessage = it.message ?: "未知错误"
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading,
                        ) {
                            Text(album.title)
                        }
                    }
                }

                BrowserStage.Tracks -> {
                    Button(
                        onClick = {
                            stage = BrowserStage.Albums
                            trackResult = null
                            selectedAlbum = null
                            selectedTrack = null
                        },
                        enabled = !isLoading,
                    ) {
                        Text("返回专辑列表")
                    }

                    val currentTrackResult = trackResult
                    if (currentTrackResult != null) {
                        Text("目标专辑: ${currentTrackResult.album.title}")
                        Text("单曲数: ${currentTrackResult.tracks.size}")
                        currentTrackResult.tracks.forEach { track ->
                            Button(
                                onClick = {
                                    selectedTrack = track
                                    stage = BrowserStage.UrlDetail
                                },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !isLoading,
                            ) {
                                Text(track.title)
                            }
                        }
                    }
                }

                BrowserStage.UrlDetail -> {
                    Button(
                        onClick = {
                            stage = BrowserStage.Tracks
                            selectedTrack = null
                        },
                        enabled = !isLoading,
                    ) {
                        Text("返回单曲列表")
                    }

                    val currentAlbum = selectedAlbum
                    val currentTrack = selectedTrack
                    if (currentAlbum != null && currentTrack != null) {
                        Text("专辑: ${currentAlbum.title}")
                        Text("单曲: ${currentTrack.title}")
                        selectedSonosRoom?.let {
                            Text("推送目标: ${it.roomName}")
                        } ?: Text("推送目标: 未选择 Sonos 房间")
                        HorizontalDivider()
                        Text("Part Key:")
                        Text(currentTrack.partKey)
                        HorizontalDivider()
                        Text("Stream URL:")
                        Text(currentTrack.streamUrl)
                        Button(
                            onClick = {
                                val room = selectedSonosRoom ?: return@Button
                                isLoading = true
                                errorMessage = null
                                actionMessage = null
                                scope.launch {
                                    runCatching {
                                        SonosController().playTrack(
                                            room = room,
                                            trackUrl = currentTrack.streamUrl,
                                            title = currentTrack.title,
                                            albumTitle = currentAlbum.title,
                                        )
                                    }.onSuccess {
                                        actionMessage = "已推送到 Sonos 房间: ${room.roomName}"
                                    }.onFailure {
                                        errorMessage = it.message ?: "未知错误"
                                    }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isLoading && selectedSonosRoom != null,
                        ) {
                            Text("推送当前单曲到 Sonos")
                        }
                    }
                }
            }
        }
    }
}

private fun stageLabel(stage: BrowserStage): String = when (stage) {
    BrowserStage.Albums -> "专辑列表"
    BrowserStage.Tracks -> "单曲列表"
    BrowserStage.UrlDetail -> "URL 详情"
}

private fun List<PlexAlbum>.pageCount(pageSize: Int): Int =
    if (isEmpty()) 1 else ((size - 1) / pageSize) + 1

private fun List<PlexAlbum>.pageSlice(pageIndex: Int, pageSize: Int): List<PlexAlbum> {
    val start = (pageIndex * pageSize).coerceAtMost(size)
    val end = (start + pageSize).coerceAtMost(size)
    return subList(start, end)
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    PlexToSonosPlayerTheme {
        PlexAlbumScreen()
    }
}
