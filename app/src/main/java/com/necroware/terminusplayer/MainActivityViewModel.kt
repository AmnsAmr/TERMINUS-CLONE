package com.necroware.terminusplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.prefs.UserPreferences
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainActivityViewModel @Inject constructor(
    preferencesRepository: UserPreferencesRepository,
    uploadQueueManager: com.necroware.terminusplayer.data.repository.UploadQueueManager,
    musicRepository: MusicRepository
) : ViewModel() {

    init {
        viewModelScope.launch { uploadQueueManager.resumePendingUploads() }
        viewModelScope.launch { musicRepository.resumePendingLikeSyncs() }
    }

    val themeId: StateFlow<ThemePresetId> = preferencesRepository.preferences
        .map { it.themeId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemePresetId.TERMINAL)

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    val motionPreference: StateFlow<MotionPreference> = preferencesRepository.preferences
        .map { it.motionPreference }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MotionPreference.FULL)
}
