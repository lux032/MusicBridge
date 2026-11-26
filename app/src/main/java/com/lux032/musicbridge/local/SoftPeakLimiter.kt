package com.lux032.musicbridge.local

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val LIMITER_LOOKAHEAD_SECS = 0.005
private const val LIMITER_RELEASE_SECS = 0.050

/**
 * Zero-allocation multi-channel-linked 5 ms lookahead peak limiter.
 *
 * Architecture matches desktop `032player-rs` (`crates/player/src/limiter.rs`):
 * 1. Fixed 5 ms interleaved delay line pre-allocated for the configured sample rate & channels.
 * 2. O(1) amortized monotonic sliding-window peak detector over `[t - L ..= t]` using primitive arrays.
 * 3. Instant attack + 50 ms exponential release envelope smoothed by a length-`L` moving average
 *    so gain ramps down cleanly across the entire 5 ms lookahead window before a transient exits.
 *
 * Audio whose peaks remain at or below `ceilingLinear` passes through the delay line with an exact
 * `1.0` multiplier (zero coloration).
 */
internal class SoftPeakLimiter(
    sampleRate: Int,
    channelCount: Int,
) {
    val sampleRate: Int = sampleRate.coerceAtLeast(8_000)
    val channelCount: Int = channelCount.coerceAtLeast(1)
    val latencyFrames: Int = (this.sampleRate * LIMITER_LOOKAHEAD_SECS).roundToInt().coerceAtLeast(1)

    private val delayBuf = FloatArray(latencyFrames * this.channelCount)
    private var delayPos = 0

    // Fixed-capacity primitive ring deque of (frameIdx, peak) in strictly decreasing peak order.
    private val dequeCapacity = latencyFrames + 2
    private val dequeFrame = LongArray(dequeCapacity)
    private val dequePeak = FloatArray(dequeCapacity)
    private var dqHead = 0
    private var dqTail = 0
    private var dqLen = 0
    private var frameIdx = 0L

    private var heldGain = 1.0
    private val releaseCoeff = 1.0 - exp(-1.0 / (LIMITER_RELEASE_SECS * this.sampleRate.toDouble()))

    private val avgBuf = DoubleArray(latencyFrames) { 1.0 }
    private var avgPos = 0
    private var avgSum = latencyFrames.toDouble()
    private val invLookahead = 1.0 / latencyFrames.toDouble()

    /** Clears delayed audio and envelope state without allocating. */
    fun reset() {
        delayBuf.fill(0f)
        delayPos = 0
        dqHead = 0
        dqTail = 0
        dqLen = 0
        frameIdx = 0L
        heldGain = 1.0
        avgBuf.fill(1.0)
        avgPos = 0
        avgSum = latencyFrames.toDouble()
    }

    /**
     * Processes [frames] interleaved frames in [interleaved] in place.
     *
     * When [limitActive] is `false`, audio still traverses the fixed 5 ms delay line at unity gain
     * so disabling limiting mid-stream never introduces a discontinuity or time jump.
     */
    fun processInPlace(
        frames: Int,
        interleaved: FloatArray,
        ceilingLinear: Float,
        limitActive: Boolean,
    ) {
        if (frames <= 0) return
        val limit = if (limitActive && ceilingLinear.isFinite() && ceilingLinear > 1e-4f) {
            ceilingLinear.toDouble()
        } else {
            Double.POSITIVE_INFINITY
        }
        val ch = channelCount
        val lookahead = latencyFrames
        val lookaheadLong = lookahead.toLong()
        val dqCap = dequeCapacity

        for (f in 0 until frames) {
            val base = f * ch
            val delayBase = delayPos * ch
            var inPeak = 0f
            var delPeak = 0f

            for (c in 0 until ch) {
                val sampleIn = interleaved[base + c]
                val absIn = abs(sampleIn)
                if (absIn > inPeak) inPeak = absIn

                val sampleDel = delayBuf[delayBase + c]
                val absDel = abs(sampleDel)
                if (absDel > delPeak) delPeak = absDel

                delayBuf[delayBase + c] = sampleIn
                // Temporarily store delayed sample in output slot before scaling below.
                interleaved[base + c] = sampleDel
            }

            delayPos += 1
            if (delayPos == lookahead) delayPos = 0

            val t = frameIdx
            frameIdx = t + 1L

            while (dqLen > 0) {
                val back = if (dqTail == 0) dqCap - 1 else dqTail - 1
                if (dequePeak[back] <= inPeak) {
                    dqTail = back
                    dqLen -= 1
                } else {
                    break
                }
            }
            dequeFrame[dqTail] = t
            dequePeak[dqTail] = inPeak
            dqTail += 1
            if (dqTail == dqCap) dqTail = 0
            dqLen += 1

            while (dqLen > 0) {
                if (t - dequeFrame[dqHead] > lookaheadLong) {
                    dqHead += 1
                    if (dqHead == dqCap) dqHead = 0
                    dqLen -= 1
                } else {
                    break
                }
            }

            val winPeak = dequePeak[dqHead].toDouble()
            val rawGain = if (winPeak > limit) min(1.0, limit / winPeak) else 1.0

            if (rawGain < heldGain) {
                heldGain = rawGain
            } else {
                heldGain += releaseCoeff * (rawGain - heldGain)
                if (heldGain > 1.0 - 1e-7 && rawGain >= 1.0) {
                    heldGain = 1.0
                }
            }

            val old = avgBuf[avgPos]
            avgBuf[avgPos] = heldGain
            avgSum += heldGain - old
            avgPos += 1
            if (avgPos == lookahead) {
                avgPos = 0
                var recomputed = 0.0
                for (i in 0 until lookahead) recomputed += avgBuf[i]
                avgSum = recomputed
            }

            val smoothGain = (avgSum * invLookahead).coerceIn(0.0, 1.0)
            val delPeakD = delPeak.toDouble()
            val finalGain = if (delPeakD * smoothGain > limit && delPeakD > 0.0) {
                min(smoothGain, limit / delPeakD)
            } else {
                smoothGain
            }

            if (finalGain < 1.0 - 1e-7) {
                val g = finalGain.toFloat()
                for (c in 0 until ch) {
                    interleaved[base + c] = interleaved[base + c] * g
                }
            }
        }
    }
}
