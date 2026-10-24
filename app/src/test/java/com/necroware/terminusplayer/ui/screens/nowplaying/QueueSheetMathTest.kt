package com.necroware.terminusplayer.ui.screens.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueSheetMathTest {
    @Test
    fun aDraggedRowMovesOncePastHalfARow() {
        val row = 100f
        assertEquals(0, reorderSteps(49f, row))
        assertEquals(1, reorderSteps(50f, row))
        assertEquals(1, reorderSteps(149f, row))
        assertEquals(2, reorderSteps(150f, row))
        assertEquals(0, reorderSteps(-49f, row))
        assertEquals(-1, reorderSteps(-50f, row))
        assertEquals(-2, reorderSteps(-160f, row))
        assertEquals(0, reorderSteps(500f, 0f))
    }
}
