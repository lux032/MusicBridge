package com.lux032.musicbridge.local

import com.lux032.musicbridge.plex.withPlexTranscodeOffsetSeconds

/** A seek plan keeps direct files on Media3's native path and restarts only Plex live streams. */
internal data class LocalSeekPlan(
    val url: String,
    val playerPositionMillis: Long,
    val logicalBaseMillis: Long,
    val restartsLiveTranscode: Boolean,
) {
    val effectiveTargetMillis: Long
        get() = logicalBaseMillis + playerPositionMillis
}

internal fun buildLocalSeekPlan(url: String, positionMillis: Long): LocalSeekPlan {
    val safePositionMillis = positionMillis.coerceAtLeast(0L)
    if (!url.isPlexLiveTranscodeUrl()) {
        return LocalSeekPlan(
            url = url,
            playerPositionMillis = safePositionMillis,
            logicalBaseMillis = 0L,
            restartsLiveTranscode = false,
        )
    }

    // Plex's universal-transcode offset accepts whole seconds. The restarted response has
    // its own zero-based Media3 timeline, so retain the source-track second as a logical base.
    val offsetSeconds = (safePositionMillis / 1_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val logicalBaseMillis = offsetSeconds * 1_000L
    val offsetUrl = url.withPlexTranscodeOffsetSeconds(offsetSeconds)
    return LocalSeekPlan(
        url = if (offsetSeconds > 0) offsetUrl.withPlexFastSeek() else offsetUrl,
        playerPositionMillis = 0L,
        logicalBaseMillis = logicalBaseMillis,
        restartsLiveTranscode = true,
    )
}

private fun String.withPlexFastSeek(): String {
    val fragment = substringAfter('#', "").takeIf { contains('#') }
    val withoutFragment = substringBefore('#')
    val baseUrl = withoutFragment.substringBefore('?')
    val query = withoutFragment.substringAfter('?', "")
    var replaced = false
    val updatedQuery = query
        .split('&')
        .filter(String::isNotBlank)
        .map { parameter ->
            if (parameter.substringBefore('=').equals("fastSeek", ignoreCase = true)) {
                replaced = true
                "fastSeek=1"
            } else {
                parameter
            }
        }
        .toMutableList()
        .apply { if (!replaced) add("fastSeek=1") }
        .joinToString("&")
    return buildString {
        append(baseUrl)
        if (updatedQuery.isNotEmpty()) append('?').append(updatedQuery)
        fragment?.let { append('#').append(it) }
    }
}

internal fun String.isPlexLiveTranscodeUrl(): Boolean =
    contains("/:/transcode/universal/start", ignoreCase = true)
