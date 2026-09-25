package com.necroware.terminusplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChartValueContractTest {
    @Test
    fun chartValuesMustBeFiniteAndNonNegative() {
        requireNonNegativeChartValues(listOf(0f, 2f), "test")
        assertThrows(IllegalArgumentException::class.java) {
            requireNonNegativeChartValues(listOf(1f, -1f), "test")
        }
        assertThrows(IllegalArgumentException::class.java) {
            requireNonNegativeChartValues(listOf(Float.NaN), "test")
        }
    }

    @Test
    fun gridLinesNeverDivideByZero() {
        assertEquals(1, safeGridLineCount(0))
        assertEquals(1, safeGridLineCount(-3))
        assertEquals(4, safeGridLineCount(4))
    }
}
