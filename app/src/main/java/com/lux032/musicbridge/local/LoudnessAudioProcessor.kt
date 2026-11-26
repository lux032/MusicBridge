package com.lux032.musicbridge.local

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.roundToInt

private const val GAIN_RAMP_FRAMES = 64
private const val UNITY_EPSILON = 1e-7

/**
 * Pure-Kotlin Media3 [AudioProcessor] that applies per-track EBU R128 loudness gain and optional
 * 5 ms multi-channel-linked lookahead soft peak limiting.
 *
 * Design guarantees:
 * - Preserves the input PCM encoding (16 / 24 / 32-bit signed LE or 32-bit float), channel count,
 *   and sample rate unchanged so Hi-Res DAC paths are never downsampled or truncated.
 * - Stays configured as active for supported PCM encodings (matching [TeeAudioProcessor]) so
 *   toggling loudness normalization ON or changing targets mid-playback takes effect immediately
 *   on the next PCM buffer without restarting the track or rebuilding AudioSink.
 * - When disabled (or at unity 0 dB gain with limiter unengaged), performs a bit-exact zero-DSP
 *   buffer copy with zero latency and zero rounding.
 * - Smooths live gain changes over [GAIN_RAMP_FRAMES] frames (~1.3-1.5 ms) to prevent clicks.
 */
@OptIn(UnstableApi::class)
internal class LoudnessAudioProcessor : BaseAudioProcessor() {
    @Volatile
    private var targetPlan: LoudnessGainPlan = LoudnessGainPlan.BYPASS

    private var currentGain: Double = 1.0
    private var limiter: SoftPeakLimiter? = null
    private var limiterEngaged: Boolean = false
    private var workBuffer: FloatArray = FloatArray(0)

    /** Publishes a new gain plan lock-free from any thread (typically main thread). */
    fun setGainPlan(plan: LoudnessGainPlan) {
        targetPlan = plan
    }

    fun currentTargetPlan(): LoudnessGainPlan = targetPlan

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val supported = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT,
            C.ENCODING_PCM_FLOAT -> true
            else -> false
        }
        if (!supported || inputAudioFormat.sampleRate <= 0 || inputAudioFormat.channelCount <= 0) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        val format = outputAudioFormat
        if (format != AudioFormat.NOT_SET) {
            val existing = limiter
            if (existing == null || existing.sampleRate != format.sampleRate || existing.channelCount != format.channelCount) {
                limiter = SoftPeakLimiter(format.sampleRate, format.channelCount)
            } else {
                existing.reset()
            }
        }
        val plan = targetPlan
        currentGain = if (plan.active) plan.linearGain else 1.0
        limiterEngaged = plan.active && plan.useLimiter
    }

    override fun onQueueEndOfStream() {
        val format = outputAudioFormat
        val activeLimiter = limiter
        if (format == AudioFormat.NOT_SET || !limiterEngaged || activeLimiter == null) return

        // Drain the remaining 5 ms of delayed frames stored in the limiter line.
        val drainFrames = activeLimiter.latencyFrames
        if (drainFrames <= 0) return
        val sampleCount = drainFrames * format.channelCount
        val work = ensureWorkBuffer(sampleCount)
        work.fill(0f, 0, sampleCount)
        val plan = targetPlan
        activeLimiter.processInPlace(
            frames = drainFrames,
            interleaved = work,
            ceilingLinear = plan.ceilingLinear,
            limitActive = plan.active && plan.useLimiter,
        )
        val bytesPerSample = bytesPerSample(format.encoding)
        val out = replaceOutputBuffer(sampleCount * bytesPerSample)
        encodePcm(format.encoding, work, sampleCount, out)
        out.flip()
    }

    override fun onReset() {
        limiter = null
        limiterEngaged = false
        currentGain = 1.0
        workBuffer = FloatArray(0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining <= 0) return

        val format = outputAudioFormat
        val encoding = format.encoding
        val channelCount = format.channelCount
        val bytesPerSample = bytesPerSample(encoding)
        val frameSize = bytesPerSample * channelCount
        if (frameSize <= 0) return
        val frames = remaining / frameSize
        if (frames <= 0) return
        val usableBytes = frames * frameSize

        val plan = targetPlan
        val desiredGain = if (plan.active) plan.linearGain else 1.0
        val wantLimiter = plan.active && plan.useLimiter

        // Bit-exact direct passthrough when unengaged and already at unity gain.
        if (!limiterEngaged && !wantLimiter &&
            abs(currentGain - 1.0) <= UNITY_EPSILON &&
            abs(desiredGain - 1.0) <= UNITY_EPSILON
        ) {
            val out = replaceOutputBuffer(usableBytes)
            val savedLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + usableBytes)
            out.put(inputBuffer)
            inputBuffer.limit(savedLimit)
            out.flip()
            return
        }

        if (wantLimiter && !limiterEngaged) {
            val existing = limiter
            if (existing == null || existing.sampleRate != format.sampleRate || existing.channelCount != channelCount) {
                limiter = SoftPeakLimiter(format.sampleRate, channelCount)
            } else {
                existing.reset()
            }
            limiterEngaged = true
        }

        val sampleCount = frames * channelCount
        val work = ensureWorkBuffer(sampleCount)
        decodePcm(encoding, inputBuffer, sampleCount, work)

        // Smooth per-frame linear gain ramp over up to GAIN_RAMP_FRAMES frames to avoid clicks.
        val startGain = currentGain
        if (abs(desiredGain - startGain) <= UNITY_EPSILON) {
            if (abs(desiredGain - 1.0) > UNITY_EPSILON) {
                val g = desiredGain.toFloat()
                for (i in 0 until sampleCount) {
                    work[i] = work[i] * g
                }
            }
            currentGain = desiredGain
        } else {
            val rampFrames = minOf(frames, GAIN_RAMP_FRAMES)
            val step = (desiredGain - startGain) / GAIN_RAMP_FRAMES.toDouble()
            var gDouble = startGain
            for (f in 0 until rampFrames) {
                gDouble += step
                val g = gDouble.toFloat()
                val base = f * channelCount
                for (c in 0 until channelCount) {
                    work[base + c] = work[base + c] * g
                }
            }
            if (frames >= GAIN_RAMP_FRAMES) {
                gDouble = desiredGain
                val g = desiredGain.toFloat()
                for (i in rampFrames * channelCount until sampleCount) {
                    work[i] = work[i] * g
                }
            }
            currentGain = gDouble
        }

        if (limiterEngaged) {
            val activeLimiter = limiter ?: SoftPeakLimiter(format.sampleRate, channelCount).also { limiter = it }
            activeLimiter.processInPlace(
                frames = frames,
                interleaved = work,
                ceilingLinear = plan.ceilingLinear,
                limitActive = wantLimiter,
            )
        }

        val out = replaceOutputBuffer(usableBytes)
        encodePcm(encoding, work, sampleCount, out)
        out.flip()
    }

    private fun ensureWorkBuffer(requiredSamples: Int): FloatArray {
        if (workBuffer.size < requiredSamples) {
            workBuffer = FloatArray(requiredSamples)
        }
        return workBuffer
    }

    private fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    private fun decodePcm(encoding: Int, src: ByteBuffer, sampleCount: Int, dst: FloatArray) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    dst[i] = src.short.toFloat() * INV_PCM_16
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) {
                    val b0 = src.get().toInt() and 0xFF
                    val b1 = src.get().toInt() and 0xFF
                    val b2 = src.get().toInt() // signed high byte
                    val signed24 = (b2 shl 16) or (b1 shl 8) or b0
                    dst[i] = signed24.toFloat() * INV_PCM_24
                }
            }
            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    dst[i] = (src.int.toDouble() * INV_PCM_32).toFloat()
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until sampleCount) {
                    dst[i] = src.float
                }
            }
        }
    }

    private fun encodePcm(encoding: Int, src: FloatArray, sampleCount: Int, dst: ByteBuffer) {
        when (encoding) {
            C.ENCODING_PCM_16BIT -> {
                for (i in 0 until sampleCount) {
                    val clamped = src[i].coerceIn(-1f, 1f)
                    val scaled = (clamped * PCM_16_SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    dst.putShort(scaled.toShort())
                }
            }
            C.ENCODING_PCM_24BIT -> {
                for (i in 0 until sampleCount) {
                    val clamped = src[i].coerceIn(-1f, 1f)
                    val scaled = (clamped * PCM_24_SCALE).roundToInt().coerceIn(PCM_24_MIN, PCM_24_MAX)
                    dst.put((scaled and 0xFF).toByte())
                    dst.put(((scaled ushr 8) and 0xFF).toByte())
                    dst.put(((scaled ushr 16) and 0xFF).toByte())
                }
            }
            C.ENCODING_PCM_32BIT -> {
                for (i in 0 until sampleCount) {
                    val clamped = src[i].toDouble().coerceIn(-1.0, 1.0)
                    val scaled = Math.round(clamped * PCM_32_SCALE)
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        .toInt()
                    dst.putInt(scaled)
                }
            }
            C.ENCODING_PCM_FLOAT -> {
                for (i in 0 until sampleCount) {
                    dst.putFloat(src[i])
                }
            }
        }
    }

    companion object {
        private const val INV_PCM_16 = 1f / 32768f
        private const val PCM_16_SCALE = 32767f
        private const val INV_PCM_24 = 1f / 8388608f
        private const val PCM_24_SCALE = 8388607f
        private const val PCM_24_MIN = -8388608
        private const val PCM_24_MAX = 8388607
        private const val INV_PCM_32 = 1.0 / 2147483648.0
        private const val PCM_32_SCALE = 2147483647.0
    }
}
