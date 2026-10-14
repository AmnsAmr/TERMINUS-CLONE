package com.necroware.terminusplayer.ui.theme

import androidx.compose.ui.graphics.Color
import com.necroware.terminusplayer.data.prefs.ThemeColorRole
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemePresetResolutionTest {
    @Test fun overrideChangesOnlyItsRole() {
        val base = themePresetById(ThemePresetId.CYBERPUNK)
        val resolved = base.withOverrides(mapOf(ThemeColorRole.ACCENT to "#39FF14"))

        assertEquals(Color(0xFF39FF14), resolved.accent)
        assertEquals(base.background, resolved.background)
        assertEquals(base.playerBackground, resolved.playerBackground)
    }

    @Test fun changingPresetKeepsSparseOverrideAndInheritsOtherColors() {
        val overrides = mapOf(ThemeColorRole.TEXT_MUTED to "#ABCDEF")
        val ocean = themePresetById(ThemePresetId.OCEAN_BLUE).withOverrides(overrides)
        val dracula = themePresetById(ThemePresetId.DRACULA).withOverrides(overrides)

        assertEquals(Color(0xFFABCDEF), ocean.textMuted)
        assertEquals(Color(0xFFABCDEF), dracula.textMuted)
        assertNotEquals(ocean.background, dracula.background)
    }

    @Test fun invalidOverrideFallsBackToPresetAndInvalidInputIsRejected() {
        val preset = themePresetById(ThemePresetId.TERMINAL_GREEN)
        assertEquals(preset.accent, preset.withOverrides(mapOf(ThemeColorRole.ACCENT to "not-a-color")).accent)
        assertNull(parseThemeColor("#12345"))
    }

    @Test fun allLegacyPresetIdsRemainAvailable() {
        listOf(ThemePresetId.TERMINAL, ThemePresetId.VECTOR, ThemePresetId.REBECCA, ThemePresetId.DUNE,
            ThemePresetId.HEX, ThemePresetId.LUCY, ThemePresetId.MAINE, ThemePresetId.FLATLINE,
            ThemePresetId.WIZ).forEach { assertEquals(it, themePresetById(it).id) }
    }
}
