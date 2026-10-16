package com.necroware.terminusplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.data.prefs.MotionPreference
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
        viewModelScope.launch {
            combine(preferencesRepository.preferences, powerSaverState.enabled) { prefs, powerSaver ->
                shouldDeferOptionalWork(prefs.performanceMode, powerSaver)
            }.distinctUntilChanged().collect { shouldDefer ->
                if (!shouldDefer) {
                    musicRepository.resumePendingLikeSyncs()
                    musicRepository.retryPendingPlaylistSync()
                }
            }
        }
    }

    val themeId: StateFlow<ThemePresetId> = preferencesRepository.preferences
        .map { it.themeId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePresetId.TERMINAL)

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val motionPreference: StateFlow<MotionPreference> = combine(
        preferencesRepository.preferences,
        powerSaverState.enabled
    ) { preferences, powerSaverEnabled ->
        resolveMotionPreference(
            preferences.motionPreference,
            preferences.performanceMode,
            powerSaverEnabled
        )
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionPreference.FULL)
}
