package com.necroware.terminusplayer.ui.components.visualizer

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.necroware.terminusplayer.playback.AudioSpectrum
import com.necroware.terminusplayer.playback.SpectrumFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * MilkDrop-style audio-reactive art: each frame re-draws the previous one zoomed, rotated,
 * warped, hue-shifted and decayed, then draws the current waveform on top. API 33+ does the
 * feedback on the GPU with an AGSL shader; older devices (or a GPU setup failure) get a Canvas
 * echo-trail renderer.
 *
 * Renders only while [active]; pass false unless it is visible, playing, motion is FULL and
 * optional work is not deferred. When inactive the last frame stays on screen, still.
 */
@Composable
fun WarpVisualizer(
    spectrum: State<SpectrumFrame>,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 300.dp
) {
    var gpuUnavailable by remember { mutableStateOf(false) }
    Box(modifier.size(size).clipToBounds().background(Color.Black)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !gpuUnavailable) {
            GpuWarp(spectrum, active, accent, onUnavailable = { gpuUnavailable = true })
        } else {
            CanvasEchoWarp(spectrum, active, accent)
        }
    }
}

/**
 * Smoothed feedback controls derived from the spectrum. Rates are per 60 Hz frame; the
 * renderers scale them by real frame time.
 */
internal class WarpParams {
    var zoom = 1.008f
        private set
    var angle = 0.002f
        private set
    var warp = 0f
        private set
    var hue = 0.004f
        private set
    /** Decays from 1 after each beat. */
    var pulse = 0f
        private set
    var time = 0f
        private set
    private var lastSequence = -1L

    fun update(frame: SpectrumFrame, dtSeconds: Float) {
        val bands = frame.bands
        val bass = average(bands, 0, 3)
        val mid = average(bands, 4, 10)
        val treble = average(bands, 10, bands.size)
        if (frame.sequence != lastSequence) {
            lastSequence = frame.sequence
            if (frame.beat) pulse = 1f
        }
        pulse = (pulse - dtSeconds * 3f).coerceAtLeast(0f)
        val follow = (dtSeconds * 8f).coerceIn(0f, 1f)
        zoom += ((1.006f + bass * 0.035f + pulse * 0.03f) - zoom) * follow
        angle += ((0.002f + (mid - 0.4f) * 0.012f) - angle) * follow
        warp += (treble * 0.9f - warp) * follow
        hue = 0.004f + pulse * 0.08f
        time += dtSeconds
    }

    private fun average(values: FloatArray, from: Int, to: Int): Float {
        val end = min(to, values.size)
        if (end <= from) return 0f
        var sum = 0f
        for (i in from until end) sum += values[i]
        return sum / (end - from)
    }
}

/** Per-60Hz-frame rate [perFrame] applied over [dtSeconds] (multiplicative, e.g. zoom/decay). */
internal fun perFrameFactor(perFrame: Float, dtSeconds: Float): Float = perFrame.pow(dtSeconds * 60f)

private const val RENDER_SCALE = 0.5f
private const val FEEDBACK_DECAY = 0.94f

/** Rendering blocks briefly on GPU present, so it gets its own serial lane off the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
private val warpRenderDispatcher = Dispatchers.IO.limitedParallelism(1)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GpuWarp(spectrum: State<SpectrumFrame>, active: Boolean, accent: Color, onUnavailable: () -> Unit) {
    val latest = remember { mutableStateOf<ImageBitmap?>(null) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val accentArgb by rememberUpdatedState(accent.toArgb())
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)

    LaunchedEffect(active, sizePx) {
        if (!active || sizePx.width <= 0 || sizePx.height <= 0) return@LaunchedEffect
        val width = (sizePx.width * RENDER_SCALE).toInt().coerceAtLeast(64)
        val height = (sizePx.height * RENDER_SCALE).toInt().coerceAtLeast(64)
        val started = withContext(warpRenderDispatcher) {
            val renderer = GpuWarpRenderer.createOrNull(width, height) ?: return@withContext false
            val params = WarpParams()
            var lastNanos = 0L
            try {
                while (currentCoroutineContext().isActive) {
                    // Compose's frame clock is still in this context, so this paces to vsync.
                    val now = withFrameNanos { it }
                    val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    lastNanos = now
                    val frame = spectrum.value
                    params.update(frame, dt)
                    renderer.render(params, frame, accentArgb, dt)?.let { latest.value = it.asImageBitmap() }
                }
            } finally {
                renderer.release()
            }
            true
        }
        if (!started) latestOnUnavailable()
    }

    Canvas(Modifier.fillMaxSize().onSizeChanged { sizePx = it }) {
        val image = latest.value ?: return@Canvas
        drawImage(
            image = image,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low
        )
    }
}

/**
 * Offscreen GPU feedback loop: a RenderNode drawn by HardwareRenderer into an ImageReader, whose
 * newest image becomes the shader input for the next frame. Single-threaded use only.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class GpuWarpRenderer private constructor(
    private val width: Int,
    private val height: Int,
    private val reader: ImageReader,
    private val renderer: HardwareRenderer,
    private val shader: RuntimeShader
) {
    private val node = RenderNode("WarpVisualizer").apply { setPosition(0, 0, width, height) }
    private val feedbackPaint = android.graphics.Paint().apply { shader = this@GpuWarpRenderer.shader }
    private val wavePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    private val path = android.graphics.Path()

    // The newest image feeds the next frame and is on screen; the one before may still be
    // drawing in the UI, so it is closed one frame later.
    private var newest: Image? = null
    private var previous: Image? = null
    private var newestBitmap: Bitmap? = null

    init {
        renderer.setContentRoot(node)
    }

    fun render(params: WarpParams, frame: SpectrumFrame, color: Int, dt: Float): Bitmap? {
        val canvas = node.beginRecording(width, height)
        try {
            val input = newestBitmap
            if (input == null) {
                canvas.drawColor(android.graphics.Color.BLACK)
            } else {
                shader.setInputShader("prev", BitmapShader(input, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
                shader.setFloatUniform("zoom", perFrameFactor(params.zoom, dt))
                shader.setFloatUniform("angle", params.angle * dt * 60f)
                shader.setFloatUniform("warp", params.warp)
                shader.setFloatUniform("time", params.time)
                shader.setFloatUniform("decay", perFrameFactor(FEEDBACK_DECAY, dt))
                shader.setFloatUniform("hue", params.hue * dt * 60f)
                canvas.drawPaint(feedbackPaint)
            }
            buildWaveRing(path, frame, width / 2f, height / 2f, min(width, height) * 0.28f)
            wavePaint.color = color
            wavePaint.strokeWidth = min(width, height) * 0.006f * (1f + params.pulse * 1.5f)
            canvas.drawPath(path, wavePaint)
        } finally {
            node.endRecording()
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

        val image = reader.acquireLatestImage() ?: return newestBitmap
        val buffer = image.hardwareBuffer
        val bitmap = buffer?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        buffer?.close()
        if (bitmap == null) {
            image.close()
            return newestBitmap
        }
        previous?.close()
        previous = newest
        newest = image
        newestBitmap = bitmap
        return bitmap
    }

    fun release() {
        renderer.destroy()
        node.discardDisplayList()
        newest?.close()
        previous?.close()
        reader.close()
    }

    companion object {
        /** Null when this device rejects the offscreen GPU setup; callers fall back to Canvas. */
        fun createOrNull(width: Int, height: Int): GpuWarpRenderer? {
            // RuntimeShader throws IllegalArgumentException if the device rejects the AGSL.
            val shader = try {
                RuntimeShader(WARP_AGSL)
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("WarpVisualizer", "Warp shader rejected, using Canvas renderer", e)
                return null
            }
            val reader = try {
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    3,
                    HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT
                )
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("WarpVisualizer", "GPU feedback unavailable, using Canvas renderer", e)
                return null
            }
            val renderer = HardwareRenderer().apply {
                setSurface(reader.surface)
                isOpaque = true
            }
            return GpuWarpRenderer(width, height, reader, renderer, shader)
        }
    }
}

/** Closed polar waveform: radius follows the samples around a ring whose size follows loudness. */
private fun buildWaveRing(path: android.graphics.Path, frame: SpectrumFrame, cx: Float, cy: Float, baseRadius: Float) {
    path.rewind()
    val wave = frame.waveform
    val radius = baseRadius * (1f + frame.level * 0.6f)
    for (i in wave.indices) {
        val theta = (2.0 * PI * i / wave.size).toFloat()
        val r = radius + wave[i] * baseRadius * 0.35f
        val x = cx + r * cos(theta)
        val y = cy + r * sin(theta)
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
}

private const val ECHO_COUNT = 10

/** Last few waveform rings, so the Canvas renderer can fake feedback with scaled, fading echoes. */
private class EchoTrail {
    val rings = Array(ECHO_COUNT) { FloatArray(AudioSpectrum.WAVEFORM_POINTS) }
    val levels = FloatArray(ECHO_COUNT)
    var head = 0
    var count = 0
    var spin = 0f
    var pulse = 0f
    private var lastSequence = -1L

    fun push(frame: SpectrumFrame) {
        if (frame.sequence == lastSequence || frame.sequence == 0L) return
        lastSequence = frame.sequence
        frame.waveform.copyInto(rings[head])
        levels[head] = frame.level
        head = (head + 1) % ECHO_COUNT
        count = min(count + 1, ECHO_COUNT)
        var mid = 0f
        for (i in 4 until 10) mid += frame.bands[i]
        spin += 0.03f + mid / 6f * 0.08f
        pulse = if (frame.beat) 1f else pulse * 0.8f
    }
}

@Composable
private fun CanvasEchoWarp(spectrum: State<SpectrumFrame>, active: Boolean, accent: Color) {
    val trail = remember { EchoTrail() }
    val path = remember { Path() }
    // One tint per echo age, drifting in hue away from the accent.
    val tints = remember(accent) {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
        Array(ECHO_COUNT) { age ->
            Color(android.graphics.Color.HSVToColor(floatArrayOf((hsv[0] + age * 14f) % 360f, hsv[1], hsv[2])))
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        // Reading the frame while drawing keeps updates to a redraw of this square only.
        if (active) trail.push(spectrum.value)
        val center = Offset(size.width / 2f, size.height / 2f)
        val baseRadius = min(size.width, size.height) * 0.28f
        for (age in trail.count - 1 downTo 0) {
            val index = (trail.head - 1 - age + ECHO_COUNT) % ECHO_COUNT
            val wave = trail.rings[index]
            val radius = baseRadius * (1f + trail.levels[index] * 0.6f)
            path.rewind()
            for (i in wave.indices) {
                val theta = (2.0 * PI * i / wave.size).toFloat()
                val r = radius + wave[i] * baseRadius * 0.35f
                val x = center.x + r * cos(theta)
                val y = center.y + r * sin(theta)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            val fade = (1f - age.toFloat() / ECHO_COUNT).pow(1.6f)
            rotate(degrees = (trail.spin - age * 0.12f) * 57.29578f, pivot = center) {
                scale(scale = 1f + age * 0.09f * (1f + trail.pulse), pivot = center) {
                    drawPath(
                        path,
                        tints[age].copy(alpha = fade),
                        style = Stroke(width = 2.dp.toPx() * (1f + if (age == 0) trail.pulse else 0f), join = StrokeJoin.Round)
                    )
                }
            }
        }
    }
}

/**
 * Feedback pass. Samples the previous frame toward the centre (zoom), rotated and warped by a
 * slow sinusoidal field, rotates its hue in YIQ space and lets it decay.
 */
private const val WARP_AGSL = """
uniform shader prev;
uniform float2 resolution;
uniform float zoom;
uniform float angle;
uniform float warp;
uniform float time;
uniform float decay;
uniform float hue;

half3 rotateHue(half3 rgb, float amount) {
    half3x3 toYiq = half3x3(0.299, 0.596, 0.211,
                            0.587, -0.274, -0.523,
                            0.114, -0.322, 0.312);
    half3x3 toRgb = half3x3(1.0, 1.0, 1.0,
                            0.956, -0.272, -1.106,
                            0.621, -0.647, 1.703);
    half3 yiq = toYiq * rgb;
    float chroma = length(float2(yiq.y, yiq.z));
    float h = atan(yiq.z, yiq.y) + amount;
    yiq.y = half(chroma * cos(h));
    yiq.z = half(chroma * sin(h));
    return toRgb * yiq;
}

half4 main(float2 coord) {
    float2 center = resolution * 0.5;
    float2 p = coord - center;
    float s = sin(angle);
    float c = cos(angle);
    p = float2(c * p.x - s * p.y, s * p.x + c * p.y) / zoom;
    float2 uv = p / resolution.y;
    p += warp * resolution.y * 0.012 * float2(sin(uv.y * 9.0 + time * 1.7), cos(uv.x * 9.0 + time * 1.3));
    half4 color = prev.eval(center + p);
    return half4(clamp(rotateHue(color.rgb, hue), 0.0, 1.0) * half(decay), 1.0);
}
"""
