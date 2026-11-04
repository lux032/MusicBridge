package com.lux032.musicbridge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.lux032.musicbridge.local.AudioSpectrumTap
import kotlinx.coroutines.flow.first
import kotlin.math.max
import kotlin.math.pow

/**
 * Whether the player page shows the spectrum instead of the cover. Process-wide so the choice
 * survives closing and reopening the player (the section leaves composition on close).
 */
internal object PlayerArtworkMode {
    var showVisualizer by mutableStateOf(false)
}

/** 20 bars rising from the bottom with accent peak caps (style A, 20 bars, look D). */
private const val BAR_COUNT = 20
/** Gap as a fraction of bar width, so the bars read as one block rather than spaced posts. */
private const val GAP_RATIO = 0.32f
/** Resting stub height: paused bars still read as "the visualizer, paused". */
private const val REST_LEVEL = 0.025f
private const val ATTACK = 0.7f
private const val RELEASE = 0.14f
private const val PEAK_HOLD_MS = 420L
/** Peak cap fall acceleration, in bar-heights per second². */
private const val PEAK_GRAVITY = 1.3f
/** Bands averaged into the glow's bass level (≈ 40–150 Hz). */
private const val BASS_BANDS = 4

/**
 * Bar levels plus a smoothed bass level, hoisted to the player page so the background glow can
 * follow the same signal the bars draw. Values are plain arrays mutated by the frame loop;
 * [frameTick] is the only snapshot state, read in draw phases so frames only redraw.
 */
@Stable
internal class SpectrumState {
    val level = FloatArray(BAR_COUNT)
    val peak = FloatArray(BAR_COUNT)
    private val peakAtMs = LongArray(BAR_COUNT)
    private val peakVelocity = FloatArray(BAR_COUNT)
    var bass = 0f
        private set
    var frameTick by mutableLongStateOf(0L)
        internal set

    fun step(target: FloatArray, nowMs: Long, dt: Float) {
        for (i in level.indices) {
            val k = if (target[i] > level[i]) ATTACK else RELEASE
            level[i] += (target[i] - level[i]) * (1f - (1f - k).pow(dt * 60f))
            if (level[i] >= peak[i]) {
                peak[i] = level[i]
                peakAtMs[i] = nowMs
                peakVelocity[i] = 0f
            } else if (nowMs - peakAtMs[i] > PEAK_HOLD_MS) {
                peakVelocity[i] += PEAK_GRAVITY * dt
                peak[i] = max(level[i], peak[i] - peakVelocity[i] * dt)
            }
        }
        var bassNow = 0f
        for (i in 0 until BASS_BANDS) bassNow += level[i]
        bassNow /= BASS_BANDS
        bass += (bassNow - bass) * (1f - 0.82f.pow(dt * 60f))
    }

    fun reset() {
        level.fill(0f)
        peak.fill(0f)
        peakAtMs.fill(0L)
        peakVelocity.fill(0f)
        bass = 0f
    }

    /** True once bars, caps and glow have come to rest, so the frame loop can stop. */
    fun isSettled(): Boolean =
        bass < 0.002f && level.all { it < 0.002f } && peak.all { it < 0.002f }
}

/**
 * Bottom-anchored spectrum for the player-page artwork slot. No backing slab: the bars grow
 * straight out of the page's colour field and fade towards their base. Draws only; the caller
 * owns size and the cover crossfade. [isPlaying] must reflect the real transport state: when
 * false the bars fall to rest instead of freezing on the last frame.
 */
@Composable
internal fun SpectrumVisualizer(
    state: SpectrumState,
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val target = remember { FloatArray(BAR_COUNT) }
    val playing by rememberUpdatedState(isPlaying)

    DisposableEffect(state) {
        AudioSpectrumTap.acquire()
        onDispose {
            AudioSpectrumTap.release()
            state.reset()
        }
    }

    LaunchedEffect(state) {
        var lastNanos = 0L
        while (true) {
            if (!playing && state.isSettled()) {
                // Nothing moves: stop requesting frames until playback resumes.
                lastNanos = 0L
                snapshotFlow { playing }.first { it }
            }
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
                lastNanos = now
                if (!playing || !AudioSpectrumTap.readBands(target, dt)) target.fill(0f)
                state.step(target, now / 1_000_000L, dt)
                state.frameTick = now
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = state.frameTick
        val w = size.width
        val h = size.height
        // Same footprint as the cover: bars span its full width, sitting on its bottom edge.
        val barWidth = w / (BAR_COUNT + (BAR_COUNT - 1) * GAP_RATIO)
        val gap = barWidth * GAP_RATIO
        val base = h - 4.dp.toPx()
        val span = base - 26.dp.toPx()
        val capHeight = (barWidth * 0.22f).coerceIn(3.dp.toPx(), 6.dp.toPx())
        val capGap = 3.dp.toPx()
        val barRadius = CornerRadius(barWidth * 0.14f)
        val capRadius = CornerRadius(minOf(barWidth * 0.14f, capHeight / 2f))
        val barTop = Color.White.copy(alpha = 0.92f)
        val barBase = Color.White.copy(alpha = 0.22f)

        for (i in 0 until BAR_COUNT) {
            val x = i * (barWidth + gap)
            val barHeight = max(REST_LEVEL, state.level[i]) * span
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(barTop, barBase),
                    startY = base - barHeight,
                    endY = base,
                ),
                topLeft = Offset(x, base - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = barRadius,
            )
            val capY = base - max(REST_LEVEL, state.peak[i]) * span - capHeight - capGap
            drawRoundRect(
                color = accent,
                topLeft = Offset(x, capY),
                size = Size(barWidth, capHeight),
                cornerRadius = capRadius,
            )
        }
    }
}

/**
 * Soft accent light behind the artwork. With the cover showing it is a faint constant halo;
 * with the visualizer showing ([visualizerProgress] → 1) it brightens and swells with the bass,
 * so the whole page responds rather than only the square. Draws outside the node's bounds, so
 * place it before any clip.
 */
internal fun Modifier.spectrumGlow(
    state: SpectrumState,
    visualizerProgress: () -> Float,
    accent: Color,
): Modifier = drawBehind {
    @Suppress("UNUSED_VARIABLE")
    val tick = state.frameTick
    val progress = visualizerProgress()
    val bass = state.bass * progress
    val alpha = mix(0.14f, 0.10f + 0.42f * state.bass, progress).coerceIn(0f, 1f)
    val scale = mix(1f, 0.92f + 0.14f * bass, progress)
    val radius = size.minDimension * 0.83f * scale
    val core = lerp(accent, Color.White, 0.45f)
    drawCircle(
        brush = Brush.radialGradient(
            colorStops = arrayOf(
                0f to core.copy(alpha = 0.75f * alpha),
                0.55f to accent.copy(alpha = 0.28f * alpha),
                1f to accent.copy(alpha = 0f),
            ),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        blendMode = BlendMode.Screen,
    )
}

private fun mix(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
