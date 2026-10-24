package com.necroware.terminusplayer.ui.components

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPauseGlyphTest {

    private val w = 100f
    private val h = 60f

    @Test
    fun zeroDrawsTheChevronMeetingAtTheRightHandPoint() {
        assertArrayEquals(
            floatArrayOf(0f, 0f, w, h / 2, 0f, h, w, h / 2),
            chevronToBars(0f, w, h),
            1e-4f
        )
    }

    @Test
    fun oneDrawsTwoFullHeightVerticalBars() {
        val p = chevronToBars(1f, w, h)
        // Each stroke is vertical (same x at both ends) and spans the full height.
        assertEquals(p[0], p[2], 1e-4f)
        assertEquals(p[4], p[6], 1e-4f)
        assertEquals(h, kotlin.math.abs(p[3] - p[1]), 1e-4f)
        assertEquals(h, kotlin.math.abs(p[7] - p[5]), 1e-4f)
        // Left bar left of the right bar, both inside the glyph box.
        assertTrue(p[0] in 0f..w && p[4] in 0f..w && p[0] < p[4])
    }

    @Test
    fun outOfRangeProgressIsClamped() {
        assertArrayEquals(chevronToBars(0f, w, h), chevronToBars(-0.5f, w, h), 1e-4f)
        assertArrayEquals(chevronToBars(1f, w, h), chevronToBars(1.7f, w, h), 1e-4f)
    }
}
