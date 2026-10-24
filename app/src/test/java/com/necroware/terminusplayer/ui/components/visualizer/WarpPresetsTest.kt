package com.necroware.terminusplayer.ui.components.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WarpPresetsTest {

    @Test
    fun sixDistinctPresetsWithUniqueIds() {
        assertEquals(6, WarpPresets.size)
        assertEquals(6, WarpPresets.map { it.id }.toSet().size)
        // Distinct looks, not just names.
        assertEquals(6, WarpPresets.map { it.copy(id = "", label = "") }.toSet().size)
    }

    @Test
    fun unknownPresetIdFallsBackToTheFirst() {
        assertEquals(0, warpPresetIndex(null))
        assertEquals(0, warpPresetIndex("REMOVED"))
        assertEquals(2, warpPresetIndex(WarpPresets[2].id))
    }

    @Test
    fun easingRunsFromZeroToOne() {
        assertEquals(0f, easeInOut(0f), 0f)
        assertEquals(0.5f, easeInOut(0.5f), 1e-6f)
        assertEquals(1f, easeInOut(1f), 0f)
        assertEquals(1f, easeInOut(3f), 0f)
    }

    @Test
    fun blendedTargetsHitBothEndsAndTheMiddle() {
        val levels = BandLevels().apply { set(FloatArray(16) { 0.5f }, 0.4f) }
        val a = WarpTargets().apply { compute(WarpPresets[0], levels, 0f) }
        val b = WarpTargets().apply { compute(WarpPresets[1], levels, 0f) }
        val mix = WarpTargets()
        mix.lerp(a, b, 0f)
        assertEquals(a.zoom, mix.zoom, 0f)
        assertEquals(a.warpFrequency, mix.warpFrequency, 0f)
        mix.lerp(a, b, 1f)
        assertEquals(b.decay, mix.decay, 0f)
        assertEquals(b.waveScale, mix.waveScale, 0f)
        mix.lerp(a, b, 0.5f)
        assertEquals((a.angle + b.angle) / 2f, mix.angle, 1e-6f)
        assertEquals((a.hue + b.hue) / 2f, mix.hue, 1e-6f)
    }

    @Test
    fun autoCycleWaitsForTheIntervalThenAStrongBeat() {
        val c = PresetCycler(0).apply { intervalSeconds = 15 }
        c.update(10f, strongBeat = true)
        assertEquals(0, c.index)
        c.update(16f, strongBeat = false)
        assertEquals(0, c.index)
        c.update(16.5f, strongBeat = true)
        assertEquals(1, c.index)
        assertEquals(0f, c.blend, 0f)
    }

    @Test
    fun withoutAStrongBeatTheTimerSwitchesAnyway() {
        val c = PresetCycler(0).apply { intervalSeconds = 15 }
        c.update(15f + BEAT_WAIT_SECONDS - 0.1f, strongBeat = false)
        assertEquals(0, c.index)
        c.update(15f + BEAT_WAIT_SECONDS, strongBeat = false)
        assertEquals(1, c.index)
    }

    @Test
    fun blendTakesThreeSecondsThenSettles() {
        val c = PresetCycler(0)
        c.next(100f)
        c.update(100f + PRESET_BLEND_SECONDS / 2, false)
        assertEquals(0.5f, c.blend, 1e-5f)
        assertSame(WarpPresets[0], c.from)
        c.update(100f + PRESET_BLEND_SECONDS, false)
        assertEquals(1f, c.blend, 0f)
        assertSame(WarpPresets[1], c.from)
    }

    @Test
    fun switchingMidBlendContinuesFromWhatIsShown() {
        val c = PresetCycler(0)
        c.next(0f)
        c.update(PRESET_BLEND_SECONDS / 2, false)
        c.next(PRESET_BLEND_SECONDS / 2)
        assertEquals(2, c.index)
        val frozen = c.from
        val expected = (WarpPresets[0].decay + WarpPresets[1].decay) / 2f
        assertEquals(expected, frozen.decay, 1e-5f)
        assertEquals(0f, c.blend, 0f)
    }

    @Test
    fun lockStopsAutoCycleAndTaps() {
        val c = PresetCycler(3).apply { locked = true; intervalSeconds = 15 }
        c.update(100f, strongBeat = true)
        assertFalse(c.next(100f))
        assertEquals(3, c.index)
    }

    @Test
    fun autoCycleOffStillAllowsTaps() {
        val c = PresetCycler(5).apply { autoCycle = false; intervalSeconds = 15 }
        c.update(100f, strongBeat = true)
        assertEquals(5, c.index)
        assertTrue(c.next(100f))
        assertEquals(0, c.index) // wraps around
    }

    @Test
    fun boundedCacheDropsTheLeastRecentlyUsed() {
        val cache = BoundedCache<Long, String>(2)
        cache[1L] = "a"
        cache[2L] = "b"
        cache[1L] // touch 1, so 2 is now the eldest
        cache[3L] = "c"
        assertEquals(2, cache.size)
        assertNull(cache[2L])
        assertEquals("a", cache[1L])
        assertEquals("c", cache[3L])
    }
}
