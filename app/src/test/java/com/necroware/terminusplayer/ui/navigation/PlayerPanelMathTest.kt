package com.necroware.terminusplayer.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.AnchoredDraggableState
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
    fun fractionClampsWhenOffsetExceedsCollapsedAnchorDuringRotation() {
        // When rotating from portrait (height 2200) to landscape (height 800),
        // offset might momentarily be 2200 while collapsedOffset is 800:
        assertEquals(0f, panelExpansionFraction(offset = 2200f, collapsedOffset = 800f), 0f)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun playerPanelStateRetainsCollapsedPositionAcrossOrientationChange() {
        val draggable = AnchoredDraggableState(
            initialValue = PlayerPanelValue.Collapsed,
            positionalThreshold = { it * 0.5f },
            velocityThreshold = { 100f },
            animationSpec = tween()
        )
        val state = PlayerPanelState(draggable)

        // Portrait layout
        state.updateAnchors(1800f)
        assertEquals(1800f, state.draggable.offset, 0.01f)
        assertEquals(0f, state.fraction, 0.01f)
        assertEquals(1f, miniBarAlpha(state.fraction), 0.01f)

        // Rotate to landscape
        state.updateAnchors(700f)
        assertEquals(700f, state.draggable.offset, 0.01f)
        assertEquals(0f, state.fraction, 0.01f)
        assertEquals(1f, miniBarAlpha(state.fraction), 0.01f)

        // Rotate back to portrait
        state.updateAnchors(1800f)
        assertEquals(1800f, state.draggable.offset, 0.01f)
        assertEquals(0f, state.fraction, 0.01f)
        assertEquals(1f, miniBarAlpha(state.fraction), 0.01f)
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
