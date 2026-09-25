package com.necroware.terminusplayer.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.StatsRange
import com.necroware.terminusplayer.data.repository.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isSyncing: Boolean = false,
    val syncError: String? = null,
    val songCount: Int = 0,
    val likedCount: Int = 0,
    val weekPlays: Int = 0,
    val weekMsPlayed: Long = 0L,
    val yourMix: List<Song> = emptyList(),
    val recentlyPlayed: List<Song> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val statsRepository: StatsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSongCount().collect { count ->
                _uiState.value = _uiState.value.copy(songCount = count)
            }
        }
        viewModelScope.launch {
            repository.observeLikedSongCount().collect { count ->
                _uiState.value = _uiState.value.copy(likedCount = count)
            }
        }
        refreshMixAndRecents()
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncError = null)
            try {
                val result = repository.syncLibrary()
                if (result.failedProviders.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        syncError = "Unavailable sources: ${result.failedProviders.joinToString()}"
                    )
                }
                reloadMixAndRecents()
                reloadWeekSummary()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(syncError = e.message ?: "Library sync failed")
            } finally {
                _uiState.value = _uiState.value.copy(isSyncing = false)
            }
        }
    }

    /** Call after a play completes elsewhere so Home reflects the latest listen. */
    fun refreshMixAndRecents() {
        viewModelScope.launch {
            reloadMixAndRecents()
            reloadWeekSummary()
        }
    }

    private suspend fun reloadMixAndRecents() {
        val mix = repository.getYourMix(limit = 25)
        val recent = repository.getRecentlyPlayed(limit = 20)
        _uiState.value = _uiState.value.copy(yourMix = mix, recentlyPlayed = recent)
    }

    private suspend fun reloadWeekSummary() {
        val summary = statsRepository.getSummary(StatsRange.WEEK)
        _uiState.value = _uiState.value.copy(
            weekPlays = summary.totalPlays,
            weekMsPlayed = summary.totalMsPlayed
        )
    }
}
