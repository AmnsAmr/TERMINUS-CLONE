package com.necroware.terminusplayer.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ThemePreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun presetAndSparseOverridesArePersistedAndRestored() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("theme.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)
            repository.setTheme(ThemePresetId.DRACULA)
            repository.setThemeColor(ThemeColorRole.ACCENT, "#39ff14")

            val restored = repository.preferences.first()
            assertEquals(ThemePresetId.DRACULA, restored.themeId)
            assertEquals(mapOf(ThemeColorRole.ACCENT to "#39FF14"), restored.themeColorOverrides)

            repository.clearThemeColor(ThemeColorRole.ACCENT)
            assertTrue(repository.preferences.first().themeColorOverrides.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun unknownThemeAndMalformedStoredOverridesFallBackSafely() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("invalid.preferences_pb")
            }
            store.edit { preferences ->
                preferences[stringPreferencesKey("theme_id")] = "REMOVED_THEME"
                preferences[stringSetPreferencesKey("theme_color_overrides_v1")] = setOf(
                    "ACCENT=#NOTHEX", "REMOVED_ROLE=#123456"
                )
            }
            val preferences = UserPreferencesRepository(store).preferences.first()
            assertEquals(ThemePresetId.TERMINAL, preferences.themeId)
            assertTrue(preferences.themeColorOverrides.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
