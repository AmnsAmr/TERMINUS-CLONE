package com.necroware.terminusplayer.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DynamicAestheticPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun resolveEffectiveMaxBitRateHonorsNetworkTransportAndPolicy() {
        // Disabled: returns configured rate without modification
        assertEquals(320, resolveEffectiveMaxBitRate(320, smartBitrateEnabled = false, isCellular = true))
        assertNull(resolveEffectiveMaxBitRate(null, smartBitrateEnabled = false, isCellular = true))
        assertEquals(64, resolveEffectiveMaxBitRate(64, smartBitrateEnabled = false, isCellular = false))

        // Enabled on Wi-Fi: unthrottled, keeps configured rate
        assertEquals(320, resolveEffectiveMaxBitRate(320, smartBitrateEnabled = true, isCellular = false))
        assertNull(resolveEffectiveMaxBitRate(null, smartBitrateEnabled = true, isCellular = false))

        // Enabled on Cellular: throttles to at most 128kbps
        assertEquals(128, resolveEffectiveMaxBitRate(320, smartBitrateEnabled = true, isCellular = true))
        assertEquals(128, resolveEffectiveMaxBitRate(null, smartBitrateEnabled = true, isCellular = true))
        assertEquals(64, resolveEffectiveMaxBitRate(64, smartBitrateEnabled = true, isCellular = true))
    }

    @Test
    fun dynamicSettingsArePersistedAndRestored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("dynamic_settings.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            val defaults = repository.preferences.first()
            assertEquals(0.0f, defaults.scanlineIntensity, 0.001f)
            assertFalse(defaults.phosphorAfterglow)
            assertEquals(TerminalCursorStyle.UNDERSCORE, defaults.cursorStyle)
            assertEquals(TerminalCursorBlink.SLOW, defaults.cursorBlinkCadence)
            assertFalse(defaults.adaptiveArtAccent)
            assertEquals(TerminalHaptics.TACTILE, defaults.terminalHaptics)
            assertFalse(defaults.smartBitrateEnabled)
            assertEquals(HudTelemetryMode.STANDARD, defaults.hudTelemetryMode)

            repository.setScanlineIntensity(0.75f)
            repository.setPhosphorAfterglow(true)
            repository.setCursorStyle(TerminalCursorStyle.BLOCK)
            repository.setCursorBlinkCadence(TerminalCursorBlink.FAST)
            repository.setAdaptiveArtAccent(true)
            repository.setTerminalHaptics(TerminalHaptics.CLICKY)
            repository.setSmartBitrateEnabled(true)
            repository.setHudTelemetryMode(HudTelemetryMode.EXPANDED)

            val updated = repository.preferences.first()
            assertEquals(0.75f, updated.scanlineIntensity, 0.001f)
            assertTrue(updated.phosphorAfterglow)
            assertEquals(TerminalCursorStyle.BLOCK, updated.cursorStyle)
            assertEquals("█", updated.cursorStyle.glyph)
            assertEquals(TerminalCursorBlink.FAST, updated.cursorBlinkCadence)
            assertEquals(500L, updated.cursorBlinkCadence.intervalMs)
            assertTrue(updated.adaptiveArtAccent)
            assertEquals(TerminalHaptics.CLICKY, updated.terminalHaptics)
            assertTrue(updated.smartBitrateEnabled)
            assertEquals(HudTelemetryMode.EXPANDED, updated.hudTelemetryMode)
        } finally {
            scope.cancel()
        }
    }
}
