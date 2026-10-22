package com.necroware.terminusplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabSwitchFrameTimingBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun tabSwitchCompilationNone() = benchmark(CompilationMode.None())

    @Test
    fun tabSwitchCompilationBaselineProfiles() =
        benchmark(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun benchmark(compilationMode: CompilationMode) {
        benchmarkRule.measureRepeated(
            packageName = "com.necroware.terminusplayer",
            metrics = listOf(FrameTimingMetric()),
            compilationMode = compilationMode,
            iterations = 5,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
            }
        ) {
            val tabs = listOf("HOME", "LIBRARY", "PLAYLISTS", "STATS", "SETTINGS")
            for (tab in tabs) {
                device.wait(Until.findObject(By.text(tab)), 3000L)?.click()
                device.waitForIdle()
            }
        }
    }
}
