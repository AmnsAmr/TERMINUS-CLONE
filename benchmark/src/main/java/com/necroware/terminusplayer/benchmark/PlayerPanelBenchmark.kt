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
        selectOption(section = "ANIMATIONS", option = "motion_option_FULL")
        selectOption(section = "CRT & AESTHETICS", option = "art_style_option_$artStyle")
    }

    /**
     * Expands [section] if it is collapsed and picks [option]. The section is left open: collapsing
     * it again would mean scrolling back to a header that may be only partly on screen.
     */
    private fun MacrobenchmarkScope.selectOption(section: String, option: String) {
        // BySelector builders mutate in place, so build a fresh selector for every lookup.
        fun header() = By.res(settingsSection(section))
        // Open/closed state can survive in the app, so read it from the header's marker.
        if (scrollTo(header()).hasObject(By.text(SECTION_COLLAPSED))) {
            scrollTo(header()).click()
            check(device.wait(Until.hasObject(header().hasDescendant(By.text(SECTION_EXPANDED))), WAIT_MS)) {
                "Settings section $section did not expand"
            }
        }
        scrollTo(By.res(option)).click()
        device.waitForIdle()
    }

    /** Scrolls the Settings list until [selector] is on screen. */
    private fun MacrobenchmarkScope.scrollTo(selector: BySelector): UiObject2 {
        device.findObject(selector)?.let { return it }
        // UiObject2.scroll() did not move this LazyColumn on the S21 FE (seen in screenshots), so
        // swipe explicitly inside the list's bounds. Look downwards first, then upwards.
        val bounds = requireObject(By.res(SETTINGS_LIST)).visibleBounds
        val x = bounds.centerX()
        val low = bounds.top + bounds.height() * 3 / 4
        val high = bounds.top + bounds.height() / 4
        for ((fromY, toY) in listOf(low to high, high to low)) {
            repeat(MAX_SCROLLS) {
                device.swipe(x, fromY, x, toY, SCROLL_STEPS)
                device.waitForIdle()
                device.findObject(selector)?.let { return it }
            }
        }
        return requireObject(selector)
    }

    /**
     * Plays the first on-device track so the queue (and every skip) stays local: results must not
     * depend on the network or the Navidrome server being reachable.
     */
    private fun MacrobenchmarkScope.startLocalPlayback() {
        requireObject(By.text("LIBRARY")).click()
        requireObject(By.text(LOCAL_MUSIC_TAB)).click()
        requireObject(By.res(SONG_ROW).hasDescendant(By.textStartsWith(LOCAL_ROW_STATUS))).click()
        requireObject(By.res(MINI_PLAYER))
    }

    private fun MacrobenchmarkScope.ensureTrackLoaded() {
        if (device.wait(Until.hasObject(By.res(MINI_PLAYER)), SHORT_WAIT_MS)) return
        requireObject(By.text("LIBRARY")).click()
        requireObject(By.res(SONG_ROW)).click()
        requireObject(By.res(MINI_PLAYER))
    }

    private fun MacrobenchmarkScope.ensurePlaying() {
        val button = requireObject(By.res(TRANSPORT_PLAY_PAUSE))
        if (button.findObject(By.text(GLYPH_PAUSE)) == null) {
            button.click()
            check(device.wait(Until.hasObject(By.res(TRANSPORT_PLAY_PAUSE).hasDescendant(By.text(GLYPH_PAUSE))), WAIT_MS)) {
                "Playback did not start"
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

    private fun MacrobenchmarkScope.requireObject(selector: BySelector, timeoutMs: Long = WAIT_MS): UiObject2 =
        device.wait(Until.findObject(selector), timeoutMs) ?: error("Not found within $timeoutMs ms: $selector")

    private companion object {
        const val PACKAGE = "com.necroware.terminusplayer"
        const val ITERATIONS = 5
        const val CYCLES = 10
        const val WAIT_MS = 5_000L
        const val SHORT_WAIT_MS = 1_500L
        const val PANEL_SETTLE_MS = 1_000L
        const val MAX_SCROLLS = 8
        /** Slow enough (about 200 ms) that the list does not fling past the target. */
        const val SCROLL_STEPS = 40
        /** Same duration as the hand-tested swipe that collapsed the panel. */
        const val SWIPE_DURATION_MS = 150
        /** Longer than the FULL track-swap transition (260 ms token), so each skip settles inside the window. */
        const val SKIP_SETTLE_MS = 800L
        const val VISUALIZER_PLAY_MS = 20_000L
        const val ART_ALBUM = "STANDARD"
        const val ART_VISUALIZER = "VISUALIZER"
        const val GLYPH_PAUSE = "||"
        const val LOCAL_MUSIC_TAB = "[ LOCAL MUSIC ]"
        /** Source line of an on-device row ("LOCAL · LOCAL ONLY · ..."). */
        const val LOCAL_ROW_STATUS = "LOCAL ·"
        const val SECTION_COLLAPSED = "[ + ]"
        const val SECTION_EXPANDED = "[ - ]"

        // Mirrors app/.../ui/TestTags.kt
        const val MINI_PLAYER = "mini_player"
        const val NOW_PLAYING_ART = "now_playing_art"
        const val TRANSPORT_PREVIOUS = "transport_previous"
        const val TRANSPORT_PLAY_PAUSE = "transport_play_pause"
        const val TRANSPORT_NEXT = "transport_next"
        const val SONG_ROW = "song_row"
        const val SETTINGS_LIST = "settings_list"
        val NonAlphanumeric = Regex("[^A-Za-z0-9]")
        fun settingsSection(label: String) = "settings_section_" + label.replace(NonAlphanumeric, "_")
    }
}
