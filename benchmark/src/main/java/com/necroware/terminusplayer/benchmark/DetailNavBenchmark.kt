package com.necroware.terminusplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame timing for opening a detail page and coming back: Library ALBUMS -> first album -> back, x10
 * per iteration. This is where the bottom chrome hides and returns.
 *
 * Setup pins FULL motion and loads a paused on-device track, so the mini bar is present (it stays on
 * detail pages from Phase 4) without playback producing frames of its own.
 * Changes the app's Settings through the UI; it does not restore them.
 */
@RunWith(AndroidJUnit4::class)
class DetailNavBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test fun albumBackCompilationNone() = albumBack(CompilationMode.None())
    @Test fun albumBackCompilationBaselineProfiles() =
        albumBack(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun albumBack(mode: CompilationMode) {
        var configured = false
        benchmarkRule.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = mode,
            iterations = ITERATIONS,
            startupMode = StartupMode.WARM,
            setupBlock = {
                pressHome()
                startActivityAndWait()
                if (!configured) {
                    requireObject(By.text("SETTINGS")).click()
                    selectSettingsOption(section = "ANIMATIONS", option = "motion_option_FULL")
                    if (!device.wait(Until.hasObject(By.res(MINI_PLAYER)), SHORT_WAIT_MS)) startLocalPlayback()
                    configured = true
                }
                ensurePaused()
                requireObject(By.text("LIBRARY")).click()
                requireObject(By.text(ALBUMS_TAB)).click()
                requireObject(By.res(ALBUM_ROW))
                settle()
            }
        ) {
            repeat(CYCLES) {
                requireObject(By.res(ALBUM_ROW)).click()
                requireObject(By.res(ALBUM_DETAIL_LIST))
                settle()
                device.pressBack()
                check(device.wait(Until.gone(By.res(ALBUM_DETAIL_LIST)), WAIT_MS)) { "Album page did not close" }
                requireObject(By.res(ALBUM_ROW))
                settle()
            }
        }
    }

    private fun MacrobenchmarkScope.ensurePaused() {
        val button = requireObject(By.res(MINI_PLAY_PAUSE))
        if (showsPause(button)) {
            button.click()
            device.waitForIdle()
        }
    }

    /**
     * Same reason as PlayerPanelBenchmark.settle(): input sent the moment a screen appears was
     * sometimes ignored on the S21 FE. No frames are produced while idle, so this does not dilute
     * the metrics.
     */
    private fun settle() {
        Thread.sleep(SETTLE_MS)
    }

    private companion object {
        const val ITERATIONS = 5
        const val CYCLES = 10
        const val SHORT_WAIT_MS = 1_500L
        const val SETTLE_MS = 1_000L
        const val ALBUMS_TAB = "[ ALBUMS ]"

        // Mirrors app/.../ui/TestTags.kt
        const val ALBUM_ROW = "album_row"
        const val ALBUM_DETAIL_LIST = "album_detail_list"
    }
}
