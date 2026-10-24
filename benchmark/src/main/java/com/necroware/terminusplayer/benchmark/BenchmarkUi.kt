package com.necroware.terminusplayer.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until

/** UI helpers shared by the journeys. Tags mirror app/.../ui/TestTags.kt. */
internal const val PACKAGE = "com.necroware.terminusplayer"
internal const val WAIT_MS = 5_000L

internal const val MINI_PLAYER = "mini_player"
internal const val MINI_PLAY_PAUSE = "mini_play_pause"
internal const val SONG_ROW = "song_row"
internal const val SETTINGS_LIST = "settings_list"
internal const val LOCAL_MUSIC_TAB = "[ LOCAL MUSIC ]"
/** Source line of an on-device row ("LOCAL · LOCAL ONLY · ..."). */
internal const val LOCAL_ROW_STATUS = "LOCAL ·"

private const val SECTION_COLLAPSED = "[ + ]"
private const val SECTION_EXPANDED = "[ - ]"
private const val MAX_SCROLLS = 8
/** Slow enough (about 200 ms) that the list does not fling past the target. */
private const val SCROLL_STEPS = 40

/** Text glyph used up to Phase 3; from Phase 4 the morphing glyph exposes this description instead. */
private const val GLYPH_PAUSE = "||"
private const val DESC_PAUSE = "Pause"

private val NonAlphanumeric = Regex("[^A-Za-z0-9]")
internal fun settingsSection(label: String) = "settings_section_" + label.replace(NonAlphanumeric, "_")

internal fun MacrobenchmarkScope.requireObject(selector: BySelector, timeoutMs: Long = WAIT_MS): UiObject2 =
    device.wait(Until.findObject(selector), timeoutMs) ?: error("Not found within $timeoutMs ms: $selector")

/** True when [button] shows the pause glyph, i.e. playback is running. Works on builds before and after Phase 4. */
internal fun showsPause(button: UiObject2): Boolean =
    button.findObject(By.text(GLYPH_PAUSE)) != null || button.contentDescription == DESC_PAUSE ||
        button.findObject(By.desc(DESC_PAUSE)) != null

/**
 * Expands the Settings [section] if it is collapsed and picks [option]. The section is left open:
 * collapsing it again would mean scrolling back to a header that may be only partly on screen.
 */
internal fun MacrobenchmarkScope.selectSettingsOption(section: String, option: String) {
    // BySelector builders mutate in place, so build a fresh selector for every lookup.
    fun header() = By.res(settingsSection(section))
    // Open/closed state can survive in the app, so read it from the header's marker.
    if (scrollSettingsTo(header()).hasObject(By.text(SECTION_COLLAPSED))) {
        scrollSettingsTo(header()).click()
        check(device.wait(Until.hasObject(header().hasDescendant(By.text(SECTION_EXPANDED))), WAIT_MS)) {
            "Settings section $section did not expand"
        }
    }
    scrollSettingsTo(By.res(option)).click()
    device.waitForIdle()
}

/** Scrolls the Settings list until [selector] is on screen. */
internal fun MacrobenchmarkScope.scrollSettingsTo(selector: BySelector): UiObject2 {
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
 * Plays the first on-device track so the queue stays local: results must not depend on the
 * network or the Navidrome server being reachable.
 */
internal fun MacrobenchmarkScope.startLocalPlayback() {
    requireObject(By.text("LIBRARY")).click()
    requireObject(By.text(LOCAL_MUSIC_TAB)).click()
    requireObject(By.res(SONG_ROW).hasDescendant(By.textStartsWith(LOCAL_ROW_STATUS))).click()
    requireObject(By.res(MINI_PLAYER))
}
