package com.necroware.terminusplayer.playback

import androidx.media3.common.C
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
    private fun pcmChunk(): ByteBuffer {
        val frames = 220
        val buffer = ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (16_000 * sin(2 * PI * 440 * i / 44_100)).toInt().toShort()
            buffer.putShort(sample).putShort(sample)
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
}
