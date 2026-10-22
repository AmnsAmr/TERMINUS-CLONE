package com.necroware.terminusplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class AudioSpectrumTest {

    // ---- band mapping ----

    @Test
    fun bandEdgesAreStrictlyIncreasingAndInsideTheSpectrum() {
        listOf(22_050, 44_100, 48_000, 96_000).forEach { rate ->
            val edges = logBandEdges(AudioSpectrum.BAND_COUNT, rate, AudioSpectrum.FFT_SIZE)
            assertEquals(AudioSpectrum.BAND_COUNT + 1, edges.size)
            assertTrue(edges.first() >= 1)
            assertTrue(edges.last() <= AudioSpectrum.FFT_SIZE / 2)
            for (i in 1 until edges.size) assertTrue("rate $rate edge $i", edges[i] > edges[i - 1])
        }
    }

    @Test
    fun upperBandsAreLogSpaced() {
        val rate = 48_000
        val binHz = rate.toFloat() / AudioSpectrum.FFT_SIZE
        val edges = logBandEdges(AudioSpectrum.BAND_COUNT, rate, AudioSpectrum.FFT_SIZE)
        // Low bands are widened to one bin each; above that the frequency ratio per band is constant.
        val ratios = (8 until AudioSpectrum.BAND_COUNT).map { i -> ln((edges[i + 1] * binHz).toDouble() / (edges[i] * binHz)) }
        val mean = ratios.average()
        ratios.forEach { assertEquals(mean, it, mean * 0.25) }
    }

    @Test
    fun aSineLandsInTheBandContainingItsFrequency() {
        val rate = 44_100
        val analyzer = SpectrumAnalyzer(AudioSpectrum.FFT_SIZE, AudioSpectrum.BAND_COUNT)
        val edges = logBandEdges(AudioSpectrum.BAND_COUNT, rate, AudioSpectrum.FFT_SIZE)
        val binHz = rate.toFloat() / AudioSpectrum.FFT_SIZE
        listOf(110f, 1_000f, 6_000f).forEach { hz ->
            val samples = FloatArray(AudioSpectrum.FFT_SIZE) { i -> 0.8f * sin(2 * PI * hz * i / rate).toFloat() }
            val bands = FloatArray(AudioSpectrum.BAND_COUNT)
            val level = analyzer.analyze(samples, rate, bands)
            val loudest = bands.indices.maxBy { bands[it] }
            val bin = (hz / binHz).toInt()
            assertTrue("$hz Hz in band $loudest", bin >= edges[loudest] - 1 && bin <= edges[loudest + 1])
            assertEquals(0.8f / kotlin.math.sqrt(2f), level, 0.02f)
        }
    }

    @Test
    fun silenceAnalysesToZero() {
        val analyzer = SpectrumAnalyzer(AudioSpectrum.FFT_SIZE, AudioSpectrum.BAND_COUNT)
        val bands = FloatArray(AudioSpectrum.BAND_COUNT) { 1f }
        assertEquals(0f, analyzer.analyze(FloatArray(AudioSpectrum.FFT_SIZE), 44_100, bands), 0f)
        bands.forEach { assertEquals(0f, it, 0f) }
    }

    // ---- throttling and gating ----

    private fun TestScope.newSpectrum(): AudioSpectrum =
        AudioSpectrum(backgroundScope, AudioSpectrum.FRAME_INTERVAL_MS).also {
            it.flush(44_100, 2, C.ENCODING_PCM_16BIT)
        }

    /** 5 ms of stereo PCM16 sine, little-endian like Android's audio pipeline. */
    private fun pcmChunk(): ByteBuffer = pcm16Chunk()

    private fun pcm16Chunk(
        freq: Double = 440.0,
        rate: Int = 44_100,
        amp: Float = 0.5f,
        channels: Int = 2,
        frames: Int = 220
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (amp * 32_767f * sin(2 * PI * freq * i / rate)).toInt().toShort()
            for (c in 0 until channels) {
                buffer.putShort(sample)
            }
        }
        buffer.flip()
        return buffer
    }

    private fun pcmFloatChunk(
        freq: Double = 440.0,
        rate: Int = 44_100,
        amp: Float = 0.5f,
        channels: Int = 2,
        frames: Int = 220
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (amp * sin(2 * PI * freq * i / rate)).toFloat()
            for (c in 0 until channels) {
                buffer.putFloat(sample)
            }
        }
        buffer.flip()
        return buffer
    }

    @Test
    fun publishesAboutThirtyFramesPerSecondWhileFedFasterThanThat() = runTest {
        val spectrum = newSpectrum()
        val frames = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { spectrum.frames.toList(frames) }
        spectrum.setPlaying(true)
        runCurrent()

        val chunk = pcmChunk()
        repeat(200) { // one virtual second of audio arriving every 5 ms
            spectrum.handleBuffer(chunk.duplicate())
            advanceTimeBy(5)
        }
        runCurrent()

        val published = frames.filter { it.sequence > 0 }
        assertTrue("published ${published.size}", published.size in 25..31)
        assertTrue(published.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 })
        assertTrue(published.last().bands.any { it > 0f })
    }

    @Test
    fun nothingIsCapturedOrPublishedWithoutACollector() = runTest {
        val spectrum = newSpectrum()
        spectrum.setPlaying(true)
        runCurrent()
        repeat(40) {
            spectrum.handleBuffer(pcmChunk())
            advanceTimeBy(5)
        }
        assertSame(SpectrumFrame.Silent, spectrum.frames.value)
    }

    @Test
    fun pausingStopsPublishingAndResetsToSilence() = runTest {
        val spectrum = newSpectrum()
        val frames = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { spectrum.frames.toList(frames) }
        spectrum.setPlaying(true)
        runCurrent()
        repeat(40) {
            spectrum.handleBuffer(pcmChunk())
            advanceTimeBy(5)
        }
        spectrum.setPlaying(false)
        runCurrent()
        assertSame(SpectrumFrame.Silent, spectrum.frames.value)

        val countAtPause = frames.size
        repeat(40) {
            spectrum.handleBuffer(pcmChunk()) // ignored: not capturing while paused
            advanceTimeBy(5)
        }
        assertEquals(countAtPause, frames.size)
    }

    @Test
    fun noNewAudioMeansNoNewFrames() = runTest {
        val spectrum = newSpectrum()
        val frames = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { spectrum.frames.toList(frames) }
        spectrum.setPlaying(true)
        runCurrent()
        spectrum.handleBuffer(pcmChunk())
        advanceTimeBy(AudioSpectrum.FRAME_INTERVAL_MS * 10)
        // One frame for the single buffer, then the loop idles (e.g. while buffering).
        assertEquals(1, frames.count { it.sequence > 0 })
    }

    // ---- beat detection ----

    @Test
    fun beatFiresOnABassJumpAndRespectsTheCooldown() {
        val detector = BeatDetector()
        val quiet = FloatArray(AudioSpectrum.BAND_COUNT) { 0.2f }
        val loud = FloatArray(AudioSpectrum.BAND_COUNT) { if (it < 3) 0.9f else 0.2f }
        // Settle the running average on a quiet passage (the first frame may count as an onset).
        repeat(60) { detector.update(quiet) }
        assertFalse(detector.update(quiet))
        assertTrue(detector.update(loud))
        // Still loud on the next frames: inside the cooldown.
        assertFalse(detector.update(loud))
        assertFalse(detector.update(loud))
    }

    // ---- float PCM and equalizer pipeline integration ----

    @Test
    fun sine440HzLandsInSameBandForPcm16AndFloatWithSimilarLevel() = runTest {
        // Run 16-bit PCM test
        val spec16 = AudioSpectrum(backgroundScope, AudioSpectrum.FRAME_INTERVAL_MS).also {
            it.flush(44_100, 2, C.ENCODING_PCM_16BIT)
            it.setPlaying(true)
        }
        val frames16 = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { spec16.frames.toList(frames16) }
        runCurrent()

        val chunk16 = pcm16Chunk(freq = 440.0, amp = 0.5f, channels = 2)
        repeat(30) {
            spec16.handleBuffer(chunk16.duplicate())
            advanceTimeBy(5)
        }
        runCurrent()
        val frame16 = frames16.last { it.sequence > 0 }
        val band16 = frame16.bands.indices.maxBy { frame16.bands[it] }
        val level16 = frame16.level

        // Run Float PCM test (stereo)
        val specFloat = AudioSpectrum(backgroundScope, AudioSpectrum.FRAME_INTERVAL_MS).also {
            it.flush(44_100, 2, C.ENCODING_PCM_FLOAT)
            it.setPlaying(true)
        }
        val framesFloat = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { specFloat.frames.toList(framesFloat) }
        runCurrent()

        val chunkFloat = pcmFloatChunk(freq = 440.0, amp = 0.5f, channels = 2)
        repeat(30) {
            specFloat.handleBuffer(chunkFloat.duplicate())
            advanceTimeBy(5)
        }
        runCurrent()
        val frameFloat = framesFloat.last { it.sequence > 0 }
        val bandFloat = frameFloat.bands.indices.maxBy { frameFloat.bands[it] }
        val levelFloat = frameFloat.level

        assertEquals("Loudest band index must match between 16-bit and float PCM", band16, bandFloat)
        assertEquals("RMS level must be similar between 16-bit and float PCM", level16, levelFloat, 0.05f)
        assertTrue("RMS level should be non-zero", levelFloat > 0.1f)
    }

    @Test
    fun monoAndMultichannelFloatBuffersDoNotCrash() = runTest {
        // Mono float
        val specMono = AudioSpectrum(backgroundScope, AudioSpectrum.FRAME_INTERVAL_MS).also {
            it.flush(44_100, 1, C.ENCODING_PCM_FLOAT)
            it.setPlaying(true)
        }
        val framesMono = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { specMono.frames.toList(framesMono) }
        runCurrent()

        val chunkMono = pcmFloatChunk(freq = 440.0, channels = 1)
        repeat(20) {
            specMono.handleBuffer(chunkMono.duplicate())
            advanceTimeBy(5)
        }
        runCurrent()
        val frameMono = framesMono.last { it.sequence > 0 }
        assertTrue("Mono published valid level", frameMono.level > 0f)

        // 6-channel 5.1 surround float
        val specSurround = AudioSpectrum(backgroundScope, AudioSpectrum.FRAME_INTERVAL_MS).also {
            it.flush(44_100, 6, C.ENCODING_PCM_FLOAT)
            it.setPlaying(true)
        }
        val framesSurround = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { specSurround.frames.toList(framesSurround) }
        runCurrent()

        val chunkSurround = pcmFloatChunk(freq = 440.0, channels = 6)
        repeat(20) {
            specSurround.handleBuffer(chunkSurround.duplicate())
            advanceTimeBy(5)
        }
        runCurrent()
        val frameSurround = framesSurround.last { it.sequence > 0 }
        assertTrue("Multichannel published valid level", frameSurround.level > 0f)
    }

    @Test
    fun equalizerChainedIntoSpectrumSinkReflectsBoostedBand() = runTest {
        val rate = 44_100
        val channels = 2
        val eq = TerminusEqualizerAudioProcessor()
        eq.configure(AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_FLOAT))
        eq.flush()

        val spectrum = AudioSpectrum(backgroundScope, AudioSpectrum.FRAME_INTERVAL_MS).also {
            it.flush(rate, channels, C.ENCODING_PCM_FLOAT)
            it.setPlaying(true)
        }
        val frames = mutableListOf<SpectrumFrame>()
        backgroundScope.launch { spectrum.frames.toList(frames) }
        runCurrent()

        var sampleIndex = 0L
        fun feedContinuousChunk(freq: Double = 60.0, amp: Float = 0.2f) {
            val framesCount = 220
            val buffer = ByteBuffer.allocate(framesCount * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until framesCount) {
                val sample = (amp * sin(2.0 * Math.PI * freq * (sampleIndex++) / rate)).toFloat()
                for (c in 0 until channels) buffer.putFloat(sample)
            }
            buffer.flip()
            eq.queueInput(buffer)
            val output = eq.output
            spectrum.handleBuffer(output)
        }

        // Part 1: Feed continuous 60 Hz with EQ flat
        repeat(50) {
            feedContinuousChunk(60.0, 0.2f)
            advanceTimeBy(5)
        }
        runCurrent()
        val flatFrame = frames.last { it.sequence > 0 }
        val flatLevel = flatFrame.level

        // Part 2: Boost Band 0 (60 Hz) by +12 dB
        eq.setParameters(EqualizerParameters(enabled = true, gainsDb = listOf(12f, 0f, 0f, 0f, 0f)))
        repeat(50) {
            feedContinuousChunk(60.0, 0.2f)
            advanceTimeBy(5)
        }
        runCurrent()
        val boostedFrame = frames.last { it.sequence > 0 }
        val boostedLevel = boostedFrame.level

        assertTrue(
            "Spectrum level with +12 dB boost ($boostedLevel) must be higher than flat ($flatLevel)",
            boostedLevel > flatLevel * 1.5f
        )
    }
}
