package com.lux032.musicbridge.local

import com.lux032.musicbridge.LoudnessMode
import com.lux032.musicbridge.LoudnessNormalizationSettings
import com.lux032.musicbridge.LoudnessPeakProtection
import com.lux032.musicbridge.LoudnessTarget
import com.lux032.musicbridge.music.MusicTrack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Output ceiling protecting DAC oversampling filters from inter-sample overs. */
const val LOUDNESS_CEILING_DB = -1.0
/** Hard safety bounds on applied per-track gain. */
const val LOUDNESS_MIN_GAIN_DB = -24.0
const val LOUDNESS_MAX_GAIN_DB = 18.0
/** Conservative floor for unmeasured-track fallback attenuation. */
const val LOUDNESS_FALLBACK_MIN_DB = -6.0
/** Safety clamp range when following the server-reported library median loudness. */
const val LOUDNESS_LIBRARY_MEDIAN_MIN_LUFS = -16.0
const val LOUDNESS_LIBRARY_MEDIAN_MAX_LUFS = -8.0
/** Fallback target when library median is unavailable. */
const val LOUDNESS_DEFAULT_FALLBACK_TARGET_LUFS = -11.0
/** Hysteresis margin above ceiling/unity before engaging the limiter. */
private const val LIMITER_ENGAGE_MARGIN_DB = 0.05

enum class LoudnessSource {
    BYPASS,
    TRACK,
    ALBUM,
    FALLBACK,
}

data class LoudnessTrackMetrics(
    val loudnessLufs: Double? = null,
    val truePeakDb: Double? = null,
    val albumLoudnessLufs: Double? = null,
    val albumTruePeakDb: Double? = null,
) {
    companion object {
        fun fromTrack(track: MusicTrack): LoudnessTrackMetrics = LoudnessTrackMetrics(
            loudnessLufs = track.loudnessLufs,
            truePeakDb = track.truePeakDb,
            albumLoudnessLufs = track.albumLoudnessLufs,
            albumTruePeakDb = track.albumTruePeakDb,
        )
    }
}

data class LoudnessGainPlan(
    val active: Boolean,
    val gainDb: Double,
    val ceilingDb: Double,
    val useLimiter: Boolean,
    val source: LoudnessSource,
    /** Effective target reference used to compute [gainDb] (LUFS). */
    val targetLufs: Double,
    /** Estimated transient peak reduction when [useLimiter] is true (dB >= 0). */
    val peakReductionDb: Double,
) {
    val linearGain: Double
        get() = if (active) dbToLinear(gainDb) else 1.0

    val ceilingLinear: Float
        get() = dbToLinear(ceilingDb).toFloat()

    companion object {
        val BYPASS = LoudnessGainPlan(
            active = false,
            gainDb = 0.0,
            ceilingDb = LOUDNESS_CEILING_DB,
            useLimiter = false,
            source = LoudnessSource.BYPASS,
            targetLufs = LOUDNESS_DEFAULT_FALLBACK_TARGET_LUFS,
            peakReductionDb = 0.0,
        )
    }
}

fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

fun LoudnessTarget.effectiveTargetLufs(libraryMedianLufs: Double?): Double = when (this) {
    LoudnessTarget.LIBRARY_MEDIAN -> libraryMedianLufs
        ?.takeIf { it.isFinite() }
        ?.coerceIn(LOUDNESS_LIBRARY_MEDIAN_MIN_LUFS, LOUDNESS_LIBRARY_MEDIAN_MAX_LUFS)
        ?: LOUDNESS_DEFAULT_FALLBACK_TARGET_LUFS
    LoudnessTarget.LOUD -> -11.0
    LoudnessTarget.STANDARD -> -14.0
    LoudnessTarget.DYNAMIC -> -18.0
}

/**
 * Stateless Smart-mode rule: use album loudness only during sequential manual playback
 * where an immediate neighbor inside the manual queue belongs to the same album.
 */
fun smartUseAlbumForQueue(
    albumKeys: List<String?>,
    position: Int,
    manualQueueCount: Int,
    isShuffle: Boolean,
): Boolean {
    if (isShuffle) return false
    val manual = manualQueueCount.coerceIn(0, albumKeys.size)
    if (position !in 0 until manual) return false
    val currentAlbum = albumKeys[position]?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val prevSame = position > 0 && albumKeys[position - 1]?.trim() == currentAlbum
    val nextSame = position + 1 < manual && albumKeys[position + 1]?.trim() == currentAlbum
    return prevSame || nextSame
}

private fun Double?.finiteOrNull(): Double? = this?.takeIf { it.isFinite() }

/**
 * Computes the per-track normalization plan matching the desktop `032player-rs` v0.2.34 algorithm.
 * Android output volume is controlled by system/hardware volume rather than ExoPlayer software
 * attenuation, so digital headroom is evaluated at unity software volume (0 dB).
 */
fun computeLoudnessPlan(
    metrics: LoudnessTrackMetrics,
    settings: LoudnessNormalizationSettings,
    useAlbumInSmart: Boolean,
    libraryMedianLufs: Double?,
    serverSupported: Boolean,
): LoudnessGainPlan {
    if (!settings.enabled || !serverSupported) {
        return LoudnessGainPlan.BYPASS
    }

    val target = settings.target.effectiveTargetLufs(libraryMedianLufs)
    val trackLufs = metrics.loudnessLufs.finiteOrNull()
    val trackPeak = metrics.truePeakDb.finiteOrNull()
    val albumLufs = metrics.albumLoudnessLufs.finiteOrNull()
    val albumPeak = metrics.albumTruePeakDb.finiteOrNull()

    val wantAlbum = when (settings.mode) {
        LoudnessMode.SMART -> useAlbumInSmart
        LoudnessMode.ALBUM -> true
        LoudnessMode.TRACK -> false
    }

    val measuredLufs: Double?
    val truePeakDb: Double?
    val source: LoudnessSource
    if (wantAlbum && albumLufs != null && albumPeak != null) {
        measuredLufs = albumLufs
        truePeakDb = albumPeak
        source = LoudnessSource.ALBUM
    } else if (trackLufs != null && trackPeak != null) {
        measuredLufs = trackLufs
        truePeakDb = trackPeak
        source = LoudnessSource.TRACK
    } else {
        val fallbackGain = libraryMedianLufs.finiteOrNull()
            ?.let { median -> (target - median).coerceIn(LOUDNESS_FALLBACK_MIN_DB, 0.0) }
            ?: 0.0
        return LoudnessGainPlan(
            active = true,
            gainDb = fallbackGain,
            ceilingDb = LOUDNESS_CEILING_DB,
            useLimiter = false,
            source = LoudnessSource.FALLBACK,
            targetLufs = target,
            peakReductionDb = 0.0,
        )
    }

    val rawGain = target - measuredLufs
    val linearHeadroom = LOUDNESS_CEILING_DB - truePeakDb
    val maxGr = settings.peakProtection.maxGainReductionDb
    val appliedGain = min(rawGain, linearHeadroom + maxGr)
        .coerceIn(LOUDNESS_MIN_GAIN_DB, LOUDNESS_MAX_GAIN_DB)
    val overshoot = max(0.0, truePeakDb + appliedGain - LOUDNESS_CEILING_DB)
    // Only engage the limiter when actually boosting; unboosted (0 dB or attenuated) tracks
    // remain pure linear / transparent bypass.
    val useLimiter = settings.peakProtection != LoudnessPeakProtection.STRICT_LINEAR &&
        appliedGain > LIMITER_ENGAGE_MARGIN_DB &&
        overshoot > LIMITER_ENGAGE_MARGIN_DB

    return LoudnessGainPlan(
        active = true,
        gainDb = appliedGain,
        ceilingDb = LOUDNESS_CEILING_DB,
        useLimiter = useLimiter,
        source = source,
        targetLufs = target,
        peakReductionDb = if (useLimiter) overshoot else 0.0,
    )
}
