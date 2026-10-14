package com.necroware.terminusplayer.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.database.dao.DayPlayCount
import com.necroware.terminusplayer.data.database.dao.HourHistogramRow
import com.necroware.terminusplayer.data.repository.SessionStats
import com.necroware.terminusplayer.data.repository.StatsRange
import com.necroware.terminusplayer.data.repository.StatsRepository
import com.necroware.terminusplayer.data.repository.TopCategory
import com.necroware.terminusplayer.data.repository.TopCategoryItem
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

data class StatsUiState(
    val range: StatsRange = StatsRange.WEEK,
    val isLoading: Boolean = true,
    val totalPlays: Int = 0,
    val totalMsPlayed: Long = 0L,
    val playsByDay: List<DayPlayCount> = emptyList(),
    val playsByHour: List<HourHistogramRow> = emptyList(),
    val sessionStats: SessionStats = SessionStats(0, 0L, 0L),
    val category: TopCategory = TopCategory.ARTIST,
    val topCategoryItems: List<TopCategoryItem> = emptyList(),
    val insights: List<String> = emptyList(),
    // Range-specific chart data — each StatsRange gets its own chart type.
    val monthlyArtistDistribution: List<Pair<String, Int>> = emptyList(),
    val yearlyMonthlyTotals: List<Pair<String, Int>> = emptyList(),
    val uploadQueue: List<UploadQueueEntity> = emptyList(),
    val downloadQueue: List<UploadQueueEntity> = emptyList(),
    val transferWorkerState: WorkInfo.State? = null,
    val transfersPaused: Boolean = false
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val uploadQueueDao: UploadQueueDao,
    private val uploadQueueManager: com.necroware.terminusplayer.data.repository.UploadQueueManager,
    userPreferencesRepository: UserPreferencesRepository,
    @ApplicationContext context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatsUiState())
    val uiState: StateFlow<StatsUiState> = _uiState.asStateFlow()
    private var loadJob: Job? = null

    init {
        loadInsightsOnce()
        load(StatsRange.WEEK, TopCategory.ARTIST)

        viewModelScope.launch {
            userPreferencesRepository.transfersPaused.collect { paused ->
                _uiState.value = _uiState.value.copy(transfersPaused = paused)
            }
        }

        viewModelScope.launch {
            uploadQueueDao.observeAll().collect { queue ->
                _uiState.value = _uiState.value.copy(
                    uploadQueue = queue.filter { it.transferType == "UPLOAD" },
                    downloadQueue = queue.filter { it.transferType == "DOWNLOAD" }
                )
            }
        }

        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(
                com.necroware.terminusplayer.data.repository.UploadQueueManager.UNIQUE_WORK_NAME
            ).collect { infos ->
                _uiState.value = _uiState.value.copy(transferWorkerState = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }?.state
                    ?: infos.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.state)
            }
        }
    }

    fun retryFailedUpload(songId: String) {
        viewModelScope.launch {
            uploadQueueManager.retryUpload(songId)
        }
    }

    fun retryAllFailedUploads() {
        viewModelScope.launch {
            uploadQueueManager.retryAllFailed()
        }
    }

    fun cancelAllUploads() {
        viewModelScope.launch {
            uploadQueueManager.cancelAllUploads()
        }
    }

    fun retryFailedDownload(songId: String) {
        viewModelScope.launch { uploadQueueManager.retryUpload(songId) }
    }

    fun cancelAllTransfers() {
        viewModelScope.launch { uploadQueueManager.cancelAllTransfers() }
    }

    fun pauseTransfers() { viewModelScope.launch { uploadQueueManager.pauseTransfers() } }
    fun resumeTransfers() { viewModelScope.launch { uploadQueueManager.resumeTransfers() } }

    fun removeTransfer(songId: String) {
        viewModelScope.launch { uploadQueueManager.removeFromQueue(songId) }
    }

    fun selectRange(range: StatsRange) {
        load(range, _uiState.value.category)
    }

    fun selectCategory(category: TopCategory) {
        load(_uiState.value.range, category)
    }

    private fun load(range: StatsRange, category: TopCategory) {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(range = range, category = category, isLoading = true)
        loadJob = viewModelScope.launch {
            val summary = statsRepository.getSummary(range)
            val topItems = statsRepository.getTopCategory(range, category)
            val sessionStats = statsRepository.getSessionStats(range)

            // Only fetch the chart data the currently-selected range actually needs.
            val monthlyArtists = if (range == StatsRange.MONTH) statsRepository.getMonthlyArtistDistribution() else emptyList()
            val yearlyMonthly = if (range == StatsRange.YEAR) statsRepository.getYearlyMonthlyTotals() else emptyList()

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                totalPlays = summary.totalPlays,
                totalMsPlayed = summary.totalMsPlayed,
                playsByDay = summary.playsByDay,
                playsByHour = summary.playsByHour,
                sessionStats = sessionStats,
                topCategoryItems = topItems,
                monthlyArtistDistribution = monthlyArtists,
                yearlyMonthlyTotals = yearlyMonthly
            )
        }
    }

    private fun loadInsightsOnce() {
        viewModelScope.launch {
            val insights = statsRepository.generateInsights()
            _uiState.value = _uiState.value.copy(insights = insights)
        }
    }
}
