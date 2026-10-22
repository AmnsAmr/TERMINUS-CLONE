package com.necroware.terminusplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Flings the Library SONGS list down and back up once per iteration.
 *
 * The list is found as the scrollable that contains song rows: the first scrollable on the screen
 * is the horizontal tab row, which the earlier By.scrollable(true) lookup sometimes flung instead.
 */
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
                requireObject(By.text("LIBRARY")).click()
                // The selected Library tab is remembered; always measure the full SONGS list.
                requireObject(By.text(SONGS_TAB)).click()
                songList()
                device.waitForIdle()
            }
        ) {
            val list = songList().visibleBounds
            val x = list.centerX()
            val low = list.top + list.height() * 4 / 5
            val high = list.top + list.height() / 5
            device.swipe(x, low, x, high, FLING_STEPS)
            device.waitForIdle()
            device.swipe(x, high, x, low, FLING_STEPS)
            device.waitForIdle()
        }
    }

    private fun MacrobenchmarkScope.songList(): UiObject2 =
        requireObject(By.scrollable(true).hasDescendant(By.res(SONG_ROW)))

    private fun MacrobenchmarkScope.requireObject(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), WAIT_MS) ?: error("Not found within $WAIT_MS ms: $selector")

    private companion object {
        const val SONGS_TAB = "[ SONGS ]"
        const val SONG_ROW = "song_row"
        const val WAIT_MS = 5_000L
        /** About 5 ms per step: a quick flick, so the list flings rather than drags. */
        const val FLING_STEPS = 8
    }
}
