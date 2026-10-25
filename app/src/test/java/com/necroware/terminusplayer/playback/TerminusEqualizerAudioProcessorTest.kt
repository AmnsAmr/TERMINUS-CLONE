package com.necroware.terminusplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.necroware.terminusplayer.data.prefs.PreampMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class TerminusEqualizerAudioProcessorTest {

    private fun analyticGainDb(
        freqHz: Double,
        sampleRate: Int,
        gainsDb: List<Float>,
        enabled: Boolean
    ): Double {
        if (!enabled) return 0.0
        val preGainDb = TerminusEqualizerAudioProcessor.autoPreGainDb(enabled, gainsDb)
        var totalMag = 10.0.pow(preGainDb / 20.0)

        val freqs = TerminusEqualizerAudioProcessor.BAND_FREQUENCIES
        val qs = TerminusEqualizerAudioProcessor.BAND_QS

        val w = 2.0 * Math.PI * freqHz / sampleRate
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = cos(2.0 * w)
        val sin2W = sin(2.0 * w)

        for (k in 0 until TerminusEqualizerAudioProcessor.BAND_COUNT) {
            val f0 = freqs[k]
            val q = qs[k]
            val g = gainsDb.getOrElse(k) { 0f }.toDouble()
            if (f0 >= TerminusEqualizerAudioProcessor.NYQUIST_GUARD_RATIO * sampleRate || abs(g) < 0.005) {
                continue
            }
            val w0 = 2.0 * Math.PI * f0 / sampleRate
            val a = 10.0.pow(g / 40.0)
            val alpha = sin(w0) / (2.0 * q)
            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cos(w0)
            val b2 = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1 = -2.0 * cos(w0)
            val a2 = 1.0 - alpha / a

            val normB0 = b0 / a0
            val normB1 = b1 / a0
            val normB2 = b2 / a0
            val normA1 = a1 / a0
            val normA2 = a2 / a0

            val numRe = normB0 + normB1 * cosW + normB2 * cos2W
            val numIm = -normB1 * sinW - normB2 * sin2W
            val denRe = 1.0 + normA1 * cosW + normA2 * cos2W
            val denIm = -normA1 * sinW - normA2 * sin2W

            val mag = sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm))
            totalMag *= mag
        }
        return 20.0 * log10(totalMag)
    }

    private fun TerminusEqualizerAudioProcessor.configureAndFlush(format: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val out = configure(format)
        flush()
        return out
    }

    private fun processFloatBuffer(
        processor: TerminusEqualizerAudioProcessor,
        samples: FloatArray
    ): FloatArray {
        val byteBuffer = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) byteBuffer.putFloat(s)
        byteBuffer.flip()

        processor.queueInput(byteBuffer)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)
        val result = FloatArray(output.remaining() / 4)
        for (i in result.indices) {
            result[i] = output.float
        }
        return result
    }

    private fun processPcm16Buffer(
        processor: TerminusEqualizerAudioProcessor,
        samples: ShortArray
    ): ShortArray {
        val byteBuffer = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) byteBuffer.putShort(s)
        byteBuffer.flip()

        processor.queueInput(byteBuffer)
        val output = processor.output
        output.order(ByteOrder.LITTLE_ENDIAN)
        val result = ShortArray(output.remaining() / 2)
        for (i in result.indices) {
            result[i] = output.short
        }
        return result
    }

    // 1. Analytic accuracy: 60 Hz at +12 dB on band 0, 14 kHz at -12 dB on band 4, 1 kHz flat = 0 dB
    @Test
    fun analyticAccuracyMatchesTheoryWithinHalfDb() {
        val sampleRate = 48000
        val count = 9600

        // Case A: 60 Hz at +12 dB on band 0
        run {
            val processor = TerminusEqualizerAudioProcessor()
            val gains = listOf(12f, 0f, 0f, 0f, 0f)
            processor.setParameters(EqualizerParameters(enabled = true, gainsDb = gains))
            processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

            val freq = 60.0
            val amp = 0.1f // Linear region (no waveshaping)
            val inSamples = FloatArray(count) { i -> (amp * sin(2.0 * Math.PI * freq * i / sampleRate)).toFloat() }
            val outSamples = processFloatBuffer(processor, inSamples)

            // Measure steady-state (skip initial transient)
            val steady = 2000 until count
            var sumInSq = 0.0
            var sumOutSq = 0.0
            for (i in steady) {
                sumInSq += inSamples[i] * inSamples[i]
                sumOutSq += outSamples[i] * outSamples[i]
            }
            val rmsIn = sqrt(sumInSq / steady.count())
            val rmsOut = sqrt(sumOutSq / steady.count())
            val measuredGainDb = 20.0 * log10(rmsOut / rmsIn)
            val expectedGainDb = analyticGainDb(freq, sampleRate, gains, true)

            // +12 dB boost with -12.5 dB auto pre-gain (full compensation + 0.5 dB margin) nets -0.5 dB
            assertEquals(-0.5, expectedGainDb, 0.1)
            assertEquals(expectedGainDb, measuredGainDb, 0.5)
        }

        // Case B: 14 kHz at -12 dB on band 4
        run {
            val processor = TerminusEqualizerAudioProcessor()
            val gains = listOf(0f, 0f, 0f, 0f, -12f)
            processor.setParameters(EqualizerParameters(enabled = true, gainsDb = gains))
            processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

            val freq = 14000.0
            val amp = 0.1f
            val inSamples = FloatArray(count) { i -> (amp * sin(2.0 * Math.PI * freq * i / sampleRate)).toFloat() }
            val outSamples = processFloatBuffer(processor, inSamples)

            val steady = 2000 until count
            var sumInSq = 0.0
            var sumOutSq = 0.0
            for (i in steady) {
                sumInSq += inSamples[i] * inSamples[i]
                sumOutSq += outSamples[i] * outSamples[i]
            }
            val rmsIn = sqrt(sumInSq / steady.count())
            val rmsOut = sqrt(sumOutSq / steady.count())
            val measuredGainDb = 20.0 * log10(rmsOut / rmsIn)
            val expectedGainDb = analyticGainDb(freq, sampleRate, gains, true)

            assertEquals(-12.0, expectedGainDb, 0.1)
            assertEquals(expectedGainDb, measuredGainDb, 0.5)
        }

        // Case C: 1 kHz with flat EQ = 0 dB change
        run {
            val processor = TerminusEqualizerAudioProcessor()
            val gains = listOf(0f, 0f, 0f, 0f, 0f)
            processor.setParameters(EqualizerParameters(enabled = true, gainsDb = gains))
            processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

            val freq = 1000.0
            val amp = 0.1f
            val inSamples = FloatArray(count) { i -> (amp * sin(2.0 * Math.PI * freq * i / sampleRate)).toFloat() }
            val outSamples = processFloatBuffer(processor, inSamples)

            val steady = 2000 until count
            var sumInSq = 0.0
            var sumOutSq = 0.0
            for (i in steady) {
                sumInSq += inSamples[i] * inSamples[i]
                sumOutSq += outSamples[i] * outSamples[i]
            }
            val rmsIn = sqrt(sumInSq / steady.count())
            val rmsOut = sqrt(sumOutSq / steady.count())
            val measuredGainDb = 20.0 * log10(rmsOut / rmsIn)

            assertEquals(0.0, measuredGainDb, 0.5)
        }
    }

    // 2. Full-chain click-free: step band 0 from 0 to +12 dB mid-stream across block boundaries
    @Test
    fun steppingGainMidStreamIsClickFree() {
        val sampleRate = 48000
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(0f, 0f, 0f, 0f, 0f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        val freq = 100.0
        val amp = 0.3f
        val totalSamples = 4096
        val allIn = FloatArray(totalSamples) { i -> (amp * sin(2.0 * Math.PI * freq * i / sampleRate)).toFloat() }

        // Process first chunk at 0 dB
        val chunk1 = allIn.copyOfRange(0, 512)
        val out1 = processFloatBuffer(processor, chunk1)

        // Step Band 0 to +12 dB mid-stream
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 0f, 0f, 0f, 0f)))

        // Process remaining chunks in small blocks crossing 32-sample boundaries
        val remainingOutputs = mutableListOf<FloatArray>()
        var offset = 512
        while (offset < totalSamples) {
            val chunkSize = minOf(64, totalSamples - offset)
            val chunk = allIn.copyOfRange(offset, offset + chunkSize)
            remainingOutputs.add(processFloatBuffer(processor, chunk))
            offset += chunkSize
        }

        val allOut = FloatArray(totalSamples)
        System.arraycopy(out1, 0, allOut, 0, out1.size)
        var writePos = out1.size
        for (chunk in remainingOutputs) {
            System.arraycopy(chunk, 0, allOut, writePos, chunk.size)
            writePos += chunk.size
        }

        // Compare max sample-to-sample delta against input delta
        var maxInDelta = 0f
        for (i in 0 until allIn.size - 1) {
            val d = abs(allIn[i + 1] - allIn[i])
            if (d > maxInDelta) maxInDelta = d
        }

        var maxOutDelta = 0f
        for (i in 0 until allOut.size - 1) {
            val d = abs(allOut[i + 1] - allOut[i])
            if (d > maxOutDelta) maxOutDelta = d
        }

        // Net gain at the boosted band is -0.5 dB after auto pre-gain. A click/glitch would produce a steep spike (> 4x input delta).
        assertTrue("Max out delta $maxOutDelta should be bounded (< 3.5x in delta $maxInDelta)", maxOutDelta <= 3.5f * maxInDelta)
    }

    // 3. Multi-tone distortion: -6 dBFS sum of 60 Hz, 500 Hz, 2 kHz with +12 dB on band 0
    @Test
    fun multiToneDistortionRemainsBoundedAndPeakWithinOne() {
        val sampleRate = 48000
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 0f, 0f, 0f, 0f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        val n = 4800 // 100 ms at 48 kHz -> exact 10 Hz bin resolution
        val scale = 0.501187 // -6 dBFS peak
        val inSamples = FloatArray(n * 2) { i ->
            val t = i.toDouble() / sampleRate
            val s1 = sin(2.0 * Math.PI * 60.0 * t)
            val s2 = sin(2.0 * Math.PI * 500.0 * t)
            val s3 = sin(2.0 * Math.PI * 2000.0 * t)
            ((scale / 3.0) * (s1 + s2 + s3)).toFloat()
        }

        val outSamples = processFloatBuffer(processor, inSamples)

        // Steady-state measurement window
        val window = outSamples.copyOfRange(n, n * 2)

        // Peak must strictly remain within (-1, 1)
        var peak = 0f
        for (v in window) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        assertTrue("Peak ($peak) must be strictly < 1.0", peak < 1.0f)
        assertTrue("Peak ($peak) must be > 0.0", peak > 0.0f)

        // Exact DFT calculation across bins (10 Hz resolution, integer frequencies: bins 6, 50, 200)
        var totalEnergy = 0.0
        var fundEnergy = 0.0
        val halfN = n / 2

        for (m in 1 until halfN) {
            var re = 0.0
            var im = 0.0
            val omegaM = 2.0 * Math.PI * m / n
            for (i in 0 until n) {
                val y = window[i].toDouble()
                re += y * cos(omegaM * i)
                im -= y * sin(omegaM * i)
            }
            val power = re * re + im * im
            totalEnergy += power
            if (m == 6 || m == 50 || m == 200) {
                fundEnergy += power
            }
        }

        val distEnergy = (totalEnergy - fundEnergy).coerceAtLeast(0.0)
        val thd = sqrt(distEnergy / totalEnergy)
        println("MULTI-TONE THD [+12 dB band 0, -6 dBFS 60/500/2000 Hz, 48 kHz float]: ${"%.6f".format(java.util.Locale.US, thd * 100)}%")

        // Full compensation keeps this signal out of the limiter, so it stays linear.
        // Measured 0.000120% (JVM, 2026-10-22); the bound leaves room for float differences only.
        assertTrue("THD must be < 0.001%, got ${thd * 100}%", thd < 0.00001)
    }

    // 4. Flat transparency: with EQ disabled/flat and settled, a signal with peaks at 0.99 comes out bit-identical.
    // Also test waveshaper stays on while gains glide down after boost is removed.
    @Test
    fun flatTransparencyAndWaveshaperGliding() {
        val sampleRate = 48000
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = false))
        processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        // Part A: Bit-identical flat skip with hot peaks (0.99f)
        val hotSamples = floatArrayOf(0.99f, -0.99f, 0.98f, -0.98f, 0.5f)
        val byteBuffer = ByteBuffer.allocateDirect(hotSamples.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in hotSamples) byteBuffer.putFloat(s)
        byteBuffer.flip()

        processor.queueInput(byteBuffer)
        val outBuffer = processor.output
        outBuffer.order(ByteOrder.LITTLE_ENDIAN)

        for (expected in hotSamples) {
            assertEquals(expected, outBuffer.float, 0.0f)
        }

        // Part B: Waveshaper stays active while gliding down after boost is removed
        val procB = TerminusEqualizerAudioProcessor()
        procB.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 0f, 0f, 0f, 0f)))
        procB.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        // Warm up so active pre-gain is -12.5 dB
        val warmUp = FloatArray(512) { 0.1f }
        processFloatBuffer(procB, warmUp)

        // Remove boost: target pre-gain becomes 0, but active pre-gain starts gliding up from -12.5 dB
        procB.setParameters(EqualizerParameters(enabled = false))

        // Immediately feed a hot sample > 0.95 (e.g. 1.2f) in the next block
        val hotChunk = floatArrayOf(1.2f, -1.2f)
        val shapedOut = processFloatBuffer(procB, hotChunk)

        // Because active pre-gain is still < -0.01 dB, waveshaper must shape it strictly below 1.0
        assertTrue("Sample was shaped below 1.0 while gliding down", abs(shapedOut[0]) < 1.0f)

        // Feed enough samples to fully settle (tau=25ms -> ~200ms = ~10000+ samples)
        val settling = FloatArray(15000) { 0f }
        processFloatBuffer(procB, settling)

        // Now flat-skip is restored: a 0.99f sample passes through unshaped
        val postSettleHot = floatArrayOf(0.99f)
        val postSettleOut = processFloatBuffer(procB, postSettleHot)
        assertEquals(0.99f, postSettleOut[0], 0.0f)
    }

    // 5. Nyquist guard at 22.05 kHz and 32 kHz: 14 kHz band bypassed, no NaN/Inf
    @Test
    fun nyquistGuardBypassesBandAndPreventsNanInf() {
        listOf(22050, 32000).forEach { rate ->
            val processor = TerminusEqualizerAudioProcessor()
            processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(0f, 0f, 0f, 0f, 12f)))
            processor.configureAndFlush(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))

            val inSamples = FloatArray(1000) { i -> (0.2f * sin(2.0 * Math.PI * 14000.0 * i / rate)).toFloat() }
            val outSamples = processFloatBuffer(processor, inSamples)

            for (s in outSamples) {
                assertFalse("Sample should not be NaN at rate $rate", s.isNaN())
                assertFalse("Sample should not be Infinite at rate $rate", s.isInfinite())
            }
        }
    }

    // 6. flush() keeps active gains (no volume change after flush) but clears states
    @Test
    fun flushPreservesActiveGainsAndClearsStates() {
        val sampleRate = 48000
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 0f, 0f, 0f, 0f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        val freq = 60.0
        val amp = 0.1f
        val inSamples = FloatArray(4800) { i -> (amp * sin(2.0 * Math.PI * freq * i / sampleRate)).toFloat() }

        // Process audio so active gains are fully settled at +12 dB
        val out1 = processFloatBuffer(processor, inSamples)
        val rms1 = sqrt(out1.takeLast(1000).map { (it * it).toDouble() }.average())

        // Seek flush
        processor.flush()

        // Process next audio chunk immediately
        val out2 = processFloatBuffer(processor, inSamples)
        val rms2 = sqrt(out2.takeLast(1000).map { (it * it).toDouble() }.average())

        // Active gains must be preserved (no volume change / no fade-in from 0 dB)
        assertEquals(rms1, rms2, rms1 * 0.05)
    }

    // 7. Format matrix: mono/stereo, 44.1/48/96/192 kHz, 16-bit and float
    @Test
    fun formatMatrixSupportsAllChannelsRatesAndEncodings() {
        val channels = listOf(1, 2)
        val rates = listOf(44100, 48000, 96000, 192000)
        val encodings = listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)

        for (ch in channels) {
            for (rate in rates) {
                for (enc in encodings) {
                    val processor = TerminusEqualizerAudioProcessor()
                    processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(3f, -2f, 1f, -1f, 2f)))
                    val outputFormat = processor.configureAndFlush(AudioProcessor.AudioFormat(rate, ch, enc))

                    assertEquals(enc, outputFormat.encoding)
                    assertEquals(rate, outputFormat.sampleRate)
                    assertEquals(ch, outputFormat.channelCount)

                    val frames = 128
                    val bytesPerSample = if (enc == C.ENCODING_PCM_FLOAT) 4 else 2
                    val byteCount = frames * ch * bytesPerSample
                    val inBuffer = ByteBuffer.allocateDirect(byteCount).order(ByteOrder.LITTLE_ENDIAN)

                    if (enc == C.ENCODING_PCM_FLOAT) {
                        for (i in 0 until frames * ch) inBuffer.putFloat(0.1f)
                    } else {
                        for (i in 0 until frames * ch) inBuffer.putShort(3000)
                    }
                    inBuffer.flip()

                    processor.queueInput(inBuffer)
                    val outBuffer = processor.output
                    assertEquals("Output bytes must match frame count for $ch ch $rate Hz $enc", byteCount, outBuffer.remaining())
                }
            }
        }
    }

    // 8. Unsupported encoding throws UnhandledAudioFormatException
    @Test
    fun unsupportedEncodingThrowsUnhandledAudioFormatException() {
        val processor = TerminusEqualizerAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_24BIT))
            fail("Expected UnhandledAudioFormatException")
        } catch (_: AudioProcessor.UnhandledAudioFormatException) {
            // Success
        }
    }

    // 9. Toggling enabled on/off mid-stream is click-free and ends in flat-skip
    @Test
    fun togglingEnabledMidStreamIsClickFreeAndEndsInFlatSkip() {
        val sampleRate = 48000
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 0f, 0f, 0f, 0f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        val freq = 440.0
        val amp = 0.2f
        val inSamples = FloatArray(4800) { i -> (amp * sin(2.0 * Math.PI * freq * i / sampleRate)).toFloat() }

        // Process first chunk with EQ ON
        val chunk1 = inSamples.copyOfRange(0, 512)
        val out1 = processFloatBuffer(processor, chunk1)

        // Toggle EQ OFF mid-stream
        processor.setParameters(EqualizerParameters(enabled = false))

        // Stream remaining chunks
        val remainingOutputs = mutableListOf<FloatArray>()
        var offset = 512
        while (offset < inSamples.size) {
            val size = minOf(64, inSamples.size - offset)
            val chunk = inSamples.copyOfRange(offset, offset + size)
            remainingOutputs.add(processFloatBuffer(processor, chunk))
            offset += size
        }

        val allOut = FloatArray(inSamples.size)
        System.arraycopy(out1, 0, allOut, 0, out1.size)
        var writePos = out1.size
        for (chunk in remainingOutputs) {
            System.arraycopy(chunk, 0, allOut, writePos, chunk.size)
            writePos += chunk.size
        }

        // Verify click-free transition (bounded delta)
        var maxInDelta = 0f
        for (i in 0 until inSamples.size - 1) {
            val d = abs(inSamples[i + 1] - inSamples[i])
            if (d > maxInDelta) maxInDelta = d
        }
        var maxOutDelta = 0f
        for (i in 0 until allOut.size - 1) {
            val d = abs(allOut[i + 1] - allOut[i])
            if (d > maxOutDelta) maxOutDelta = d
        }
        assertTrue("Max out delta $maxOutDelta should be bounded (< 3.5x in delta $maxInDelta)", maxOutDelta <= 3.5f * maxInDelta)

        // Feed remaining samples to ensure full settling into flat-skip
        val settle = FloatArray(15000) { 0f }
        processFloatBuffer(processor, settle)

        // Verify flat-skip is reached: hot sample 0.99 comes out bit-identical
        val testSample = floatArrayOf(0.99f)
        val testOut = processFloatBuffer(processor, testSample)
        assertEquals(0.99f, testOut[0], 0.0f)
    }

    // 10. Invalid sample rate or channel count throws UnhandledAudioFormatException
    @Test
    fun invalidSampleRateOrChannelCountThrowsUnhandledAudioFormatException() {
        val processor = TerminusEqualizerAudioProcessor()
        try {
            processor.configure(AudioProcessor.AudioFormat(0, 2, C.ENCODING_PCM_FLOAT))
            fail("Expected UnhandledAudioFormatException for sampleRate=0")
        } catch (_: AudioProcessor.UnhandledAudioFormatException) {}

        try {
            processor.configure(AudioProcessor.AudioFormat(44100, 0, C.ENCODING_PCM_FLOAT))
            fail("Expected UnhandledAudioFormatException for channelCount=0")
        } catch (_: AudioProcessor.UnhandledAudioFormatException) {}

        try {
            processor.configure(AudioProcessor.AudioFormat(-44100, 2, C.ENCODING_PCM_16BIT))
            fail("Expected UnhandledAudioFormatException for negative sampleRate")
        } catch (_: AudioProcessor.UnhandledAudioFormatException) {}
    }

    // 11. Flush and Reset cleanly reset delay registers and processor state
    @Test
    fun flushAndResetCleanlyResetProcessorState() {
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 12f, 12f, 12f, 12f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))

        // Feed an impulse to load filter states
        val impulse = FloatArray(64) { if (it == 0) 1.0f else 0.0f }
        processFloatBuffer(processor, impulse)

        // Flush processor
        processor.flush()

        // Feed zeros: output should be clean zero (no lingering ring from before flush)
        val zeros = FloatArray(64) { 0.0f }
        val outAfterFlush = processFloatBuffer(processor, zeros)
        for (i in outAfterFlush.indices) {
            assertEquals("Sample after flush should be 0", 0.0f, outAfterFlush[i], 1e-6f)
        }

        // Reset processor
        processor.reset()
        assertTrue("Processor remains active after reset", processor.isActive)

        // Reconfigure and ensure it works cleanly
        processor.configureAndFlush(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        val testPcm16 = ShortArray(64) { 1000 }
        val outPcm16 = processPcm16Buffer(processor, testPcm16)
        assertEquals(64, outPcm16.size)
    }

    // 12. queueEndOfStream and isEnded lifecycle behavior
    @Test
    fun queueEndOfStreamSetsIsEndedCorrectly() {
        val processor = TerminusEqualizerAudioProcessor()
        processor.configureAndFlush(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))

        val input = ByteBuffer.allocateDirect(128).order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) input.putFloat(0.5f)
        input.flip()

        processor.queueInput(input)
        val out = processor.output
        assertTrue("Output should be produced", out.hasRemaining())
        out.position(out.limit()) // consume output

        assertFalse("Processor should not be ended before queueEndOfStream", processor.isEnded)
        processor.queueEndOfStream()
        assertTrue("Processor should be ended after queueEndOfStream and output consumed", processor.isEnded)
    }

    // 13. NaN and Infinity inputs are sanitized without poisoning subsequent audio
    @Test
    fun nanAndInfinityInputsAreSanitizedCleanly() {
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(6f, -3f, 12f, -6f, 9f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(44100, 2, C.ENCODING_PCM_FLOAT))

        val dirtyInput = floatArrayOf(
            Float.NaN, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, 1e20f,
            -1e20f, Float.NaN
        )
        val dirtyOut = processFloatBuffer(processor, dirtyInput)
        for (sample in dirtyOut) {
            assertFalse("Output must not be NaN", sample.isNaN())
            assertFalse("Output must not be Infinite", sample.isInfinite())
        }

        // Now feed normal sine tone and verify filter recovered completely
        val normalTone = FloatArray(512) { i -> (0.3 * sin(2.0 * Math.PI * 1000.0 * i / 44100.0)).toFloat() }
        val normalOut = processFloatBuffer(processor, normalTone)
        for (sample in normalOut) {
            assertFalse("Recovered output must not be NaN", sample.isNaN())
            assertFalse("Recovered output must not be Infinite", sample.isInfinite())
            assertTrue("Recovered output must be bounded", abs(sample) <= 1.5f)
        }
    }

    // 14. Stress test: random parameter changes every few blocks for ~30 seconds of audio at 44.1k and 96k
    @Test
    fun stressTestRandomParameterChangesOver30Seconds() {
        val testConfigs = listOf(
            Pair(44100, C.ENCODING_PCM_16BIT),
            Pair(44100, C.ENCODING_PCM_FLOAT),
            Pair(96000, C.ENCODING_PCM_16BIT),
            Pair(96000, C.ENCODING_PCM_FLOAT)
        )

        val random = java.util.Random(1337)
        val durationSeconds = 30
        val framesPerBuffer = 1024
        val channels = 2

        for ((rate, encoding) in testConfigs) {
            val processor = TerminusEqualizerAudioProcessor()
            processor.configureAndFlush(AudioProcessor.AudioFormat(rate, channels, encoding))

            val totalFrames = rate * durationSeconds
            val isFloat = encoding == C.ENCODING_PCM_FLOAT
            val bytesPerSample = if (isFloat) 4 else 2
            val bufferSizeBytes = framesPerBuffer * channels * bytesPerSample

            val inBuffer = ByteBuffer.allocateDirect(bufferSizeBytes).order(ByteOrder.LITTLE_ENDIAN)

            var framesProcessed = 0
            var bufferCount = 0

            while (framesProcessed < totalFrames) {
                // Random parameter change every 4 buffers (~90 ms)
                if (bufferCount % 4 == 0) {
                    val enabled = random.nextBoolean()
                    val gains = List(5) { random.nextFloat() * 24f - 12f }
                    processor.setParameters(EqualizerParameters(enabled = enabled, gainsDb = gains))
                }

                inBuffer.clear()
                val framesThisBuffer = minOf(framesPerBuffer, totalFrames - framesProcessed)

                for (f in 0 until framesThisBuffer) {
                    val phase = 2.0 * Math.PI * 440.0 * (framesProcessed + f) / rate
                    val amp = 0.8 * sin(phase)
                    if (isFloat) {
                        inBuffer.putFloat(amp.toFloat())
                        inBuffer.putFloat(amp.toFloat())
                    } else {
                        val s = (amp * 32767.0).toInt().toShort()
                        inBuffer.putShort(s)
                        inBuffer.putShort(s)
                    }
                }
                inBuffer.flip()

                processor.queueInput(inBuffer)
                val outBuffer = processor.output
                outBuffer.order(ByteOrder.LITTLE_ENDIAN)

                if (isFloat) {
                    while (outBuffer.hasRemaining()) {
                        val sample = outBuffer.float
                        assertFalse("Sample in stress test must not be NaN at $rate Hz", sample.isNaN())
                        assertFalse("Sample in stress test must not be Infinite at $rate Hz", sample.isInfinite())
                        assertTrue("Sample in stress test must be bounded at $rate Hz, was $sample", abs(sample) <= 2.0f)
                    }
                } else {
                    while (outBuffer.hasRemaining()) {
                        val sample = outBuffer.short
                        assertTrue("Sample must be in valid short range", sample in -32768..32767)
                    }
                }

                framesProcessed += framesThisBuffer
                bufferCount++
            }
        }
    }

    /**
     * THD+N of a steady tone that spans exactly [cycles] periods: fit the fundamental (and DC) by
     * single-bin DFT, subtract it, and return residual RMS / fundamental RMS.
     */
    private fun thdPlusN(signal: DoubleArray, cycles: Int): Double {
        val n = signal.size
        var re = 0.0
        var im = 0.0
        var mean = 0.0
        for (i in 0 until n) {
            val w = 2.0 * Math.PI * cycles * i / n
            re += signal[i] * cos(w)
            im += signal[i] * sin(w)
            mean += signal[i]
        }
        val a = 2.0 * re / n
        val b = 2.0 * im / n
        mean /= n
        var residualSq = 0.0
        for (i in 0 until n) {
            val w = 2.0 * Math.PI * cycles * i / n
            val r = signal[i] - mean - (a * cos(w) + b * sin(w))
            residualSq += r * r
        }
        val fundamentalRms = sqrt((a * a + b * b) / 2.0)
        return sqrt(residualSq / n) / fundamentalRms
    }

    // 16. Full compensation: a 0 dBFS sine at each band centre with only that band at +12 dB
    // never exceeds full scale, never engages the limiter, and stays clean.
    @Test
    fun singleBoostedBandAtFullScaleStaysBelowCeilingWithoutLimiter() {
        for (encoding in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)) {
            for (rate in listOf(44100, 48000)) {
                for (band in 0 until TerminusEqualizerAudioProcessor.BAND_COUNT) {
                    val freq = TerminusEqualizerAudioProcessor.BAND_FREQUENCIES[band]
                    val gains = List(TerminusEqualizerAudioProcessor.BAND_COUNT) { if (it == band) 12f else 0f }
                    val processor = TerminusEqualizerAudioProcessor()
                    processor.setParameters(EqualizerParameters(enabled = true, gainsDb = gains))
                    processor.configureAndFlush(AudioProcessor.AudioFormat(rate, 1, encoding))

                    // 0.5 s to settle, then a 100 ms window: every band centre is a whole number of cycles in it.
                    val window = rate / 10
                    val total = rate / 2 + window
                    val cycles = (freq / 10.0).roundToInt()
                    val output = DoubleArray(total)
                    if (encoding == C.ENCODING_PCM_FLOAT) {
                        val input = FloatArray(total) { i -> sin(2.0 * Math.PI * freq * i / rate).toFloat() }
                        processFloatBuffer(processor, input).forEachIndexed { i, v -> output[i] = v.toDouble() }
                    } else {
                        val input = ShortArray(total) { i -> (32767.0 * sin(2.0 * Math.PI * freq * i / rate)).roundToInt().toShort() }
                        processPcm16Buffer(processor, input).forEachIndexed { i, v -> output[i] = v / 32768.0 }
                    }

                    val label = "${if (encoding == C.ENCODING_PCM_FLOAT) "float" else "pcm16"} $rate Hz, ${freq.toInt()} Hz band +12 dB"
                    val peak = output.maxOf { abs(it) }
                    val thd = thdPlusN(output.copyOfRange(total - window, total), cycles)
                    println("SINGLE BAND [$label]: peak=${"%.6f".format(java.util.Locale.US, peak)} limiterSamples=${processor.limiterEngagedSamples} THD+N=${"%.6f".format(java.util.Locale.US, thd * 100)}%")

                    assertTrue("Peak $peak must be <= 1.0 ($label)", peak <= 1.0)
                    assertEquals("Limiter must never engage ($label)", 0L, processor.limiterEngagedSamples)
                    assertTrue("THD+N ${thd * 100}% must be < 0.1% ($label)", thd < 0.001)
                }
            }
        }
    }

    // 17. The 0.5 dB margin is fixed, not proportional: a small boost also stays clear of the limiter knee.
    @Test
    fun smallBoostAtFullScaleNeverEngagesLimiter() {
        val rate = 48000
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(0.3f, 0f, 0f, 0f, 0f)))
        processor.configureAndFlush(AudioProcessor.AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))

        val input = FloatArray(rate / 2) { i -> sin(2.0 * Math.PI * 60.0 * i / rate).toFloat() }
        val output = processFloatBuffer(processor, input)

        assertEquals(-0.8, TerminusEqualizerAudioProcessor.autoPreGainDb(true, listOf(0.3f, 0f, 0f, 0f, 0f)), 1e-6)
        assertTrue("Peak must be <= 1.0", output.maxOf { abs(it) } <= 1.0f)
        assertEquals("Limiter must never engage", 0L, processor.limiterEngagedSamples)
    }

    // 18. Cut-only and flat settings need no compensation, so the bit-identical flat bypass stays reachable.
    @Test
    fun autoPreGainIsZeroWithoutBoostsAndFullyCompensatesTheLargestBoost() {
        assertEquals(0.0, TerminusEqualizerAudioProcessor.autoPreGainDb(false, listOf(12f, 0f, 0f, 0f, 0f)), 0.0)
        assertEquals(0.0, TerminusEqualizerAudioProcessor.autoPreGainDb(true, listOf(0f, 0f, 0f, 0f, 0f)), 0.0)
        assertEquals(0.0, TerminusEqualizerAudioProcessor.autoPreGainDb(true, listOf(-6f, -3f, 0f, -12f, 0f)), 0.0)
        assertEquals(-12.5, TerminusEqualizerAudioProcessor.autoPreGainDb(true, listOf(6f, -3f, 12f, -6f, 9f)), 1e-6)
    }

    // 15. Lightweight performance benchmark estimating CPU cost per second of stereo audio at 44.1 kHz
    @Test
    fun benchmarkCpuCostPerSecondOfStereoAudio() {
        val sampleRate = 44100
        val channels = 2
        val benchmarkDurationSec = 5.0
        val totalFrames = (sampleRate * benchmarkDurationSec).toInt()
        val framesPerBuffer = 2048

        val activeGains = listOf(6f, -3f, 12f, -6f, 9f)

        for (encoding in listOf(C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_16BIT)) {
            val isFloat = encoding == C.ENCODING_PCM_FLOAT
            val encName = if (isFloat) "PCM_FLOAT" else "PCM_16BIT"

            val processor = TerminusEqualizerAudioProcessor()
            processor.setParameters(EqualizerParameters(enabled = true, gainsDb = activeGains))
            processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, channels, encoding))

            val bytesPerSample = if (isFloat) 4 else 2
            val inBuffer = ByteBuffer.allocateDirect(framesPerBuffer * channels * bytesPerSample).order(ByteOrder.LITTLE_ENDIAN)

            // Warm-up: 1 second of audio
            for (w in 0 until (sampleRate / framesPerBuffer)) {
                inBuffer.clear()
                for (f in 0 until framesPerBuffer) {
                    val s = (0.5 * sin(2.0 * Math.PI * 440.0 * f / sampleRate)).toFloat()
                    if (isFloat) {
                        inBuffer.putFloat(s); inBuffer.putFloat(s)
                    } else {
                        val sh = (s * 32767.0).toInt().toShort()
                        inBuffer.putShort(sh); inBuffer.putShort(sh)
                    }
                }
                inBuffer.flip()
                processor.queueInput(inBuffer)
                processor.output.position(processor.output.limit())
            }

            // Benchmark run: 5 seconds of audio
            val startNs = System.nanoTime()
            var framesDone = 0
            while (framesDone < totalFrames) {
                inBuffer.clear()
                val chunkFrames = minOf(framesPerBuffer, totalFrames - framesDone)
                for (f in 0 until chunkFrames) {
                    val s = (0.7 * sin(2.0 * Math.PI * 880.0 * (framesDone + f) / sampleRate)).toFloat()
                    if (isFloat) {
                        inBuffer.putFloat(s); inBuffer.putFloat(s)
                    } else {
                        val sh = (s * 32767.0).toInt().toShort()
                        inBuffer.putShort(sh); inBuffer.putShort(sh)
                    }
                }
                inBuffer.flip()
                processor.queueInput(inBuffer)
                val out = processor.output
                out.position(out.limit())
                framesDone += chunkFrames
            }
            val elapsedNs = System.nanoTime() - startNs
            val elapsedMs = elapsedNs / 1_000_000.0
            val cpuMsPerSecAudio = elapsedMs / benchmarkDurationSec
            val cpuPercentCore = (cpuMsPerSecAudio / 1000.0) * 100.0

            println("PERF BENCHMARK [$encName 44.1kHz stereo]: ${String.format(java.util.Locale.US, "%.3f", cpuMsPerSecAudio)} ms CPU per second of audio (${String.format(java.util.Locale.US, "%.3f", cpuPercentCore)}% of 1 CPU core)")

            // Assert that DSP equalizer uses less than 5% of 1 CPU core (< 50 ms CPU per 1000 ms audio)
            assertTrue("Equalizer CPU usage should be well below 5% core ($cpuMsPerSecAudio ms/sec)", cpuMsPerSecAudio < 50.0)
        }
    }

    @Test
    fun targetPreGainDbCalculatesAutoVsManualCorrectly() {
        val autoBoostParams = EqualizerParameters(
            enabled = true,
            gainsDb = listOf(6f, 4f, 0f, 0f, -1f),
            preampMode = PreampMode.AUTO,
            manualPreampDb = 0f
        )
        // Auto with max boost 6 dB -> -(6.0 + 0.5) = -6.5 dB
        assertEquals(-6.5, TerminusEqualizerAudioProcessor.targetPreGainDb(autoBoostParams), 0.001)

        val autoCutParams = EqualizerParameters(
            enabled = true,
            gainsDb = listOf(-2f, -1f, 0f, -3f, -5f),
            preampMode = PreampMode.AUTO,
            manualPreampDb = 0f
        )
        // Auto with flat or cut -> 0.0 dB
        assertEquals(0.0, TerminusEqualizerAudioProcessor.targetPreGainDb(autoCutParams), 0.001)

        val manualBoostParams = EqualizerParameters(
            enabled = true,
            gainsDb = listOf(6f, 4f, 0f, 0f, -1f),
            preampMode = PreampMode.MANUAL,
            manualPreampDb = 3.5f
        )
        // Manual mode ignores boost and uses manualPreampDb
        assertEquals(3.5, TerminusEqualizerAudioProcessor.targetPreGainDb(manualBoostParams), 0.001)

        val disabledParams = EqualizerParameters(
            enabled = false,
            gainsDb = listOf(6f, 4f, 0f, 0f, -1f),
            preampMode = PreampMode.MANUAL,
            manualPreampDb = 3.5f
        )
        // Disabled -> 0.0 dB
        assertEquals(0.0, TerminusEqualizerAudioProcessor.targetPreGainDb(disabledParams), 0.001)
    }

    @Test
    fun manualPositivePreampKeepsLimiterActiveAndClampsSafely() {
        val sampleRate = 44100
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(
            EqualizerParameters(
                enabled = true,
                gainsDb = listOf(0f, 0f, 0f, 0f, 0f),
                preampMode = PreampMode.MANUAL,
                manualPreampDb = 6.0f // +6 dB boost
            )
        )
        processor.configureAndFlush(AudioProcessor.AudioFormat(sampleRate, 1, C.ENCODING_PCM_FLOAT))

        val frames = 1024
        val inBuffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        // 0.9FS sine wave
        for (i in 0 until frames) {
            val s = (0.9 * sin(2.0 * Math.PI * 1000.0 * i / sampleRate)).toFloat()
            inBuffer.putFloat(s)
        }
        inBuffer.flip()

        processor.queueInput(inBuffer)
        val out = processor.output

        var maxSample = 0.0f
        while (out.hasRemaining()) {
            val sample = out.float
            assertFalse("Sample must not be NaN", sample.isNaN())
            assertFalse("Sample must not be Infinite", sample.isInfinite())
            val absSample = abs(sample)
            if (absSample > maxSample) maxSample = absSample
            assertTrue("Sample must remain <= 1.0f due to tanh safety limiter (got $sample)", absSample <= 1.0f + 1e-4f)
        }
        // Since input was 0.9 and preamp was +6 dB (factor ~2.0), unconstrained would be ~1.8
        // With tanh limiter, it should be between 0.95 and 1.0
        assertTrue("Limiter should have compressed positive manual preamp to near ceiling (got $maxSample)", maxSample in 0.95f..1.0001f)
    }

    @Test
    fun nonZeroManualPreampDoesNotBypassFlatProcessing() {
        val processor = TerminusEqualizerAudioProcessor()
        processor.setParameters(
            EqualizerParameters(
                enabled = true,
                gainsDb = listOf(0f, 0f, 0f, 0f, 0f),
                preampMode = PreampMode.MANUAL,
                manualPreampDb = -6.0f // -6 dB attenuation
            )
        )
        processor.configureAndFlush(AudioProcessor.AudioFormat(44100, 1, C.ENCODING_PCM_FLOAT))

        val frames = 512
        val inBuffer = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            inBuffer.putFloat(1.0f)
        }
        inBuffer.flip()

        processor.queueInput(inBuffer)
        val out = processor.output
        assertTrue("Output buffer should have been produced", out.hasRemaining())
        val firstSample = out.float
        // With -6dB gain, 1.0f is scaled down
        assertTrue("Output should be attenuated, not bit-identical to 1.0f (got $firstSample)", firstSample < 0.99f)
    }
}
