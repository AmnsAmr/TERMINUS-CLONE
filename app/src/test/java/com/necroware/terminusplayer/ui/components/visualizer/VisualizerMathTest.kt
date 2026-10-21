package com.necroware.terminusplayer.ui.components.visualizer

import com.necroware.terminusplayer.playback.AudioSpectrum
import com.necroware.terminusplayer.playback.SpectrumFrame
import com.necroware.terminusplayer.playback.downsampleWaveform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualizerMathTest {
    @Test
    fun resamplingSixteenBandsToTwelveKeepsTheLoudestOverlap() {
        val source = FloatArray(16) { it / 15f }
        val target = FloatArray(12)
        resampleBands(source, target)
        // Rising input stays rising, and the top bar carries the loudest band.
        for (i in 1 until target.size) assertTrue(target[i] >= target[i - 1])
        assertEquals(1f, target.last(), 0f)
        assertEquals(source[0], target[0], 0f)
        // Every source band lands in some bar: a lone loud band is never dropped.
        for (loud in source.indices) {
            val single = FloatArray(16).also { it[loud] = 1f }
            resampleBands(single, target)
            assertEquals("band $loud", 1f, target.max(), 0f)
        }
    }

    @Test
    fun resamplingEmptyInputClearsTheBars() {
        val target = FloatArray(12) { 1f }
        resampleBands(FloatArray(0), target)
        assertArrayEquals(FloatArray(12), target, 0f)
    }

    @Test
    fun meterScaleCoversFortyEightDecibels() {
        assertEquals(0f, meterLevel(0f), 0f)
        assertEquals(1f, meterLevel(1f), 1e-6f)
        assertEquals(0.5f, meterLevel(0.0631f), 0.01f) // -24 dB
        assertEquals(0f, meterLevel(0.001f), 0f)       // below -48 dB
    }

    @Test
    fun waveformIsEvenlySampledAndPeakIsTheLargestMagnitude() {
        val window = FloatArray(1024) { i -> if (i == 500) -0.9f else (i % 16) / 32f }
        val out = FloatArray(AudioSpectrum.WAVEFORM_POINTS)
        val peak = downsampleWaveform(window, out)
        assertEquals(0.9f, peak, 0f)
        assertEquals(window[0], out[0], 0f)
        assertEquals(window[16], out[1], 0f)
    }

    @Test
    fun perFrameRatesScaleWithFrameTime() {
        assertEquals(0.94f, perFrameFactor(0.94f, 1f / 60f), 1e-5f)
        // Two 120 Hz frames equal one 60 Hz frame.
        val half = perFrameFactor(0.94f, 1f / 120f)
        assertEquals(0.94f, half * half, 1e-5f)
    }

    private fun frame(bands: FloatArray, beat: Boolean, sequence: Long) = SpectrumFrame(
        bands = bands,
        level = 0.3f,
        peak = 0.5f,
        waveform = FloatArray(AudioSpectrum.WAVEFORM_POINTS),
        beat = beat,
        sequence = sequence
    )

    @Test
    fun bassPushesZoomAndABeatPulsesOncePerFrame() {
        val params = WarpParams()
        val quiet = FloatArray(AudioSpectrum.BAND_COUNT)
        val bassy = FloatArray(AudioSpectrum.BAND_COUNT) { if (it < 3) 1f else 0f }
        repeat(60) { params.update(frame(quiet, false, it + 1L), 1f / 60f) }
        val restingZoom = params.zoom
        repeat(60) { params.update(frame(bassy, false, 100L + it), 1f / 60f) }
        assertTrue(params.zoom > restingZoom)

        params.update(frame(bassy, beat = true, sequence = 500L), 1f / 60f)
        val afterBeat = params.pulse
        assertTrue(afterBeat > 0.9f)
        // Rendering faster than frames arrive must not re-trigger the same beat.
        params.update(frame(bassy, beat = true, sequence = 500L), 1f / 60f)
        assertTrue(params.pulse < afterBeat)
    }

    @Test
    fun trebleDrivesWarpAndSilenceSettlesIt() {
        val params = WarpParams()
        val bright = FloatArray(AudioSpectrum.BAND_COUNT) { if (it >= 10) 1f else 0f }
        repeat(120) { params.update(frame(bright, false, it + 1L), 1f / 60f) }
        assertEquals(0.9f, params.warp, 0.02f)
        repeat(240) { params.update(SpectrumFrame.Silent, 1f / 60f) }
        assertEquals(0f, params.warp, 0.02f)
    }
}
