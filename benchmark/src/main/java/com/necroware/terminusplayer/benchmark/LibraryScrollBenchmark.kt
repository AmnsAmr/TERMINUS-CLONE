package com.necroware.terminusplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LibraryScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun scrollCompilationNone() = benchmark(CompilationMode.None())

    @Test
    fun scrollCompilationBaselineProfiles() =
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
                device.wait(Until.findObject(By.text("LIBRARY")), 3000L)?.click()
                device.waitForIdle()
            }
        ) {
            val scrollable = device.wait(Until.findObject(By.scrollable(true)), 3000L)
            if (scrollable != null) {
                scrollable.setGestureMargin(device.displayWidth / 5)
                scrollable.fling(Direction.DOWN)
                device.waitForIdle()
                scrollable.fling(Direction.UP)
                device.waitForIdle()
            } else {
                val centerX = device.displayWidth / 2
                val startY = (device.displayHeight * 0.75).toInt()
                val endY = (device.displayHeight * 0.25).toInt()
                device.swipe(centerX, startY, centerX, endY, 10)
                device.waitForIdle()
                device.swipe(centerX, endY, centerX, startY, 10)
                device.waitForIdle()
            }
        }
    }
}
