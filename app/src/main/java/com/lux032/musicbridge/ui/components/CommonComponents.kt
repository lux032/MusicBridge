package com.lux032.musicbridge

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.plex.PlexAlbum
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import androidx.compose.ui.graphics.painter.ColorPainter
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        label = "icon_button_scale",
    )
    Surface(
        modifier = modifier
            .size(42.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            ),
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
    val navigationBarBottomInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = AppColors.Surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 16.dp,
                    top = 12.dp,
                    end = 16.dp,
                    bottom = navigationBarBottomInset + 12.dp,
                ),
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
                section = AppSection.Playlists,
                isSelected = primarySection == AppSection.Playlists,
                onClick = { onSectionChange(AppSection.Playlists) },
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
        AppSection.Playlists -> NavIcon.Playlists
        AppSection.Settings -> NavIcon.Settings
        else -> NavIcon.Home
    }
    val label = when (section) {
        AppSection.Home -> Strings.home
        AppSection.Artists -> Strings.artists
        AppSection.Playlists -> Strings.playlists
        AppSection.Settings -> Strings.settings
        else -> ""
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.9f else 1f,
        label = "nav_button_scale",
    )
    val animatedTint by animateColorAsState(
        targetValue = if (isSelected) AppColors.Accent else AppColors.TextSecondary,
        label = "nav_tint",
    )
    Column(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NavIconGraphic(
            icon = icon,
            tint = animatedTint,
        )
        Text(
            text = label,
            color = animatedTint,
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
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Surface(
        modifier = Modifier
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true),
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = if (isPressed) 0.95f else 1f
                scaleY = if (isPressed) 0.95f else 1f
            },
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
                        text = album.artistName ?: Strings.unknownArtist,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) {
                        Text(
                            text = Strings.currentViewing,
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
            contentDescription = Strings.play,
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Pause -> Icon(
            imageVector = Icons.Filled.Pause,
            contentDescription = Strings.pause,
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Back -> Icon(
            imageVector = Icons.Filled.ArrowBack,
            contentDescription = Strings.back,
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Previous -> Icon(
            imageVector = Icons.Filled.SkipPrevious,
            contentDescription = Strings.previous,
            tint = tint,
            modifier = modifier,
        )
        PlayerIcon.Next -> Icon(
            imageVector = Icons.Filled.SkipNext,
            contentDescription = Strings.next,
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
        NavIcon.Home -> Icon(Icons.Filled.Home, Strings.home, tint = tint, modifier = modifier)
        NavIcon.Artists -> Icon(Icons.Filled.Person, Strings.artists, tint = tint, modifier = modifier)
        NavIcon.Playlists -> Icon(Icons.Filled.QueueMusic, Strings.playlists, tint = tint, modifier = modifier)
        NavIcon.Settings -> Icon(Icons.Filled.Settings, Strings.settings, tint = tint, modifier = modifier)
        NavIcon.Covers -> Icon(Icons.Filled.GridView, "Covers", tint = tint, modifier = modifier)
        NavIcon.List -> Icon(Icons.Filled.List, "List", tint = tint, modifier = modifier)
    }
}

@Composable
internal fun FavoriteIconGraphic(
    isFavorite: Boolean,
    tint: Color = AppColors.TextPrimary,
    modifier: Modifier = Modifier,
    baseSize: androidx.compose.ui.unit.Dp = 24.dp,
    selectedSize: androidx.compose.ui.unit.Dp = 26.dp,
) {
    val animatedSize by animateDpAsState(
        targetValue = if (isFavorite) selectedSize else baseSize,
        label = "favorite_size",
    )
    val animatedTint by animateColorAsState(
        targetValue = if (isFavorite) AppColors.Accent else AppColors.TextSecondary,
        label = "favorite_tint",
    )
    if (isFavorite) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = Strings.favorited,
            tint = animatedTint,
            modifier = modifier.size(animatedSize),
        )
    } else {
        Icon(
            imageVector = Icons.Outlined.FavoriteBorder,
            contentDescription = Strings.notFavorited,
            tint = animatedTint,
            modifier = modifier.size(animatedSize),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalVolumeOverlay(
    room: SonosRoom,
    volume: Float,
    hasLoadedVolume: Boolean,
    isVolumeLoading: Boolean,
    isVolumeChanging: Boolean,
    modifier: Modifier = Modifier,
    onVolumeChange: (Float) -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(max = 360.dp),
        shape = RoundedCornerShape(24.dp),
        color = AppColors.SurfaceStrong,
        border = BorderStroke(1.dp, AppColors.BorderStrong),
        shadowElevation = 10.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = Strings.volume,
                    color = AppColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = if (hasLoadedVolume) "${volume.toInt()}" else "--",
                    color = AppColors.Accent,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                text = if (isVolumeLoading) Strings.loadingVolume(room.roomName) else room.roomName,
                color = AppColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = volume.coerceIn(0f, 100f),
                onValueChange = onVolumeChange,
                valueRange = 0f..100f,
                enabled = hasLoadedVolume && !isVolumeLoading,
                colors = SliderDefaults.colors(
                    thumbColor = AppColors.Accent,
                    activeTrackColor = AppColors.Accent,
                    inactiveTrackColor = AppColors.SurfaceMuted,
                    disabledThumbColor = AppColors.TextTertiary,
                    disabledActiveTrackColor = AppColors.TextTertiary,
                    disabledInactiveTrackColor = AppColors.SurfaceMuted,
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(AppColors.Accent, CircleShape),
                    )
                },
                track = { sliderState ->
                    val fraction = if (sliderState.valueRange.endInclusive - sliderState.valueRange.start == 0f) 0f
                        else (sliderState.value - sliderState.valueRange.start) / (sliderState.valueRange.endInclusive - sliderState.valueRange.start)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(AppColors.SurfaceMuted),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppColors.Accent),
                        )
                    }
                },
            )
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
                .crossfade(200)
                .build(),
            contentDescription = title,
            imageLoader = imageLoader,
            modifier = modifier,
            contentScale = contentScale,
            placeholder = ColorPainter(AppColors.SurfaceMuted),
            error = ColorPainter(AppColors.SurfaceMuted),
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
    if (epochMillis == null) return Strings.notSynced
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
