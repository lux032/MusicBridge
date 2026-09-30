package com.lux032.musicbridge.music

import com.lux032.musicbridge.LocalPlaybackPolicy
import com.lux032.musicbridge.plex.PlexTrackStream

/**
 * 032 源的本机限速转码（规划 G8）。
 *
 * 与 Plex 的判定口径一致：原档码率超过当前网络的流媒体上限时转码；码率未知时，
 * Opus 原档不转、其他编码转。转码使用服务端实时接口
 * `transcode.ogg?bitrate=`（Opus，chunked，拖动通过 `offsetMs` 重开）。
 *
 * 服务端能力未知时乐观转码（与 Sonos 计划一致）；明确不支持 ogg（旧版服务端或
 * 未安装 ffmpeg）时回退原档。Plex 曲目不会进入这里。
 */
internal object Music032LocalTranscode {
    /** 服务端 ogg(opus) 码率白名单的默认值（与 /api/v1/capabilities 一致）。 */
    val DEFAULT_OPUS_BITRATES = listOf(64, 96, 128, 160, 192, 256)

    fun isMusic032Track(track: PlexTrackStream): Boolean =
        Music032TranscodeUrls.isOriginalStream(track.streamUrl)

    fun serverBaseUrl(streamUrl: String): String? =
        streamUrl.substringBefore("/api/v1/tracks/", "").trimEnd('/').ifBlank { null }

    fun capabilitiesFor(track: PlexTrackStream): Music032Capabilities? =
        serverBaseUrl(track.streamUrl)?.let(Music032CapabilitiesStore::snapshotForBaseUrl)

    /** 把设置里的 Opus 码率映射到服务端白名单：取不超过设定值的最大档，否则取最低档。 */
    fun opusBitrate(requestedKbps: Int, caps: Music032Capabilities?): Int {
        val allowed = caps?.transcodeFormats?.get("ogg")?.bitrates?.filter { it > 0 }?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_OPUS_BITRATES
        return allowed.filter { it <= requestedKbps }.maxOrNull() ?: allowed.min()
    }

    /**
     * 当前策略下的 032 转码 URL；不需要或无法转码时返回 null（播放原档）。
     * 调用方需先确认 [isMusic032Track]。
     */
    fun transcodeUrl(
        track: PlexTrackStream,
        policy: LocalPlaybackPolicy,
        caps: Music032Capabilities? = capabilitiesFor(track),
    ): String? {
        val limitKbps = policy.streamingLimit.maxSourceBitrateKbps ?: return null
        val sourceBitrate = track.sourceAudioBitrateKbps
        val isNativeOpusWithUnknownBitrate =
            track.sourceAudioCodec.equals("opus", ignoreCase = true) && sourceBitrate == null
        if (isNativeOpusWithUnknownBitrate || (sourceBitrate != null && sourceBitrate <= limitKbps)) return null
        if (caps != null && (caps.apiRevision <= 1 || !caps.isFormatAvailable("ogg"))) return null
        val bitrate = opusBitrate(policy.opusBitrate.kbps, caps)
        return Music032TranscodeUrls.derive(track.streamUrl, track.ratingKey, "ogg", listOf("bitrate" to bitrate.toString()))
    }
}
