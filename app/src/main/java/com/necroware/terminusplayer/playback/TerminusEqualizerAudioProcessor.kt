package com.necroware.terminusplayer.playback

import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.necroware.terminusplayer.data.prefs.PreampMode
import java.nio.ByteBuffer
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.tanh

/**
 * Immutable configuration snapshot for [TerminusEqualizerAudioProcessor].
 */
class EqualizerParameters(
    val enabled: Boolean = false,
    val gainsDb: List<Float> = List(5) { 0f },
    val preampMode: PreampMode = PreampMode.AUTO,
    val manualPreampDb: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EqualizerParameters) return false
        return enabled == other.enabled &&
            gainsDb == other.gainsDb &&
            preampMode == other.preampMode &&
            manualPreampDb == other.manualPreampDb
    }

    override fun hashCode(): Int {
        var result = enabled.hashCode()
        result = 31 * result + gainsDb.hashCode()
        result = 31 * result + preampMode.hashCode()
        result = 31 * result + manualPreampDb.hashCode()
        return result
    }
}

/**
 * High-performance 5-band peaking DSP equalizer operating in Direct Form II Transposed.
 *
 * Implements Robert Bristow-Johnson's Audio EQ Cookbook peaking bell filters at:
 * [60, 230, 910, 3600, 14000] Hz with Q [0.8, 1.0, 1.0, 1.0, 0.8].
 * All filter coefficients and state delay registers operate in 64-bit Double precision.
 */
@OptIn(UnstableApi::class)
class TerminusEqualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES = doubleArrayOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
        val BAND_QS = doubleArrayOf(0.8, 1.0, 1.0, 1.0, 0.8)
        const val BAND_COUNT = 5
        const val BLOCK_SIZE = 32
        const val SMOOTHING_TIME_CONSTANT = 0.025 // 25 ms
        const val SNAP_THRESHOLD_DB = 0.005
        const val FLAT_SKIP_STATE_EPSILON = 1e-7
        const val WAVESHAPER_THRESHOLD_DB = -0.01
        const val NYQUIST_GUARD_RATIO = 0.40

        /**
         * Extra cut on top of full compensation whenever any band is boosted. A single boosted band
         * driven by a full-scale sine at its centre then peaks at 10^(-0.5/20) = 0.944, below
         * [LIMITER_KNEE], so the limiter never touches it.
         */
        const val AUTO_PREAMP_MARGIN_DB = 0.5

        /** The safety limiter is transparent below this level and rounds off to a ceiling of 1.0 above it. */
        const val LIMITER_KNEE = 0.95
        private const val LIMITER_RANGE = 1.0 - LIMITER_KNEE

        private val FLAT_GAINS = List(BAND_COUNT) { 0f }

        /**
         * Auto pre-gain: fully compensates the largest boost, plus [AUTO_PREAMP_MARGIN_DB].
         * Cuts alone need no compensation, so flat or cut-only settings return exactly 0.0 and keep
         * the bit-identical flat bypass reachable.
         */
        fun autoPreGainDb(enabled: Boolean, gainsDb: List<Float>): Double {
            if (!enabled) return 0.0
            var maxBoost = 0.0
            for (k in 0 until BAND_COUNT) {
                val g = gainsDb.getOrElse(k) { 0f }.toDouble()
                if (g > maxBoost) maxBoost = g
            }
            return if (maxBoost > 0.0) -(maxBoost + AUTO_PREAMP_MARGIN_DB) else 0.0
        }

        fun targetPreGainDb(p: EqualizerParameters): Double {
            if (!p.enabled) return 0.0
            return if (p.preampMode == PreampMode.AUTO) {
                autoPreGainDb(enabled = true, gainsDb = p.gainsDb)
            } else {
                p.manualPreampDb.toDouble()
            }
        }
    }

    @Volatile
    private var parameters = EqualizerParameters()

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID

    // Rate-dependent cached trigonometric constants
    private val isBypassed = BooleanArray(BAND_COUNT)
    private val sinW0 = DoubleArray(BAND_COUNT)
    private val cosW0 = DoubleArray(BAND_COUNT)
    private val alpha = DoubleArray(BAND_COUNT)

    // Biquad normalized coefficients: b0, b1, b2, a1, a2 (a0 is normalized to 1.0)
    private val normB0 = DoubleArray(BAND_COUNT) { 1.0 }
    private val normB1 = DoubleArray(BAND_COUNT) { 0.0 }
    private val normB2 = DoubleArray(BAND_COUNT) { 0.0 }
    private val normA1 = DoubleArray(BAND_COUNT) { 0.0 }
    private val normA2 = DoubleArray(BAND_COUNT) { 0.0 }

    // Direct Form II Transposed state delay registers: z1[channel][band], z2[channel][band]
    private var z1 = Array(1) { DoubleArray(BAND_COUNT) }
    private var z2 = Array(1) { DoubleArray(BAND_COUNT) }

    // Active smoothed gains and pre-gain
    private val activeGainsDb = DoubleArray(BAND_COUNT)
    private var activePreGainDb = 0.0

    // Linear pre-gain values for per-sample interpolation across 32-sample blocks
    private var linPreStart = 1.0
    private var linPreEnd = 1.0

    // Block smoothing factor
    private var alphaBlock = 0.0
    private var sampleInBlock = 0

    /**
     * Samples the safety limiter has reshaped since construction. Written only by the audio thread and
     * only on the rare samples above [LIMITER_KNEE]; tests read it to prove the limiter stayed idle.
     */
    @VisibleForTesting
    internal var limiterEngagedSamples = 0L
        private set

    /**
     * Updates equalizer parameters. Thread-safe: writes an immutable reference read
     * atomically by the audio thread per queueInput call.
     */
    fun setParameters(p: EqualizerParameters) {
        parameters = p
    }

    override fun isActive(): Boolean {
        super.isActive()
        return true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if ((inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) ||
            inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.channelCount <= 0
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        this.sampleRate = inputAudioFormat.sampleRate
        this.channelCount = inputAudioFormat.channelCount
        this.encoding = inputAudioFormat.encoding

        val rate = inputAudioFormat.sampleRate
        val channels = inputAudioFormat.channelCount

        z1 = Array(channels) { DoubleArray(BAND_COUNT) }
        z2 = Array(channels) { DoubleArray(BAND_COUNT) }

        alphaBlock = 1.0 - exp(-BLOCK_SIZE.toDouble() / (SMOOTHING_TIME_CONSTANT * rate))

        for (k in 0 until BAND_COUNT) {
            val f0 = BAND_FREQUENCIES[k]
            val q = BAND_QS[k]
            if (f0 >= NYQUIST_GUARD_RATIO * rate) {
                isBypassed[k] = true
            } else {
                isBypassed[k] = false
                val w0 = 2.0 * Math.PI * f0 / rate
                sinW0[k] = sin(w0)
                cosW0[k] = cos(w0)
                alpha[k] = sinW0[k] / (2.0 * q)
            }
        }

        // Initialize active gains = target gains (no fade-in at track start)
        val p = parameters
        val enabled = p.enabled
        for (k in 0 until BAND_COUNT) {
            activeGainsDb[k] = if (enabled) p.gainsDb.getOrElse(k) { 0f }.toDouble() else 0.0
            recomputeCoefficientsForBand(k, activeGainsDb[k])
        }

        activePreGainDb = targetPreGainDb(p)
        linPreEnd = 10.0.pow(activePreGainDb / 20.0)
        linPreStart = linPreEnd
        sampleInBlock = 0

        return inputAudioFormat
    }

    override fun onFlush() {
        clearDelayStates()
        sampleInBlock = 0
    }

    override fun onReset() {
        clearDelayStates()
        sampleInBlock = 0
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
        z1 = Array(1) { DoubleArray(BAND_COUNT) }
        z2 = Array(1) { DoubleArray(BAND_COUNT) }
    }

    private fun clearDelayStates() {
        for (c in z1.indices) {
            z1[c].fill(0.0)
            z2[c].fill(0.0)
        }
    }

    private fun recomputeCoefficientsForBand(k: Int, gainDb: Double) {
        if (isBypassed[k]) {
            normB0[k] = 1.0
            normB1[k] = 0.0
            normB2[k] = 0.0
            normA1[k] = 0.0
            normA2[k] = 0.0
            return
        }

        val a = 10.0.pow(gainDb / 40.0)
        val aA = alpha[k] * a
        val aDivA = alpha[k] / a
        val a0 = 1.0 + aDivA

        normB0[k] = (1.0 + aA) / a0
        normB1[k] = (-2.0 * cosW0[k]) / a0
        normB2[k] = (1.0 - aA) / a0
        normA1[k] = normB1[k]
        normA2[k] = (1.0 - aDivA) / a0
    }

    private fun isFilterStatesClean(): Boolean {
        for (c in z1.indices) {
            for (k in 0 until BAND_COUNT) {
                if (Math.abs(z1[c][k]) + Math.abs(z2[c][k]) >= FLAT_SKIP_STATE_EPSILON) {
                    return false
                }
            }
        }
        return true
    }

    private fun canFlatSkip(p: EqualizerParameters): Boolean {
        if (activePreGainDb != 0.0) return false
        for (k in 0 until BAND_COUNT) {
            if (activeGainsDb[k] != 0.0) return false
        }
        // Verify target gains and pre-gain are also zero so we don't flat-skip during gain ramp onset
        if (p.enabled) {
            if (targetPreGainDb(p) != 0.0) return false
            for (k in 0 until BAND_COUNT) {
                if (p.gainsDb.getOrElse(k) { 0f } != 0f) return false
            }
        }
        return isFilterStatesClean()
    }

    private fun advanceBlock(targetGains: List<Float>, targetPreGainDb: Double) {
        for (k in 0 until BAND_COUNT) {
            val target = targetGains.getOrElse(k) { 0f }.toDouble()
            val diff = target - activeGainsDb[k]
            if (Math.abs(diff) <= SNAP_THRESHOLD_DB) {
                if (activeGainsDb[k] != target) {
                    activeGainsDb[k] = target
                    recomputeCoefficientsForBand(k, activeGainsDb[k])
                }
            } else {
                activeGainsDb[k] += alphaBlock * diff
                recomputeCoefficientsForBand(k, activeGainsDb[k])
            }
        }

        linPreStart = linPreEnd
        val preDiff = targetPreGainDb - activePreGainDb
        if (Math.abs(preDiff) <= SNAP_THRESHOLD_DB) {
            activePreGainDb = targetPreGainDb
        } else {
            activePreGainDb += alphaBlock * preDiff
        }
        linPreEnd = 10.0.pow(activePreGainDb / 20.0)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val p = parameters

        // Bit-identical flat-skip bypass
        if (canFlatSkip(p)) {
            clearDelayStates()
            sampleInBlock = 0
            val outputBuffer = replaceOutputBuffer(remaining)
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val isFloat = encoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val channels = channelCount
        if (channels <= 0) return
        val frameBytes = bytesPerSample * channels
        val frameCount = remaining / frameBytes

        val outputBuffer = replaceOutputBuffer(frameCount * frameBytes)
        outputBuffer.order(inputBuffer.order())

        val enabled = p.enabled
        val targetGains = if (enabled) p.gainsDb else FLAT_GAINS
        val targetPreGainDb = targetPreGainDb(p)

        for (frame in 0 until frameCount) {
            if (sampleInBlock == 0) {
                advanceBlock(targetGains, targetPreGainDb)
            }

            val preGainLin = linPreStart + (sampleInBlock.toDouble() / BLOCK_SIZE) * (linPreEnd - linPreStart)
            val applyWaveshaper = activePreGainDb < WAVESHAPER_THRESHOLD_DB || p.preampMode == PreampMode.MANUAL

            for (c in 0 until channels) {
                var x = if (isFloat) {
                    inputBuffer.float.toDouble()
                } else {
                    inputBuffer.short.toDouble() / 32768.0
                }

                x *= preGainLin

                if (x.isNaN() || x.isInfinite()) {
                    clearDelayStates()
                    x = 0.0
                }

                // 5-band Direct Form II Transposed filtering in series
                for (k in 0 until BAND_COUNT) {
                    val y = normB0[k] * x + z1[c][k]
                    z1[c][k] = normB1[k] * x - normA1[k] * y + z2[c][k]
                    z2[c][k] = normB2[k] * x - normA2[k] * y
                    x = y
                }

                if (x.isNaN() || x.isInfinite()) {
                    clearDelayStates()
                    x = 0.0
                }

                // Safety limiter for residual overshoot (neighbouring boosted bands, phase-shifted
                // peaks). Applied only while the pre-gain is active; a single boosted band stays
                // below the knee because of AUTO_PREAMP_MARGIN_DB.
                if (applyWaveshaper && Math.abs(x) > LIMITER_KNEE) {
                    x = Math.signum(x) * (LIMITER_KNEE + LIMITER_RANGE * tanh((Math.abs(x) - LIMITER_KNEE) / LIMITER_RANGE))
                    limiterEngagedSamples++
                }

                if (isFloat) {
                    outputBuffer.putFloat(x.toFloat())
                } else {
                    val s = (x * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort()
                    outputBuffer.putShort(s)
                }
            }

            sampleInBlock++
            if (sampleInBlock == BLOCK_SIZE) {
                sampleInBlock = 0
            }
        }

        outputBuffer.flip()
    }
}
