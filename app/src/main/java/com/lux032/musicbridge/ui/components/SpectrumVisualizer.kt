package com.lux032.musicbridge

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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

/** Style A (chosen from the prototype): 20 bars rising from the bottom, accent peak caps. */
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

/** Fast attack, slower release; caps hold briefly then fall under gravity. */
private class BarMeter(count: Int) {
    val level = FloatArray(count)
    val peak = FloatArray(count)
    private val peakAtMs = LongArray(count)
    private val peakVelocity = FloatArray(count)

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
    }

    /** True once bars and caps have come to rest, so the frame loop can stop. */
    fun isSettled(): Boolean =
        level.all { it < 0.002f } && peak.all { it < 0.002f }
}

/**
 * Classic bottom-anchored spectrum for the player-page artwork slot. Draws only; the caller
 * owns size, clip and the cover crossfade. [isPlaying] must reflect the real transport state:
 * when false the bars fall to rest instead of freezing on the last frame.
 */
@Composable
internal fun SpectrumVisualizer(
    isPlaying: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val meter = remember { BarMeter(BAR_COUNT) }
    val target = remember { FloatArray(BAR_COUNT) }
    // Bumped every animation frame; read in the draw phase so only drawing is invalidated.
    var frameTick by remember { mutableLongStateOf(0L) }
    val playing by rememberUpdatedState(isPlaying)

    DisposableEffect(Unit) {
        AudioSpectrumTap.acquire()
        onDispose { AudioSpectrumTap.release() }
    }

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (true) {
            if (!playing && meter.isSettled()) {
                // Nothing moves: stop requesting frames until playback resumes.
                lastNanos = 0L
                snapshotFlow { playing }.first { it }
            }
            withFrameNanos { now ->
                val dt = if (lastNanos == 0L) 0f else ((now - lastNanos) / 1e9f).coerceAtMost(0.05f)
                lastNanos = now
                if (!playing || !AudioSpectrumTap.readBands(target)) target.fill(0f)
                meter.step(target, now / 1_000_000L, dt)
                frameTick = now
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val tick = frameTick
        // A slightly deeper slab of the colour field, same footprint as the cover.
        drawRect(Color.Black.copy(alpha = 0.28f))

        val w = size.width
        val h = size.height
        val pad = w * 0.07f
        val barWidth = (w - pad * 2f) / (BAR_COUNT + (BAR_COUNT - 1) * GAP_RATIO)
        val gap = barWidth * GAP_RATIO
        val base = h - pad
        val span = base - pad * 1.4f
        val capHeight = (barWidth * 0.22f).coerceIn(3.dp.toPx(), 6.dp.toPx())
        val capGap = 3.dp.toPx()
        val barRadius = CornerRadius(2.dp.toPx())
        val capRadius = CornerRadius(1.5.dp.toPx())
        val barColor = Color.White.copy(alpha = 0.9f)

        for (i in 0 until BAR_COUNT) {
            val x = pad + i * (barWidth + gap)
            val barHeight = max(REST_LEVEL, meter.level[i]) * span
            drawRoundRect(
                color = barColor,
                topLeft = Offset(x, base - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = barRadius,
            )
            val capY = base - max(REST_LEVEL, meter.peak[i]) * span - capHeight - capGap
            drawRoundRect(
                color = accent,
                topLeft = Offset(x, capY),
                size = Size(barWidth, capHeight),
                cornerRadius = capRadius,
            )
        }
    }
}
