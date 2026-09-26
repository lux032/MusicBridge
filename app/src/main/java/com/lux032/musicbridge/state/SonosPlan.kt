package com.lux032.musicbridge

import com.lux032.musicbridge.music.Music032Capabilities
import com.lux032.musicbridge.music.Music032Json
import com.lux032.musicbridge.music.Music032TranscodeUrls
import com.lux032.musicbridge.plex.PlexTrackStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async

enum class SonosPlanKind { ORIGINAL, LIVE_MP3, STATIC_FLAC }
data class SonosPlan(val url: String, val mime: String?, val kind: SonosPlanKind,
                     val needsWarmup: Boolean = false, val fellBackFromFlac: Boolean = false)

internal fun sonosPushMessage(message: String, fellBackFromFlac: Boolean): String =
    if (fellBackFromFlac) "$message（${Strings.music032FlacFallback}）" else message

internal fun playlistTrackMessage(trackTitle: String, is032: Boolean, fellBackFromFlac: Boolean): String? =
    if (is032) sonosPushMessage("正在连续播放：$trackTitle", fellBackFromFlac) else null
internal data class SonosPlayback(val url: String, val mime: String?, val positionEncoded: Boolean)
internal fun shouldSonosSeekAfterPush(kind: SonosPlanKind, offset: Long): Boolean =
    offset > 0 && kind != SonosPlanKind.LIVE_MP3

internal fun retainSonosPlanForTrack(planSourceId: String?, planRatingKey: String?,
                                    track: PlexTrackStream): Boolean =
    planSourceId == track.sourceId && planRatingKey == track.ratingKey

internal fun shouldRestart032Seek(predictedLive: Boolean, actualKind: SonosPlanKind): Boolean =
    predictedLive && actualKind == SonosPlanKind.LIVE_MP3

internal class Music032HydrationCache(
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val track: PlexTrackStream?, val expiresAt: Long)
    private val results = mutableMapOf<String, Entry>()
    private val inFlight = mutableMapOf<String, Deferred<PlexTrackStream?>>()

    suspend fun hydrate(track: PlexTrackStream, fetch: suspend () -> PlexTrackStream?): PlexTrackStream {
        if (!needs032SonosHydration(track)) return track
        val key = "${track.sourceId}:${track.ratingKey}"
        val request = synchronized(results) {
            results[key]?.takeIf { it.expiresAt > nowMillis() }?.let { return merge032Hydration(track, it.track) }
            results.remove(key)
            inFlight[key] ?: scope.async(Dispatchers.IO) {
                val value = try { fetch() } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { null }
                synchronized(results) {
                    results[key] = Entry(value, if (value == null) nowMillis() + 120_000L else Long.MAX_VALUE)
                }
                value
            }.also { deferred ->
                inFlight[key] = deferred
                deferred.invokeOnCompletion {
                    synchronized(results) { if (inFlight[key] === deferred) inFlight.remove(key) }
                }
            }
        }
        return merge032Hydration(track, request.await())
    }
}

internal fun merge032Hydration(track: PlexTrackStream, fresh: PlexTrackStream?): PlexTrackStream =
    fresh?.let {
        track.copy(
            streamUrl = it.streamUrl,
            sourceAudioCodec = it.sourceAudioCodec ?: track.sourceAudioCodec,
            sourceAudioBitrateKbps = it.sourceAudioBitrateKbps ?: track.sourceAudioBitrateKbps,
            sourceAudioSampleRate = it.sourceAudioSampleRate,
            playbackMimeType = it.playbackMimeType,
        )
    } ?: track

internal fun needs032SonosHydration(track: PlexTrackStream): Boolean =
    track.sourceAudioCodec == null ||
        (track.sourceAudioCodec.lowercase() in LOSSLESS_AUDIO_CODECS && track.sourceAudioSampleRate == null)

internal fun plan032(track: PlexTrackStream, opusMode: OpusTranscodeMode,
                     hiResMode: SonosHiResTranscodeMode, caps: Music032Capabilities?): Result<SonosPlan> {
    val original = SonosPlan(track.streamUrl, Music032Json.original032MimeType(track.sourceAudioCodec, null), SonosPlanKind.ORIGINAL)
    val opus = track.sourceAudioCodec.equals("opus", true)
    val hiRes = track.sourceAudioCodec?.lowercase() in LOSSLESS_AUDIO_CODECS &&
        (track.sourceAudioSampleRate ?: 0) > SONOS_MAX_SAMPLE_RATE_HZ && hiResMode != SonosHiResTranscodeMode.DIRECT_ORIGINAL
    if (!opus && !hiRes) return Result.success(original)
    val flacRequested = (opus && opusMode == OpusTranscodeMode.PROXY_FLAC) ||
        (hiRes && hiResMode == SonosHiResTranscodeMode.TRANSCODE_FLAC_48K)
    val flacAvailable = caps == null || (caps.apiRevision > 1 && caps.isFormatAvailable("flac"))
    if (flacRequested && flacAvailable) {
        val params = if (hiRes) listOf("maxSampleRate" to "48000") else emptyList()
        val url = Music032TranscodeUrls.derive(track.streamUrl, track.ratingKey, "flac", params)
        if (url != null) return Result.success(SonosPlan(url, "audio/flac", SonosPlanKind.STATIC_FLAC, needsWarmup = true))
    }
    return fallback032Mp3(track, caps, original, opus)
}

internal fun fallback032Mp3(track: PlexTrackStream, caps: Music032Capabilities?, original: SonosPlan,
                            opus: Boolean = track.sourceAudioCodec.equals("opus", true)): Result<SonosPlan> {
    if (caps != null && (caps.apiRevision <= 1 || !caps.isFormatAvailable("mp3"))) {
        return if (opus) Result.failure(IllegalStateException(Strings.music032SonosTranscodeUnavailable)) else Result.success(original)
    }
    val url = Music032TranscodeUrls.derive(track.streamUrl, track.ratingKey, "mp3", listOf("bitrate" to "320"))
        ?: return if (opus) Result.failure(IllegalStateException(Strings.music032SonosTranscodeUnavailable)) else Result.success(original)
    return Result.success(SonosPlan(url, "audio/mpeg", SonosPlanKind.LIVE_MP3))
}

internal fun next032WarmupCandidate(
    tracks: List<PlexTrackStream>,
    currentIndex: Int,
    sequential: Boolean,
    opusMode: OpusTranscodeMode,
    hiResMode: SonosHiResTranscodeMode,
    caps: (PlexTrackStream) -> Music032Capabilities?,
): PlexTrackStream? = tracks.getOrNull(currentIndex + 1)?.takeIf {
    sequential && predictSonosKind(it, opusMode, hiResMode, caps(it)) == SonosPlanKind.STATIC_FLAC
}

internal fun predictSonosKind(track: PlexTrackStream, opusMode: OpusTranscodeMode,
                              hiResMode: SonosHiResTranscodeMode, capsSnapshot: Music032Capabilities?): SonosPlanKind =
    plan032(track, opusMode, hiResMode, capsSnapshot).getOrNull()?.kind ?: SonosPlanKind.ORIGINAL
