package com.lux032.musicbridge

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.outlined.ThumbDown
import androidx.compose.material.icons.outlined.ThumbUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.lux032.musicbridge.music.Music032DailyMix
import com.lux032.musicbridge.music.Music032DailyMixReasons

private val MixCardHeight = 148.dp
private val MixCardMosaic = 124.dp
private val MixCardMaxWidth = 420.dp
private val MixCardPeek = 28.dp

/** “宁静放松 · 摇滚”, or the theme type for work / artist / rediscover themes. */
internal fun Music032DailyMix.themeLine(): String =
    tags.joinToString(" · ") { it.label }.ifBlank { themeTypeLabel }

internal fun Music032DailyMix.minutesLine(): String =
    Strings.dailyMixMinutes(trackCount, (durationMillis + 30_000) / 60_000)

/**
 * Home “今日推荐” (design B, compact strip): one mix per page, a 2×2 cover mosaic on a card
 * tinted from the mix's own first cover, title / theme / one-line description, and a light
 * play key. The next card peeks from the right edge so the row reads as swipeable.
 */
@Composable
internal fun DailyMixBanner(
    state: DailyMixHomeState,
    playingMixKey: String?,
    onOpen: (Music032DailyMix) -> Unit,
    onPlay: (Music032DailyMix, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionHeader(title = Strings.dailyMixes, modifier = Modifier.padding(top = 4.dp))
        if (state.items.isEmpty()) {
            DailyMixGeneratingCard()
            return@Column
        }
        val pagerState = rememberPagerState(pageCount = { state.items.size })
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val single = state.items.size == 1
            val cardWidth = if (single) min(maxWidth, MixCardMaxWidth) else min(maxWidth - MixCardPeek, MixCardMaxWidth)
            HorizontalPager(
                state = pagerState,
                pageSize = PageSize.Fixed(cardWidth),
                pageSpacing = 10.dp,
                beyondViewportPageCount = 1,
                key = { state.items[it].id },
                modifier = Modifier.fillMaxWidth(),
            ) { page ->
                val mix = state.items[page]
                DailyMixCard(
                    mix = mix,
                    isPlaying = playingMixKey == dailyMixPlaylistKey(mix.id),
                    onOpen = { onOpen(mix) },
                    onPlay = { shuffle -> onPlay(mix, shuffle) },
                )
            }
        }
        if (state.items.size > 1) {
            PagerDots(count = state.items.size, current = pagerState.currentPage)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DailyMixCard(
    mix: Music032DailyMix,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
) {
    val palette = rememberArtworkPalette(mix.coverUrls.firstOrNull())
    val nowPlaying = LocalNowPlaying.current
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MixCardHeight)
                // A disliked mix stays (the server keeps it for the day) but steps back.
                .alpha(if (mix.rating < 0) 0.6f else 1f)
                .clip(RoundedCornerShape(20.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(palette.backgroundTop, lerp(palette.backgroundBottom, Color.Black, 0.25f)),
                    ),
                )
                .combinedClickable(onClick = onOpen, onLongClick = { showMenu = true })
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(MixCardMosaic)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                DailyMixMosaic(urls = mix.coverUrls, title = mix.title, size = MixCardMosaic)
                if (isPlaying) NowPlayingCoverBadge(nowPlaying)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 2.dp),
            ) {
                Text(
                    text = mix.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 19.sp, lineHeight = 24.sp),
                    color = AppColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = mix.themeLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (mix.description.isNotBlank()) {
                    Text(
                        text = mix.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary.copy(alpha = 0.8f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mix.rating > 0) {
                        Icon(
                            Icons.Filled.ThumbUp,
                            contentDescription = Strings.dailyMixLiked,
                            tint = palette.accent,
                            modifier = Modifier
                                .padding(end = 6.dp)
                                .size(14.dp),
                        )
                    }
                    Text(
                        text = mix.minutesLine(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    IconKey(
                        icon = Icons.Filled.PlayArrow,
                        contentDescription = Strings.playDailyMix(mix.title),
                        onClick = { onPlay(false) },
                        style = KeyStyle.Hi,
                        size = 44.dp,
                        iconSize = 24.dp,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RoundedCornerShape(16.dp),
        ) {
            DropdownMenuItem(
                text = { Text(Strings.play) },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onPlay(false)
                },
            )
            DropdownMenuItem(
                text = { Text(Strings.shuffleShort) },
                leadingIcon = { Icon(Icons.Filled.Shuffle, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onPlay(true)
                },
            )
        }
    }
}

/** Same footprint as a card so the home page doesn't jump when the mixes arrive. */
@Composable
private fun DailyMixGeneratingCard() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(MixCardHeight)
            .clip(RoundedCornerShape(20.dp))
            .background(AppColors.CardSurface)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.size(MixCardMosaic),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            repeat(2) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(2) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(AppColors.CardSurface),
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Strings.dailyMixGenerating, style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary)
            Text(Strings.dailyMixGeneratingBody, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
        }
    }
}

@Composable
private fun PagerDots(count: Int, current: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "${current + 1} / $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        repeat(count) { index ->
            val on = index == current
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (on) 18.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (on) AppColors.TextPrimary else AppColors.TextPrimary.copy(alpha = 0.28f)),
            )
        }
    }
}

/** 2×2 mosaic of the mix's first four albums; one full cover when there are fewer. */
@Composable
internal fun DailyMixMosaic(urls: List<String>, title: String, size: Dp, modifier: Modifier = Modifier) {
    if (urls.size < 4) {
        AsyncAlbumArtwork(
            imageUrl = urls.firstOrNull(),
            title = title,
            modifier = modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            thumbnailSize = size,
        )
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        repeat(2) { row ->
            Row(modifier = Modifier.weight(1f)) {
                repeat(2) { column ->
                    AsyncAlbumArtwork(
                        imageUrl = urls[row * 2 + column],
                        title = title,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentScale = ContentScale.Crop,
                        thumbnailSize = size / 2,
                    )
                }
            }
        }
    }
}

/**
 * Daily mix feedback under the play key: like / dislike (with optional reasons), replace (counts
 * against today's budget) and save as an ordinary playlist. Ratings are undone by tapping again.
 */
@Composable
internal fun DailyMixFeedbackPanel(
    detail: DailyMixDetailState,
    onRate: (Int, List<String>) -> Unit,
    onReplace: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val mix = detail.mix
    var showDislike by rememberSaveable { mutableStateOf(false) }
    if (showDislike) {
        DislikeReasonSheet(
            initial = mix.ratingReasons,
            onSubmit = { reasons ->
                showDislike = false
                onRate(-1, reasons)
            },
            onDismiss = { showDislike = false },
        )
    }
    val enabled = !detail.isBusy
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleKey(
                checked = mix.rating > 0,
                icon = Icons.Outlined.ThumbUp,
                checkedIcon = Icons.Filled.ThumbUp,
                contentDescription = if (mix.rating > 0) Strings.dailyMixLiked else Strings.dailyMixLike,
                onClick = { onRate(if (mix.rating > 0) 0 else 1, emptyList()) },
                enabled = enabled,
            )
            ToggleKey(
                checked = mix.rating < 0,
                icon = Icons.Outlined.ThumbDown,
                checkedIcon = Icons.Filled.ThumbDown,
                contentDescription = Strings.dailyMixDislike,
                onClick = { if (mix.rating < 0) onRate(0, emptyList()) else showDislike = true },
                enabled = enabled,
            )
            if (mix.isActive) {
                LabelKey(
                    text = Strings.dailyMixReplace,
                    icon = Icons.Filled.Autorenew,
                    onClick = onReplace,
                    enabled = enabled && detail.replacementsLeft > 0,
                    isLoading = detail.isBusy,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            IconKey(
                icon = Icons.Filled.LibraryAdd,
                contentDescription = if (mix.savedPlaylistId != null) Strings.dailyMixSavedAgain else Strings.dailyMixSave,
                onClick = onSave,
                enabled = enabled,
            )
        }
        val caption = buildList {
            if (mix.rating < 0) {
                val reasons = mix.ratingReasons.joinToString("、") { Strings.dislikeReason(it) }
                add(if (reasons.isBlank()) Strings.dislikedPlain else Strings.dislikedWith(reasons))
            }
            if (mix.isActive) {
                add(if (detail.replacementsLeft > 0) Strings.dailyMixReplacementsLeft(detail.replacementsLeft) else Strings.dailyMixNoReplacements)
            }
        }
        caption.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.TextTertiary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DislikeReasonSheet(
    initial: List<String>,
    onSubmit: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selected by remember { mutableStateOf(initial.toSet()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        containerColor = LocalArtworkPalette.current.backgroundBottom,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(Strings.dislikeTitle, style = MaterialTheme.typography.titleLarge, color = AppColors.TextPrimary)
            Text(
                Strings.dislikeBody,
                style = MaterialTheme.typography.bodyMedium,
                color = AppColors.TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Music032DailyMixReasons.forEach { code ->
                val on = code in selected
                ChoiceRow(
                    selected = on,
                    onClick = { selected = if (on) selected - code else selected + code },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        Strings.dislikeReason(code),
                        color = AppColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            LabelKey(
                text = Strings.dislikeSubmit,
                onClick = { onSubmit(Music032DailyMixReasons.filter { it in selected }) },
                style = KeyStyle.Hi,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
