package com.necroware.terminusplayer.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One analysed slice of the playing audio, for visualizers only. */
class SpectrumFrame(
    /** [AudioSpectrum.BAND_COUNT] log-spaced band levels, each 0..1. */
    val bands: FloatArray,
    /** RMS level of the analysed window, 0..1. */
    val level: Float,
    /** True on a detected bass onset. */
    val beat: Boolean,
    /** Increases by one per published frame; 0 for silence. */
    val sequence: Long
) {
    override fun equals(other: Any?): Boolean =
        other is SpectrumFrame && other.sequence == sequence && (sequence != 0L || other === this)

    override fun hashCode(): Int = sequence.hashCode()

    companion object {
        val Silent = SpectrumFrame(FloatArray(AudioSpectrum.BAND_COUNT), level = 0f, beat = false, sequence = 0L)
    }
}

/**
 * Taps decoded PCM from the player's audio sink (via [TeeAudioProcessor]) and publishes a
 * band spectrum. It never changes the audio: the tee passes samples through untouched.
 *
 * Work only happens while [frames] has a collector and playback is running. The audio-thread
 * callback then copies into a preallocated ring buffer (no allocation). Analysis runs on
 * [Dispatchers.Default] at most once per [frameIntervalMs] and skips frames with no new audio.
 * Collectors should subscribe only while a visualizer is visible and motion is FULL.
 */
@Singleton
@OptIn(UnstableApi::class)
class AudioSpectrum internal constructor(
    private val scope: CoroutineScope,
    private val frameIntervalMs: Long
) : TeeAudioProcessor.AudioBufferSink {

    @Inject
    constructor() : this(CoroutineScope(SupervisorJob() + Dispatchers.Default), FRAME_INTERVAL_MS)

    private val _frames = MutableStateFlow(SpectrumFrame.Silent)
    val frames: StateFlow<SpectrumFrame> = _frames.asStateFlow()

    private val playing = MutableStateFlow(false)

    // Written on the playback thread, read by the analysis loop.
    @Volatile private var capturing = false
    @Volatile private var sampleRate = 0
    @Volatile private var channelCount = 0
    @Volatile private var encoding = C.ENCODING_INVALID
    @Volatile private var writtenSamples = 0L
    private val ring = FloatArray(RING_SIZE)

    init {
        scope.launch {
            combine(_frames.subscriptionCount.map { it > 0 }, playing) { subscribed, isPlaying -> subscribed && isPlaying }
                .distinctUntilChanged()
                .collectLatest { active ->
                    capturing = active
                    if (active) analyzeWhileActive() else _frames.value = SpectrumFrame.Silent
                }
        }
    }

    /** Called by the playback service on every play/pause change. */
    fun setPlaying(isPlaying: Boolean) {
        playing.value = isPlaying
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this.sampleRate = sampleRateHz
        this.channelCount = channelCount
        this.encoding = encoding
    }

    /** Audio thread. Mono downmix into [ring]; must not allocate. */
    override fun handleBuffer(buffer: ByteBuffer) {
        if (!capturing) return
        val channels = channelCount
        if (channels <= 0) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val frameBytes = bytesPerSample * channels
        var index = buffer.position()
        val end = buffer.limit()
        var written = writtenSamples
        while (index + frameBytes <= end) {
            var sum = 0f
            for (channel in 0 until channels) {
                val at = index + channel * bytesPerSample
                // Android PCM is little-endian; read bytes so the buffer's order flag is irrelevant.
                sum += if (bytesPerSample == 2) {
                    ((buffer.get(at + 1).toInt() shl 8) or (buffer.get(at).toInt() and 0xFF)) / 32768f
                } else {
                    java.lang.Float.intBitsToFloat(
                        (buffer.get(at).toInt() and 0xFF) or
                            ((buffer.get(at + 1).toInt() and 0xFF) shl 8) or
                            ((buffer.get(at + 2).toInt() and 0xFF) shl 16) or
                            (buffer.get(at + 3).toInt() shl 24)
                    )
                }
            }
            ring[(written and RING_MASK).toInt()] = sum / channels
            written++
            index += frameBytes
        }
        writtenSamples = written
    }

    private suspend fun analyzeWhileActive() {
        val analyzer = SpectrumAnalyzer(FFT_SIZE, BAND_COUNT)
        val beats = BeatDetector()
        val window = FloatArray(FFT_SIZE)
        var lastWritten = writtenSamples
        var sequence = 0L
        while (currentCoroutineContext().isActive) {
            delay(frameIntervalMs)
            val written = writtenSamples
            val rate = sampleRate
            if (written == lastWritten || rate <= 0) continue
            lastWritten = written
            // Torn reads while the audio thread writes only blur a visual; they are tolerated.
            val start = written - FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                val sample = start + i
                window[i] = if (sample < 0) 0f else ring[(sample and RING_MASK).toInt()]
            }
            val bands = FloatArray(BAND_COUNT)
            val level = analyzer.analyze(window, rate, bands)
            _frames.value = SpectrumFrame(bands, level, beats.update(bands), ++sequence)
        }
    }

    companion object {
        const val BAND_COUNT = 16
        /** About 30 frames per second, enough for smooth visuals. */
        const val FRAME_INTERVAL_MS = 33L
        internal const val FFT_SIZE = 1024
        private const val RING_SIZE = 4096
        private const val RING_MASK = (RING_SIZE - 1).toLong()
    }
}

/**
 * FFT bin boundaries for [bandCount] log-spaced bands between [minHz] and [maxHz]
 * (capped below Nyquist). Band i covers bins [edges[i], edges[i + 1]); every band has at
 * least one bin.
 */
internal fun logBandEdges(bandCount: Int, sampleRate: Int, fftSize: Int, minHz: Float = 40f, maxHz: Float = 16_000f): IntArray {
    val binCount = fftSize / 2
    val binHz = sampleRate.toFloat() / fftSize
    val top = min(maxHz, sampleRate * 0.5f * 0.95f)
    val bottom = min(minHz, top / 2f)
    val logBottom = ln(bottom)
    val logStep = (ln(top) - logBottom) / bandCount
    val edges = IntArray(bandCount + 1)
    for (i in 0..bandCount) {
        val hz = kotlin.math.exp(logBottom + logStep * i)
        edges[i] = (hz / binHz).toInt().coerceIn(1, binCount)
    }
    for (i in 1..bandCount) {
        edges[i] = max(edges[i], edges[i - 1] + 1).coerceAtMost(binCount)
    }
    return edges
}

/** Windowed FFT into band levels. Owns its buffers; one instance per analysis thread. */
internal class SpectrumAnalyzer(private val fftSize: Int, private val bandCount: Int) {
    private val hann = FloatArray(fftSize) { i -> (0.5f - 0.5f * cos(2.0 * PI * i / (fftSize - 1)).toFloat()) }
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val fft = Fft(fftSize)
    private var edgesForRate = 0
    private var edges = IntArray(0)

    /** Fills [outBands] with 0..1 levels and returns the RMS level of [samples]. */
    fun analyze(samples: FloatArray, sampleRate: Int, outBands: FloatArray): Float {
        if (sampleRate != edgesForRate) {
            edges = logBandEdges(bandCount, sampleRate, fftSize)
            edgesForRate = sampleRate
        }
        var sumSquares = 0f
        for (i in 0 until fftSize) {
            val s = samples[i]
            sumSquares += s * s
            re[i] = s * hann[i]
            im[i] = 0f
        }
        fft.transform(re, im)
        // A full-scale sine peaks near fftSize / 4 after the Hann window.
        val fullScale = fftSize / 4f
        for (band in 0 until bandCount) {
            val from = edges[band]
            val to = edges[band + 1]
            var sum = 0f
            for (bin in from until to) sum += sqrt(re[bin] * re[bin] + im[bin] * im[bin])
            val magnitude = sum / (to - from) / fullScale
            outBands[band] = ((20f * log10(magnitude + 1e-9f) + DYNAMIC_RANGE_DB) / DYNAMIC_RANGE_DB).coerceIn(0f, 1f)
        }
        return sqrt(sumSquares / fftSize).coerceIn(0f, 1f)
    }

    private companion object {
        const val DYNAMIC_RANGE_DB = 60f
    }
}

/** In-place iterative radix-2 FFT with precomputed tables. [size] must be a power of two. */
internal class Fft(private val size: Int) {
    private val cosTable = FloatArray(size / 2) { cos(2.0 * PI * it / size).toFloat() }
    private val sinTable = FloatArray(size / 2) { sin(2.0 * PI * it / size).toFloat() }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }

    init {
        require(size >= 2 && size and (size - 1) == 0) { "FFT size must be a power of two" }
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += length
            }
            length *= 2
        }
    }
}

/** Flags bass onsets: low-band energy jumping well above its recent average, with a cooldown. */
internal class BeatDetector(
    private val threshold: Float = 1.35f,
    private val minimumEnergy: Float = 0.15f,
    private val cooldownFrames: Int = 8,
    private val averaging: Float = 0.05f
) {
    private var average = 0f
    private var framesSinceBeat = Int.MAX_VALUE / 2

    fun update(bands: FloatArray): Boolean {
        val lowBands = min(3, bands.size)
        var energy = 0f
        for (i in 0 until lowBands) energy += bands[i]
        energy /= lowBands
        val isBeat = framesSinceBeat >= cooldownFrames && energy >= minimumEnergy && energy > average * threshold
        average += (energy - average) * averaging
        framesSinceBeat = if (isBeat) 0 else framesSinceBeat + 1
        return isBeat
    }
}
