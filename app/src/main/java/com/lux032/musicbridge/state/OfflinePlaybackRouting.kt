package com.lux032.musicbridge

import com.lux032.musicbridge.plex.PlexTrackStream
import com.lux032.musicbridge.sonos.SonosRoom

/**
 * How an entry tapped inside the offline downloads list should be rendered.
 *
 * The offline list is an album filter, not an output switch: opening it must never move
 * playback off the speaker the user already selected. Only [PlaybackTarget] decides the
 * route, and the offline originals are just the preferred bytes for the local route.
 */
internal enum class OfflinePlaybackRoute {
    /** Play the downloaded original files through the on-device engine. */
    LocalOriginal,

    /** Keep casting: re-hydrate the authoritative server streams and push them to Sonos. */
    SonosHydrated,
}

internal fun offlinePlaybackRoute(target: PlaybackTarget): OfflinePlaybackRoute =
    when (target) {
        PlaybackTarget.LocalDevice -> OfflinePlaybackRoute.LocalOriginal
        is PlaybackTarget.Sonos -> OfflinePlaybackRoute.SonosHydrated
    }

/**
 * Re-derives the queue index after Sonos hydration.
 *
 * Hydration can drop tracks the server no longer exposes, so the offline list position of
 * the tapped track is not a valid index into the hydrated queue. Locate the track by its
 * rating key instead, falling back to the head of the queue.
 */
internal fun resolveHydratedStartIndex(
    hydratedTracks: List<PlexTrackStream>,
    requestedRatingKey: String?,
): Int {
    if (requestedRatingKey.isNullOrBlank()) return 0
    return hydratedTracks.indexOfFirst { it.ratingKey == requestedRatingKey }.takeIf { it >= 0 } ?: 0
}

/**
 * Whether a finished offline hydration may still drive playback.
 *
 * Hydration goes to the network, so the user can switch output or start other playback
 * while it is in flight. A late result must not hijack the queue that took its place, and
 * a late failure must not raise a Sonos error over an output the user already left.
 */
internal fun isOfflineHydrationStillCurrent(
    requestedTarget: PlaybackTarget,
    currentTarget: PlaybackTarget,
    requestedGeneration: Long,
    currentGeneration: Long,
): Boolean = requestedTarget == currentTarget && requestedGeneration == currentGeneration

/**
 * The Sonos room that must be silenced before on-device playback starts, or null.
 *
 * Only a room this app is actually driving qualifies. A room that is merely *selected* as
 * the output may be rendering something the user started from another controller, and
 * stopping it would interrupt playback this app never owned.
 */
internal fun sonosRoomToReleaseForLocalPlayback(activePlayback: MiniPlayerState?): SonosRoom? =
    activePlayback?.sonosRoom
