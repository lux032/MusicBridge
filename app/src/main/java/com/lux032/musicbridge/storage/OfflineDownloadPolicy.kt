package com.lux032.musicbridge.storage

import com.lux032.musicbridge.LocalOpusBitrate
import com.lux032.musicbridge.OfflineDownloadFormat
import com.lux032.musicbridge.music.Music032Capabilities
import com.lux032.musicbridge.music.Music032LocalTranscode
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.music.MusicTrack
import java.io.File
import java.io.FileInputStream

internal data class ResolvedDownloadPlan(
    val downloadUrl: String,
    val isOpusTranscode: Boolean,
    val effectiveCodec: String?,
    val effectiveBitrateKbps: Int?,
    val effectiveSampleRate: Int?,
    val effectiveBitDepth: Int?,
    val fileExtension: String,
)

private const val MIN_VALID_OGG_BYTES = 64L
private const val OPUS_SAMPLE_RATE_HZ = 48_000
private val OGG_MAGIC = byteArrayOf('O'.code.toByte(), 'g'.code.toByte(), 'g'.code.toByte(), 'S'.code.toByte())

internal fun shouldBlockDownloadForWifi(wifiOnly: Boolean, isWifi: Boolean): Boolean =
    wifiOnly && !isWifi

/**
 * Resolves whether an offline download should fetch the original file or request a server-side
 * Opus (`transcode.ogg`) transcode reusing the configured [opusBitrate].
 *
 * Automatically falls back to the original file when:
 * - [format] is [OfflineDownloadFormat.ORIGINAL]
 * - the track is not a 032 original stream
 * - server capabilities explicitly report `ogg` transcoding unavailable
 * - the source track is already Opus at or below the requested target bitrate (avoiding lossy re-encode)
 */
internal fun resolveOfflineDownloadPlan(
    track: MusicTrack,
    format: OfflineDownloadFormat,
    opusBitrate: LocalOpusBitrate,
    caps: Music032Capabilities? = Music032LocalTranscode.capabilitiesFor(track),
): ResolvedDownloadPlan {
    fun originalPlan() = ResolvedDownloadPlan(
        downloadUrl = track.streamUrl,
        isOpusTranscode = false,
        effectiveCodec = track.sourceAudioCodec,
        effectiveBitrateKbps = track.sourceAudioBitrateKbps,
        effectiveSampleRate = track.sourceAudioSampleRate,
        effectiveBitDepth = track.bitDepth,
        fileExtension = "audio",
    )

    if (format != OfflineDownloadFormat.OPUS) return originalPlan()
    if (!Music032LocalTranscode.isMusic032Track(track)) return originalPlan()
    if (caps != null && (caps.apiRevision <= 1 || !caps.isFormatAvailable("ogg"))) return originalPlan()

    val targetKbps = Music032LocalTranscode.opusBitrate(opusBitrate.kbps, caps)
    val isAlreadyLowOrEqualOpus = track.sourceAudioCodec.equals("opus", ignoreCase = true) &&
        (track.sourceAudioBitrateKbps == null || track.sourceAudioBitrateKbps <= targetKbps)
    if (isAlreadyLowOrEqualOpus) return originalPlan()

    val transcodeUrl = Music032TranscodeUrls.derive(
        streamUrl = track.streamUrl,
        ratingKey = track.ratingKey,
        format = "ogg",
        params = listOf("bitrate" to targetKbps.toString()),
    ) ?: return originalPlan()

    return ResolvedDownloadPlan(
        downloadUrl = transcodeUrl,
        isOpusTranscode = true,
        effectiveCodec = "opus",
        effectiveBitrateKbps = targetKbps,
        effectiveSampleRate = OPUS_SAMPLE_RATE_HZ,
        effectiveBitDepth = null,
        fileExtension = "ogg",
    )
}

/**
 * Verifies that a chunked `transcode.ogg` download produced a plausible Ogg container
 * (non-trivial size + `"OggS"` magic header) instead of an empty or HTML error payload.
 */
internal fun isValidTranscodedOggFile(file: File): Boolean {
    if (!file.exists() || file.length() < MIN_VALID_OGG_BYTES) return false
    val header = ByteArray(4)
    val read = runCatching {
        FileInputStream(file).use { it.read(header) }
    }.getOrDefault(-1)
    if (read != 4) return false
    return header.contentEquals(OGG_MAGIC)
}
