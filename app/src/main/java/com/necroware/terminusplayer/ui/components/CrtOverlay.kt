package com.necroware.terminusplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Renders retro CRT scanlines and subtle phosphor glow with low GPU/CPU overhead.
 * Uses [Modifier.drawWithCache] so the scanline path and radial glow brush
 * are precalculated once per resize rather than recreated on every frame.
 * When [intensity] is 0, nothing is drawn.
 */
@Composable
fun CrtScanlineOverlay(
    intensity: Float,
    phosphorAfterglow: Boolean,
    modifier: Modifier = Modifier
) {
    if (intensity <= 0.01f && !phosphorAfterglow) return

    val scanlineAlpha = (intensity * 0.35f).coerceIn(0f, 0.7f)
    val glowColor = remember { Color(0xFF39FF14).copy(alpha = 0.04f) }

    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val width = size.width
                val height = size.height

                val radialBrush = if (phosphorAfterglow && width > 0f && height > 0f) {
                    Brush.radialGradient(
                        colors = listOf(glowColor, Color.Transparent),
                        center = Offset(width / 2f, height / 2f),
                        radius = maxOf(width, height) * 0.75f
                    )
                } else null

                val scanlinePath = if (scanlineAlpha > 0.01f && width > 0f && height > 0f) {
                    val path = Path()
                    val step = 4.dp.toPx()
                    var y = 0f
                    while (y < height) {
                        path.moveTo(0f, y)
                        path.lineTo(width, y)
                        y += step
                    }
                    path
                } else null

                val scanlineStroke = Stroke(width = 1.2.dp.toPx())
                val scanlineColor = Color.Black.copy(alpha = scanlineAlpha)

                onDrawBehind {
                    if (radialBrush != null) {
                        drawRect(brush = radialBrush)
                    }
                    if (scanlinePath != null) {
                        drawPath(
                            path = scanlinePath,
                            color = scanlineColor,
                            style = scanlineStroke
                        )
                    }
                }
            }
    )
}

/**
 * Vintage CRT power-down / collapse animation.
 * The display vertically squashes into a bright horizontal beam,
 * then horizontally collapses into a central point and vanishes.
 */
@Composable
fun CrtCollapseOverlay(
    isCollapsing: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isCollapsing) return

    val progress = remember { Animatable(0f) }
    LaunchedEffect(isCollapsing) {
        progress.snapTo(0f)
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 360, easing = FastOutLinearInEasing)
        )
    }

    val t = progress.value
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val centerX = w / 2f

        val vScale = (1f - (t * 2f).coerceAtMost(1f)).coerceAtLeast(0.002f)
        val hScale = if (t > 0.5f) (1f - ((t - 0.5f) * 2f)).coerceIn(0f, 1f) else 1f

        val halfCollapseHeight = (h * vScale) / 2f
        drawRect(
            color = Color.Black,
            topLeft = Offset(0f, 0f),
            size = Size(w, (centerY - halfCollapseHeight).coerceAtLeast(0f))
        )
        drawRect(
            color = Color.Black,
            topLeft = Offset(0f, centerY + halfCollapseHeight),
            size = Size(w, (h - (centerY + halfCollapseHeight)).coerceAtLeast(0f))
        )

        val lineWidth = w * hScale
        val lineLeft = centerX - (lineWidth / 2f)
        val lineAlpha = (1f - (t - 0.75f) / 0.25f).coerceIn(0f, 1f)
        if (lineWidth > 0f && lineAlpha > 0f) {
            drawLine(
                color = Color.White.copy(alpha = lineAlpha),
                start = Offset(lineLeft, centerY),
                end = Offset(lineLeft + lineWidth, centerY),
                strokeWidth = 3.dp.toPx()
            )
            drawLine(
                color = Color(0xFF39FF14).copy(alpha = lineAlpha * 0.5f),
                start = Offset(lineLeft, centerY),
                end = Offset(lineLeft + lineWidth, centerY),
                strokeWidth = 6.dp.toPx()
            )
        }
    }
}
