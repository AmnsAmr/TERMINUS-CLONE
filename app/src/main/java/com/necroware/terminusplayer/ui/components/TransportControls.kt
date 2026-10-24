package com.necroware.terminusplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.media3.common.Player
import com.necroware.terminusplayer.ui.TestTags
import com.necroware.terminusplayer.ui.navigation.MotionEasing

/**
 * Plain bracketed ASCII transport glyphs, each in a bordered box, matching
 * the terminal "button" language elsewhere in the app. Shuffle/repeat use
 * short text labels (SHUF / RPT / RPT1) rather than arrow-style symbols —
 * "x2" and "<->" read as ambiguous rather than as shuffle/repeat at a glance.
 */
private object Glyph {
    const val PREVIOUS = "|<<"
    const val NEXT = ">>|"
    const val SHUFFLE = "SHUF"
    const val REPEAT_OFF = "RPT"
    const val REPEAT_ONE = "RPT1"
}

@Composable
private fun GlyphButton(
    text: String,
    onClick: () -> Unit,
    color: Color,
    boxWidth: Dp,
    boxHeight: Dp,
    fontSizeSp: Int,
    testTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .testTag(testTag)
            .width(boxWidth)
            .height(boxHeight)
            .border(1.dp, color.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = fontSizeSp.sp,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            color = color
        )
    }
}

/**
 * Play/pause drawn as two strokes in the accent colour: the `>` chevron's strokes swing apart into
 * the two `||` bars and back. The terminal line-glyph look of the text buttons is kept, and the
 * strokes are sized from the same font size the text glyphs use.
 *
 * @param morphMs Morph duration from the motion tokens; 0 swaps immediately (OFF).
 */
@Composable
private fun PlayPauseButton(
    isPlaying: Boolean,
    onClick: () -> Unit,
    color: Color,
    boxSize: Dp,
    fontSizeSp: Int,
    morphMs: Int,
    testTag: String,
    enabled: Boolean = true
) {
    val target = if (isPlaying) 1f else 0f
    val morph = remember { Animatable(target) }
    LaunchedEffect(target, morphMs) {
        if (morphMs <= 0) morph.snapTo(target)
        else morph.animateTo(target, tween(morphMs, easing = MotionEasing.Standard))
    }
    val glyphHeight = with(LocalDensity.current) { (fontSizeSp * GLYPH_HEIGHT_EM).sp.toDp() }
    Box(
        modifier = Modifier
            .testTag(testTag)
            .size(boxSize)
            .border(1.dp, color.copy(alpha = 0.5f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
        contentAlignment = Alignment.Center
    ) {
        // The morph value is read only here, so an animating glyph redraws without recomposing.
        Canvas(Modifier.size(width = glyphHeight * GLYPH_ASPECT, height = glyphHeight)) {
            drawPlayPause(morph.value, color)
        }
    }
}

/** Glyph height as a share of the font size, close to the `>` of the terminal font. */
private const val GLYPH_HEIGHT_EM = 0.62f
private const val GLYPH_ASPECT = 0.66f
private const val STROKE_EM_OF_HEIGHT = 0.16f
/** Bar positions as a share of the glyph width. */
private const val PAUSE_LEFT = 0.18f
private const val PAUSE_RIGHT = 0.82f

/**
 * [pause] 0 draws `>` (top stroke and bottom stroke meeting at the right-hand point); 1 draws `||`.
 * The top stroke becomes the left bar and the bottom stroke the right bar.
 */
internal fun chevronToBars(pause: Float, width: Float, height: Float): FloatArray {
    val p = pause.coerceIn(0f, 1f)
    val left = width * PAUSE_LEFT
    val right = width * PAUSE_RIGHT
    val mid = height / 2f
    return floatArrayOf(
        // Top stroke: (0, 0) -> (w, h/2) becomes (left, 0) -> (left, h).
        lerp(0f, left, p), 0f, lerp(width, left, p), lerp(mid, height, p),
        // Bottom stroke: (0, h) -> (w, h/2) becomes (right, h) -> (right, 0).
        lerp(0f, right, p), height, lerp(width, right, p), lerp(mid, 0f, p)
    )
}

private fun DrawScope.drawPlayPause(pause: Float, color: Color) {
    val stroke = size.height * STROKE_EM_OF_HEIGHT
    // Inset by half a stroke so square caps stay inside the glyph box.
    val inset = stroke / 2f
    val w = size.width - stroke
    val h = size.height - stroke
    val pts = chevronToBars(pause, w, h)
    for (i in 0 until 2) {
        val o = i * 4
        drawLine(
            color = color,
            start = Offset(pts[o] + inset, pts[o + 1] + inset),
            end = Offset(pts[o + 2] + inset, pts[o + 3] + inset),
            strokeWidth = stroke,
            cap = StrokeCap.Square
        )
    }
}

/** Full-size transport row for the Now Playing screen. */
@Composable
fun FullTransportControls(
    isPlaying: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Play/pause morph duration from the motion tokens; 0 swaps immediately. */
    morphMs: Int = 0
) {
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphButton(Glyph.SHUFFLE, onToggleShuffle, if (shuffleEnabled) accent else dim, 56.dp, 40.dp, 11, testTag = TestTags.TRANSPORT_SHUFFLE, enabled = enabled)
        GlyphButton(Glyph.PREVIOUS, onSkipPrevious, accent, 56.dp, 56.dp, 16, testTag = TestTags.TRANSPORT_PREVIOUS, enabled = enabled)
        PlayPauseButton(isPlaying, onTogglePlayPause, accent, 68.dp, 19, morphMs, testTag = TestTags.TRANSPORT_PLAY_PAUSE, enabled = enabled)
        GlyphButton(Glyph.NEXT, onSkipNext, accent, 56.dp, 56.dp, 16, testTag = TestTags.TRANSPORT_NEXT, enabled = enabled)
        GlyphButton(
            if (repeatMode == Player.REPEAT_MODE_ONE) Glyph.REPEAT_ONE else Glyph.REPEAT_OFF,
            onCycleRepeat,
            if (repeatMode != Player.REPEAT_MODE_OFF) accent else dim,
            56.dp,
            40.dp,
            10,
            testTag = TestTags.TRANSPORT_REPEAT,
            enabled = enabled
        )
    }
}

/** Compact prev/play-pause/next row for the mini player. */
@Composable
fun CompactTransportControls(
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier,
    /** Play/pause morph duration from the motion tokens; 0 swaps immediately. */
    morphMs: Int = 0
) {
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlyphButton(Glyph.PREVIOUS, onSkipPrevious, accent, 28.dp, 28.dp, 9, testTag = TestTags.MINI_PREVIOUS)
        PlayPauseButton(isPlaying, onTogglePlayPause, accent, 32.dp, 11, morphMs, testTag = TestTags.MINI_PLAY_PAUSE)
        GlyphButton(Glyph.NEXT, onSkipNext, accent, 28.dp, 28.dp, 9, testTag = TestTags.MINI_NEXT)
    }
}
