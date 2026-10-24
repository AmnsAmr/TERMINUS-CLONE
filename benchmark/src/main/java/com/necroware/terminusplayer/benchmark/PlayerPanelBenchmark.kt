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
 * Frame timing for the persistent player panel. Needs at least one on-device track
 * (Library -> [ LOCAL MUSIC ]); playback always starts from there so no journey depends on the network.
 *
 * Journeys (each iteration):
 *  (a) tap mini bar -> panel fully open -> swipe down on the art -> panel collapsed, x10
 *  (b) in the expanded player: skip next x10, then previous x10
 *  (c) Warp Visualizer art + FULL motion, expanded, playing for 20 s
 *
 * (a) and (b) pin ALBUM ART + FULL motion so later phases compare like with like.
 * Setup changes the app's Settings (art style, motion) through the UI; it does not restore them.
 * Tags come from app/.../ui/TestTags.kt (exposed via testTagsAsResourceId).
 */
@RunWith(AndroidJUnit4::class)
class PlayerPanelBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test fun openCloseCompilationNone() = openClose(CompilationMode.None())
    @Test fun openCloseCompilationBaselineProfiles() = openClose(partial())

    @Test fun skipCompilationNone() = skip(CompilationMode.None())
    @Test fun skipCompilationBaselineProfiles() = skip(partial())

    @Test fun visualizerCompilationNone() = visualizer(CompilationMode.None())
    @Test fun visualizerCompilationBaselineProfiles() = visualizer(partial())

    private fun partial() = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require)

    // (a)
    private fun openClose(mode: CompilationMode) = measure(mode, ART_ALBUM, expanded = false) {
        repeat(CYCLES) {
            requireObject(By.res(MINI_PLAYER)).click()
            awaitExpanded()
            swipeDownOnArt()
            awaitCollapsed()
        }
    }

    // (b)
    private fun skip(mode: CompilationMode) = measure(mode, ART_ALBUM, expanded = true) {
        repeat(CYCLES) {
            requireObject(By.res(TRANSPORT_NEXT)).click()
            Thread.sleep(SKIP_SETTLE_MS)
        }
        repeat(CYCLES) {
            requireObject(By.res(TRANSPORT_PREVIOUS)).click()
            Thread.sleep(SKIP_SETTLE_MS)
        }
    }

    // (c)
    private fun visualizer(mode: CompilationMode) = measure(mode, ART_VISUALIZER, expanded = true) {
        Thread.sleep(VISUALIZER_PLAY_MS)
    }

    private fun measure(
        mode: CompilationMode,
        artStyle: String,
        expanded: Boolean,
        block: MacrobenchmarkScope.() -> Unit
    ) {
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
                collapseIfExpanded()
                if (!configured) {
                    configureSettings(artStyle)
                    startLocalPlayback()
                    configured = true
                }
                ensureTrackLoaded()
                if (expanded) {
                    requireObject(By.res(MINI_PLAYER)).click()
                    awaitExpanded()
                    ensurePlaying()
                }
            },
            measureBlock = block
        )
    }

    private fun MacrobenchmarkScope.configureSettings(artStyle: String) {
        requireObject(By.text("SETTINGS")).click()
        selectSettingsOption(section = "ANIMATIONS", option = "motion_option_FULL")
        selectSettingsOption(section = "CRT & AESTHETICS", option = "art_style_option_$artStyle")
    }

    private fun MacrobenchmarkScope.ensureTrackLoaded() {
        if (device.wait(Until.hasObject(By.res(MINI_PLAYER)), SHORT_WAIT_MS)) return
        requireObject(By.text("LIBRARY")).click()
        requireObject(By.res(SONG_ROW)).click()
        requireObject(By.res(MINI_PLAYER))
    }

    private fun MacrobenchmarkScope.ensurePlaying() {
        val button = requireObject(By.res(TRANSPORT_PLAY_PAUSE))
        if (!showsPause(button)) {
            button.click()
            val deadline = System.currentTimeMillis() + WAIT_MS
            while (!showsPause(requireObject(By.res(TRANSPORT_PLAY_PAUSE)))) {
                check(System.currentTimeMillis() < deadline) { "Playback did not start" }
                Thread.sleep(POLL_MS)
            }
        }
    }

    private fun MacrobenchmarkScope.collapseIfExpanded() {
        if (device.hasObject(By.res(NOW_PLAYING_ART)) && !device.hasObject(By.res(MINI_PLAYER))) {
            device.pressBack()
            awaitCollapsed()
        }
    }

    private fun MacrobenchmarkScope.awaitExpanded() {
        // The mini bar leaves composition only once the panel is fully open.
        requireObject(By.res(NOW_PLAYING_ART))
        check(device.wait(Until.gone(By.res(MINI_PLAYER)), WAIT_MS)) { "Player panel did not fully expand" }
        settle()
    }

    private fun MacrobenchmarkScope.awaitCollapsed() {
        // The full player leaves composition only once the panel is fully closed.
        check(device.wait(Until.gone(By.res(NOW_PLAYING_ART)), WAIT_MS)) { "Player panel did not collapse" }
        requireObject(By.res(MINI_PLAYER))
        settle()
    }

    /**
     * Fixed pause after the panel reaches an end. On the S21 FE, a tap or swipe sent as soon as the
     * mini bar/art appeared or disappeared was sometimes ignored (waitForIdle did not help); by hand,
     * input 0.6 s after a tap worked and 0.3 s did not. Frames are only produced while something
     * animates, so this idle time does not dilute the frame metrics.
     */
    private fun settle() {
        Thread.sleep(PANEL_SETTLE_MS)
    }

    /**
     * Uses the shell `input swipe` command: on the S21 FE (Android 16) UiDevice.swipe on the art
     * intermittently failed to move the panel, while the same swipe from the shell collapsed it
     * every time it was tried by hand.
     */
    private fun MacrobenchmarkScope.swipeDownOnArt() {
        val art = requireObject(By.res(NOW_PLAYING_ART)).visibleBounds
        val endY = (device.displayHeight * 0.9f).toInt()
        device.executeShellCommand("input swipe ${art.centerX()} ${art.centerY()} ${art.centerX()} $endY $SWIPE_DURATION_MS")
    }

    private companion object {
        const val ITERATIONS = 5
        const val CYCLES = 10
        const val SHORT_WAIT_MS = 1_500L
        const val PANEL_SETTLE_MS = 1_000L
        const val POLL_MS = 100L
        /** Same duration as the hand-tested swipe that collapsed the panel. */
        const val SWIPE_DURATION_MS = 150
        /** Longer than the FULL track-swap transition (260 ms token), so each skip settles inside the window. */
        const val SKIP_SETTLE_MS = 800L
        const val VISUALIZER_PLAY_MS = 20_000L
        const val ART_ALBUM = "STANDARD"
        const val ART_VISUALIZER = "VISUALIZER"

        // Mirrors app/.../ui/TestTags.kt (shared tags live in BenchmarkUi.kt)
        const val NOW_PLAYING_ART = "now_playing_art"
        const val TRANSPORT_PREVIOUS = "transport_previous"
        const val TRANSPORT_PLAY_PAUSE = "transport_play_pause"
        const val TRANSPORT_NEXT = "transport_next"
    }
}
