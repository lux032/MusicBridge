package com.lux032.musicbridge.state

/**
 * Position at which a play is reported via `scrobble`.
 *
 * 032 servers count a play (and queue the Last.fm scrobble) once half of the track has
 * been played, matching the web player. Reporting later would lose plays that are left
 * between 50% and the end. Plex keeps its own "mark as played" convention of 90%.
 */
internal fun scrobbleThresholdMillis(durationMillis: Long, is032Source: Boolean): Long =
    if (is032Source) durationMillis / 2 else (durationMillis * 0.9f).toLong()
