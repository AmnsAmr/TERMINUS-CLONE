package com.necroware.terminusplayer.ui.components.visualizer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.prefs.VisualizerStyle
import com.necroware.terminusplayer.playback.SpectrumFrame
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.log10
import kotlin.math.max

/**
 * Spectrum frames while [active], otherwise a fixed silent frame. Collecting is what turns the
 * analysis on, so pass `active` only when a visualizer is visible, playing and motion is FULL.
 */
@Composable
fun collectSpectrum(source: StateFlow<SpectrumFrame>, active: Boolean): State<SpectrumFrame> {
    val silent = remember { mutableStateOf(SpectrumFrame.Silent) }
    return if (active) source.collectAsStateWithLifecycle() else silent
}

/** Maps [source] bands onto [target] (fewer or more), taking the loudest overlapping band. */
internal fun resampleBands(source: FloatArray, target: FloatArray) {
    if (source.isEmpty()) {
        target.fill(0f)
        return
    }
    for (i in target.indices) {
        val from = i * source.size / target.size
        val to = max(from + 1, (i + 1) * source.size / target.size)
        var loudest = 0f
        for (j in from until to.coerceAtMost(source.size)) loudest = max(loudest, source[j])
        target[i] = loudest
    }
}

/** RMS or peak level on a 48 dB meter scale, 0..1. */
internal fun meterLevel(linear: Float): Float =
    if (linear <= 0f) 0f else ((20f * log10(linear) + 48f) / 48f).coerceIn(0f, 1f)

/** Display state carried between frames: fast attack, slower release, and peak holds. */
private class BarSmoothing(count: Int) {
    val shown = FloatArray(count)
    val peaks = FloatArray(count)
    val target = FloatArray(count)
    var lastSequence = -1L

    fun update(frame: SpectrumFrame) {
        if (frame.sequence == lastSequence) return
        lastSequence = frame.sequence
        resampleBands(frame.bands, target)
        for (i in shown.indices) {
            shown[i] = max(target[i], shown[i] * 0.82f)
            peaks[i] = max(shown[i], peaks[i] - 0.02f)
        }
    }
}

/**
 * The audio monitor strip, drawn from real spectrum data. When [active] is false it shows the
 * same resting pattern the simulated version did and reads no data.
 */
@Composable
fun SpectrumVisualizer(
    spectrum: State<SpectrumFrame>,
    active: Boolean,
    style: VisualizerStyle,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val barColor = primaryColor.copy(alpha = 0.75f)
    val glowColor = primaryColor.copy(alpha = 0.25f)
    val dimColor = primaryColor.copy(alpha = 0.10f)
    val bars = remember { BarSmoothing(BAR_COUNT) }
    val path = remember { Path() }

    Canvas(modifier = modifier.fillMaxWidth().height(26.dp)) {
        val w = size.width
        val h = size.height
        val midY = h / 2f
        // Read here, not in composition: each new frame only redraws this strip.
        val frame = if (active) spectrum.value else SpectrumFrame.Silent

        when (style) {
            VisualizerStyle.BARS -> {
                if (active) bars.update(frame)
                val spacing = 2.dp.toPx()
                val barWidth = (w - spacing * (BAR_COUNT - 1)) / BAR_COUNT
                val capHeight = 1.5.dp.toPx()
                for (i in 0 until BAR_COUNT) {
                    val left = i * (barWidth + spacing)
                    val level = if (active) bars.shown[i] else 0f
                    val barHeight = h * level.coerceIn(0.12f, 0.98f)
                    drawRect(barColor, Offset(left, h - barHeight), Size(barWidth, barHeight))
                    if (active) {
                        val peakY = h - h * bars.peaks[i].coerceIn(0.12f, 0.98f)
                        drawRect(primaryColor, Offset(left, peakY - capHeight), Size(barWidth, capHeight))
                    }
                }
            }
            VisualizerStyle.OSCILLOSCOPE -> {
                if (!active) {
                    drawLine(barColor, Offset(0f, midY), Offset(w, midY), strokeWidth = 1.5.dp.toPx())
                } else {
                    val wave = frame.waveform
                    path.rewind()
                    val stepX = w / (wave.size - 1)
                    for (i in wave.indices) {
                        val y = midY - wave[i] * midY * 0.85f
                        if (i == 0) path.moveTo(0f, y) else path.lineTo(i * stepX, y)
                    }
                    drawPath(path, glowColor, style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(path, primaryColor, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            VisualizerStyle.VU_METER -> {
                val segments = 20
                val spacing = 2.dp.toPx()
                val segW = (w - (segments - 1) * spacing) / segments
                val channelH = (h / 2f) - 3.dp.toPx()
                // Analysis is a mono downmix: the top meter shows RMS, the bottom the peak.
                val litTop = if (active) (meterLevel(frame.level) * segments).toInt() else 0
                val litBottom = if (active) (meterLevel(frame.peak) * segments).toInt() else 0
                for (row in 0..1) {
                    val lit = if (row == 0) litTop else litBottom
                    val topY = if (row == 0) 1.dp.toPx() else (h / 2f) + 2.dp.toPx()
                    for (i in 0 until segments) {
                        val color = when {
                            !active -> if (i % 2 == 0) primaryColor.copy(alpha = 0.15f) else Color.Transparent
                            i >= lit -> dimColor
                            i > segments * 0.85f -> Color(0xFFFF3B30)
                            i > segments * 0.65f -> Color(0xFFFFCC00)
                            else -> primaryColor
                        }
                        drawRect(color, Offset(i * (segW + spacing), topY), Size(segW, channelH))
                    }
                }
            }
        }
    }
}

private const val BAR_COUNT = 12
