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
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
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
import androidx.compose.ui.input.pointer.pointerInput
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Auto-cycle settings for the visualizer presets (from user preferences). */
data class WarpPresetSettings(
    val autoCycle: Boolean = true,
    val intervalSeconds: Int = 30,
    /** Keeps the current preset: no auto-cycle and taps do not change it. */
    val locked: Boolean = false
)

/**
 * MilkDrop-style audio-reactive art: each frame re-draws the previous one zoomed, rotated,
 * warped, hue-shifted and decayed, then draws the current audio on top. The look comes from
 * [WarpPresets], blended over [PRESET_BLEND_SECONDS] when it changes; a tap shows the next preset.
 * API 33+ does the feedback on the GPU with an AGSL shader; older devices (or a GPU setup failure)
 * get a reduced Canvas echo-trail version of the same presets.
 *
 * Renders only while [active]; pass false unless it is visible, playing, motion is FULL and
 * optional work is not deferred. When inactive the last frame stays on screen, still.
 *
 * @param presetId Preset to start from (the last one shown).
 * @param onPresetShown Called with the preset on screen when rendering stops or the visualizer
 *   leaves, so it can be remembered; not called per frame.
 */
@Composable
fun WarpVisualizer(
    spectrum: State<SpectrumFrame>,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    size: Dp = 300.dp,
    presetId: String? = null,
    presetSettings: WarpPresetSettings = WarpPresetSettings(),
    onPresetShown: (String) -> Unit = {}
) {
    var gpuUnavailable by remember { mutableStateOf(false) }
    // One cycler for the visualizer's life, so pausing does not restart the preset sequence. Used by
    // one renderer at a time: the GPU loop's single thread, or the Canvas draw on the main thread.
    val cycler = remember { PresetCycler(warpPresetIndex(presetId)) }
    // Taps are counted on the main thread and applied by whichever renderer is running.
    val taps = remember { AtomicInteger(0) }
    // Written by the renderer, read when reporting the preset shown.
    val shownIndex = remember { AtomicInteger(cycler.index) }
    val latestOnPresetShown by rememberUpdatedState(onPresetShown)
    DisposableEffect(active) {
        onDispose { latestOnPresetShown(WarpPresets[shownIndex.get()].id) }
    }

    Box(
        modifier
            .size(size)
            .clipToBounds()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { taps.incrementAndGet() } }
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !gpuUnavailable) {
            GpuWarp(spectrum, active, accent, cycler, presetSettings, taps, shownIndex, onUnavailable = { gpuUnavailable = true })
        } else {
            CanvasEchoWarp(spectrum, active, accent, cycler, presetSettings, taps, shownIndex)
        }
    }
}

/**
 * Smoothed feedback controls derived from the spectrum and the current preset (or a blend of two).
 * Rates are per 60 Hz frame; the renderers scale them by real frame time. Allocation-free per frame.
 */
internal class WarpParams {
    var zoom = 1.008f
        private set
    var angle = 0.002f
        private set
    var warp = 0f
        private set
    var warpFrequency = 9f
        private set
    var decay = 0.94f
        private set
    var hue = 0.004f
        private set
    var strokeBoost = 0f
        private set
    var waveScale = 0.28f
        private set
    /** Decays from 1 after each beat. */
    var pulse = 0f
        private set
    var time = 0f
        private set
    /** True for the frame on which a new beat with loud bass arrived. */
    var strongBeat = false
        private set
    val levels = BandLevels()
    private var lastSequence = -1L
    private val fromTargets = WarpTargets()
    private val toTargets = WarpTargets()
    private val mixed = WarpTargets()

    fun update(
        frame: SpectrumFrame,
        dtSeconds: Float,
        from: WarpPreset = WarpPresets[0],
        to: WarpPreset = from,
        blend: Float = 1f
    ) {
        levels.set(frame.bands, frame.level)
        strongBeat = false
        if (frame.sequence != lastSequence) {
            lastSequence = frame.sequence
            if (frame.beat) {
                pulse = 1f
                strongBeat = levels.bass >= STRONG_BEAT_BASS
            }
        }
        pulse = (pulse - dtSeconds * 3f).coerceAtLeast(0f)
        if (blend >= 1f || from === to) {
            mixed.compute(to, levels, pulse)
        } else {
            fromTargets.compute(from, levels, pulse)
            toTargets.compute(to, levels, pulse)
            mixed.lerp(fromTargets, toTargets, blend)
        }
        val follow = (dtSeconds * 8f).coerceIn(0f, 1f)
        zoom += (mixed.zoom - zoom) * follow
        angle += (mixed.angle - angle) * follow
        warp += (mixed.warp - warp) * follow
        warpFrequency = mixed.warpFrequency
        decay = mixed.decay
        hue = mixed.hue
        strokeBoost = mixed.strokeBoost
        waveScale = mixed.waveScale
        time += dtSeconds
    }
}

/** Per-60Hz-frame rate [perFrame] applied over [dtSeconds] (multiplicative, e.g. zoom/decay). */
internal fun perFrameFactor(perFrame: Float, dtSeconds: Float): Float = perFrame.pow(dtSeconds * 60f)

private const val RENDER_SCALE = 0.5f

/** Rendering blocks briefly on GPU present, so it gets its own serial lane off the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
private val warpRenderDispatcher = Dispatchers.IO.limitedParallelism(1)

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun GpuWarp(
    spectrum: State<SpectrumFrame>,
    active: Boolean,
    accent: Color,
    cycler: PresetCycler,
    presetSettings: WarpPresetSettings,
    taps: AtomicInteger,
    shownIndex: AtomicInteger,
    onUnavailable: () -> Unit
) {
    // The renderer hands out a few recycled images, so the same instance can come back: never skip a write.
    val latest = remember { mutableStateOf<ImageBitmap?>(null, neverEqualPolicy()) }
    var sizePx by remember { mutableStateOf(IntSize.Zero) }
    val accentArgb by rememberUpdatedState(accent.toArgb())
    val settings by rememberUpdatedState(presetSettings)
    val latestOnUnavailable by rememberUpdatedState(onUnavailable)

    LaunchedEffect(active, sizePx) {
        if (!active || sizePx.width <= 0 || sizePx.height <= 0) return@LaunchedEffect
        val width = (sizePx.width * RENDER_SCALE).toInt().coerceAtLeast(64)
        val height = (sizePx.height * RENDER_SCALE).toInt().coerceAtLeast(64)
        val started = withContext(warpRenderDispatcher) {
            val renderer = GpuWarpRenderer.createOrNull(width, height) ?: return@withContext false
            val params = WarpParams()
            var lastNanos = 0L
            // Taps made while nothing was rendering are not replayed.
            var seenTaps = taps.get()
            try {
                while (currentCoroutineContext().isActive) {
                    // Compose's frame clock is still in this context, so this paces to vsync.
                    val now = withFrameNanos { it }
                    val dt = if (lastNanos == 0L) 1f / 60f else ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                    lastNanos = now
                    val clock = now / 1_000_000_000.0
                    seenTaps = advancePresets(cycler, settings, taps, seenTaps, clock, params.strongBeat)
                    shownIndex.set(cycler.index)
                    val frame = spectrum.value
                    params.update(frame, dt, cycler.from, cycler.to, cycler.blend)
                    renderer.render(params, frame, accentArgb, dt, cycler.from.waveShape, cycler.to.waveShape, cycler.blend)
                        ?.let { latest.value = it }
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

/** Applies settings and pending taps to [cycler], then lets it advance. Returns the taps now handled. */
private fun advancePresets(
    cycler: PresetCycler,
    settings: WarpPresetSettings,
    taps: AtomicInteger,
    seenTaps: Int,
    clockSeconds: Double,
    strongBeat: Boolean
): Int {
    cycler.autoCycle = settings.autoCycle
    cycler.intervalSeconds = settings.intervalSeconds
    cycler.locked = settings.locked
    val tapCount = taps.get()
    val now = clockSeconds.toFloatClock()
    if (tapCount != seenTaps) cycler.next(now)
    cycler.update(now, strongBeat)
    return tapCount
}

/**
 * Seconds on a clock that keeps sub-millisecond precision as a Float: the uptime-based frame time
 * is folded into a window far longer than any preset interval.
 */
private fun Double.toFloatClock(): Float = (this % CLOCK_WINDOW_SECONDS).toFloat()

/**
 * A fold back to 0 makes the cycler see time going backwards once per window (about 11.5 days of
 * uptime); it then waits one interval longer before the next auto switch. Harmless.
 */
private const val CLOCK_WINDOW_SECONDS = 1_000_000.0

/**
 * Wrappers for one ImageReader buffer. Created the first time a buffer is seen and reused every
 * time the reader hands it out again, instead of wrapping it anew each frame.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class BufferTarget(val bitmap: Bitmap, val shader: BitmapShader, val image: ImageBitmap)

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
    private var newestTarget: BufferTarget? = null

    /**
     * Keyed by HardwareBuffer id (API 34+). The reader cycles through [READER_IMAGES] buffers, so
     * this normally holds that many; it is bounded in case the reader ever reallocates.
     */
    private val targets = BoundedCache<Long, BufferTarget>(READER_IMAGES + 1)

    init {
        renderer.setContentRoot(node)
    }

    fun render(
        params: WarpParams,
        frame: SpectrumFrame,
        color: Int,
        dt: Float,
        fromShape: WaveShape,
        toShape: WaveShape,
        blend: Float
    ): ImageBitmap? {
        val canvas = node.beginRecording(width, height)
        try {
            val input = newestTarget
            if (input == null) {
                canvas.drawColor(android.graphics.Color.BLACK)
            } else {
                shader.setInputShader("prev", input.shader)
                shader.setFloatUniform("resolution", width.toFloat(), height.toFloat())
                shader.setFloatUniform("zoom", perFrameFactor(params.zoom, dt))
                shader.setFloatUniform("angle", params.angle * dt * 60f)
                shader.setFloatUniform("warp", params.warp)
                shader.setFloatUniform("warpFreq", params.warpFrequency)
                shader.setFloatUniform("time", params.time)
                shader.setFloatUniform("decay", perFrameFactor(params.decay, dt))
                shader.setFloatUniform("hue", params.hue * dt * 60f)
                canvas.drawPaint(feedbackPaint)
            }
            val base = min(width, height) * params.waveScale
            wavePaint.color = color
            wavePaint.strokeWidth = min(width, height) * 0.006f * (1f + params.strokeBoost)
            if (fromShape == toShape || blend >= 1f) {
                drawShape(canvas, if (blend >= 1f) toShape else fromShape, frame, base, 255)
            } else {
                drawShape(canvas, fromShape, frame, base, ((1f - blend) * 255).toInt())
                drawShape(canvas, toShape, frame, base, (blend * 255).toInt())
            }
        } finally {
            node.endRecording()
        }
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()

        val image = reader.acquireLatestImage() ?: return newestTarget?.image
        val target = targetFor(image)
        if (target == null) {
            image.close()
            return newestTarget?.image
        }
        previous?.close()
        previous = newest
        newest = image
        newestTarget = target
        return target.image
    }

    private fun drawShape(canvas: android.graphics.Canvas, shape: WaveShape, frame: SpectrumFrame, base: Float, alpha: Int) {
        if (alpha <= 2) return
        path.rewind()
        traceWave(
            shape, frame.waveform, frame.bands, frame.level, width / 2f, height / 2f, base,
            moveTo = { x, y -> path.moveTo(x, y) },
            lineTo = { x, y -> path.lineTo(x, y) },
            close = { path.close() }
        )
        wavePaint.alpha = alpha
        canvas.drawPath(path, wavePaint)
    }

    /** Cached wrappers for [image]'s buffer, or new ones; null if the buffer cannot be wrapped. */
    private fun targetFor(image: Image): BufferTarget? {
        val buffer = image.hardwareBuffer ?: return null
        try {
            val id = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) buffer.id else null
            id?.let { targets[it] }?.let { return it }
            // The wrapping Bitmap keeps its own reference to the buffer, so the Java handle can close.
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB)) ?: return null
            val target = BufferTarget(
                bitmap = bitmap,
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP),
                image = bitmap.asImageBitmap()
            )
            // API 33 has no stable buffer id: wrappers are then made per frame, as before.
            if (id != null) targets[id] = target
            return target
        } finally {
            buffer.close()
        }
    }

    fun release() {
        renderer.destroy()
        node.discardDisplayList()
        newest?.close()
        previous?.close()
        reader.close()
        // Bitmaps are not recycled: the UI may still be drawing the last frame. Dropping the
        // references lets them go with that frame.
        targets.clear()
        newestTarget = null
    }

    companion object {
        private const val READER_IMAGES = 3

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
                    READER_IMAGES,
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

/** Small least-recently-used map; the eldest entry is dropped beyond [capacity]. Not thread-safe. */
internal class BoundedCache<K, V>(private val capacity: Int) {
    private val map = object : LinkedHashMap<K, V>(capacity + 1, 1f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }

    operator fun get(key: K): V? = map[key]
    operator fun set(key: K, value: V) {
        map[key] = value
    }
    val size: Int get() = map.size
    fun clear() = map.clear()
}

/**
 * Traces [shape] for the current audio around (cx, cy). Inline, so the callbacks do not allocate.
 * RING: closed polar waveform whose size follows loudness. SPOKES: one radial line per band.
 * LINE: horizontal waveform across the middle.
 */
internal inline fun traceWave(
    shape: WaveShape,
    wave: FloatArray,
    bands: FloatArray,
    level: Float,
    cx: Float,
    cy: Float,
    base: Float,
    moveTo: (Float, Float) -> Unit,
    lineTo: (Float, Float) -> Unit,
    close: () -> Unit
) {
    when (shape) {
        WaveShape.RING -> {
            if (wave.isEmpty()) return
            val radius = base * (1f + level * 0.6f)
            for (i in wave.indices) {
                val theta = (2.0 * PI * i / wave.size).toFloat()
                val r = radius + wave[i] * base * 0.35f
                val x = cx + r * cos(theta)
                val y = cy + r * sin(theta)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        WaveShape.SPOKES -> {
            if (bands.isEmpty()) return
            val inner = base * 0.45f
            for (i in bands.indices) {
                // Mirrored, so low bands sit at the top and bottom.
                for (side in 0..1) {
                    val theta = (PI * (i + 0.5) / bands.size + side * PI - PI / 2).toFloat()
                    val outer = inner + base * (0.15f + bands[i] * 1.1f)
                    moveTo(cx + inner * cos(theta), cy + inner * sin(theta))
                    lineTo(cx + outer * cos(theta), cy + outer * sin(theta))
                }
            }
        }
        WaveShape.LINE -> {
            if (wave.isEmpty()) return
            val half = base * 1.6f
            for (i in wave.indices) {
                val x = cx - half + 2f * half * i / (wave.size - 1).coerceAtLeast(1)
                val y = cy + wave[i] * base * (0.8f + level)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
    }
}

private const val ECHO_COUNT = 10

/** Last few waveforms and bands, so the Canvas renderer can fake feedback with scaled, fading echoes. */
private class EchoTrail {
    val rings = Array(ECHO_COUNT) { FloatArray(AudioSpectrum.WAVEFORM_POINTS) }
    val bands = Array(ECHO_COUNT) { FloatArray(AudioSpectrum.BAND_COUNT) }
    val levels = FloatArray(ECHO_COUNT)
    var head = 0
    var count = 0
    var spin = 0f
    private var lastSequence = -1L

    /** Records a new frame; returns false when [frame] was already recorded (or is silence). */
    fun push(frame: SpectrumFrame, spinStep: Float): Boolean {
        if (frame.sequence == lastSequence || frame.sequence == 0L) return false
        lastSequence = frame.sequence
        frame.waveform.copyInto(rings[head])
        frame.bands.copyInto(bands[head], endIndex = min(frame.bands.size, AudioSpectrum.BAND_COUNT))
        levels[head] = frame.level
        head = (head + 1) % ECHO_COUNT
        count = min(count + 1, ECHO_COUNT)
        spin += spinStep
        return true
    }
}

/**
 * Reduced presets for API 29-32: no feedback buffer, so a preset sets how fast the echoes spin
 * (rotation), how far apart they scale (zoom), how quickly they fade (decay), how far their hue
 * drifts (hue speed) and the wave shape. Blends like the GPU version.
 */
@Composable
private fun CanvasEchoWarp(
    spectrum: State<SpectrumFrame>,
    active: Boolean,
    accent: Color,
    cycler: PresetCycler,
    presetSettings: WarpPresetSettings,
    taps: AtomicInteger,
    shownIndex: AtomicInteger
) {
    val trail = remember { EchoTrail() }
    val params = remember { WarpParams() }
    val path = remember { Path() }
    val accentHsv = remember(accent) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(accent.toArgb(), it) }
    }
    val tintHsv = remember { FloatArray(3) }
    val settings by rememberUpdatedState(presetSettings)
    // Draw-phase bookkeeping; plain holders, not state, so updating them does not invalidate.
    val clock = remember { LongArray(1) }
    val seenTaps = remember { intArrayOf(taps.get()) }

    Canvas(Modifier.fillMaxSize()) {
        // Reading the frame while drawing keeps updates to a redraw of this square only.
        if (active) {
            val frame = spectrum.value
            val nowMs = SystemClock.uptimeMillis()
            val dt = if (clock[0] == 0L) 1f / 60f else ((nowMs - clock[0]) / 1000f).coerceIn(0f, 0.1f)
            clock[0] = nowMs
            seenTaps[0] = advancePresets(cycler, settings, taps, seenTaps[0], nowMs / 1000.0, params.strongBeat)
            shownIndex.set(cycler.index)
            params.update(frame, dt, cycler.from, cycler.to, cycler.blend)
            trail.push(frame, spinStep = 0.03f + abs(params.angle) * 4f)
        }
        val center = Offset(size.width / 2f, size.height / 2f)
        val base = min(size.width, size.height) * params.waveScale
        // zoom 1.008 (NEBULA at rest) gives the original 0.09 step between echoes.
        val scaleStep = ((params.zoom - 1f) / 0.008f * 0.09f).coerceIn(0.02f, 0.2f)
        val fadePower = (1.6f + (0.94f - params.decay) * 20f).coerceIn(0.6f, 3f)
        val hueStep = (14f * params.hue / 0.004f).coerceIn(4f, 40f)
        val fromShape = cycler.from.waveShape
        val toShape = cycler.to.waveShape
        val blend = cycler.blend
        val strokePx = 2.dp.toPx()
        for (age in trail.count - 1 downTo 0) {
            val index = (trail.head - 1 - age + ECHO_COUNT) % ECHO_COUNT
            val fade = (1f - age.toFloat() / ECHO_COUNT).pow(fadePower)
            tintHsv[0] = (accentHsv[0] + age * hueStep) % 360f
            tintHsv[1] = accentHsv[1]
            tintHsv[2] = accentHsv[2]
            val tint = Color(android.graphics.Color.HSVToColor(tintHsv))
            val stroke = Stroke(width = strokePx * (1f + if (age == 0) params.strokeBoost else 0f), join = StrokeJoin.Round)
            for (pass in 0..1) {
                val shape = if (pass == 0) fromShape else toShape
                val weight = when {
                    fromShape == toShape || blend >= 1f -> if (pass == 1) 1f else 0f
                    pass == 0 -> 1f - blend
                    else -> blend
                }
                if (weight <= 0.01f) continue
                path.rewind()
                traceWave(
                    shape, trail.rings[index], trail.bands[index], trail.levels[index], center.x, center.y, base,
                    moveTo = { x, y -> path.moveTo(x, y) },
                    lineTo = { x, y -> path.lineTo(x, y) },
                    close = { path.close() }
                )
                rotate(degrees = (trail.spin - age * 0.12f) * 57.29578f, pivot = center) {
                    scale(scale = 1f + age * scaleStep * (1f + params.pulse), pivot = center) {
                        drawPath(path, tint.copy(alpha = fade * weight), style = stroke)
                    }
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
uniform float warpFreq;
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
    p += warp * resolution.y * 0.012 * float2(sin(uv.y * warpFreq + time * 1.7), cos(uv.x * warpFreq + time * 1.3));
    half4 color = prev.eval(center + p);
    return half4(clamp(rotateHue(color.rgb, hue), 0.0, 1.0) * half(decay), 1.0);
}
"""
