package com.necroware.terminusplayer.ui.components.visualizer

import androidx.compose.runtime.Immutable
import kotlin.math.min

/** Which part of the spectrum drives a preset parameter. */
enum class WarpBand { BASS, MID, TREBLE, LEVEL }

/** Shape the current audio is drawn as, on top of the feedback. */
enum class WaveShape { RING, SPOKES, LINE }

/**
 * One look of the warp visualizer: every value the feedback shader and the wave overlay read.
 * Rates are per 60 Hz frame; the renderers scale them by real frame time.
 *
 * A driven parameter is `base + drive * band`, with `band` the smoothed 0..1 level of the mapped band.
 */
@Immutable
data class WarpPreset(
    val id: String,
    val label: String,
    /** Feedback zoom per frame (>1 pulls the image toward the viewer). */
    val zoomBase: Float,
    val zoomDrive: Float,
    val zoomBand: WarpBand,
    /** Feedback rotation per frame, radians. */
    val rotationBase: Float,
    val rotationDrive: Float,
    val rotationBand: WarpBand,
    /** Strength and spatial frequency of the sinusoidal warp field. */
    val warpAmplitude: Float,
    val warpFrequency: Float,
    val warpBand: WarpBand,
    /** Brightness kept per frame, 0..1. Lower fades trails faster. */
    val decay: Float,
    /** Hue rotation per frame, radians. */
    val hueSpeed: Float,
    /** How strongly a beat kicks zoom, hue and the wave stroke, 0..1+. */
    val beatResponse: Float,
    val waveShape: WaveShape,
    /** Wave size as a share of the shorter side. */
    val waveScale: Float
)

/** Built-in presets. The first is the original (pre-preset) look. */
val WarpPresets: List<WarpPreset> = listOf(
    WarpPreset(
        id = "NEBULA", label = "NEBULA",
        zoomBase = 1.006f, zoomDrive = 0.035f, zoomBand = WarpBand.BASS,
        rotationBase = -0.0028f, rotationDrive = 0.012f, rotationBand = WarpBand.MID,
        warpAmplitude = 0.9f, warpFrequency = 9f, warpBand = WarpBand.TREBLE,
        decay = 0.94f, hueSpeed = 0.004f, beatResponse = 1f,
        waveShape = WaveShape.RING, waveScale = 0.28f
    ),
    WarpPreset(
        id = "TUNNEL", label = "TUNNEL",
        zoomBase = 1.02f, zoomDrive = 0.05f, zoomBand = WarpBand.BASS,
        rotationBase = 0.001f, rotationDrive = 0.004f, rotationBand = WarpBand.LEVEL,
        warpAmplitude = 0.2f, warpFrequency = 5f, warpBand = WarpBand.MID,
        decay = 0.965f, hueSpeed = 0.002f, beatResponse = 1.3f,
        waveShape = WaveShape.RING, waveScale = 0.18f
    ),
    WarpPreset(
        id = "VORTEX", label = "VORTEX",
        zoomBase = 1.004f, zoomDrive = 0.015f, zoomBand = WarpBand.LEVEL,
        rotationBase = 0.012f, rotationDrive = 0.03f, rotationBand = WarpBand.MID,
        warpAmplitude = 0.35f, warpFrequency = 7f, warpBand = WarpBand.TREBLE,
        decay = 0.95f, hueSpeed = 0.006f, beatResponse = 0.8f,
        waveShape = WaveShape.SPOKES, waveScale = 0.3f
    ),
    WarpPreset(
        id = "LIQUID", label = "LIQUID",
        zoomBase = 0.998f, zoomDrive = 0.012f, zoomBand = WarpBand.BASS,
        rotationBase = 0.0005f, rotationDrive = 0.003f, rotationBand = WarpBand.LEVEL,
        warpAmplitude = 2.2f, warpFrequency = 4f, warpBand = WarpBand.TREBLE,
        decay = 0.955f, hueSpeed = 0.003f, beatResponse = 0.6f,
        waveShape = WaveShape.LINE, waveScale = 0.32f
    ),
    WarpPreset(
        id = "PULSE", label = "PULSE",
        zoomBase = 1.0f, zoomDrive = 0.02f, zoomBand = WarpBand.BASS,
        rotationBase = 0f, rotationDrive = -0.01f, rotationBand = WarpBand.BASS,
        warpAmplitude = 0.5f, warpFrequency = 12f, warpBand = WarpBand.BASS,
        decay = 0.9f, hueSpeed = 0.01f, beatResponse = 2f,
        waveShape = WaveShape.SPOKES, waveScale = 0.24f
    ),
    WarpPreset(
        id = "DRIFT", label = "DRIFT",
        zoomBase = 1.003f, zoomDrive = 0.008f, zoomBand = WarpBand.LEVEL,
        rotationBase = -0.003f, rotationDrive = 0.004f, rotationBand = WarpBand.TREBLE,
        warpAmplitude = 0.6f, warpFrequency = 3f, warpBand = WarpBand.MID,
        decay = 0.975f, hueSpeed = 0.0015f, beatResponse = 0.3f,
        waveShape = WaveShape.LINE, waveScale = 0.22f
    )
)

/** Index of the preset with [id], or 0 when it is unknown (e.g. a preset removed in an update). */
fun warpPresetIndex(id: String?): Int = WarpPresets.indexOfFirst { it.id == id }.coerceAtLeast(0)

/** Auto-cycle choices offered in Settings, in seconds. */
val WarpCycleIntervals: List<Int> = listOf(15, 30, 60)

/** Smoothed band levels a preset maps from, 0..1. */
internal class BandLevels {
    var bass = 0f
    var mid = 0f
    var treble = 0f
    var level = 0f

    fun of(band: WarpBand): Float = when (band) {
        WarpBand.BASS -> bass
        WarpBand.MID -> mid
        WarpBand.TREBLE -> treble
        WarpBand.LEVEL -> level
    }

    fun set(bands: FloatArray, rms: Float) {
        bass = average(bands, 0, 3)
        mid = average(bands, 4, 10)
        treble = average(bands, 10, bands.size)
        level = rms.coerceIn(0f, 1f)
    }

    private fun average(values: FloatArray, from: Int, to: Int): Float {
        val end = min(to, values.size)
        if (end <= from) return 0f
        var sum = 0f
        for (i in from until end) sum += values[i]
        return sum / (end - from)
    }
}

/** Shader and overlay targets for one frame, before smoothing. Reused; never allocated per frame. */
internal class WarpTargets {
    var zoom = 1f
    var angle = 0f
    var warp = 0f
    var warpFrequency = 9f
    var decay = 0.94f
    var hue = 0f
    var strokeBoost = 0f
    var waveScale = 0.28f

    fun compute(p: WarpPreset, levels: BandLevels, pulse: Float) {
        zoom = p.zoomBase + p.zoomDrive * levels.of(p.zoomBand) + pulse * 0.03f * p.beatResponse
        angle = p.rotationBase + p.rotationDrive * levels.of(p.rotationBand)
        warp = p.warpAmplitude * levels.of(p.warpBand)
        warpFrequency = p.warpFrequency
        decay = p.decay
        hue = p.hueSpeed + pulse * 0.08f * p.beatResponse
        strokeBoost = pulse * 1.5f * p.beatResponse
        waveScale = p.waveScale
    }

    /** this = a + (b - a) * t, for every field. */
    fun lerp(a: WarpTargets, b: WarpTargets, t: Float) {
        zoom = a.zoom + (b.zoom - a.zoom) * t
        angle = a.angle + (b.angle - a.angle) * t
        warp = a.warp + (b.warp - a.warp) * t
        warpFrequency = a.warpFrequency + (b.warpFrequency - a.warpFrequency) * t
        decay = a.decay + (b.decay - a.decay) * t
        hue = a.hue + (b.hue - a.hue) * t
        strokeBoost = a.strokeBoost + (b.strokeBoost - a.strokeBoost) * t
        waveScale = a.waveScale + (b.waveScale - a.waveScale) * t
    }
}

/**
 * A preset frozen part-way from [a] to [b]: every number blended by [t]. Bands and wave shape
 * cannot be mixed, so they come from the nearer preset; used only when a switch interrupts a blend.
 */
internal fun lerpPreset(a: WarpPreset, b: WarpPreset, t: Float): WarpPreset {
    fun f(x: Float, y: Float) = x + (y - x) * t
    val near = if (t < 0.5f) a else b
    return near.copy(
        zoomBase = f(a.zoomBase, b.zoomBase), zoomDrive = f(a.zoomDrive, b.zoomDrive),
        rotationBase = f(a.rotationBase, b.rotationBase), rotationDrive = f(a.rotationDrive, b.rotationDrive),
        warpAmplitude = f(a.warpAmplitude, b.warpAmplitude), warpFrequency = f(a.warpFrequency, b.warpFrequency),
        decay = f(a.decay, b.decay), hueSpeed = f(a.hueSpeed, b.hueSpeed),
        beatResponse = f(a.beatResponse, b.beatResponse), waveScale = f(a.waveScale, b.waveScale)
    )
}

/** Ease-in-out cubic on 0..1. */
internal fun easeInOut(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
}

/** Length of a blend between two presets. */
internal const val PRESET_BLEND_SECONDS = 3f

/** After the interval, wait at most this long for a strong beat before switching anyway. */
internal const val BEAT_WAIT_SECONDS = 4f

/** A beat counts as strong when the bass is at least this loud. */
internal const val STRONG_BEAT_BASS = 0.55f

/**
 * Chooses which preset shows and how far a blend has got. Pure state machine on the
 * visualizer's own clock (seconds), so it can be tested without rendering.
 */
internal class PresetCycler(startIndex: Int, private val presets: List<WarpPreset> = WarpPresets) {
    /** Where the current blend starts (a preset, or a frozen mid-blend mix). */
    var from: WarpPreset = presets[startIndex.coerceIn(0, presets.lastIndex)]
        private set
    /** Index of the preset being shown or blended to. */
    var index: Int = startIndex.coerceIn(0, presets.lastIndex)
        private set
    val to: WarpPreset get() = presets[index]

    private var blendStart = Float.NaN
    private var lastSwitch = 0f

    /** Eased 0..1 progress from [from] to [to]; 1 when not blending. */
    var blend: Float = 1f
        private set

    var autoCycle = true
    var intervalSeconds = 30
    var locked = false

    fun update(now: Float, strongBeat: Boolean) {
        if (!blendStart.isNaN()) {
            val raw = (now - blendStart) / PRESET_BLEND_SECONDS
            if (raw >= 1f) {
                blendStart = Float.NaN
                from = to
                blend = 1f
            } else {
                blend = easeInOut(raw)
            }
            return
        }
        if (!autoCycle || locked) return
        val elapsed = now - lastSwitch
        if (elapsed >= intervalSeconds && (strongBeat || elapsed >= intervalSeconds + BEAT_WAIT_SECONDS)) {
            switchTo((index + 1) % presets.size, now)
        }
    }

    /** Tap: next preset, unless locked. Returns whether it switched. */
    fun next(now: Float): Boolean {
        if (locked) return false
        switchTo((index + 1) % presets.size, now)
        return true
    }

    private fun switchTo(newIndex: Int, now: Float) {
        // Mid-blend: continue from what is on screen instead of jumping back.
        from = if (blendStart.isNaN()) to else lerpPreset(from, to, blend)
        index = newIndex
        blendStart = now
        blend = 0f
        lastSwitch = now
    }
}
