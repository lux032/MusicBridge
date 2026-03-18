package com.lux032.plextosonosplayer

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.plextosonosplayer.plex.PlexAlbum
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun FavoriteIconButton(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
) {
    IconCircleButton(
        onClick = onClick,
        modifier = modifier,
        enabled = !isLoading,
        highlighted = isFavorite,
    ) {
        FavoriteIconGraphic(
            isFavorite = isFavorite,
            tint = if (isFavorite) AppColors.Accent else AppColors.TextSecondary,
        )
    }
}

@Composable
internal fun IconCircleButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
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
internal fun BottomNavigationBar(
    primarySection: AppSection,
    modifier: Modifier = Modifier,
    onSectionChange: (AppSection) -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AppColors.Surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomNavButton(
                section = AppSection.Home,
                isSelected = primarySection == AppSection.Home,
                onClick = { onSectionChange(AppSection.Home) },
            )
            BottomNavButton(
                section = AppSection.Artists,
                isSelected = primarySection == AppSection.Artists,
                onClick = { onSectionChange(AppSection.Artists) },
            )
            BottomNavButton(
                section = AppSection.Settings,
                isSelected = primarySection == AppSection.Settings,
                onClick = { onSectionChange(AppSection.Settings) },
            )
        }
    }
}

@Composable
internal fun BottomNavButton(
    section: AppSection,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val icon = when (section) {
        AppSection.Home -> NavIcon.Home
        AppSection.Artists -> NavIcon.Artists
        AppSection.Settings -> NavIcon.Settings
        else -> NavIcon.Home
    }
    val label = when (section) {
        AppSection.Home -> "首页"
        AppSection.Artists -> "歌手"
        AppSection.Settings -> "设置"
        else -> ""
    }
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavIconGraphic(
            icon = icon,
            tint = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
        )
        Text(
            text = label,
            color = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
internal fun MessageCard(label: String, message: String, tone: MessageTone) {
    val backgroundColor = when (tone) {
        MessageTone.Error -> AppColors.ErrorBg
        MessageTone.Info -> AppColors.Surface
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = label, fontWeight = FontWeight.Bold, color = AppColors.TextPrimary)
            Text(text = message, color = AppColors.TextSecondary)
        }
    }
}

@Composable
internal fun LoadingCard(message: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = AppColors.Surface,
        border = BorderStroke(1.dp, AppColors.Border),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = AppColors.Accent,
                strokeWidth = 3.dp,
            )
            Text(text = message, color = AppColors.TextSecondary)
        }
    }
}

@Composable
internal fun EmptyStateCard(
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
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = AppColors.TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Text(text = body, color = AppColors.TextSecondary)
            Button(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

@Composable
internal fun AlbumGrid(
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
internal fun AlbumCoverCard(
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
internal fun PlayerIconGraphic(
    icon: PlayerIcon,
    tint: Color = AppColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    when (icon) {
        PlayerIcon.Play -> Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = "播放",
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Pause -> Icon(
            imageVector = Icons.Filled.Pause,
            contentDescription = "暂停",
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Back -> Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = "返回",
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Previous -> Icon(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = "上一首",
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Next -> Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = "下一首",
            tint = tint,
            modifier = modifier,
        )
    }
}

@Composable
internal fun NavIconGraphic(
    icon: NavIcon,
    tint: Color = AppColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    when (icon) {
        NavIcon.Home -> Icon(Icons.Filled.Home, "首页", tint = tint, modifier = modifier)
        NavIcon.Artists -> Icon(Icons.Filled.Person, "歌手", tint = tint, modifier = modifier)
        NavIcon.Settings -> Icon(Icons.Filled.Settings, "设置", tint = tint, modifier = modifier)
        NavIcon.Covers -> Icon(Icons.Filled.GridView, "封面", tint = tint, modifier = modifier)
        NavIcon.List -> Icon(Icons.Filled.List, "列表", tint = tint, modifier = modifier)
    }
}

@Composable
internal fun FavoriteIconGraphic(
    isFavorite: Boolean,
    tint: Color = AppColors.TextPrimary,
    modifier: Modifier = Modifier,
) {
    val size = 24.dp
    if (isFavorite) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = "已收藏",
            tint = tint,
            modifier = modifier.size(size),
        )
    } else {
        Canvas(modifier = modifier.size(size)) {
            val path = Path().apply {
                val w = this@Canvas.size.width
                val h = this@Canvas.size.height
                moveTo(w * 0.5f, h * 0.85f)
                lineTo(w * 0.15f, h * 0.45f)
                cubicTo(w * 0.1f, h * 0.35f, w * 0.1f, h * 0.2f, w * 0.2f, h * 0.1f)
                cubicTo(w * 0.3f, h * 0.0f, w * 0.45f, h * 0.05f, w * 0.5f, h * 0.15f)
                cubicTo(w * 0.55f, h * 0.05f, w * 0.7f, h * 0.0f, w * 0.8f, h * 0.1f)
                cubicTo(w * 0.9f, h * 0.2f, w * 0.9f, h * 0.35f, w * 0.85f, h * 0.45f)
                close()
            }
            drawPath(path = path, color = tint)
        }
    }
}

@Composable
internal fun AsyncAlbumArtwork(
    imageUrl: String?,
    title: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }

    if (imageUrl != null) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(imageUrl)
                .build(),
            contentDescription = title,
            imageLoader = imageLoader,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(
            modifier = modifier.background(AppColors.SurfaceMuted),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Album,
                contentDescription = null,
                tint = AppColors.TextSecondary,
                modifier = Modifier.size(48.dp),
            )
        }
    }
}

internal fun PlexConnectionPreferences.hasCredentials(): Boolean =
    token.isNotBlank() || (username.isNotBlank() && password.isNotBlank())

internal fun formatSyncStatus(epochMillis: Long?): String {
    if (epochMillis == null) return "未同步"
    val instant = Instant.ofEpochMilli(epochMillis)
    val formatter = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}

internal fun formatDuration(durationMillis: Long): String {
    val totalSeconds = durationMillis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
