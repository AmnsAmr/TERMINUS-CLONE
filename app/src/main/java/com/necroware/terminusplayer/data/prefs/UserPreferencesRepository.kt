package com.necroware.terminusplayer.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.necroware.terminusplayer.data.network.SystemNetworkState
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import javax.inject.Inject
import javax.inject.Singleton

private object Keys {
    val THEME_ID = stringPreferencesKey("theme_id")
    val THEME_COLOR_OVERRIDES = stringSetPreferencesKey("theme_color_overrides_v1")
    val SORT_FIELD = stringPreferencesKey("sort_field")
    val SORT_DIRECTION = stringPreferencesKey("sort_direction")
    val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    val EQ_BAND_PREFIX = "eq_band_"
    val EQ_PRESETS_JSON = stringPreferencesKey("eq_presets_json")
    val EQ_ACTIVE_PRESET_ID = stringPreferencesKey("eq_active_preset_id")
    val EQ_PREAMP_MODE = stringPreferencesKey("eq_preamp_mode")
    val EQ_MANUAL_PREAMP_DB = androidx.datastore.preferences.core.floatPreferencesKey("eq_manual_preamp_db")
    val EQ_OUTPUT_ASSIGNMENTS_JSON = stringPreferencesKey("eq_output_assignments_json")
    val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
    val CROSSFADE_DURATION_MS = intPreferencesKey("crossfade_duration_ms")
    val PLAYBACK_ART_STYLE = stringPreferencesKey("playback_art_style")
    val MOTION_PREFERENCE = stringPreferencesKey("motion_preference")
    val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
    val SCANLINE_INTENSITY = androidx.datastore.preferences.core.floatPreferencesKey("scanline_intensity")
    val PHOSPHOR_AFTERGLOW = booleanPreferencesKey("phosphor_afterglow")
    val CURSOR_STYLE = stringPreferencesKey("cursor_style")
    val CURSOR_BLINK_CADENCE = stringPreferencesKey("cursor_blink_cadence")
    val ADAPTIVE_ART_ACCENT = booleanPreferencesKey("adaptive_art_accent")
    val TERMINAL_HAPTICS = stringPreferencesKey("terminal_haptics")
    val SMART_BITRATE_ENABLED = booleanPreferencesKey("smart_bitrate_enabled")
    val CELLULAR_MAX_BIT_RATE = intPreferencesKey("cellular_max_bit_rate")
    val OPEN_PLAYER_ON_LIBRARY_TRACK_SELECT = booleanPreferencesKey("open_player_on_library_track_select")
    val OPEN_PLAYER_ON_PLAYLIST_TRACK_SELECT = booleanPreferencesKey("open_player_on_playlist_track_select")
    val REACTIVE_HUD_FLASH = booleanPreferencesKey("reactive_hud_flash")
    val WARP_PRESET_ID = stringPreferencesKey("warp_preset_id")
    val WARP_AUTO_CYCLE = booleanPreferencesKey("warp_auto_cycle")
    val WARP_CYCLE_SECONDS = intPreferencesKey("warp_cycle_seconds")
    val WARP_PRESET_LOCKED = booleanPreferencesKey("warp_preset_locked")
    val VISUALIZER_STYLE = stringPreferencesKey("visualizer_style")
    val HUD_TELEMETRY_MODE = stringPreferencesKey("hud_telemetry_mode")
    val LAST_PLAYED_SONG_ID = stringPreferencesKey("last_played_song_id_v2")
    val LAST_PLAYED_POSITION_MS = longPreferencesKey("last_played_position_ms")
    val SERVER_URL = stringPreferencesKey("server_url")
    val USERNAME = stringPreferencesKey("username")
    val PASSWORD = stringPreferencesKey("password")
    val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
    val HAS_SETUP_DEFAULT_EXCLUDES = booleanPreferencesKey("has_setup_default_excludes")
    val MAX_BIT_RATE = intPreferencesKey("max_bit_rate")
    val DOWNLOAD_MAX_BIT_RATE = intPreferencesKey("download_max_bit_rate")
    val DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")
    val TRANSFERS_PAUSED = booleanPreferencesKey("transfers_paused")
}

private fun eqBandKey(index: Int) = intPreferencesKey("${Keys.EQ_BAND_PREFIX}$index")

private val moshiInstance: Moshi by lazy { Moshi.Builder().build() }
private val presetsListType = Types.newParameterizedType(List::class.java, EqualizerPreset::class.java)
private val presetsAdapter by lazy { moshiInstance.adapter<List<EqualizerPreset>>(presetsListType) }
private val assignmentsMapType = Types.newParameterizedType(Map::class.java, String::class.java, String::class.java)
private val assignmentsAdapter by lazy { moshiInstance.adapter<Map<String, String>>(assignmentsMapType) }

internal fun decodePresets(json: String?, currentBands: List<Int>): List<EqualizerPreset> {
    val decoded = if (!json.isNullOrBlank()) {
        runCatching { presetsAdapter.fromJson(json) }.getOrNull()
    } else null

    if (decoded != null && decoded.isNotEmpty()) {
        return decoded
    }

    // Migration of existing settings: create Custom preset with current bands
    val customPreset = EqualizerPreset(
        id = PRESET_ID_CUSTOM,
        name = "Custom",
        gainsDb = currentBands.map { it.toFloat() },
        isBuiltIn = false
    )
    return BUILT_IN_PRESETS + customPreset
}

internal fun encodePresets(presets: List<EqualizerPreset>): String {
    return presetsAdapter.toJson(presets)
}

internal fun decodeAssignments(json: String?): Map<String, String> {
    if (json.isNullOrBlank()) return emptyMap()
    return runCatching { assignmentsAdapter.fromJson(json) }.getOrNull() ?: emptyMap()
}

internal fun encodeAssignments(assignments: Map<String, String>): String {
    return assignmentsAdapter.toJson(assignments)
}

data class ServerConnectionConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val maxBitRate: Int? = null,
    val isLoaded: Boolean = false
)

fun resolveEffectiveMaxBitRate(
    configuredBitRate: Int?,
    smartBitrateEnabled: Boolean,
    cellularBitRate: Int? = 128,
    isCellular: Boolean
): Int? {
    if (!smartBitrateEnabled) return configuredBitRate
    return if (isCellular) {
        cellularBitRate ?: 128
    } else {
        configuredBitRate
    }
}

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val networkState: SystemNetworkState? = null
) {
    val transfersPaused: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs[Keys.TRANSFERS_PAUSED] ?: false }

    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs.toUserPreferences() }
    private val _serverConnectionConfig = MutableStateFlow(ServerConnectionConfig())
    val serverConnectionConfig = _serverConnectionConfig.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val cellularFlow = networkState?.isCellular ?: kotlinx.coroutines.flow.flowOf(false)
            combine(preferences, cellularFlow) { prefs, isCellular ->
                val effectiveBitRate = resolveEffectiveMaxBitRate(
                    configuredBitRate = prefs.maxBitRate,
                    smartBitrateEnabled = prefs.smartBitrateEnabled,
                    cellularBitRate = prefs.cellularMaxBitRate,
                    isCellular = isCellular
                )
                ServerConnectionConfig(
                    serverUrl = prefs.serverUrl,
                    username = prefs.username,
                    password = prefs.password,
                    maxBitRate = effectiveBitRate,
                    isLoaded = true
                )
            }.distinctUntilChanged().collect { config ->
                _serverConnectionConfig.value = config
            }
        }
    }

    suspend fun awaitServerConnectionConfig(): ServerConnectionConfig =
        serverConnectionConfig.first { it.isLoaded }

    suspend fun setTheme(themeId: ThemePresetId) {
        dataStore.edit { it[Keys.THEME_ID] = themeId.name }
    }

    suspend fun setThemeColor(role: ThemeColorRole, hex: String) {
        val normalized = hex.removePrefix("#").uppercase()
        if (!normalized.matches(Regex("(?:[0-9A-F]{6}|[0-9A-F]{8})"))) return
        dataStore.edit { prefs ->
            val overrides = (prefs[Keys.THEME_COLOR_OVERRIDES] ?: emptySet())
                .filterNot { it.substringBefore('=') == role.name }.toMutableSet()
            overrides += "${role.name}=#$normalized"
            prefs[Keys.THEME_COLOR_OVERRIDES] = overrides
        }
    }

    suspend fun clearThemeColor(role: ThemeColorRole) {
        dataStore.edit { prefs ->
            prefs[Keys.THEME_COLOR_OVERRIDES] = (prefs[Keys.THEME_COLOR_OVERRIDES] ?: emptySet())
                .filterNot { it.substringBefore('=') == role.name }.toSet()
        }
    }

    suspend fun clearThemeColors(roles: Set<ThemeColorRole>? = null) {
        dataStore.edit { prefs ->
            if (roles == null) prefs.remove(Keys.THEME_COLOR_OVERRIDES)
            else prefs[Keys.THEME_COLOR_OVERRIDES] = (prefs[Keys.THEME_COLOR_OVERRIDES] ?: emptySet())
                .filterNot { entry -> roles.any { it.name == entry.substringBefore('=') } }.toSet()
        }
    }

    suspend fun setTransfersPaused(paused: Boolean) {
        dataStore.edit { it[Keys.TRANSFERS_PAUSED] = paused }
    }

    suspend fun setSortOrder(order: LibrarySortOrder) {
        dataStore.edit {
            it[Keys.SORT_FIELD] = order.field.name
            it[Keys.SORT_DIRECTION] = order.direction.name
        }
    }

    suspend fun setPlaybackArtStyle(style: PlaybackArtStyle) {
        dataStore.edit { it[Keys.PLAYBACK_ART_STYLE] = style.name }
    }

    suspend fun setMotionPreference(preference: MotionPreference) {
        dataStore.edit { it[Keys.MOTION_PREFERENCE] = preference.name }
    }

    suspend fun setPerformanceMode(mode: PerformanceMode) {
        dataStore.edit { it[Keys.PERFORMANCE_MODE] = mode.name }
    }

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EQ_ENABLED] = enabled }
    }

    suspend fun setEqualizerBand(index: Int, gainDb: Int) {
        dataStore.edit { prefs ->
            prefs[eqBandKey(index)] = gainDb
            val currentGains = List(5) { i -> if (i == index) gainDb else (prefs[eqBandKey(i)] ?: 0) }
            val currentPresets = decodePresets(prefs[Keys.EQ_PRESETS_JSON], currentGains).toMutableList()
            val customIdx = currentPresets.indexOfFirst { it.id == PRESET_ID_CUSTOM }
            val customPreset = EqualizerPreset(PRESET_ID_CUSTOM, "Custom", currentGains.map { it.toFloat() }, isBuiltIn = false)
            if (customIdx >= 0) {
                currentPresets[customIdx] = customPreset
            } else {
                currentPresets.add(customPreset)
            }
            prefs[Keys.EQ_PRESETS_JSON] = encodePresets(currentPresets)
            prefs[Keys.EQ_ACTIVE_PRESET_ID] = PRESET_ID_CUSTOM
        }
    }

    suspend fun setEqualizerBands(gainsDb: List<Int>) {
        dataStore.edit { prefs ->
            gainsDb.forEachIndexed { index, gain -> prefs[eqBandKey(index)] = gain }
            val currentPresets = decodePresets(prefs[Keys.EQ_PRESETS_JSON], gainsDb).toMutableList()
            val customIdx = currentPresets.indexOfFirst { it.id == PRESET_ID_CUSTOM }
            val customPreset = EqualizerPreset(PRESET_ID_CUSTOM, "Custom", gainsDb.map { it.toFloat() }, isBuiltIn = false)
            if (customIdx >= 0) {
                currentPresets[customIdx] = customPreset
            } else {
                currentPresets.add(customPreset)
            }
            prefs[Keys.EQ_PRESETS_JSON] = encodePresets(currentPresets)
            prefs[Keys.EQ_ACTIVE_PRESET_ID] = PRESET_ID_CUSTOM
        }
    }

    suspend fun selectEqualizerPreset(presetId: String) {
        dataStore.edit { prefs ->
            val eqBands = List(5) { index -> prefs[eqBandKey(index)] ?: 0 }
            val presets = decodePresets(prefs[Keys.EQ_PRESETS_JSON], eqBands)
            val selected = presets.firstOrNull { it.id == presetId } ?: return@edit
            prefs[Keys.EQ_ACTIVE_PRESET_ID] = presetId
            selected.gainsDb.forEachIndexed { index, gain ->
                prefs[eqBandKey(index)] = gain.toInt()
            }
        }
    }

    suspend fun saveEqualizerPreset(name: String, gainsDb: List<Float>): EqualizerPreset {
        val newId = "user_${System.currentTimeMillis()}"
        val newPreset = EqualizerPreset(id = newId, name = name.trim().ifBlank { "User Preset" }, gainsDb = gainsDb, isBuiltIn = false)
        dataStore.edit { prefs ->
            val eqBands = List(5) { index -> prefs[eqBandKey(index)] ?: 0 }
            val currentPresets = decodePresets(prefs[Keys.EQ_PRESETS_JSON], eqBands).toMutableList()
            currentPresets.add(newPreset)
            prefs[Keys.EQ_PRESETS_JSON] = encodePresets(currentPresets)
            prefs[Keys.EQ_ACTIVE_PRESET_ID] = newId
            gainsDb.forEachIndexed { index, gain ->
                prefs[eqBandKey(index)] = gain.toInt()
            }
        }
        return newPreset
    }

    suspend fun renameEqualizerPreset(presetId: String, newName: String): Boolean {
        var renamed = false
        dataStore.edit { prefs ->
            val eqBands = List(5) { index -> prefs[eqBandKey(index)] ?: 0 }
            val currentPresets = decodePresets(prefs[Keys.EQ_PRESETS_JSON], eqBands).toMutableList()
            val index = currentPresets.indexOfFirst { it.id == presetId }
            if (index >= 0 && !currentPresets[index].isBuiltIn) {
                currentPresets[index] = currentPresets[index].copy(name = newName.trim().ifBlank { currentPresets[index].name })
                prefs[Keys.EQ_PRESETS_JSON] = encodePresets(currentPresets)
                renamed = true
            }
        }
        return renamed
    }

    suspend fun deleteEqualizerPreset(presetId: String): Boolean {
        var deleted = false
        dataStore.edit { prefs ->
            val eqBands = List(5) { index -> prefs[eqBandKey(index)] ?: 0 }
            val currentPresets = decodePresets(prefs[Keys.EQ_PRESETS_JSON], eqBands).toMutableList()
            val index = currentPresets.indexOfFirst { it.id == presetId }
            if (index >= 0 && !currentPresets[index].isBuiltIn) {
                currentPresets.removeAt(index)
                prefs[Keys.EQ_PRESETS_JSON] = encodePresets(currentPresets)
                if (prefs[Keys.EQ_ACTIVE_PRESET_ID] == presetId) {
                    val fallback = currentPresets.firstOrNull { it.id == PRESET_ID_CUSTOM }
                        ?: currentPresets.firstOrNull { it.id == PRESET_ID_FLAT }
                        ?: currentPresets.first()
                    prefs[Keys.EQ_ACTIVE_PRESET_ID] = fallback.id
                    fallback.gainsDb.forEachIndexed { i, g -> prefs[eqBandKey(i)] = g.toInt() }
                }
                val assignments = decodeAssignments(prefs[Keys.EQ_OUTPUT_ASSIGNMENTS_JSON]).toMutableMap()
                if (assignments.values.contains(presetId)) {
                    val cleaned = assignments.filterValues { it != presetId }
                    prefs[Keys.EQ_OUTPUT_ASSIGNMENTS_JSON] = encodeAssignments(cleaned)
                }
                deleted = true
            }
        }
        return deleted
    }

    suspend fun setPreampMode(mode: PreampMode) {
        dataStore.edit { it[Keys.EQ_PREAMP_MODE] = mode.name }
    }

    suspend fun setManualPreampDb(gainDb: Float) {
        dataStore.edit { it[Keys.EQ_MANUAL_PREAMP_DB] = gainDb.coerceIn(-12f, 6f) }
    }

    suspend fun setOutputDevicePresetAssignment(deviceKey: String, presetId: String?) {
        dataStore.edit { prefs ->
            val assignments = decodeAssignments(prefs[Keys.EQ_OUTPUT_ASSIGNMENTS_JSON]).toMutableMap()
            if (presetId != null) {
                assignments[deviceKey] = presetId
            } else {
                assignments.remove(deviceKey)
            }
            prefs[Keys.EQ_OUTPUT_ASSIGNMENTS_JSON] = encodeAssignments(assignments)
        }
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_ENABLED] = enabled }
    }

    suspend fun setCrossfadeDurationMs(durationMs: Int) {
        dataStore.edit { it[Keys.CROSSFADE_DURATION_MS] = durationMs }
    }

    suspend fun setScanlineIntensity(intensity: Float) {
        dataStore.edit { it[Keys.SCANLINE_INTENSITY] = intensity.coerceIn(0f, 1f) }
    }

    suspend fun setPhosphorAfterglow(enabled: Boolean) {
        dataStore.edit { it[Keys.PHOSPHOR_AFTERGLOW] = enabled }
    }

    suspend fun setCursorStyle(style: TerminalCursorStyle) {
        dataStore.edit { it[Keys.CURSOR_STYLE] = style.name }
    }

    suspend fun setCursorBlinkCadence(cadence: TerminalCursorBlink) {
        dataStore.edit { it[Keys.CURSOR_BLINK_CADENCE] = cadence.name }
    }

    suspend fun setAdaptiveArtAccent(enabled: Boolean) {
        dataStore.edit { it[Keys.ADAPTIVE_ART_ACCENT] = enabled }
    }

    suspend fun setTerminalHaptics(haptics: TerminalHaptics) {
        dataStore.edit { it[Keys.TERMINAL_HAPTICS] = haptics.name }
    }

    suspend fun setSmartBitrateEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SMART_BITRATE_ENABLED] = enabled }
    }

    suspend fun setCellularMaxBitRate(bitRate: Int?) {
        dataStore.edit { prefs ->
            if (bitRate != null) {
                prefs[Keys.CELLULAR_MAX_BIT_RATE] = bitRate
            } else {
                prefs.remove(Keys.CELLULAR_MAX_BIT_RATE)
            }
        }
    }

    suspend fun setOpenPlayerOnLibraryTrackSelect(enabled: Boolean) {
        dataStore.edit { it[Keys.OPEN_PLAYER_ON_LIBRARY_TRACK_SELECT] = enabled }
    }

    suspend fun setOpenPlayerOnPlaylistTrackSelect(enabled: Boolean) {
        dataStore.edit { it[Keys.OPEN_PLAYER_ON_PLAYLIST_TRACK_SELECT] = enabled }
    }

    suspend fun setWarpPresetId(id: String) {
        dataStore.edit { if (it[Keys.WARP_PRESET_ID] != id) it[Keys.WARP_PRESET_ID] = id }
    }

    suspend fun setWarpAutoCycle(enabled: Boolean) {
        dataStore.edit { it[Keys.WARP_AUTO_CYCLE] = enabled }
    }

    suspend fun setWarpCycleSeconds(seconds: Int) {
        dataStore.edit { it[Keys.WARP_CYCLE_SECONDS] = seconds }
    }

    suspend fun setWarpPresetLocked(locked: Boolean) {
        dataStore.edit { it[Keys.WARP_PRESET_LOCKED] = locked }
    }

    suspend fun setReactiveHudFlash(enabled: Boolean) {
        dataStore.edit { it[Keys.REACTIVE_HUD_FLASH] = enabled }
    }

    suspend fun setVisualizerStyle(style: VisualizerStyle) {
        dataStore.edit { it[Keys.VISUALIZER_STYLE] = style.name }
    }

    suspend fun setHudTelemetryMode(mode: HudTelemetryMode) {
        dataStore.edit { it[Keys.HUD_TELEMETRY_MODE] = mode.name }
    }

    suspend fun setLastPlayed(songId: String?, positionMs: Long) {
        dataStore.edit { prefs ->
            if (songId != null) {
                prefs[Keys.LAST_PLAYED_SONG_ID] = songId
            } else {
                prefs.remove(Keys.LAST_PLAYED_SONG_ID)
            }
            prefs[Keys.LAST_PLAYED_POSITION_MS] = positionMs
        }
    }

    private fun Preferences.toUserPreferences(): UserPreferences {
        val defaults = UserPreferences()
        val themeId = this[Keys.THEME_ID]?.let { runCatching { ThemePresetId.valueOf(it) }.getOrNull() }
            ?: defaults.themeId
        val themeColorOverrides = (this[Keys.THEME_COLOR_OVERRIDES] ?: emptySet()).mapNotNull { entry ->
            val roleName = entry.substringBefore('=', "")
            val value = entry.substringAfter('=', "")
            val role = runCatching { ThemeColorRole.valueOf(roleName) }.getOrNull()
            if (role != null && value.removePrefix("#").matches(Regex("(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})"))) {
                role to "#${value.removePrefix("#").uppercase()}"
            } else null
        }.toMap()
        val sortField = this[Keys.SORT_FIELD]?.let { runCatching { SortField.valueOf(it) }.getOrNull() }
            ?: defaults.librarySortOrder.field
        val sortDirection = this[Keys.SORT_DIRECTION]?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
            ?: defaults.librarySortOrder.direction
        val playbackArtStyle = this[Keys.PLAYBACK_ART_STYLE]?.let { runCatching { PlaybackArtStyle.valueOf(it) }.getOrNull() }
            ?: defaults.playbackArtStyle
        val performanceMode = this[Keys.PERFORMANCE_MODE]?.let { runCatching { PerformanceMode.valueOf(it) }.getOrNull() }
            ?: defaults.performanceMode
        val cursorStyle = this[Keys.CURSOR_STYLE]?.let { runCatching { TerminalCursorStyle.valueOf(it) }.getOrNull() }
            ?: defaults.cursorStyle
        val cursorBlinkCadence = this[Keys.CURSOR_BLINK_CADENCE]?.let { runCatching { TerminalCursorBlink.valueOf(it) }.getOrNull() }
            ?: defaults.cursorBlinkCadence
        val terminalHaptics = this[Keys.TERMINAL_HAPTICS]?.let { runCatching { TerminalHaptics.valueOf(it) }.getOrNull() }
            ?: defaults.terminalHaptics
        val hudTelemetryMode = this[Keys.HUD_TELEMETRY_MODE]?.let { runCatching { HudTelemetryMode.valueOf(it) }.getOrNull() }
            ?: defaults.hudTelemetryMode
        val cellularMaxBitRate = this[Keys.CELLULAR_MAX_BIT_RATE] ?: defaults.cellularMaxBitRate
        val openPlayerOnLibraryTrackSelect = this[Keys.OPEN_PLAYER_ON_LIBRARY_TRACK_SELECT] ?: defaults.openPlayerOnLibraryTrackSelect
        val openPlayerOnPlaylistTrackSelect = this[Keys.OPEN_PLAYER_ON_PLAYLIST_TRACK_SELECT] ?: defaults.openPlayerOnPlaylistTrackSelect
        val reactiveHudFlash = this[Keys.REACTIVE_HUD_FLASH] ?: defaults.reactiveHudFlash
        val warpCycleSeconds = this[Keys.WARP_CYCLE_SECONDS]?.takeIf { it in setOf(15, 30, 60) } ?: defaults.warpCycleSeconds
        val visualizerStyle = this[Keys.VISUALIZER_STYLE]?.let { runCatching { VisualizerStyle.valueOf(it) }.getOrNull() }
            ?: defaults.visualizerStyle
        val eqBands = List(5) { index -> this[eqBandKey(index)] ?: 0 }
        val presets = decodePresets(this[Keys.EQ_PRESETS_JSON], eqBands)
        val activePresetId = this[Keys.EQ_ACTIVE_PRESET_ID] ?: if (eqBands.any { it != 0 }) PRESET_ID_CUSTOM else PRESET_ID_FLAT
        val preampMode = this[Keys.EQ_PREAMP_MODE]?.let { runCatching { PreampMode.valueOf(it) }.getOrNull() } ?: PreampMode.AUTO
        val manualPreampDb = this[Keys.EQ_MANUAL_PREAMP_DB] ?: 0f
        val outputAssignments = decodeAssignments(this[Keys.EQ_OUTPUT_ASSIGNMENTS_JSON])
        val maxBitRate = this[Keys.MAX_BIT_RATE]
        val downloadMaxBitRate = this[Keys.DOWNLOAD_MAX_BIT_RATE]

        return UserPreferences(
            themeId = themeId,
            themeColorOverrides = themeColorOverrides,
            librarySortOrder = LibrarySortOrder(sortField, sortDirection),
            equalizer = EqualizerSettings(
                enabled = this[Keys.EQ_ENABLED] ?: defaults.equalizer.enabled,
                bandGainsDb = eqBands,
                presets = presets,
                activePresetId = activePresetId,
                preampMode = preampMode,
                manualPreampDb = manualPreampDb,
                outputDeviceAssignments = outputAssignments
            ),
            crossfade = CrossfadeSettings(
                enabled = this[Keys.CROSSFADE_ENABLED] ?: defaults.crossfade.enabled,
                durationMs = this[Keys.CROSSFADE_DURATION_MS] ?: defaults.crossfade.durationMs
            ),
            playbackArtStyle = playbackArtStyle,
            motionPreference = parseMotionPreference(this[Keys.MOTION_PREFERENCE]),
            performanceMode = performanceMode,
            scanlineIntensity = this[Keys.SCANLINE_INTENSITY] ?: defaults.scanlineIntensity,
            phosphorAfterglow = this[Keys.PHOSPHOR_AFTERGLOW] ?: defaults.phosphorAfterglow,
            cursorStyle = cursorStyle,
            cursorBlinkCadence = cursorBlinkCadence,
            adaptiveArtAccent = this[Keys.ADAPTIVE_ART_ACCENT] ?: defaults.adaptiveArtAccent,
            terminalHaptics = terminalHaptics,
            smartBitrateEnabled = this[Keys.SMART_BITRATE_ENABLED] ?: defaults.smartBitrateEnabled,
            cellularMaxBitRate = cellularMaxBitRate,
            openPlayerOnLibraryTrackSelect = openPlayerOnLibraryTrackSelect,
            openPlayerOnPlaylistTrackSelect = openPlayerOnPlaylistTrackSelect,
            reactiveHudFlash = reactiveHudFlash,
            warpPresetId = this[Keys.WARP_PRESET_ID] ?: defaults.warpPresetId,
            warpAutoCycle = this[Keys.WARP_AUTO_CYCLE] ?: defaults.warpAutoCycle,
            warpCycleSeconds = warpCycleSeconds,
            warpPresetLocked = this[Keys.WARP_PRESET_LOCKED] ?: defaults.warpPresetLocked,
            visualizerStyle = visualizerStyle,
            hudTelemetryMode = hudTelemetryMode,
            lastPlayedSongId = this[Keys.LAST_PLAYED_SONG_ID],
            lastPlayedPositionMs = this[Keys.LAST_PLAYED_POSITION_MS] ?: 0L,
            serverUrl = this[Keys.SERVER_URL] ?: "",
            username = this[Keys.USERNAME] ?: "",
            password = this[Keys.PASSWORD] ?: "",
            excludedFolders = this[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            hasSetupDefaultExcludes = this[Keys.HAS_SETUP_DEFAULT_EXCLUDES] ?: false,
            maxBitRate = maxBitRate,
            downloadMaxBitRate = downloadMaxBitRate,
            downloadFolderUri = this[Keys.DOWNLOAD_FOLDER_URI]
        )
    }

    suspend fun setNavidromeSettings(serverUrl: String, username: String, password: String) {
        val currentConfig = awaitServerConnectionConfig()
        dataStore.edit {
            it[Keys.SERVER_URL] = serverUrl
            it[Keys.USERNAME] = username
            it[Keys.PASSWORD] = password
        }
        _serverConnectionConfig.value = currentConfig.copy(
            serverUrl = serverUrl,
            username = username,
            password = password
        )
    }

    suspend fun setExcludedFolders(folders: Set<String>) {
        dataStore.edit { it[Keys.EXCLUDED_FOLDERS] = folders }
    }

    suspend fun setFolderExcluded(folderPath: String, excluded: Boolean) {
        dataStore.edit { prefs ->
            val updated = (prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()).toMutableSet()
            if (excluded) updated.add(folderPath) else updated.remove(folderPath)
            prefs[Keys.EXCLUDED_FOLDERS] = updated
        }
    }

    suspend fun addExcludedFolders(folders: Set<String>) {
        if (folders.isEmpty()) return
        dataStore.edit { prefs ->
            prefs[Keys.EXCLUDED_FOLDERS] = (prefs[Keys.EXCLUDED_FOLDERS] ?: emptySet()) + folders
        }
    }

    suspend fun setHasSetupDefaultExcludes(setup: Boolean) {
        dataStore.edit { it[Keys.HAS_SETUP_DEFAULT_EXCLUDES] = setup }
    }

    suspend fun setMaxBitRate(bitRate: Int?) {
        dataStore.edit { prefs ->
            if (bitRate != null) {
                prefs[Keys.MAX_BIT_RATE] = bitRate
            } else {
                prefs.remove(Keys.MAX_BIT_RATE)
            }
        }
        val currentConfig = awaitServerConnectionConfig()
        _serverConnectionConfig.value = currentConfig.copy(maxBitRate = bitRate)
    }

    suspend fun setDownloadMaxBitRate(bitRate: Int?) {
        dataStore.edit { prefs ->
            if (bitRate != null) {
                prefs[Keys.DOWNLOAD_MAX_BIT_RATE] = bitRate
            } else {
                prefs.remove(Keys.DOWNLOAD_MAX_BIT_RATE)
            }
        }
    }

    suspend fun setDownloadFolderUri(uri: String?) {
        dataStore.edit { prefs ->
            if (uri.isNullOrBlank()) prefs.remove(Keys.DOWNLOAD_FOLDER_URI)
            else prefs[Keys.DOWNLOAD_FOLDER_URI] = uri
        }
    }
}
