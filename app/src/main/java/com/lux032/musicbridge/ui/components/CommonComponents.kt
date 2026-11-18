package com.lux032.musicbridge

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.sonos.SonosRoom
import com.lux032.musicbridge.music.MusicAlbum
import androidx.compose.foundation.Image
import coil3.compose.AsyncImage
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.painter.ColorPainter
import kotlin.math.roundToInt
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
    // Toggle key: tonal while off, inverted to the light hi fill while favourited.
    Key(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        style = if (isFavorite) KeyStyle.Hi else KeyStyle.Tonal,
        enabled = !isLoading,
    ) {
        FavoriteIconGraphic(
            isFavorite = isFavorite,
            tint = if (isFavorite) keyHiInk() else AppColors.TextPrimary,
            baseSize = 22.dp,
            selectedSize = 22.dp,
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
        // Bare glyph (utility tier): offset so the arrow optically aligns with the content edge.
        BareIcon(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = contentDescription,
            onClick = onClick,
            tint = AppColors.TextPrimary,
            modifier = Modifier.offset(x = (-12).dp),
        )
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
    // Legacy entry point, now a borderless squircle key (48dp). Callers that passed a custom
    // container keep it; everything else is the tonal secondary key.
    Key(
        onClick = onClick,
        modifier = modifier.size(48.dp),
        style = if (highlighted) KeyStyle.Hi else KeyStyle.Tonal,
        enabled = enabled,
        containerColor = if (highlighted) null else containerColor,
        content = content,
    )
}

/** Height of the docked navigation bar's content (system navigation inset is added below it). */
internal val DockedNavigationHeight = 72.dp

@Composable
internal fun BottomNavigationBar(
    primarySection: AppSection,
    modifier: Modifier = Modifier,
    onSectionChange: (AppSection) -> Unit,
) {
    val navigationBarBottomInset = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()
    val sections = listOf(AppSection.Home, AppSection.Search, AppSection.Playlists, AppSection.Settings)

    // Docked Material navigation bar (Design D): it shares the deep dock colour with the room
    // bar above it, so the two read as one bottom block instead of two floating pills.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(dockColor())
            .padding(bottom = navigationBarBottomInset)
            .height(DockedNavigationHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
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

/** Keeps once-per-second playback progress updates inside the mini-player subtree. */
@Composable
internal fun BottomMiniPlayerHost(
    appState: AppState,
    modifier: Modifier = Modifier,
    isPlayerOpen: Boolean = false,
    bottomInset: Dp = 0.dp,
    onOpenPlayback: () -> Unit,
) {
    val playerState = appState.miniPlayerState ?: return
    val volume = if (playerState.target is PlaybackTarget.Sonos && appState.hasLoadedSonosVolume) {
        appState.sonosVolume.toInt()
    } else {
        null
    }
    BottomMiniPlayer(
        state = playerState,
        modifier = modifier,
        isPlayerOpen = isPlayerOpen,
        volume = volume,
        bottomInset = bottomInset,
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
    val systemBarPadding = WindowInsets.safeDrawing.asPaddingValues()

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(NavigationRailWidth),
        color = dockColor(),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = systemBarPadding.calculateTopPadding() + 20.dp,
                    bottom = systemBarPadding.calculateBottomPadding() + 20.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            listOf(
                AppSection.Home,
                AppSection.Search,
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
        AppSection.Search -> NavIcon.Search
        AppSection.Artists -> NavIcon.Artists
        AppSection.Playlists -> NavIcon.Playlists
        AppSection.Settings -> NavIcon.Settings
        else -> NavIcon.Home
    }
    val label = when (section) {
        AppSection.Home -> Strings.home
        AppSection.Search -> Strings.search
        AppSection.Artists -> Strings.artists
        AppSection.Playlists -> Strings.playlists
        AppSection.Settings -> Strings.settings
        else -> ""
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "nav_button_scale",
    )
    // Selected destination: the squircle indicator inverts to the light hi key with a dark
    // glyph (Design D toggle language); the label brightens and bolds.
    val indicatorColor by animateColorAsState(
        targetValue = if (isSelected) KeyColors.Hi else Color.Transparent,
        label = "nav_indicator",
    )
    val iconTint by animateColorAsState(
        targetValue = if (isSelected) keyHiInk() else AppColors.TextTertiary,
        label = "nav_icon_tint",
    )
    val labelColor by animateColorAsState(
        targetValue = if (isSelected) AppColors.TextPrimary else AppColors.TextTertiary,
        label = "nav_label_tint",
    )
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 32.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(indicatorColor),
            contentAlignment = Alignment.Center,
        ) {
            NavIconGraphic(
                icon = icon,
                tint = iconTint,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = label,
            color = labelColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
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
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = LocalArtworkPalette.current.accent,
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
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = AppColors.CardSurface,
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
            if (actionLabel != null && onAction != null) {
                LabelKey(text = actionLabel, onClick = onAction, style = KeyStyle.Hi)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumCoverCard(
    album: MusicAlbum,
    selected: Boolean,
    onClick: () -> Unit,
    onQueueAction: ((QueuePlacement) -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    compact: Boolean = false,
    subtitleOverride: String? = null,
    nowPlaying: NowPlayingMarker? = LocalNowPlaying.current,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    var showQueueMenu by remember { mutableStateOf(false) }
    val playingFromAlbum = nowPlaying?.takeIf { it.matchesAlbum(album) }

    Box {
    Surface(
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { if (onQueueAction != null) showQueueMenu = true },
        ),
        // Rectangle on purpose: the Surface is a transparent click container, and a rounded
        // shape here would make Surface clip its content — the rounded bottom corners shaved
        // off the bottom of the first/last character of the artist line (音 looked broken).
        // The artwork carries its own rounded clip, so nothing visual depends on this shape.
        shape = RectangleShape,
        color = Color.Transparent,
        border = BorderStroke(0.dp, Color.Transparent),
        shadowElevation = 0.dp,
    ) {
        if (compact) {
            AlbumCoverArtwork(album, playingFromAlbum)
        } else {
            var centerCaption by remember(album.thumbUrl) { mutableStateOf(false) }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlbumCoverArtwork(album, playingFromAlbum, onCenterCaption = { centerCaption = it })
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = album.title,
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (layoutInfo.isWide) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (centerCaption) TextAlign.Center else TextAlign.Start,
                    )
                    Text(
                        text = subtitleOverride ?: album.artistName ?: Strings.unknownArtist,
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (centerCaption) TextAlign.Center else TextAlign.Start,
                    )
                    if (selected) {
                        Text(
                            text = Strings.currentViewing,
                            modifier = Modifier.fillMaxWidth(),
                            color = AppColors.TextTertiary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            textAlign = if (centerCaption) TextAlign.Center else TextAlign.Start,
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
            onPlay = onPlay?.let { playAction ->
                {
                    showQueueMenu = false
                    playAction()
                }
            },
            onAddToPlaylist = addAlbumToPlaylistAction(album),
        )
    }
}

@Composable
private fun AlbumCoverArtwork(
    album: MusicAlbum,
    nowPlaying: NowPlayingMarker?,
    onCenterCaption: ((Boolean) -> Unit)? = null,
) {
    val layoutInfo = LocalAppLayoutInfo.current
    // The square slot stays put so the grid doesn't reflow. The cover itself
    // shrinks to its aspect and sits on the bottom edge, just above the title.
    AsyncAlbumArtwork(
        imageUrl = album.thumbUrl,
        title = album.title,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        thumbnailSize = layoutInfo.albumGridMinimumWidth * 1.1f,
        fit = ArtworkFit.Contain,
        alignment = Alignment.BottomCenter,
        shape = RoundedCornerShape(12.dp),
        onCenterCaption = onCenterCaption,
        overlay = { AlbumCoverOverlayBadges(nowPlaying, album.discCount) },
    )
}

/**
 * Tracks where the user pressed on a row so long-pressing opens its context menu right at the
 * finger, while tapping the trailing `⋯` button opens the menu anchored to that button.
 */
@Stable
internal class ContextualMenuState {
    var expanded by mutableStateOf(false)
        private set
    var pressOffset by mutableStateOf<IntOffset?>(null)
        private set
    internal var lastDownOffset: Offset? = null

    fun openAtLastPress() {
        pressOffset = lastDownOffset?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
        expanded = true
    }

    fun openAtAnchor() {
        pressOffset = null
        expanded = true
    }

    fun dismiss() {
        expanded = false
    }
}

@Composable
internal fun rememberContextualMenuState(): ContextualMenuState = remember { ContextualMenuState() }

internal fun Modifier.trackMenuPressPosition(menuState: ContextualMenuState): Modifier =
    pointerInput(menuState) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            menuState.lastDownOffset = down.position
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
            } while (event.changes.any { it.pressed })
            menuState.lastDownOffset = null
        }
    }

@Composable
internal fun ContextualMenuAnchor(
    menuState: ContextualMenuState,
    menu: @Composable () -> Unit,
) {
    val offset = menuState.pressOffset ?: return
    Box(modifier = Modifier.offset { offset }.size(0.dp)) {
        menu()
    }
}

@Composable
internal fun QueueActionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (QueuePlacement) -> Unit,
    onPlay: (() -> Unit)? = null,
    onShowTrackInfo: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    downloadLabel: String? = null,
    onDownloadAction: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    recommendationBlocked: Boolean = false,
    onToggleRecommendationBlock: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, shape = RoundedCornerShape(16.dp)) {
        if (onPlay != null) {
            DropdownMenuItem(
                text = { Text(Strings.play) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onPlay()
                },
            )
            HorizontalDivider(
                color = KeyColors.Line,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (onShowTrackInfo != null) {
            DropdownMenuItem(
                text = { Text(Strings.trackInfoAndCredits) },
                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onShowTrackInfo()
                },
            )
            HorizontalDivider(
                color = KeyColors.Line,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (onDownloadAction != null) {
            DropdownMenuItem(
                text = { Text(downloadLabel ?: if (isDownloaded) Strings.deleteDownloaded else Strings.downloadOriginal) },
                leadingIcon = {
                    Icon(
                        if (isDownloaded) Icons.Filled.Delete else Icons.Filled.Download,
                        contentDescription = null,
                        tint = if (isDownloaded) AppColors.TextSecondary else AppColors.TextPrimary,
                    )
                },
                onClick = {
                    onDismiss()
                    onDownloadAction()
                },
            )
            HorizontalDivider(
                color = KeyColors.Line,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (onAddToPlaylist != null || onRemoveFromPlaylist != null) {
            if (onAddToPlaylist != null) {
                DropdownMenuItem(
                    text = { Text(Strings.addToPlaylist) },
                    leadingIcon = { Icon(Icons.Filled.LibraryAdd, contentDescription = null) },
                    onClick = {
                        onDismiss()
                        onAddToPlaylist()
                    },
                )
            }
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text(Strings.removeFromPlaylist) },
                    leadingIcon = {
                        Icon(Icons.Filled.PlaylistRemove, contentDescription = null, tint = AppColors.TextSecondary)
                    },
                    onClick = {
                        onDismiss()
                        onRemoveFromPlaylist()
                    },
                )
            }
            HorizontalDivider(
                color = KeyColors.Line,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (onToggleFavorite != null) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) Strings.searchUnfavoriteAction else Strings.searchFavoriteAction) },
                leadingIcon = {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onDismiss()
                    onToggleFavorite()
                },
            )
            HorizontalDivider(
                color = KeyColors.Line,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        if (onToggleRecommendationBlock != null) {
            DropdownMenuItem(
                text = { Text(if (recommendationBlocked) Strings.unblockRecommendation else Strings.blockRecommendation) },
                leadingIcon = {
                    Icon(
                        if (recommendationBlocked) Icons.Filled.Restore else Icons.Filled.Block,
                        contentDescription = null,
                        tint = AppColors.TextSecondary,
                    )
                },
                onClick = {
                    onDismiss()
                    onToggleRecommendationBlock()
                },
            )
            HorizontalDivider(
                color = KeyColors.Line,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
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
        NavIcon.Search -> Icon(Icons.Filled.Search, Strings.search, tint = tint, modifier = modifier)
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
        // Docked chrome tone, no outline: reads as the same family as the room bar.
        color = dockColor(),
        shadowElevation = 12.dp,
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
    fit: ArtworkFit = ArtworkFit.Cover,
    alignment: Alignment = Alignment.Center,
    shape: Shape = RectangleShape,
    imageModifier: Modifier = Modifier,
    artworkModifier: Modifier = Modifier,
    onCenterCaption: ((Boolean) -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    if (fit == ArtworkFit.Contain) {
        ContainAlbumArtwork(
            imageUrl = imageUrl,
            title = title,
            modifier = modifier,
            thumbnailSize = thumbnailSize,
            placeholderThumbnailSize = placeholderThumbnailSize,
            alignment = alignment,
            shape = shape,
            imageModifier = imageModifier,
            artworkModifier = artworkModifier,
            onCenterCaption = onCenterCaption,
            overlay = overlay,
        )
        return
    }
    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }

    if (imageUrl != null) {
        val density = LocalDensity.current
        val requestUrl = remember(imageUrl, thumbnailSize, density) {
            if (thumbnailSize != null) {
                thumbnailUrl(imageUrl, with(density) { thumbnailSize.roundToPx() })
            } else {
                imageUrl
            }
        }
        // Shared-element morphs swap from the small mini-player request to a larger one with
        // a different cache key; pointing the placeholder at the small request's cache entry
        // keeps the first frame of the morph a real cover instead of a grey flash.
        val placeholderCacheKey = remember(imageUrl, placeholderThumbnailSize, density) {
            placeholderThumbnailSize?.let {
                thumbnailUrl(imageUrl, with(density) { it.roundToPx() })
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

/**
 * [ArtworkFit.Contain] inside a fixed slot. [modifier] is the reserved box
 * (it does not change size when the image arrives). The decoded bitmap's
 * intrinsic size drives [fittedSize]; the image is then center-cropped into
 * that box so clamped extremes and near-square snaps match the Rust path.
 * The muted placeholder is only shown until a bitmap with a real aspect exists.
 */
@Composable
private fun ContainAlbumArtwork(
    imageUrl: String?,
    title: String,
    modifier: Modifier,
    thumbnailSize: Dp?,
    placeholderThumbnailSize: Dp?,
    alignment: Alignment,
    shape: Shape,
    imageModifier: Modifier,
    artworkModifier: Modifier,
    onCenterCaption: ((Boolean) -> Unit)?,
    overlay: @Composable BoxScope.() -> Unit,
) {
    if (imageUrl == null) {
        SideEffect { onCenterCaption?.invoke(false) }
        Box(modifier) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(imageModifier)
                    .clip(shape)
                    .background(AppColors.SurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = AppColors.TextSecondary,
                    modifier = Modifier.size(48.dp),
                )
                overlay()
            }
        }
        return
    }

    val context = LocalContext.current
    val imageLoader = remember(context) { AlbumArtworkImageLoader.get(context) }
    val density = LocalDensity.current
    val requestUrl = remember(imageUrl, thumbnailSize, density) {
        if (thumbnailSize != null) {
            thumbnailUrl(imageUrl, with(density) { thumbnailSize.roundToPx() })
        } else {
            imageUrl
        }
    }
    val placeholderCacheKey = remember(imageUrl, placeholderThumbnailSize, density) {
        placeholderThumbnailSize?.let {
            thumbnailUrl(imageUrl, with(density) { it.roundToPx() })
        }
    }
    val imageRequest = remember(requestUrl, placeholderCacheKey, context) {
        ImageRequest.Builder(context)
            .data(requestUrl)
            .apply { placeholderCacheKey?.let { placeholderMemoryCacheKey(it) } }
            .build()
    }
    // rememberAsyncImagePainter decodes at the file's own size (Coil falls
    // back to SizeResolver.ORIGINAL), so intrinsicSize is the real aspect
    // rather than the slot we happen to draw into.
    val painter = rememberAsyncImagePainter(
        model = imageRequest,
        imageLoader = imageLoader,
        contentScale = ContentScale.Crop,
    )
    val source = painter.intrinsicSize
    val hasAspect = source.width.isFinite() && source.height.isFinite() &&
        source.width > 0f && source.height > 0f

    BoxWithConstraints(modifier) {
        val slotW = constraints.maxWidth
        val slotH = constraints.maxHeight
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight &&
            slotW > 0 && slotH > 0 && slotW != androidx.compose.ui.unit.Constraints.Infinity &&
            slotH != androidx.compose.ui.unit.Constraints.Infinity
        val (boxW, boxH) = if (bounded && hasAspect) {
            fittedSize(
                source.width.roundToInt().coerceAtLeast(1),
                source.height.roundToInt().coerceAtLeast(1),
                slotW,
                slotH,
            )
        } else if (bounded) {
            slotW to slotH
        } else {
            0 to 0
        }
        val narrower = bounded && hasAspect && boxW < slotW - 1
        SideEffect { onCenterCaption?.invoke(narrower) }
        Box(Modifier.fillMaxSize()) {
            if (!bounded) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(imageModifier)
                        .clip(shape)
                        .background(if (hasAspect) Color.Transparent else AppColors.SurfaceMuted),
                ) {
                    if (hasAspect) {
                        Image(
                            painter = painter,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize().then(artworkModifier),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    overlay()
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(alignment)
                        .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                        .then(imageModifier)
                        .clip(shape)
                        .then(if (hasAspect) Modifier else Modifier.background(AppColors.SurfaceMuted)),
                ) {
                    if (hasAspect) {
                        Image(
                            painter = painter,
                            contentDescription = title,
                            modifier = Modifier.fillMaxSize().then(artworkModifier),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    overlay()
                }
            }
        }
    }
}

internal fun MusicServerProfile.hasCredentials(): Boolean = when (type) {
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
