package com.lux032.musicbridge

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.plex.PlexAlbum
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
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
            tint = if (isFavorite) LocalArtworkPalette.current.accent else AppColors.TextSecondary,
        )
    }
}

/**
 * Small translucent back button matching the player page's top bar. The Box wrapper keeps it
 * left-aligned at its natural size inside lazy grids/lists, whose tight width constraints
 * would otherwise stretch the circle into a full-width pill.
 */
@Composable
internal fun BackCircleButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        IconCircleButton(
            onClick = onClick,
            containerColor = Color.White.copy(alpha = 0.10f),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = contentDescription,
                tint = AppColors.TextPrimary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
internal fun IconCircleButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    containerColor: Color? = null,
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
        color = if (highlighted) AppColors.CardSurfaceStrong else (containerColor ?: AppColors.CardSurface),
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
    val palette = LocalArtworkPalette.current
    val sections = listOf(AppSection.Home, AppSection.Artists, AppSection.Playlists, AppSection.Settings)
    val selectedIndex = sections.indexOf(primarySection).coerceAtLeast(0)

    // The capsule's two edges follow the selection with springs of different stiffness, so the
    // faster edge leads and the slower one trails: the pill stretches while it travels
    // (位移+形变) and settles once both edges arrive.
    val leadingEdge by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 500f),
        label = "nav_pill_leading_edge",
    )
    val trailingEdge by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.92f, stiffness = Spring.StiffnessLow),
        label = "nav_pill_trailing_edge",
    )

    // Floating rounded pill matching the player page's action bar: tinted translucent
    // container, hairline light border, icon + label items.
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 16.dp,
                end = 16.dp,
                bottom = navigationBarBottomInset + 10.dp,
            ),
        shape = RoundedCornerShape(28.dp),
        color = palette.backgroundBottom.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
        shadowElevation = 8.dp,
    ) {
        var rowSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            if (rowSize.width > 0 && rowSize.height > 0) {
                val density = LocalDensity.current
                val cellWidth = with(density) { (rowSize.width / sections.size).toDp() }
                val leftEdge = minOf(leadingEdge, trailingEdge)
                val rightEdge = maxOf(leadingEdge, trailingEdge) + 1f
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = cellWidth * leftEdge + 4.dp)
                        .width(cellWidth * (rightEdge - leftEdge) - 8.dp)
                        .height(with(density) { rowSize.height.toDp() } - 8.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(palette.accent.copy(alpha = 0.16f)),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { rowSize = it },
            ) {
                sections.forEach { section ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        BottomNavButton(
                            section = section,
                            isSelected = primarySection == section,
                            onClick = { onSectionChange(section) },
                        )
                    }
                }
            }
        }
    }
}

/** Keeps once-per-second playback progress updates inside the mini-player subtree. */
@Composable
internal fun BottomMiniPlayerHost(
    appState: AppState,
    modifier: Modifier = Modifier,
    isPlayerOpen: Boolean = false,
    onOpenPlayback: () -> Unit,
) {
    val playerState = appState.miniPlayerState ?: return
    BottomMiniPlayer(
        state = playerState,
        modifier = modifier,
        isPlayerOpen = isPlayerOpen,
        onArtworkClick = onOpenPlayback,
        onTogglePause = { appState.togglePause(playerState) },
    )
}

@Composable
internal fun AppNavigationRail(
    primarySection: AppSection,
    modifier: Modifier = Modifier,
    onSectionChange: (AppSection) -> Unit,
) {
    val systemBarPadding = WindowInsets.systemBars.asPaddingValues()

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(104.dp),
        color = LocalArtworkPalette.current.backgroundBottom.copy(alpha = 0.94f),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(
                    top = systemBarPadding.calculateTopPadding() + 20.dp,
                    bottom = systemBarPadding.calculateBottomPadding() + 20.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            listOf(
                AppSection.Home,
                AppSection.Artists,
                AppSection.Playlists,
                AppSection.Settings,
            ).forEach { section ->
                BottomNavButton(
                    section = section,
                    isSelected = primarySection == section,
                    onClick = { onSectionChange(section) },
                )
            }
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
        targetValue = if (isSelected) LocalArtworkPalette.current.accent else AppColors.TextSecondary,
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
        MessageTone.Info -> AppColors.CardSurface
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
        color = AppColors.CardSurface,
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
        color = AppColors.CardSurface,
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumCoverCard(
    album: PlexAlbum,
    selected: Boolean,
    onClick: () -> Unit,
    onQueueAction: ((QueuePlacement) -> Unit)? = null,
    compact: Boolean = false,
    subtitleOverride: String? = null,
    sharedArtworkKey: Any? = null,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    var showQueueMenu by remember { mutableStateOf(false) }
    // When a key is supplied the cover joins the section transition as a shared element
    // (list -> detail morph); null keeps the card a plain static cover.
    val sharedArtworkModifier = if (sharedArtworkKey != null) {
        Modifier.sectionSharedElement(sharedArtworkKey)
    } else {
        Modifier
    }

    Box {
    Surface(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { if (onQueueAction != null) showQueueMenu = true },
        ),
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
                    .then(sharedArtworkModifier)
                    .clip(RoundedCornerShape(18.dp)),
                contentScale = ContentScale.Crop,
                thumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
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
                        .then(sharedArtworkModifier)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop,
                    thumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
                )
                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = album.title,
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (layoutInfo.isWide) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = subtitleOverride ?: album.artistName ?: Strings.unknownArtist,
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
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
        QueueActionMenu(
            expanded = showQueueMenu,
            onDismiss = { showQueueMenu = false },
            onAction = { placement ->
                showQueueMenu = false
                onQueueAction?.invoke(placement)
            },
        )
    }
}

@Composable
internal fun QueueActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (QueuePlacement) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Text(
            text = Strings.addToPlaybackQueue,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.labelMedium,
        )
        DropdownMenuItem(
            text = { Text(Strings.playNext) },
            leadingIcon = { Icon(Icons.Filled.SkipNext, contentDescription = null) },
            onClick = { onAction(QueuePlacement.Next) },
        )
        DropdownMenuItem(
            text = { Text(Strings.addToQueueEnd) },
            leadingIcon = { Icon(Icons.Filled.PlaylistAdd, contentDescription = null) },
            onClick = { onAction(QueuePlacement.End) },
        )
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
    // Honor the caller-provided tint for the favorite state so the playback screen can
    // recolor the heart with the artwork palette accent.
    val animatedTint by animateColorAsState(
        targetValue = if (isFavorite) tint else AppColors.TextSecondary,
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
    val accent = LocalArtworkPalette.current.accent
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
                    color = accent,
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
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = AppColors.SurfaceMuted,
                    disabledThumbColor = AppColors.TextTertiary,
                    disabledActiveTrackColor = AppColors.TextTertiary,
                    disabledInactiveTrackColor = AppColors.SurfaceMuted,
                ),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(accent, CircleShape),
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
                                .background(accent),
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
    thumbnailSize: Dp? = null,
    placeholderThumbnailSize: Dp? = null,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }

    if (imageUrl != null) {
        val density = LocalDensity.current
        val requestUrl = remember(imageUrl, thumbnailSize, density) {
            if (thumbnailSize != null) {
                plexThumbnailUrl(imageUrl, with(density) { thumbnailSize.roundToPx() })
            } else {
                imageUrl
            }
        }
        // Shared-element morphs swap from the small mini-player request to a larger one with
        // a different cache key; pointing the placeholder at the small request's cache entry
        // keeps the first frame of the morph a real cover instead of a grey flash.
        val placeholderCacheKey = remember(imageUrl, placeholderThumbnailSize, density) {
            placeholderThumbnailSize?.let {
                plexThumbnailUrl(imageUrl, with(density) { it.roundToPx() })
            }
        }
        val imageRequest = remember(requestUrl, placeholderCacheKey, context) {
            ImageRequest.Builder(context)
                .data(requestUrl)
                .apply { placeholderCacheKey?.let { placeholderMemoryCacheKey(it) } }
                .build()
        }
        AsyncImage(
            model = imageRequest,
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

internal fun MusicServerProfile.hasCredentials(): Boolean = when (type) {
    MusicServerType.Plex -> token.isNotBlank() || (username.isNotBlank() && password.isNotBlank())
    MusicServerType.Music032 -> baseUrl.isNotBlank() && token.isNotBlank()
}

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
