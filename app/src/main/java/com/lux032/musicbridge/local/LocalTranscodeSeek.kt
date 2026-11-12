package com.lux032.musicbridge.local

import com.lux032.musicbridge.music.Music032TranscodeUrls

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
    if (!Music032TranscodeUrls.isLive(url)) {
        return LocalSeekPlan(
            url = url,
            playerPositionMillis = safePositionMillis,
            logicalBaseMillis = 0L,
            restartsLiveTranscode = false,
        )
    }
    // 032 实时转码用 offsetMs 重开（毫秒精度，服务端要求小于曲目时长）。重开后的响应
    // 拥有从 0 开始的 Media3 时间轴，因此保留曲目内偏移作为逻辑基准。
    val offsetMillis = Music032TranscodeUrls.clampedOffsetMs(safePositionMillis, durationMillis?.takeIf { it > 0L })
    return LocalSeekPlan(
        url = Music032TranscodeUrls.withOffsetMs(url, offsetMillis, durationMillis?.takeIf { it > 0L }),
        playerPositionMillis = 0L,
        logicalBaseMillis = offsetMillis,
        restartsLiveTranscode = true,
    )
}

/** 实时转码流（无 Content-Length、不支持 Range、拖动靠偏移重开）：032 的 transcode.ogg / transcode.mp3。 */
internal fun String.isLiveTranscodeUrl(): Boolean = Music032TranscodeUrls.isLive(this)
