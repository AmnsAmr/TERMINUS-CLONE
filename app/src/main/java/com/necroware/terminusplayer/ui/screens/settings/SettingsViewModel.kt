package com.necroware.terminusplayer.ui.screens.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.LibrarySortOrder
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.data.prefs.PerformanceMode
import com.necroware.terminusplayer.data.prefs.PlaybackArtStyle
import com.necroware.terminusplayer.data.prefs.SortDirection
import com.necroware.terminusplayer.data.prefs.SortField
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.data.prefs.ThemeColorRole
import com.necroware.terminusplayer.data.prefs.UserPreferences
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.prefs.SystemPowerSaverState
import com.necroware.terminusplayer.data.prefs.effectiveMotionPreference as resolveMotionPreference
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ImportStatus {
    data object Idle : ImportStatus
    data object Running : ImportStatus
    data class FilesDone(val count: Int) : ImportStatus
    data class PlaylistDone(val matched: Int, val total: Int) : ImportStatus
    data class Failed(val message: String) : ImportStatus
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: MusicRepository,
    private val preferencesRepository: UserPreferencesRepository,
    powerSaverState: SystemPowerSaverState
) : ViewModel() {

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val effectiveMotionPreference: StateFlow<MotionPreference> = combine(
        preferencesRepository.preferences,
        powerSaverState.enabled
    ) { preferences, powerSaverEnabled ->
        resolveMotionPreference(
            preferences.motionPreference,
            preferences.performanceMode,
            powerSaverEnabled
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionPreference.FULL)

    private val _importStatus = MutableStateFlow<ImportStatus>(ImportStatus.Idle)
    val importStatus: StateFlow<ImportStatus> = _importStatus.asStateFlow()

    fun setTheme(id: ThemePresetId) = viewModelScope.launch { preferencesRepository.setTheme(id) }

    fun setThemeColor(role: ThemeColorRole, hex: String) = viewModelScope.launch {
        preferencesRepository.setThemeColor(role, hex)
    }

    fun clearThemeColor(role: ThemeColorRole) = viewModelScope.launch {
        preferencesRepository.clearThemeColor(role)
    }

    fun clearThemeColors(roles: Set<ThemeColorRole>? = null) = viewModelScope.launch {
        preferencesRepository.clearThemeColors(roles)
    }

    fun setSortField(field: SortField) = viewModelScope.launch {
        preferencesRepository.setSortOrder(preferences.value.librarySortOrder.copy(field = field))
    }

    fun setSortDirection(direction: SortDirection) = viewModelScope.launch {
        preferencesRepository.setSortOrder(preferences.value.librarySortOrder.copy(direction = direction))
    }

    fun setPlaybackArtStyle(style: PlaybackArtStyle) = viewModelScope.launch {
        preferencesRepository.setPlaybackArtStyle(style)
    }

    fun setMotionPreference(preference: MotionPreference) = viewModelScope.launch {
        preferencesRepository.setMotionPreference(preference)
    }

    fun setPerformanceMode(mode: PerformanceMode) = viewModelScope.launch {
        preferencesRepository.setPerformanceMode(mode)
    }

    fun setEqualizerEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setEqualizerEnabled(enabled)
    }

    fun setEqualizerBand(index: Int, gainDb: Int) = viewModelScope.launch {
        preferencesRepository.setEqualizerBand(index, gainDb)
    }

    fun resetEqualizerBands() = viewModelScope.launch {
        preferencesRepository.setEqualizerBands(List(5) { 0 })
    }

    fun setCrossfadeEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setCrossfadeEnabled(enabled)
    }

    fun setCrossfadeDurationMs(durationMs: Int) = viewModelScope.launch {
        preferencesRepository.setCrossfadeDurationMs(durationMs)
    }

    fun setPreferHardwareDecoder(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setPreferHardwareDecoder(enabled)
    }

    private var scanlineIntensityJob: Job? = null
    fun setScanlineIntensity(intensity: Float) {
        scanlineIntensityJob?.cancel()
        scanlineIntensityJob = viewModelScope.launch {
            delay(32L)
            preferencesRepository.setScanlineIntensity(intensity)
        }
    }

    fun setPhosphorAfterglow(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setPhosphorAfterglow(enabled)
    }

    fun setCursorStyle(style: com.necroware.terminusplayer.data.prefs.TerminalCursorStyle) = viewModelScope.launch {
        preferencesRepository.setCursorStyle(style)
    }

    fun setCursorBlinkCadence(cadence: com.necroware.terminusplayer.data.prefs.TerminalCursorBlink) = viewModelScope.launch {
        preferencesRepository.setCursorBlinkCadence(cadence)
    }

    fun setAdaptiveArtAccent(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setAdaptiveArtAccent(enabled)
    }

    fun setTerminalHaptics(haptics: com.necroware.terminusplayer.data.prefs.TerminalHaptics) = viewModelScope.launch {
        preferencesRepository.setTerminalHaptics(haptics)
    }

    fun setSmartBitrateEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setSmartBitrateEnabled(enabled)
    }

    fun setCellularMaxBitRate(bitRate: Int?) = viewModelScope.launch {
        preferencesRepository.setCellularMaxBitRate(bitRate)
    }

    fun setOpenPlayerOnLibraryTrackSelect(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setOpenPlayerOnLibraryTrackSelect(enabled)
    }

    fun setOpenPlayerOnPlaylistTrackSelect(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setOpenPlayerOnPlaylistTrackSelect(enabled)
    }

    fun setReactiveHudFlash(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setReactiveHudFlash(enabled)
    }

    fun setVisualizerStyle(style: com.necroware.terminusplayer.data.prefs.VisualizerStyle) = viewModelScope.launch {
        preferencesRepository.setVisualizerStyle(style)
    }

    fun setHudTelemetryMode(mode: com.necroware.terminusplayer.data.prefs.HudTelemetryMode) = viewModelScope.launch {
        preferencesRepository.setHudTelemetryMode(mode)
    }

    fun setMaxBitRate(bitRate: Int?) = viewModelScope.launch {
        preferencesRepository.setMaxBitRate(bitRate)
    }

    fun setDownloadFolderUri(uri: Uri, grantFlags: Int) = viewModelScope.launch {
        try {
            context.contentResolver.takePersistableUriPermission(uri, grantFlags)
            preferencesRepository.setDownloadFolderUri(uri.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            _downloadFolderError.value = "Couldn't save access to that folder."
        }
    }

    private val _downloadFolderError = MutableStateFlow<String?>(null)
    val downloadFolderError = _downloadFolderError.asStateFlow()

    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _importStatus.value = ImportStatus.Running
        viewModelScope.launch {
            val count = try {
                repository.importAudioFiles(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                    _importStatus.value = ImportStatus.Failed("Couldn't import those files")
                    return@launch
                }
            _importStatus.value = ImportStatus.FilesDone(count)
        }
    }

    fun importPlaylist(uri: Uri) {
        _importStatus.value = ImportStatus.Running
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                displayNameFor(uri).substringBeforeLast('.').ifBlank { "IMPORTED PLAYLIST" }
            }
            val (matched, total) = try {
                repository.importPlaylistFromM3u(uri, name)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                    _importStatus.value = ImportStatus.Failed("Couldn't read that playlist file")
                    return@launch
                }
            _importStatus.value = ImportStatus.PlaylistDone(matched, total)
        }
    }

    fun dismissImportStatus() {
        _importStatus.value = ImportStatus.Idle
    }

    fun setServerSettings(url: String, user: String, pass: String) = viewModelScope.launch {
        preferencesRepository.setNavidromeSettings(url, user, pass)
        repository.retryPendingPlaylistSync()
        androidx.work.WorkManager.getInstance(context).enqueue(
            androidx.work.OneTimeWorkRequestBuilder<com.necroware.terminusplayer.sync.LibrarySyncWorker>().build()
        )
    }

    private fun displayNameFor(uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx) ?: ""
            }
        }
        return uri.lastPathSegment.orEmpty()
    }
}
