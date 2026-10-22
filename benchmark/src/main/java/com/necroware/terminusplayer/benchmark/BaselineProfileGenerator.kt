package com.necroware.terminusplayer.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Generates an Android Baseline Profile for Terminus Player.
 * Exercises critical user journeys (cold start, bottom nav tab switching,
 * library flinging, album/playlist navigation, and opening Now Playing).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() {
        rule.collect(
            packageName = "com.necroware.terminusplayer",
            includeInStartupProfile = true
        ) {
            // Cold app start
            pressHome()
            startActivityAndWait()
            device.waitForIdle()

            // 1. Cycle through all 5 bottom-navigation tabs
            val tabs = listOf("HOME", "LIBRARY", "PLAYLISTS", "STATS", "SETTINGS")
            for (tab in tabs) {
                device.wait(Until.findObject(By.text(tab)), 3000L)?.click()
                device.waitForIdle()
            }

            // Return to LIBRARY
            device.wait(Until.findObject(By.text("LIBRARY")), 3000L)?.click()
            device.waitForIdle()

            // 2. Fling the Library song list
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

            // 3. Open Albums tab and inspect an album if available
            val albumsTab = device.wait(Until.findObject(By.text("ALBUMS")), 2000L)
            if (albumsTab != null) {
                albumsTab.click()
                device.waitForIdle()
                val firstItem = device.wait(Until.findObject(By.clickable(true)), 2000L)
                firstItem?.click()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }

            // 4. Open Playlists tab and inspect a playlist if available
            val playlistsTab = device.wait(Until.findObject(By.text("PLAYLISTS")), 2000L)
            if (playlistsTab != null) {
                playlistsTab.click()
                device.waitForIdle()
                val playlistItem = device.wait(Until.findObject(By.text("LIKED SONGS")), 2000L)
                    ?: device.wait(Until.findObject(By.clickable(true)), 2000L)
                playlistItem?.click()
                device.waitForIdle()
                device.pressBack()
                device.waitForIdle()
            }

            // 5. Open Now Playing (swipe up or tap player bar)
            val centerX = device.displayWidth / 2
            val bottomY = device.displayHeight - 120
            val topY = (device.displayHeight * 0.3).toInt()
            device.swipe(centerX, bottomY, centerX, topY, 15)
            device.waitForIdle()

            // Collapse back down
            device.pressBack()
            device.waitForIdle()
        }
    }
}
