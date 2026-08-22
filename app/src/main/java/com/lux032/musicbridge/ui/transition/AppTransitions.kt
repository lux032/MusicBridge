package com.lux032.musicbridge

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.lux032.musicbridge.plex.PlexAlbum
import com.lux032.musicbridge.plex.PlexTrackStream

/** Direction of the most recent navigation, recorded by AppState so the section
 *  transition can pick the right motion (P1 uses Push/Pop; P0 only needs player vs. not). */
enum class NavAction { Push, Pop, TabSwitch }

/** Root SharedTransitionLayout scope, provided at the app root so any screen can attach
 *  shared elements without threading the scope through parameters. Null in previews. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The AnimatedContent scope of the currently displayed section, so player-side shared
 *  elements and staggered enter animations can hook into the section transition. */
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** Shared-element keys for the mini player <-> full player morph. */
object PlayerSharedKeys {
    const val Container = "playerContainer"
    // NOTE: the play/pause button is deliberately NOT a shared element — shared elements
    // render in the transition overlay while morphing, and hit-testing stays at the layout
    // position, so a flying button eats taps. Reliability beats the morph here.
    fun artwork(track: PlexTrackStream): String = "playerArtwork-${track.sourceId}-${track.ratingKey}"
}

/** Shared-element keys for the album list -> album detail cover morph (P1.5 pilot). */
object AlbumSharedKeys {
    fun artwork(album: PlexAlbum): String = "albumArtwork-${album.sourceId}-${album.ratingKey}"
}

object TransitionDurations {
    const val PageFadeInMillis = 240
    const val PageFadeOutMillis = 180
    const val DismissSettleMillis = 160
    /** Player expand/collapse crossfade — faster than the generic page fades; the shared
     *  artwork/container morph is the star, so the pages must not lag behind it. */
    const val PlayerFadeInMillis = 140
    const val PlayerFadeOutMillis = 110
    /** Push/Pop horizontal slide — snappier than the 600ms ambient background gradient,
     *  slow enough for the parallax to read. */
    const val PageSlideMillis = 320
    const val TabFadeInMillis = 280
}

/** Bounds morph for shared elements. A fixed 240ms decelerate curve: springs looked right
 *  on paper but measured ~350ms on-device (soft tail + velocity thresholds stretch the
 *  settle), which read as a stall next to ~250ms mainstream players. The tween's end is
 *  deterministic, so expand and collapse both land exactly on time. */
@OptIn(ExperimentalSharedTransitionApi::class)
val PlayerBoundsTransform: BoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    tween(durationMillis = 240, easing = FastOutSlowInEasing)
}

/**
 * Section-level transition spec, dispatched on [action]:
 * - Player involved (either side): the shared elements carry the motion, so the page
 *   itself only crossfades — anything more fights the artwork/container morph.
 * - Push (list -> detail): new page slides in from the right while the old page parallax
 *   drifts left (1/4 speed), fades and shrinks slightly.
 * - Pop (back): exact mirror — detail exits right, list returns from the left parallax.
 * - Tab switch: gentle fade + slight rise, no lateral motion (tabs aren't ordered).
 */
fun AnimatedContentTransitionScope<AppSection>.sectionTransitionSpec(
    action: NavAction,
): ContentTransform {
    val playerInvolved = targetState == AppSection.PlaybackDetail ||
        initialState == AppSection.PlaybackDetail
    if (playerInvolved) {
        return ContentTransform(
            targetContentEnter = fadeIn(tween(TransitionDurations.PlayerFadeInMillis)),
            initialContentExit = fadeOut(tween(TransitionDurations.PlayerFadeOutMillis)),
            sizeTransform = SizeTransform(clip = false),
        )
    }
    return when (action) {
        NavAction.Push -> ContentTransform(
            targetContentEnter = slideInHorizontally(
                tween(TransitionDurations.PageSlideMillis, easing = FastOutSlowInEasing),
            ) { it } + fadeIn(tween(TransitionDurations.PageSlideMillis)),
            initialContentExit = slideOutHorizontally(
                tween(TransitionDurations.PageSlideMillis, easing = FastOutSlowInEasing),
            ) { -it / 4 } + fadeOut(tween(TransitionDurations.PageFadeOutMillis)) +
                scaleOut(
                    tween(TransitionDurations.PageSlideMillis, easing = FastOutSlowInEasing),
                    targetScale = 0.95f,
                ),
            sizeTransform = SizeTransform(clip = false),
        )
        NavAction.Pop -> ContentTransform(
            targetContentEnter = slideInHorizontally(
                tween(TransitionDurations.PageSlideMillis, easing = FastOutSlowInEasing),
            ) { -it / 4 } + fadeIn(tween(TransitionDurations.PageSlideMillis)) +
                scaleIn(
                    tween(TransitionDurations.PageSlideMillis, easing = FastOutSlowInEasing),
                    initialScale = 0.95f,
                ),
            initialContentExit = slideOutHorizontally(
                tween(TransitionDurations.PageSlideMillis, easing = FastOutSlowInEasing),
            ) { it } + fadeOut(tween(TransitionDurations.PageFadeOutMillis)),
            sizeTransform = SizeTransform(clip = false),
        )
        NavAction.TabSwitch -> ContentTransform(
            targetContentEnter = fadeIn(tween(TransitionDurations.TabFadeInMillis)) +
                slideInVertically(tween(TransitionDurations.TabFadeInMillis)) { it / 28 },
            initialContentExit = fadeOut(tween(TransitionDurations.PageFadeOutMillis)),
            sizeTransform = SizeTransform(clip = false),
        )
    }
}

// --- Shared-element modifier helpers (null-safe when no SharedTransitionLayout is present) ---

/** Player-side (inside AnimatedContent) shared element, e.g. the big artwork. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerSharedElement(key: Any): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animScope = LocalAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@playerSharedElement.sharedElement(
            rememberSharedContentState(key),
            animatedVisibilityScope = animScope,
            boundsTransform = PlayerBoundsTransform,
        )
    }
}

/** Player-side (inside AnimatedContent) shared bounds, e.g. the page backdrop / play button.
 *  Implemented via sharedElement — sharedBounds has no caller-managed counterpart on the
 *  mini side, and sharedElement gives the same bounds morph. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerSharedBounds(key: Any): Modifier = playerSharedElement(key)

/** Section-to-section shared element (album grid cover -> detail header, …). Same wiring
 *  as [playerSharedElement]; the neutral name keeps call sites readable. Unmatched keys
 *  degrade to plain fade — e.g. opening a detail whose cover wasn't on the previous page. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sectionSharedElement(key: Any): Modifier = playerSharedElement(key)

/** Mini-player-side shared element with caller-managed visibility: the element is hidden
 *  (and handed to the transition overlay) whenever [visible] is false, i.e. while the
 *  full player is open, so only one end of each shared key is ever on screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.miniSharedElement(key: Any, visible: Boolean): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    return with(sharedScope) {
        this@miniSharedElement.sharedElementWithCallerManagedVisibility(
            rememberSharedContentState(key),
            visible = visible,
            boundsTransform = PlayerBoundsTransform,
        )
    }
}

/** Mini-player-side shared bounds with caller-managed visibility (see [miniSharedElement]).
 *  Implemented via sharedElementWithCallerManagedVisibility — there is no sharedBounds
 *  caller-managed variant in this Compose version, and the bounds morph is equivalent. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.miniSharedBounds(key: Any, visible: Boolean): Modifier = miniSharedElement(key, visible)

/** Content blocks of the player page all fade+rise together, starting from the first
 *  frame of the transition — no stagger. Staggering made the page read as "cover first,
 *  everything else later"; simultaneous entry keeps the whole UI in sync with the morph. */
@Composable
fun Modifier.staggeredPlayerEnter(@Suppress("UNUSED_PARAMETER") index: Int): Modifier {
    val animScope = LocalAnimatedVisibilityScope.current ?: return this
    return with(animScope) {
        this@staggeredPlayerEnter.animateEnterExit(
            enter = fadeIn(tween(TransitionDurations.PlayerFadeInMillis)) +
                slideInVertically(tween(TransitionDurations.PlayerFadeInMillis)) { it / 24 },
            exit = fadeOut(tween(100)),
        )
    }
}
