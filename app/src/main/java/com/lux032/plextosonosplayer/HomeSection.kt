package com.lux032.plextosonosplayer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbum
import com.lux032.plextosonosplayer.sonos.SonosRoom
import coil3.request.ImageRequest
import kotlinx.coroutines.launch

@Composable
internal fun HomeSection(
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
internal fun EntryActionCard(
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
internal fun AlbumPreviewGridSection(
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
internal fun AlbumCollectionSection(
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
internal fun AllAlbumsSection(
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
internal fun HomeStatusCard(
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
internal fun SonosSettingsCard(
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
