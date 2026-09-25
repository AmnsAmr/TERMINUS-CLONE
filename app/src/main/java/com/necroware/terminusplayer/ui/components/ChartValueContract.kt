package com.necroware.terminusplayer.ui.components

internal fun requireNonNegativeChartValues(values: Iterable<Float>, chartName: String) {
    require(values.all { it.isFinite() && it >= 0f }) {
        "$chartName only accepts finite, non-negative values"
    }
}

internal fun safeGridLineCount(gridLines: Int): Int = gridLines.coerceAtLeast(1)
