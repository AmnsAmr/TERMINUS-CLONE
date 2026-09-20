package com.necroware.terminusplayer.ui.screens.gapfinder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.api.custom.GapFinderSettings
import com.necroware.terminusplayer.data.api.custom.GapFinderStatus
import com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GapFinderViewModel @Inject constructor(
    private val api: NavidromeNativeApiService
) : ViewModel() {

    private val _settings = MutableStateFlow<GapFinderSettings?>(null)
    val settings = _settings.asStateFlow()

    private val _status = MutableStateFlow<GapFinderStatus?>(null)
    val status = _status.asStateFlow()

    private val _errorMessage = MutableStateFlow<String>("")
    val errorMessage = _errorMessage.asStateFlow()

    init {
        fetchSettings()
        fetchStatus()
    }

    private fun fetchSettings() = viewModelScope.launch {
        runCatching {
            val response = api.getGapfinderSettings()
            if (response.isSuccessful) {
                _settings.value = response.body()
            } else {
                _errorMessage.value = "Failed to load settings: ${response.code()}"
            }
        }.onFailure {
            _errorMessage.value = "Error loading settings: ${it.message}"
        }
    }

    private fun fetchStatus() = viewModelScope.launch {
        runCatching {
            val response = api.getGapfinderStatus()
            if (response.isSuccessful) {
                _status.value = response.body()
            }
        }
    }

    fun updateSettings(enabled: Boolean, threshold: Float) = viewModelScope.launch {
        runCatching {
            val newSettings = GapFinderSettings(enabled, threshold)
            val response = api.updateGapfinderSettings(newSettings)
            if (response.isSuccessful) {
                _settings.value = newSettings
                _errorMessage.value = "Settings updated."
            } else {
                _errorMessage.value = "Failed to update settings"
            }
        }.onFailure {
            _errorMessage.value = "Error: ${it.message}"
        }
    }

    fun runGapFinder() = viewModelScope.launch {
        runCatching {
            val response = api.runGapfinder()
            if (response.isSuccessful) {
                _errorMessage.value = "GapFinder started."
                fetchStatus()
            } else {
                _errorMessage.value = "Failed to run GapFinder"
            }
        }.onFailure {
            _errorMessage.value = "Error: ${it.message}"
        }
    }
}
