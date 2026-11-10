package com.lux032.musicbridge.local

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps the decoded PCM that the local ExoPlayer hands to its AudioTrack and turns it into
 * spectrum bands for the player-page visualizer. Installed as a [TeeAudioProcessor] sink in
 * [LocalPlaybackEngine], so it needs no RECORD_AUDIO permission and sees exactly what plays.
 *
 * Threading: [handleBuffer] / [flush] run on ExoPlayer's playback thread; [readBands] runs on
 * the UI frame loop. A single lock guards the ring buffer; both sides hold it only briefly.
 *
 * Sonos playback never passes through here, so the visualizer is local-only by construction.
 */
@OptIn(UnstableApi::class)
object AudioSpectrumTap : TeeAudioProcessor.AudioBufferSink {

    /** Ring capacity in mono frames: covers the longest latency + the largest window at 192 kHz. */
    private const val RING_SIZE = 1 shl 18
    private const val RING_MASK = RING_SIZE - 1

    /**
     * Samples reach the tap before they sit in the AudioTrack buffer, which DefaultAudioSink
     * keeps full. The buffer length is device-dependent (PCM: 250–750 ms), so the engine reports
     * the real one through [onAudioTrackBuffer]; this is only the value used until it does.
     */
    private const val DEFAULT_TRACK_BUFFER_SECONDS = 0.25
    private const val MAX_TRACK_BUFFER_SECONDS = 1.0

    /** Mixer + HAL delay after the AudioTrack buffer on the built-in / wired output path. */
    private const val OUTPUT_LATENCY_SECONDS = 0.04

    /** How quickly the smoothed playhead is pulled onto the position estimated from the chunks. */
    private const val PLAYHEAD_CORRECTION_SECONDS = 0.15
    /** Beyond this error (seek, stall, resume) the playhead snaps instead of gliding. */
    private const val PLAYHEAD_SNAP_SECONDS = 0.12
    private const val PLAYHEAD_STALE_NANOS = 100_000_000L

    private val lock = Any()
    private val ring = FloatArray(RING_SIZE)
    private var written = 0L
    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    // The tap is handed one whole decoder buffer at a time (≈93 ms for FLAC, ≈25 ms for
    // MP3/AAC), so [written] moves in steps. The sink starts feeding a chunk to the AudioTrack
    // when the tap sees it and takes about the chunk's own duration to do so; remembering where
    // and when the newest chunk started lets [readBands] interpolate a position that advances
    // every frame instead of standing still between chunks.
    private var chunkStart = 0L
    private var chunkFrames = 0
    private var chunkNanos = 0L
    /** Smoothed "frame now entering the AudioTrack", in frames since the last flush. */
    private var playhead = 0.0
    private var playheadNanos = 0L

    @Volatile
    private var trackBufferSeconds = DEFAULT_TRACK_BUFFER_SECONDS

    /** Length of the AudioTrack buffer the sink just created; it sets the audible delay. */
    fun onAudioTrackBuffer(seconds: Double) {
        if (seconds > 0.0) trackBufferSeconds = seconds.coerceAtMost(MAX_TRACK_BUFFER_SECONDS)
    }

    /** Only a visible visualizer turns the tap on, so it costs nothing the rest of the time. */
    @Volatile
    private var isEnabled = false
    private var users = 0

    /** Called when a visualizer appears; pair with [release]. */
    fun acquire() {
        synchronized(lock) {
            if (users++ == 0) {
                // Drop whatever was buffered last time so stale audio never flashes up.
                clearLocked()
                resetGain = true
                isEnabled = true
            }
        }
    }

    fun release() {
        synchronized(lock) {
            users = (users - 1).coerceAtLeast(0)
            if (users == 0) isEnabled = false
        }
    }

    private val analyzer = SpectrumAnalyzer()
    private var scratch = FloatArray(0)
    @Volatile
    private var resetGain = false

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        synchronized(lock) {
            this.sampleRate = sampleRateHz
            this.channelCount = channelCount
            this.encoding = encoding
            clearLocked()
        }
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!isEnabled) return
        synchronized(lock) {
            val channels = channelCount
            if (channels <= 0 || sampleRate <= 0) return
            val bytesPerSample = bytesPerSample(encoding) ?: return
            val data = buffer.duplicate().order(ByteOrder.nativeOrder())
            val frameBytes = bytesPerSample * channels
            val frames = data.remaining() / frameBytes
            if (frames == 0) return
            chunkStart = written
            chunkFrames = frames
            chunkNanos = System.nanoTime()
            var offset = data.position()
            for (f in 0 until frames) {
                var sum = 0f
                for (c in 0 until channels) {
                    sum += readSample(data, offset, encoding)
                    offset += bytesPerSample
                }
                ring[(written and RING_MASK.toLong()).toInt()] = sum / channels
                written++
            }
        }
    }

    /**
     * Fills [out] with band levels in 0..1 for the audio that is audible right now.
     * Returns false (and leaves zeros) when there is not yet enough audio to analyse.
     */
    fun readBands(out: FloatArray, dtSeconds: Float): Boolean {
        out.fill(0f)
        val rate: Int
        val windowSize: Int
        synchronized(lock) {
            rate = sampleRate
            if (rate <= 0) return false
            windowSize = SpectrumAnalyzer.windowSizeFor(rate)
            val now = System.nanoTime()
            val fed = chunkStart +
                min((now - chunkNanos) / 1e9 * rate, chunkFrames.toDouble())
            val elapsed = now - playheadNanos
            if (playheadNanos == 0L || elapsed > PLAYHEAD_STALE_NANOS ||
                abs(fed - playhead) > PLAYHEAD_SNAP_SECONDS * rate
            ) {
                playhead = fed
            } else {
                // Run on the wall clock and only lean towards the estimate: the estimate
                // carries the player loop's ~10 ms scheduling jitter, the clock does not.
                val seconds = elapsed / 1e9
                playhead += seconds * rate
                playhead += (fed - playhead) * (1.0 - exp(-seconds / PLAYHEAD_CORRECTION_SECONDS))
            }
            playheadNanos = now
            // Centre the window on what is audible now, rather than ending it there: a window
            // that ends at "now" shows the average of the last ~90 ms and so trails the beat.
            val audible = playhead - (trackBufferSeconds + OUTPUT_LATENCY_SECONDS) * rate
            val end = min(written, (audible + windowSize / 2).toLong())
            if (end < windowSize) return false
            if (scratch.size != windowSize) scratch = FloatArray(windowSize)
            val start = end - windowSize
            for (i in 0 until windowSize) {
                scratch[i] = ring[((start + i) and RING_MASK.toLong()).toInt()]
            }
        }
        if (resetGain) {
            resetGain = false
            analyzer.resetGain()
        }
        analyzer.bands(scratch, rate, out, dtSeconds)
        return true
    }

    private fun clearLocked() {
        written = 0L
        chunkStart = 0L
        chunkFrames = 0
        chunkNanos = 0L
        playhead = 0.0
        playheadNanos = 0L
        ring.fill(0f)
    }

    private fun bytesPerSample(encoding: Int): Int? = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> null
    }

    private fun readSample(data: ByteBuffer, offset: Int, encoding: Int): Float = when (encoding) {
        C.ENCODING_PCM_16BIT -> data.getShort(offset) / 32768f
        C.ENCODING_PCM_16BIT_BIG_ENDIAN -> {
            val hi = data.get(offset).toInt()
            val lo = data.get(offset + 1).toInt() and 0xFF
            ((hi shl 8) or lo).toShort() / 32768f
        }
        C.ENCODING_PCM_FLOAT -> data.getFloat(offset)
        C.ENCODING_PCM_32BIT -> data.getInt(offset) / 2147483648f
        C.ENCODING_PCM_24BIT -> {
            val b0 = data.get(offset).toInt() and 0xFF
            val b1 = data.get(offset + 1).toInt() and 0xFF
            val b2 = data.get(offset + 2).toInt()
            ((b2 shl 16) or (b1 shl 8) or b0) / 8388608f
        }
        C.ENCODING_PCM_8BIT -> ((data.get(offset).toInt() and 0xFF) - 128) / 128f
        else -> 0f
    }
}

/**
 * Pure FFT → log-spaced band levels. No Android dependencies so it is unit-testable.
 *
 * Two windows share one centre: the whole buffer resolves the bass bands, and its middle
 * quarter (~21 ms) feeds everything above [SPLIT_HZ], so hats, snares and consonants flick on
 * the beat instead of being smeared across the long window.
 *
 * Levels are relative, not absolute: modern masters sit so hot that a fixed dBFS scale left
 * every bar in the upper half. Each band's summed energy is measured against an auto-gain
 * reference that jumps to the loudest band and sinks at [REFERENCE_DECAY_DB_PER_S]. Music also
 * carries far less energy up top than in the bass, so each band's ceiling is pulled part of the
 * way ([BAND_MIX]) towards that band's own recent peak; otherwise the right half never moves.
 * A power curve keeps most bars low-to-mid so only hits and the dominant notes reach the top.
 * Stateful because of those references: use one instance per stream.
 */
class SpectrumAnalyzer {
    private val long = Spectrum()
    private val short = Spectrum()
    private var bandDb = DoubleArray(0)
    private var bandPeakDb = DoubleArray(0)
    private var referenceDb = INITIAL_REFERENCE_DB

    /** Forget the loudness history, e.g. when the visualizer reappears. */
    fun resetGain() {
        referenceDb = INITIAL_REFERENCE_DB
        bandPeakDb.fill(NO_PEAK_DB)
    }

    /** [dtSeconds] is the time since the previous call; it drives the reference decay. */
    fun bands(samples: FloatArray, sampleRate: Int, out: FloatArray, dtSeconds: Float) {
        val n = samples.size
        require(n >= 4 && n and (n - 1) == 0) { "window size must be a power of two" }
        val shortSize = n / SHORT_WINDOW_DIVISOR
        long.transform(samples, 0, n)
        short.transform(samples, (n - shortSize) / 2, shortSize)

        val bandCount = out.size
        val nyquist = sampleRate / 2.0
        val hiHz = min(MAX_HZ, nyquist * 0.95)
        val lo = ln(MIN_HZ)
        val hi = ln(hiHz)
        if (bandDb.size != bandCount) {
            bandDb = DoubleArray(bandCount)
            bandPeakDb = DoubleArray(bandCount) { NO_PEAK_DB }
        }
        var loudest = -200.0
        for (b in 0 until bandCount) {
            val f0 = exp(lo + (hi - lo) * b / bandCount)
            val f1 = exp(lo + (hi - lo) * (b + 1) / bandCount)
            val spectrum = if (sqrt(f0 * f1) < SPLIT_HZ) long else short
            val tilt = TREBLE_LIFT_DB * b / max(1, bandCount - 1)
            val db = 10.0 * log10(max(spectrum.power(f0, f1, sampleRate), 1e-12)) + tilt
            bandDb[b] = db
            if (db > loudest) loudest = db
        }

        referenceDb = if (loudest > referenceDb) {
            loudest
        } else {
            // Sinks steadily and never below the floor: a quiet verse after a loud chorus
            // starts visibly lower, and near-silence is not pumped up to full height.
            max(MIN_REFERENCE_DB, referenceDb - REFERENCE_DECAY_DB_PER_S * dtSeconds)
        }
        for (b in 0 until bandCount) {
            val db = bandDb[b]
            bandPeakDb[b] = max(db, bandPeakDb[b] - REFERENCE_DECAY_DB_PER_S * dtSeconds)
            val shortfall = (referenceDb - bandPeakDb[b]).coerceIn(0.0, BAND_MIX_LIMIT_DB)
            val top = referenceDb - BAND_MIX * shortfall
            out[b] = if (db < SILENCE_DB) {
                0f
            } else {
                val x = ((db - (top - RANGE_DB)) / (RANGE_DB + HEADROOM_DB)).coerceIn(0.0, 1.0)
                x.pow(CURVE).toFloat()
            }
        }
    }

    /** One Hann-windowed FFT and the band-energy lookup over it. */
    private class Spectrum {
        private var size = 0
        private var window = FloatArray(0)
        private var re = FloatArray(0)
        private var im = FloatArray(0)

        fun transform(samples: FloatArray, offset: Int, n: Int) {
            if (size != n) {
                size = n
                window = FloatArray(n) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))).toFloat() }
                re = FloatArray(n)
                im = FloatArray(n)
            }
            for (i in 0 until n) {
                re[i] = samples[offset + i] * window[i]
                im[i] = 0f
            }
            fft(re, im)
        }

        /** Summed power of the bins whose centre frequency lies in [f0, f1). */
        fun power(f0: Double, f1: Double, sampleRate: Int): Double {
            val n = size
            val binHz = sampleRate.toDouble() / n
            // Hann coherent gain is 0.5; one-sided spectrum doubles it back → amplitude = 4|X|/N.
            val scale = 4.0 / n
            // Bass bands narrower than one bin fall back to the bin nearest the band centre.
            var bin0 = max(1, ceil(f0 / binHz).toInt())
            var bin1 = ceil(f1 / binHz).toInt().coerceAtMost(n / 2)
            if (bin1 <= bin0) {
                bin0 = (sqrt(f0 * f1) / binHz).roundToInt().coerceIn(1, n / 2 - 1)
                bin1 = bin0 + 1
            }
            var power = 0.0
            for (k in bin0 until bin1) {
                power += (re[k] * re[k] + im[k] * im[k]).toDouble() * scale * scale
            }
            return power
        }
    }

    companion object {
        const val MIN_HZ = 40.0
        const val MAX_HZ = 16_000.0
        /** Bands centred below this use the full window, the rest its middle quarter. */
        const val SPLIT_HZ = 300.0
        private const val SHORT_WINDOW_DIVISOR = 4
        /** dB below a band's ceiling that maps to an empty bar. */
        const val RANGE_DB = 28.0
        /** The reference band itself stops a little short of full height. */
        const val HEADROOM_DB = 2.0
        /** >1 pushes mid values down so the bars have real height differences. */
        const val CURVE = 1.7
        /** Music rolls off faster than pink noise up top; the lift keeps the right side alive. */
        const val TREBLE_LIFT_DB = 14.0
        /** Share of a band's shortfall against the global reference that is forgiven. */
        const val BAND_MIX = 0.35
        const val BAND_MIX_LIMIT_DB = 18.0
        const val INITIAL_REFERENCE_DB = -30.0
        const val MIN_REFERENCE_DB = -42.0
        /** Fast enough that one loud hit does not hold every bar down for the next ten seconds. */
        const val REFERENCE_DECAY_DB_PER_S = 3.0
        private const val NO_PEAK_DB = -200.0
        /** Bands quieter than this (absolute dBFS) are treated as silence. */
        const val SILENCE_DB = -80.0

        /** ~85 ms of audio, enough resolution for the 40–60 Hz band at any common rate. */
        fun windowSizeFor(sampleRate: Int): Int = when {
            sampleRate <= 48_000 -> 4096
            sampleRate <= 96_000 -> 8192
            else -> 16384
        }

        /** In-place iterative radix-2 FFT. */
        internal fun fft(re: FloatArray, im: FloatArray) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var len = 2
            while (len <= n) {
                val angle = -2.0 * PI / len
                val wRe = cos(angle).toFloat()
                val wIm = sin(angle).toFloat()
                val half = len / 2
                var start = 0
                while (start < n) {
                    var curRe = 1f
                    var curIm = 0f
                    for (k in 0 until half) {
                        val a = start + k
                        val b = a + half
                        val tRe = re[b] * curRe - im[b] * curIm
                        val tIm = re[b] * curIm + im[b] * curRe
                        re[b] = re[a] - tRe
                        im[b] = im[a] - tIm
                        re[a] += tRe
                        im[a] += tIm
                        val nextRe = curRe * wRe - curIm * wIm
                        curIm = curRe * wIm + curIm * wRe
                        curRe = nextRe
                    }
                    start += len
                }
                len = len shl 1
            }
        }
    }
}
