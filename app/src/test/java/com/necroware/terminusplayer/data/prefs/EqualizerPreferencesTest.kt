package com.necroware.terminusplayer.data.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EqualizerPreferencesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun builtInPresetsExistByDefault() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_default.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)
            val prefs = repository.preferences.first()

            val presets = prefs.equalizer.presets
            assertTrue(presets.any { it.id == PRESET_ID_FLAT && it.isBuiltIn })
            assertTrue(presets.any { it.id == PRESET_ID_BASS && it.isBuiltIn })
            assertTrue(presets.any { it.id == PRESET_ID_VOCAL && it.isBuiltIn })
            assertTrue(presets.any { it.id == PRESET_ID_TREBLE && it.isBuiltIn })
            assertTrue(presets.any { it.id == PRESET_ID_LOUDNESS && it.isBuiltIn })
            assertEquals(PreampMode.AUTO, prefs.equalizer.preampMode)
            assertEquals(0f, prefs.equalizer.manualPreampDb)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun selectPresetUpdatesActiveIdAndBandGains() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_select.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            repository.selectEqualizerPreset(PRESET_ID_BASS)
            val prefs = repository.preferences.first()

            assertEquals(PRESET_ID_BASS, prefs.equalizer.activePresetId)
            assertEquals(listOf(6, 4, 0, 0, -1), prefs.equalizer.bandGainsDb)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun saveCustomPresetAndCRUDOperations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_crud.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            val customGains = listOf(3f, 2f, 1f, 0f, -2f)
            val created = repository.saveEqualizerPreset("Club Sound", customGains)
            val customId = created.id

            var prefs = repository.preferences.first()
            assertEquals(customId, prefs.equalizer.activePresetId)
            val savedPreset = prefs.equalizer.presets.firstOrNull { it.id == customId }
            assertNotNull(savedPreset)
            assertEquals("Club Sound", savedPreset?.name)
            assertEquals(customGains, savedPreset?.gainsDb)
            assertFalse(savedPreset?.isBuiltIn == true)

            // Rename custom preset
            repository.renameEqualizerPreset(customId, "Club Sound V2")
            prefs = repository.preferences.first()
            assertEquals("Club Sound V2", prefs.equalizer.presets.first { it.id == customId }.name)

            // Cannot rename built-in preset
            repository.renameEqualizerPreset(PRESET_ID_BASS, "Mega Bass")
            prefs = repository.preferences.first()
            assertEquals("Bass", prefs.equalizer.presets.first { it.id == PRESET_ID_BASS }.name)

            // Delete custom preset
            repository.deleteEqualizerPreset(customId)
            prefs = repository.preferences.first()
            assertNull(prefs.equalizer.presets.firstOrNull { it.id == customId })

            // Cannot delete built-in preset
            repository.deleteEqualizerPreset(PRESET_ID_FLAT)
            prefs = repository.preferences.first()
            assertNotNull(prefs.equalizer.presets.firstOrNull { it.id == PRESET_ID_FLAT })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun preampModeAndManualPreampPersistence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_preamp.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            repository.setPreampMode(PreampMode.MANUAL)
            repository.setManualPreampDb(3.5f)

            var prefs = repository.preferences.first()
            assertEquals(PreampMode.MANUAL, prefs.equalizer.preampMode)
            assertEquals(3.5f, prefs.equalizer.manualPreampDb)

            // Clamping check
            repository.setManualPreampDb(15f) // Should clamp to +6 dB
            prefs = repository.preferences.first()
            assertEquals(6.0f, prefs.equalizer.manualPreampDb)

            repository.setManualPreampDb(-20f) // Should clamp to -12 dB
            prefs = repository.preferences.first()
            assertEquals(-12.0f, prefs.equalizer.manualPreampDb)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun outputDeviceAssignmentsPersistence() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_routing.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            val deviceId = "bt:Galaxy Buds2 Pro"
            repository.setOutputDevicePresetAssignment(deviceId, PRESET_ID_LOUDNESS)

            var prefs = repository.preferences.first()
            assertEquals(PRESET_ID_LOUDNESS, prefs.equalizer.outputDeviceAssignments[deviceId])

            // Remove assignment
            repository.setOutputDevicePresetAssignment(deviceId, null)
            prefs = repository.preferences.first()
            assertNull(prefs.equalizer.outputDeviceAssignments[deviceId])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun legacyGainsMigrationCreatesCustomPreset() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = temporaryFolder.newFolder().resolve("eq_legacy.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope) { file }

            // Simulate legacy preference written before presets existed (stored as eq_band_0..4)
            store.edit { prefs ->
                prefs[androidx.datastore.preferences.core.intPreferencesKey("eq_band_0")] = 4
                prefs[androidx.datastore.preferences.core.intPreferencesKey("eq_band_1")] = 2
                prefs[androidx.datastore.preferences.core.intPreferencesKey("eq_band_2")] = -1
                prefs[androidx.datastore.preferences.core.intPreferencesKey("eq_band_3")] = 3
                prefs[androidx.datastore.preferences.core.intPreferencesKey("eq_band_4")] = 5
            }

            val repository = UserPreferencesRepository(store)
            val prefs = repository.preferences.first()

            val customPreset = prefs.equalizer.presets.firstOrNull { it.id == PRESET_ID_CUSTOM }
            assertNotNull(customPreset)
            assertEquals(listOf(4f, 2f, -1f, 3f, 5f), customPreset?.gainsDb)
            assertEquals(PRESET_ID_CUSTOM, prefs.equalizer.activePresetId)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun setEqualizerBandUpdatesCustomPresetAndActiveId() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_band_edit.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            // Select built-in preset first
            repository.selectEqualizerPreset(PRESET_ID_BASS)
            var prefs = repository.preferences.first()
            assertEquals(PRESET_ID_BASS, prefs.equalizer.activePresetId)

            // Edit band 0 to +8 dB
            repository.setEqualizerBand(0, 8)
            prefs = repository.preferences.first()

            // activePresetId must automatically switch to Custom
            assertEquals(PRESET_ID_CUSTOM, prefs.equalizer.activePresetId)
            assertEquals(8, prefs.equalizer.bandGainsDb[0])

            // Custom preset must reflect the updated band
            val customPreset = prefs.equalizer.presets.firstOrNull { it.id == PRESET_ID_CUSTOM }
            assertNotNull(customPreset)
            assertEquals(8f, customPreset?.gainsDb?.get(0))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun customPresetTakesPrecedenceWhenDeviceAssignmentExists() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope) {
                temporaryFolder.newFolder().resolve("eq_precedence.preferences_pb")
            }
            val repository = UserPreferencesRepository(store)

            val deviceId = "builtin_speaker"
            // Device has an assigned preset (FLAT)
            repository.setOutputDevicePresetAssignment(deviceId, PRESET_ID_FLAT)

            // User moves slider to +7 dB on band 1
            repository.setEqualizerBand(1, 7)
            val prefs = repository.preferences.first()

            assertEquals(PRESET_ID_CUSTOM, prefs.equalizer.activePresetId)
            assertEquals(PRESET_ID_FLAT, prefs.equalizer.outputDeviceAssignments[deviceId])

            // Verify precedence policy (matching MusicService logic)
            val assignedPresetId = prefs.equalizer.outputDeviceAssignments[deviceId]
            val effectivePreset = if (prefs.equalizer.activePresetId == PRESET_ID_CUSTOM) {
                prefs.equalizer.presets.firstOrNull { it.id == PRESET_ID_CUSTOM }
            } else {
                prefs.equalizer.presets.firstOrNull { it.id == prefs.equalizer.activePresetId }
                    ?: assignedPresetId?.let { id -> prefs.equalizer.presets.firstOrNull { it.id == id } }
            }

            assertNotNull(effectivePreset)
            assertEquals(PRESET_ID_CUSTOM, effectivePreset?.id)
            assertEquals(7f, effectivePreset?.gainsDb?.get(1))
        } finally {
            scope.cancel()
        }
    }
}
