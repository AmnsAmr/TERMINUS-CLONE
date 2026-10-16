package com.necroware.terminusplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.data.prefs.ThemeColorRole
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.data.prefs.TerminalCursorStyle
import com.necroware.terminusplayer.data.prefs.TerminalCursorBlink
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.prefs.UserPreferences
import com.necroware.terminusplayer.data.prefs.SystemPowerSaverState
import com.necroware.terminusplayer.data.prefs.effectiveMotionPreference as resolveMotionPreference
import com.necroware.terminusplayer.data.prefs.shouldDeferOptionalWork
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MainActivityThemeState(
    val themeId: ThemePresetId = ThemePresetId.TERMINAL,
    val colorOverrides: Map<ThemeColorRole, String> = emptyMap(),
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.BLOCK,
    val cursorBlinkCadence: TerminalCursorBlink = TerminalCursorBlink.FAST
)

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    preferencesRepository: UserPreferencesRepository,
    powerSaverState: SystemPowerSaverState,
    uploadQueueManager: com.necroware.terminusplayer.data.repository.UploadQueueManager,
    musicRepository: MusicRepository
) : ViewModel() {

    init {
        viewModelScope.launch { uploadQueueManager.resumePendingUploads() }
        viewModelScope.launch { musicRepository.resumePendingLikeSyncs() }
        viewModelScope.launch { musicRepository.retryPendingPlaylistSync() }
        viewModelScope.launch {
            var wasDeferred = false
            combine(preferencesRepository.preferences, powerSaverState.enabled) { prefs, powerSaver ->
                shouldDeferOptionalWork(prefs.performanceMode, powerSaver)
            }.distinctUntilChanged().collect { shouldDefer ->
                if (shouldDefer) {
                    wasDeferred = true
                } else if (wasDeferred) {
                    wasDeferred = false
                    musicRepository.resumePendingLikeSyncs()
                    musicRepository.retryPendingPlaylistSync()
                }
            }
        }
    }

    val themeId: StateFlow<ThemePresetId> = preferencesRepository.preferences
        .map { it.themeId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePresetId.TERMINAL)

    val themeState: StateFlow<MainActivityThemeState> = preferencesRepository.preferences
        .map {
            MainActivityThemeState(
                themeId = it.themeId,
                colorOverrides = it.themeColorOverrides,
                cursorStyle = it.cursorStyle,
                cursorBlinkCadence = it.cursorBlinkCadence
            )
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MainActivityThemeState())

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val motionPreference: StateFlow<MotionPreference> = combine(
        preferencesRepository.preferences.map { it.motionPreference to it.performanceMode }.distinctUntilChanged(),
        powerSaverState.enabled
    ) { (motionPref, perfMode), powerSaverEnabled ->
        resolveMotionPreference(
            motionPref,
            perfMode,
            powerSaverEnabled
        )
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionPreference.FULL)
}
