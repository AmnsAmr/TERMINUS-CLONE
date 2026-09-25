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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private object Keys {
    val THEME_ID = stringPreferencesKey("theme_id")
    val SORT_FIELD = stringPreferencesKey("sort_field")
    val SORT_DIRECTION = stringPreferencesKey("sort_direction")
    val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
    val EQ_BAND_PREFIX = "eq_band_"
    val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
    val CROSSFADE_DURATION_MS = intPreferencesKey("crossfade_duration_ms")
    val PREFER_HW_DECODER = booleanPreferencesKey("prefer_hw_decoder")
    val PLAYBACK_ART_STYLE = stringPreferencesKey("playback_art_style")
    val MOTION_PREFERENCE = stringPreferencesKey("motion_preference")
    val LAST_PLAYED_SONG_ID = stringPreferencesKey("last_played_song_id_v2")
    val LAST_PLAYED_POSITION_MS = longPreferencesKey("last_played_position_ms")
    val SERVER_URL = stringPreferencesKey("server_url")
    val USERNAME = stringPreferencesKey("username")
    val PASSWORD = stringPreferencesKey("password")
    val EXCLUDED_FOLDERS = stringSetPreferencesKey("excluded_folders")
    val HAS_SETUP_DEFAULT_EXCLUDES = booleanPreferencesKey("has_setup_default_excludes")
    val MAX_BIT_RATE = intPreferencesKey("max_bit_rate")
}

private fun eqBandKey(index: Int) = intPreferencesKey("${Keys.EQ_BAND_PREFIX}$index")

data class ServerConnectionConfig(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val maxBitRate: Int? = null,
    val isLoaded: Boolean = false
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val preferences: Flow<UserPreferences> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { prefs -> prefs.toUserPreferences() }
    private val _serverConnectionConfig = MutableStateFlow(ServerConnectionConfig())
    val serverConnectionConfig = _serverConnectionConfig.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            preferences.collect { prefs ->
                _serverConnectionConfig.value = ServerConnectionConfig(
                    serverUrl = prefs.serverUrl,
                    username = prefs.username,
                    password = prefs.password,
                    maxBitRate = prefs.maxBitRate,
                    isLoaded = true
                )
            }
        }
    }

    suspend fun awaitServerConnectionConfig(): ServerConnectionConfig =
        serverConnectionConfig.first { it.isLoaded }

    suspend fun setTheme(themeId: ThemePresetId) {
        dataStore.edit { it[Keys.THEME_ID] = themeId.name }
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

    suspend fun setEqualizerEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.EQ_ENABLED] = enabled }
    }

    suspend fun setEqualizerBand(index: Int, gainDb: Int) {
        dataStore.edit { it[eqBandKey(index)] = gainDb }
    }

    suspend fun setEqualizerBands(gainsDb: List<Int>) {
        dataStore.edit { prefs -> gainsDb.forEachIndexed { index, gain -> prefs[eqBandKey(index)] = gain } }
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.CROSSFADE_ENABLED] = enabled }
    }

    suspend fun setCrossfadeDurationMs(durationMs: Int) {
        dataStore.edit { it[Keys.CROSSFADE_DURATION_MS] = durationMs }
    }

    suspend fun setPreferHardwareDecoder(enabled: Boolean) {
        dataStore.edit { it[Keys.PREFER_HW_DECODER] = enabled }
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
        val sortField = this[Keys.SORT_FIELD]?.let { runCatching { SortField.valueOf(it) }.getOrNull() }
            ?: defaults.librarySortOrder.field
        val sortDirection = this[Keys.SORT_DIRECTION]?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
            ?: defaults.librarySortOrder.direction
        val playbackArtStyle = this[Keys.PLAYBACK_ART_STYLE]?.let { runCatching { PlaybackArtStyle.valueOf(it) }.getOrNull() }
            ?: defaults.playbackArtStyle
        val eqBands = List(5) { index -> this[eqBandKey(index)] ?: 0 }
        val maxBitRate = this[Keys.MAX_BIT_RATE]

        return UserPreferences(
            themeId = themeId,
            librarySortOrder = LibrarySortOrder(sortField, sortDirection),
            equalizer = EqualizerSettings(
                enabled = this[Keys.EQ_ENABLED] ?: defaults.equalizer.enabled,
                bandGainsDb = eqBands
            ),
            crossfade = CrossfadeSettings(
                enabled = this[Keys.CROSSFADE_ENABLED] ?: defaults.crossfade.enabled,
                durationMs = this[Keys.CROSSFADE_DURATION_MS] ?: defaults.crossfade.durationMs
            ),
            preferHardwareDecoder = this[Keys.PREFER_HW_DECODER] ?: defaults.preferHardwareDecoder,
            playbackArtStyle = playbackArtStyle,
            motionPreference = parseMotionPreference(this[Keys.MOTION_PREFERENCE]),
            lastPlayedSongId = this[Keys.LAST_PLAYED_SONG_ID],
            lastPlayedPositionMs = this[Keys.LAST_PLAYED_POSITION_MS] ?: 0L,
            serverUrl = this[Keys.SERVER_URL] ?: "",
            username = this[Keys.USERNAME] ?: "",
            password = this[Keys.PASSWORD] ?: "",
            excludedFolders = this[Keys.EXCLUDED_FOLDERS] ?: emptySet(),
            hasSetupDefaultExcludes = this[Keys.HAS_SETUP_DEFAULT_EXCLUDES] ?: false,
            maxBitRate = maxBitRate
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
}
