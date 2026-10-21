package com.necroware.terminusplayer.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPanelMathTest {
    @Test
    fun fractionIsZeroAtTheCollapsedAnchorAndOneAtTheTop() {
        assertEquals(0f, panelExpansionFraction(offset = 1600f, collapsedOffset = 1600f), 0f)
        assertEquals(1f, panelExpansionFraction(offset = 0f, collapsedOffset = 1600f), 0f)
        assertEquals(0.5f, panelExpansionFraction(offset = 800f, collapsedOffset = 1600f), 1e-6f)
    }

    @Test
    fun fractionStaysInRangeWhenASpringOvershoots() {
        assertEquals(1f, panelExpansionFraction(offset = -40f, collapsedOffset = 1600f), 0f)
        assertEquals(0f, panelExpansionFraction(offset = 1650f, collapsedOffset = 1600f), 0f)
    }

    @Test
    fun fractionWithoutMeasuredAnchorsFollowsTheOffsetEdge() {
        assertEquals(1f, panelExpansionFraction(offset = 0f, collapsedOffset = 0f), 0f)
        assertEquals(0f, panelExpansionFraction(offset = 10f, collapsedOffset = 0f), 0f)
    }

    @Test
    fun miniFadesOutBeforeTheFullContentFadesIn() {
        assertEquals(1f, miniBarAlpha(0f), 0f)
        assertEquals(0f, miniBarAlpha(MiniFadeEnd), 0f)
        assertEquals(0f, fullContentAlpha(MiniFadeEnd), 0f)
        assertEquals(1f, fullContentAlpha(1f), 0f)
        // Never both partly visible at once: the two curves do not overlap.
        listOf(0.1f, 0.2f, 0.3f, 0.4f, 0.6f, 0.9f).forEach { f ->
            assertTrue(miniBarAlpha(f) == 0f || fullContentAlpha(f) == 0f)
        }
    }

    @Test
    fun titleCrossfadeMirrorsTheMiniFade() {
        listOf(0f, 0.1f, 0.2f, MiniFadeEnd, 0.8f).forEach { f ->
            assertEquals(1f, miniBarAlpha(f) + sharedTitleAlpha(f), 1e-6f)
        }
    }

    @Test
    fun sharedBoundsStartOnTheMiniElementAndEndInPlace() {
        // Full art laid out at x=48, mini art at x=10.
        assertEquals(-38f, sharedBoundsTranslation(fromEdge = 10f, toEdge = 48f, fraction = 0f), 0f)
        assertEquals(-19f, sharedBoundsTranslation(fromEdge = 10f, toEdge = 48f, fraction = 0.5f), 1e-6f)
        assertEquals(0f, sharedBoundsTranslation(fromEdge = 10f, toEdge = 48f, fraction = 1f), 0f)

        val miniOverFull = 34f / 300f
        assertEquals(miniOverFull, sharedBoundsScale(miniOverFull, 0f), 0f)
        assertEquals(1f, sharedBoundsScale(miniOverFull, 1f), 1e-6f)
    }
}
