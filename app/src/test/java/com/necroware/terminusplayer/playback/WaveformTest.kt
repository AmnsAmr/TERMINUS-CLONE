package com.necroware.terminusplayer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class WaveformTest {

    @Test
    fun generateCacheKeySanitizesSpecialCharacters() {
        val key = WaveformMath.generateCacheKey(
            trackId = "content://media/external/audio/media/12345?foo=bar#seg",
            sizeBytes = 10_485_760L,
            durationMs = 240_000L
        )
        // Should only contain alphanumeric and underscore / hyphen
        assertTrue(key.matches(Regex("^[a-zA-Z0-9_-]+$")))
        assertTrue(key.startsWith("wf_"))
        assertTrue(key.contains("10485760"))
        assertTrue(key.contains("240000"))
    }

    @Test
    fun normalizeScalesMaximumPeakToOne() {
        val buckets = floatArrayOf(0.1f, 0.4f, 0.2f, 0.8f, 0.05f)
        WaveformMath.normalize(buckets)

        assertEquals(1.0f, buckets[3], 0.0001f)
        assertEquals(0.5f, buckets[1], 0.0001f)
        assertEquals(0.125f, buckets[0], 0.0001f)
    }

    @Test
    fun computeNormalizedBucketsBlendsRmsAndPeakPreservingSilence() {
        val count = 10
        val sumSquares = DoubleArray(count)
        val sampleCounts = IntArray(count)
        val peaks = FloatArray(count)

        // Buckets 0-1: Silence (count = 0)
        // Buckets 2-4: Verse (low RMS 0.1, peak 0.3)
        for (i in 2..4) {
            sampleCounts[i] = 100
            sumSquares[i] = 100 * (0.1 * 0.1)
            peaks[i] = 0.3f
        }
        // Buckets 5-7: Chorus (high RMS 0.4, peak 0.9)
        for (i in 5..7) {
            sampleCounts[i] = 100
            sumSquares[i] = 100 * (0.4 * 0.4)
            peaks[i] = 0.9f
        }
        // Buckets 8-9: Outro fade to silence
        sampleCounts[8] = 100
        sumSquares[8] = 100 * (0.05 * 0.05)
        peaks[8] = 0.1f
        // Bucket 9: Silence (count = 0)

        val result = WaveformMath.computeNormalizedBuckets(sumSquares, sampleCounts, peaks)

        // Leading and trailing silence should stay 0
        assertEquals(0f, result[0], 0.0001f)
        assertEquals(0f, result[1], 0.0001f)
        assertEquals(0f, result[9], 0.0001f)

        // Chorus reaches maximum (1.0)
        assertEquals(1.0f, result[6], 0.001f)

        // Verse is significantly lower than chorus (around 0.25 - 0.35)
        assertTrue(result[3] < 0.4f)
        assertTrue(result[3] > 0.2f)
    }

    @Test
    fun interpolateInteriorGapsPreservesBoundarySilence() {
        val buckets = floatArrayOf(0f, 0f, 0.4f, 0f, 0.8f, 0f, 0f)
        val counts = intArrayOf(0, 0, 100, 0, 100, 0, 0)
        WaveformMath.interpolateInteriorGaps(buckets, counts)

        // Leading silence preserved
        assertEquals(0f, buckets[0], 0.0001f)
        assertEquals(0f, buckets[1], 0.0001f)

        // Interior gap at index 3 interpolated halfway between 0.4 and 0.8 -> 0.6
        assertEquals(0.6f, buckets[3], 0.0001f)

        // Trailing silence preserved
        assertEquals(0f, buckets[5], 0.0001f)
        assertEquals(0f, buckets[6], 0.0001f)
    }

    @Test
    fun normalizeHandlesSilenceWithoutNaN() {
        val silent = FloatArray(300) { 0f }
        WaveformMath.normalize(silent)

        for (v in silent) {
            assertEquals(0f, v, 0.0001f)
            assertFalse(v.isNaN())
            assertFalse(v.isInfinite())
        }
    }

    @Test
    fun smoothGapsInterpolatesMissingBuckets() {
        val buckets = floatArrayOf(0f, 0f, 0.4f, 0f, 0f, 0.7f, 0f)
        WaveformMath.smoothGaps(buckets)

        // Leading zeros filled with first non-zero (0.4)
        assertEquals(0.4f, buckets[0], 0.0001f)
        assertEquals(0.4f, buckets[1], 0.0001f)
        assertEquals(0.4f, buckets[2], 0.0001f)

        // Interior gap linearly interpolated between 0.4 and 0.7 across 3 steps: 0.4 -> 0.5 -> 0.6 -> 0.7
        assertEquals(0.5f, buckets[3], 0.0001f)
        assertEquals(0.6f, buckets[4], 0.0001f)
        assertEquals(0.7f, buckets[5], 0.0001f)

        // Trailing zero filled with last non-zero (0.7)
        assertEquals(0.7f, buckets[6], 0.0001f)
    }

    @Test
    fun serializationAndDeserializationRoundtripExact() {
        val original = FloatArray(WAVEFORM_BUCKET_COUNT) { i -> (i % 50) / 50.0f }
        val serialized = WaveformMath.serializeWaveform(original)

        // Magic 4 bytes + Int count 4 bytes + 300 * 4 = 1208 bytes
        assertEquals(1208, serialized.size)

        val restored = WaveformMath.deserializeWaveform(serialized)
        assertNotNull(restored)
        assertEquals(WAVEFORM_BUCKET_COUNT, restored!!.size)
        assertArrayEquals(original, restored, 0.00001f)
    }

    @Test
    fun deserializationRejectsCorruptedData() {
        val valid = WaveformMath.serializeWaveform(FloatArray(WAVEFORM_BUCKET_COUNT) { 0.5f })

        // Corrupt magic header
        val corruptedMagic = valid.copyOf()
        corruptedMagic[0] = 'X'.code.toByte()
        assertNull(WaveformMath.deserializeWaveform(corruptedMagic))

        // Truncated data
        val truncated = valid.copyOfRange(0, 500)
        assertNull(WaveformMath.deserializeWaveform(truncated))

        // Extra trailing garbage
        val tooLong = valid + byteArrayOf(1, 2, 3)
        assertNull(WaveformMath.deserializeWaveform(tooLong))
    }

    @Test
    fun resampleBucketsDownsamplesCorrectly() {
        val buckets = FloatArray(300) { 0.1f }
        // Introduce a sharp transient at bucket 150
        buckets[150] = 0.95f

        val resampled = WaveformMath.resampleBuckets(buckets, targetBarCount = 50)
        assertEquals(50, resampled.size)

        // Bucket 150 maps to bar index 150 * 50 / 300 = 25
        assertEquals(0.95f, resampled[25], 0.0001f)

        // Other bars should reflect the baseline amplitude
        assertEquals(0.1f, resampled[0], 0.0001f)
        assertEquals(0.1f, resampled[49], 0.0001f)
    }

    @Test
    fun waveformDataEqualsAndHashCodeContract() {
        val data1 = WaveformData(floatArrayOf(0.1f, 0.5f, 0.8f))
        val data2 = WaveformData(floatArrayOf(0.1f, 0.5f, 0.8f))
        val data3 = WaveformData(floatArrayOf(0.1f, 0.5f, 0.9f))

        assertEquals(data1, data2)
        assertEquals(data1.hashCode(), data2.hashCode())
        assertFalse(data1 == data3)
    }
}
