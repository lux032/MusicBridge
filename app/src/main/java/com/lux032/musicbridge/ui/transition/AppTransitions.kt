package com.lux032.musicbridge

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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

object TransitionDurations {
    const val PageFadeInMillis = 240
    const val PageFadeOutMillis = 180
    /** Player expand/collapse crossfade — faster than the generic page fades; the shared
     *  artwork/container morph is the star, so the pages must not lag behind it. */
    const val PlayerFadeInMillis = 140
    const val PlayerFadeOutMillis = 110
    /** Push/Pop horizontal slide — snappier than the 600ms ambient background gradient,
     *  slow enough for the parallax to read. */
    const val PageSlideMillis = 320
    const val TabFadeInMillis = 280
    /** Player collapse (back from the full player): the whole page drops as one sheet. */
    const val PlayerCollapseMillis = 380
}

/** Player collapse curve. Starts already moving (~1.5x average speed, so a released
 *  swipe carries straight on) but with a capped peak velocity. M3 "emphasized
 *  decelerate" (0.05, 0.7, 0.1, 1) was tried first: its ~14x initial slope made a
 *  full-height sheet jump ~1/5 of the screen per frame, which the eye reads as
 *  strobing afterimages (残影). */
val PlayerCollapseEasing = CubicBezierEasing(0.2f, 0.3f, 0.1f, 1f)

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
fun AnimatedContentTransitionScope<NavigationEntry>.sectionTransitionSpec(
    action: NavAction,
): ContentTransform {
    // Collapse: ONE opaque thing moves — the player page drops as a sheet with all its
    // content (artwork included) attached. The page beneath is simply there, fully opaque
    // and still; the dock is static on top. Earlier attempts layered a flying artwork, a
    // fading/scaling underlay and a fading mini bar on the moving sheet, and the
    // overlapping semi-transparent motions read as ghosting.
    if (initialState.section == AppSection.PlaybackDetail && action == NavAction.Pop) {
        return ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = slideOutVertically(
                tween(TransitionDurations.PlayerCollapseMillis, easing = PlayerCollapseEasing),
            ) { it },
            // The dropping sheet must stay above the page it reveals.
            targetContentZIndex = -1f,
            sizeTransform = SizeTransform(clip = false),
        )
    }
    val playerInvolved = targetState.section == AppSection.PlaybackDetail ||
        initialState.section == AppSection.PlaybackDetail
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

/** Player-side (inside AnimatedContent) shared element, e.g. the big artwork.
 *  Enter-only: the expand morphs mini -> player, but on exit the element stays in the
 *  page and drops with the sheet (a separately flying artwork caused ghosting). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.playerSharedElement(key: Any): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val animScope = LocalAnimatedVisibilityScope.current ?: return this
    if (animScope.transition.targetState != EnterExitState.Visible) return this
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

/** Mini-player-side shared element with caller-managed visibility: the element is hidden
 *  (and handed to the transition overlay) whenever [visible] is false, i.e. while the
 *  full player is open, so only one end of each shared key is ever on screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.miniSharedElement(key: Any, visible: Boolean): Modifier {
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    // Collapse guard. The player end is enter-only, so on collapse this end has no partner,
    // yet the shared state still remembers the player's full-screen bounds: the mini bar
    // would morph from full screen down to the dock, drawn in the overlay on top of
    // everything (a dark dock-coloured curtain falling over the screen — confirmed by a
    // frame-by-frame screen recording). So when visibility flips back to true, detach the
    // shared element for the collapse window, then re-attach with fresh state, ready for
    // the next expand. Decided synchronously in composition so the very first collapse
    // frame is already detached.
    val guard = remember { CollapseGuard(lastVisible = visible) }
    // Bumped when the guard window ends, forcing a recomposition that re-attaches.
    var reattachTick by remember { mutableIntStateOf(0) }
    if (visible && !guard.lastVisible) {
        guard.detachedUntilNanos = System.nanoTime() + CollapseGuardMillis * 1_000_000L
    }
    guard.lastVisible = visible
    val untilNanos = guard.detachedUntilNanos
    LaunchedEffect(untilNanos) {
        val remainingMillis = (untilNanos - System.nanoTime()) / 1_000_000L
        if (remainingMillis > 0L) {
            delay(remainingMillis + 16L)
            reattachTick++
        }
    }
    val detached = reattachTick >= 0 && visible && System.nanoTime() < untilNanos
    if (detached) return this
    return with(sharedScope) {
        this@miniSharedElement.sharedElementWithCallerManagedVisibility(
            rememberSharedContentState(key),
            visible = visible,
            boundsTransform = PlayerBoundsTransform,
        )
    }
}

private const val CollapseGuardMillis = TransitionDurations.PlayerCollapseMillis + 60L

private class CollapseGuard(var lastVisible: Boolean) {
    var detachedUntilNanos: Long = 0L
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
            // No exit of its own: on collapse the content rides down with the sheet; on
            // other exits the page-level fade already covers it.
            exit = ExitTransition.None,
        )
    }
}
