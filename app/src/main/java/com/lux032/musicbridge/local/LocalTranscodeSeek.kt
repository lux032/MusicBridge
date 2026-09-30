package com.lux032.musicbridge.local

import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.plex.withPlexTranscodeOffsetSeconds

/** A seek plan keeps direct files on Media3's native path and restarts only live transcode streams. */
internal data class LocalSeekPlan(
    val url: String,
    val playerPositionMillis: Long,
    val logicalBaseMillis: Long,
    val restartsLiveTranscode: Boolean,
) {
    val effectiveTargetMillis: Long
        get() = logicalBaseMillis + playerPositionMillis
}

internal fun buildLocalSeekPlan(url: String, positionMillis: Long, durationMillis: Long? = null): LocalSeekPlan {
    val safePositionMillis = positionMillis.coerceAtLeast(0L)
    if (Music032TranscodeUrls.isLive(url)) {
        // 032 实时转码用 offsetMs 重开（毫秒精度，服务端要求小于曲目时长），
        // 不需要 Plex 的整秒截断与 fastSeek。
        val offsetMillis = Music032TranscodeUrls.clampedOffsetMs(safePositionMillis, durationMillis?.takeIf { it > 0L })
        return LocalSeekPlan(
            url = Music032TranscodeUrls.withOffsetMs(url, offsetMillis, durationMillis?.takeIf { it > 0L }),
            playerPositionMillis = 0L,
            logicalBaseMillis = offsetMillis,
            restartsLiveTranscode = true,
        )
    }
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

/**
 * 实时转码流（无 Content-Length、不支持 Range、拖动靠偏移重开）：Plex universal transcode
 * 或 032 的 transcode.ogg / transcode.mp3。Plex 会话注入/停止等仍只认 Plex URL。
 */
internal fun String.isLiveTranscodeUrl(): Boolean =
    isPlexLiveTranscodeUrl() || Music032TranscodeUrls.isLive(this)
