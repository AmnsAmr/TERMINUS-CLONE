package com.necroware.terminusplayer.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.YouTubeMusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchScope(val label: String) {
    LOCAL("LOCAL"),
    YOUTUBE("YOUTUBE")
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val youTubeRepository: YouTubeMusicRepository
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchScope = MutableStateFlow(SearchScope.LOCAL)
    val searchScope: StateFlow<SearchScope> = _searchScope.asStateFlow()

    private val _youTubeFilter = MutableStateFlow(YouTubeSearchFilter.ALL)
    val youTubeFilter: StateFlow<YouTubeSearchFilter> = _youTubeFilter.asStateFlow()

    private val _youTubeResults = MutableStateFlow<List<Song>>(emptyList())
    val youTubeResults: StateFlow<List<Song>> = _youTubeResults.asStateFlow()

    private val _isYouTubeSearching = MutableStateFlow(false)
    val isYouTubeSearching: StateFlow<Boolean> = _isYouTubeSearching.asStateFlow()

    private val _isYouTubeLoadingMore = MutableStateFlow(false)
    val isYouTubeLoadingMore: StateFlow<Boolean> = _isYouTubeLoadingMore.asStateFlow()

    private var youTubeContinuationToken: String? = null

    private val _hasMoreYouTubeResults = MutableStateFlow(false)
    val hasMoreYouTubeResults: StateFlow<Boolean> = _hasMoreYouTubeResults.asStateFlow()

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()

    private val _showSuggestions = MutableStateFlow(false)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    private var youTubeSearchJob: Job? = null
    private var suggestionJob: Job? = null

    val results: StateFlow<List<Song>> = _query
        .debounce(200)
        .distinctUntilChanged()
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList()) else repository.searchSongs(q.trim())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        if (_searchScope.value == SearchScope.YOUTUBE) {
            suggestionJob?.cancel()
            if (newQuery.trim().length >= 2) {
                suggestionJob = viewModelScope.launch {
                    delay(220)
                    val s = youTubeRepository.getSearchSuggestions(newQuery).getOrDefault(emptyList())
                    _suggestions.value = s
                    _showSuggestions.value = s.isNotEmpty()
                }
            } else {
                _suggestions.value = emptyList()
                _showSuggestions.value = false
            }
        }
    }

    fun setSearchScope(scope: SearchScope) {
        _searchScope.value = scope
        _showSuggestions.value = false
        if (scope == SearchScope.YOUTUBE && _query.value.isNotBlank() && _youTubeResults.value.isEmpty()) {
            executeYouTubeSearch()
        }
    }

    fun setYouTubeFilter(filter: YouTubeSearchFilter) {
        _youTubeFilter.value = filter
        if (_query.value.isNotBlank()) {
            executeYouTubeSearch()
        }
    }

    fun selectSuggestion(suggestion: String) {
        _query.value = suggestion
        _showSuggestions.value = false
        executeYouTubeSearch()
    }

    fun dismissSuggestions() {
        _showSuggestions.value = false
    }

    fun clearQuery() {
        _query.value = ""
        _showSuggestions.value = false
        _suggestions.value = emptyList()
        _youTubeResults.value = emptyList()
        youTubeContinuationToken = null
        _hasMoreYouTubeResults.value = false
    }

    fun executeYouTubeSearch() {
        val q = _query.value.trim()
        if (q.isBlank()) {
            _youTubeResults.value = emptyList()
            return
        }
        _showSuggestions.value = false
        suggestionJob?.cancel()
        youTubeSearchJob?.cancel()
        youTubeSearchJob = viewModelScope.launch {
            _isYouTubeSearching.value = true
            youTubeContinuationToken = null
            _hasMoreYouTubeResults.value = false
            val res = youTubeRepository.searchSongsPaged(query = q, params = _youTubeFilter.value.params)
            _isYouTubeSearching.value = false
            res.onSuccess { paged ->
                _youTubeResults.value = paged.songs
                youTubeContinuationToken = paged.continuationToken
                _hasMoreYouTubeResults.value = !paged.continuationToken.isNullOrBlank()
            }.onFailure {
                _youTubeResults.value = emptyList()
            }
        }
    }

    fun loadMoreYouTube() {
        val token = youTubeContinuationToken ?: return
        if (_isYouTubeSearching.value || _isYouTubeLoadingMore.value) return
        val q = _query.value.trim()
        viewModelScope.launch {
            _isYouTubeLoadingMore.value = true
            val res = youTubeRepository.searchSongsPaged(
                query = q,
                params = _youTubeFilter.value.params,
                continuation = token
            )
            _isYouTubeLoadingMore.value = false
            res.onSuccess { paged ->
                val currentIds = _youTubeResults.value.map { it.id }.toSet()
                val newUnique = paged.songs.filter { it.id !in currentIds }
                _youTubeResults.value = _youTubeResults.value + newUnique
                youTubeContinuationToken = paged.continuationToken
                _hasMoreYouTubeResults.value = !paged.continuationToken.isNullOrBlank()
            }
        }
    }
}
