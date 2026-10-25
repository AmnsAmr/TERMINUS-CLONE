package com.necroware.terminusplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.necroware.terminusplayer.playback.WaveformData

/**
 * Terminal-styled waveform seek bar. Renders discrete vertical block segments
 * matching the cyber/retro aesthetic of [BlockSeekBar].
 *
 * Performance:
 * Playback position is read ONLY during the draw phase via [positionMsProvider],
 * completely eliminating recompositions on playback ticks (up to 120 FPS).
 */
@Composable
fun WaveformSeekBar(
    waveformData: WaveformData?,
    positionMsProvider: () -> Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    barHeight: Dp = 44.dp,
    barWidthDp: Dp = 2.dp,
    barGapDp: Dp = 1.5.dp,
    enabled: Boolean = true
) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    val primaryColor = MaterialTheme.colorScheme.primary
    val unplayedColor = MaterialTheme.colorScheme.surfaceVariant

    var componentWidthPx by remember { mutableStateOf(1f) }

    fun seekToOffsetX(x: Float) {
        val fraction = (x / componentWidthPx).coerceIn(0f, 1f)
        onSeek((fraction * safeDuration).toLong())
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .then(
                if (enabled) {
                    Modifier
                        .pointerInput(safeDuration) {
                            detectTapGestures { offset ->
                                seekToOffsetX(offset.x)
                            }
                        }
                        .pointerInput(safeDuration) {
                            detectDragGestures { change, _ ->
                                seekToOffsetX(change.position.x)
                            }
                        }
                } else Modifier
            )
    ) {
        componentWidthPx = size.width.coerceAtLeast(1f)
        val barWidthPx = barWidthDp.toPx()
        val barGapPx = barGapDp.toPx()
        val slotWidthPx = barWidthPx + barGapPx

        if (slotWidthPx <= 0f || size.width <= 0f) return@Canvas

        val barCount = (size.width / slotWidthPx).toInt().coerceAtLeast(1)
        val buckets = waveformData?.buckets

        // Read playback position in DRAW phase only: zero recompositions!
        val currentPositionMs = positionMsProvider().coerceIn(0L, safeDuration)
        val progressFraction = currentPositionMs.toFloat() / safeDuration
        val currentProgressX = progressFraction * size.width

        val minBarHeightPx = 3.dp.toPx()
        val maxBarHeightPx = size.height

        for (i in 0 until barCount) {
            val barLeft = i * slotWidthPx
            val barRight = barLeft + barWidthPx
            if (barLeft >= size.width) break

            val peak = if (buckets != null && buckets.isNotEmpty()) {
                val startBucketIdx = (i * buckets.size) / barCount
                val endBucketIdx = (((i + 1) * buckets.size) / barCount).coerceAtMost(buckets.size)
                var sum = 0f
                var maxVal = 0f
                var cnt = 0
                for (b in startBucketIdx until endBucketIdx) {
                    val v = buckets[b]
                    sum += v
                    if (v > maxVal) maxVal = v
                    cnt++
                }
                if (cnt > 0) {
                    (sum / cnt) * 0.7f + maxVal * 0.3f
                } else if (startBucketIdx < buckets.size) {
                    buckets[startBucketIdx]
                } else {
                    0f
                }
            } else {
                0f // Resting ambient state while computing
            }

            val computedHeight = (peak * (maxBarHeightPx - minBarHeightPx) + minBarHeightPx)
                .coerceIn(minBarHeightPx, maxBarHeightPx)
            val top = (size.height - computedHeight) / 2f

            val isPlayed = (barLeft + barRight) / 2f <= currentProgressX
            val color = if (isPlayed) primaryColor else unplayedColor

            drawRect(
                color = color,
                topLeft = Offset(barLeft, top),
                size = Size(barWidthPx, computedHeight)
            )
        }
    }
}
