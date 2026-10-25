package com.necroware.terminusplayer.playback

import androidx.compose.runtime.Immutable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

const val WAVEFORM_BUCKET_COUNT = 300
private val WAVEFORM_MAGIC = byteArrayOf('W'.code.toByte(), 'F'.code.toByte(), '0'.code.toByte(), '2'.code.toByte())
private const val WAVEFORM_HEADER_SIZE = 8 // 4 bytes magic + 4 bytes Int count

@Immutable
data class WaveformData(
    val buckets: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformData) return false
        return buckets.contentEquals(other.buckets)
    }

    override fun hashCode(): Int {
        return buckets.contentHashCode()
    }
}

object WaveformMath {

    fun generateCacheKey(trackId: String, sizeBytes: Long, durationMs: Long): String {
        val sanitizedId = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return "wf_${sanitizedId}_${sizeBytes}_${durationMs}"
    }

    /**
     * Computes high-definition waveform buckets using RMS acoustic power (75%)
     * blended with peak transient punch (25%), normalized relative to the loudest section.
     * This avoids flatlined 1.0 walls on modern mastered audio and highlights natural song contours.
     */
    fun computeNormalizedBuckets(
        sumSquares: DoubleArray,
        sampleCounts: IntArray,
        peaks: FloatArray
    ): FloatArray {
        val count = sumSquares.size
        val rms = FloatArray(count)
        var maxRms = 0f
        var maxPeak = 0f

        for (i in 0 until count) {
            val r = if (sampleCounts[i] > 0) {
                kotlin.math.sqrt(sumSquares[i] / sampleCounts[i]).toFloat()
            } else 0f
            rms[i] = r
            if (r > maxRms) maxRms = r
            if (peaks[i] > maxPeak) maxPeak = peaks[i]
        }

        val combined = FloatArray(count)
        var maxCombined = 0f

        for (i in 0 until count) {
            val normR = if (maxRms > 0.0001f) (rms[i] / maxRms) else 0f
            val normP = if (maxPeak > 0.0001f) (peaks[i] / maxPeak) else 0f
            // 75% RMS (loudness/energy dynamics) + 25% Peak (percussion/transient punch)
            val c = normR * 0.75f + normP * 0.25f
            combined[i] = c
            if (c > maxCombined) maxCombined = c
        }

        if (maxCombined > 0.0001f) {
            val factor = 1f / maxCombined
            for (i in 0 until count) {
                combined[i] = (combined[i] * factor).coerceIn(0f, 1f)
            }
        }

        interpolateInteriorGaps(combined, sampleCounts)
        return combined
    }

    /**
     * Bridges small interior gaps between decoded buffers where sampleCounts == 0,
     * while preserving leading and trailing silence naturally.
     */
    fun interpolateInteriorGaps(buckets: FloatArray, sampleCounts: IntArray) {
        if (buckets.isEmpty()) return

        var firstWithSamples = -1
        for (i in buckets.indices) {
            if (sampleCounts[i] > 0) {
                firstWithSamples = i
                break
            }
        }
        if (firstWithSamples < 0) return

        var lastWithSamples = -1
        for (i in buckets.indices.reversed()) {
            if (sampleCounts[i] > 0) {
                lastWithSamples = i
                break
            }
        }
        if (lastWithSamples <= firstWithSamples) return

        var prevValid = firstWithSamples
        for (i in (firstWithSamples + 1)..lastWithSamples) {
            if (sampleCounts[i] > 0) {
                if (i > prevValid + 1) {
                    val startVal = buckets[prevValid]
                    val endVal = buckets[i]
                    val span = (i - prevValid).toFloat()
                    for (j in (prevValid + 1) until i) {
                        val fraction = (j - prevValid) / span
                        buckets[j] = startVal + fraction * (endVal - startVal)
                    }
                }
                prevValid = i
            }
        }
    }

    /**
     * Linear normalization to peak = 1.0.
     */
    fun normalize(buckets: FloatArray) {
        var maxVal = 0f
        for (v in buckets) {
            if (v > maxVal) maxVal = v
        }
        if (maxVal > 0.0001f) {
            val factor = 1f / maxVal
            for (i in buckets.indices) {
                buckets[i] = (buckets[i] * factor).coerceIn(0f, 1f)
            }
        }
    }

    /**
     * Smooths interior missing buckets and fills edges.
     */
    fun smoothGaps(buckets: FloatArray) {
        if (buckets.isEmpty()) return

        var firstNonZero = -1
        for (i in buckets.indices) {
            if (buckets[i] > 0f) {
                firstNonZero = i
                break
            }
        }
        if (firstNonZero < 0) return // entirely silent

        // Fill leading zeros
        val initialVal = buckets[firstNonZero]
        for (i in 0 until firstNonZero) {
            buckets[i] = initialVal
        }

        // Interpolate interior gaps
        var prevIdx = firstNonZero
        for (i in (firstNonZero + 1) until buckets.size) {
            if (buckets[i] > 0f) {
                if (i > prevIdx + 1) {
                    val startVal = buckets[prevIdx]
                    val endVal = buckets[i]
                    val span = (i - prevIdx).toFloat()
                    for (j in (prevIdx + 1) until i) {
                        val fraction = (j - prevIdx) / span
                        buckets[j] = startVal + fraction * (endVal - startVal)
                    }
                }
                prevIdx = i
            }
        }

        // Fill trailing zeros
        val lastVal = buckets[prevIdx]
        for (i in (prevIdx + 1) until buckets.size) {
            buckets[i] = lastVal
        }
    }

    fun serializeWaveform(buckets: FloatArray): ByteArray {
        val totalBytes = WAVEFORM_HEADER_SIZE + buckets.size * 4
        val buffer = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(WAVEFORM_MAGIC)
        buffer.putInt(buckets.size)
        for (f in buckets) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    fun deserializeWaveform(bytes: ByteArray): FloatArray? {
        if (bytes.size < WAVEFORM_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Validate magic
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(WAVEFORM_MAGIC)) return null

        val count = buffer.int
        if (count != WAVEFORM_BUCKET_COUNT || bytes.size != WAVEFORM_HEADER_SIZE + count * 4) {
            return null
        }

        val buckets = FloatArray(count)
        for (i in 0 until count) {
            buckets[i] = buffer.float
        }
        return buckets
    }

    fun resampleBuckets(buckets: FloatArray, targetBarCount: Int): FloatArray {
        if (targetBarCount <= 0 || buckets.isEmpty()) return FloatArray(0)
        val result = FloatArray(targetBarCount)
        val bucketCount = buckets.size

        for (i in 0 until targetBarCount) {
            val startIdx = (i * bucketCount) / targetBarCount
            val endIdx = max(startIdx + 1, ((i + 1) * bucketCount) / targetBarCount).coerceAtMost(bucketCount)
            var peak = 0f
            for (b in startIdx until endIdx) {
                if (buckets[b] > peak) peak = buckets[b]
            }
            result[i] = peak
        }
        return result
    }
}
