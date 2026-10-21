package com.necroware.terminusplayer.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Album
import com.necroware.terminusplayer.data.model.Artist
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.prefs.LibrarySortOrder
import com.necroware.terminusplayer.data.prefs.SortDirection
import com.necroware.terminusplayer.data.prefs.SortField
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.necroware.terminusplayer.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.necroware.terminusplayer.data.repository.YouTubeMusicRepository
import javax.inject.Inject

enum class LibraryTab(val label: String) {
    SONGS("SONGS"), LOCAL_MUSIC("LOCAL MUSIC"), ALBUMS("ALBUMS"), ARTISTS("ARTISTS"), YOUTUBE("YOUTUBE")
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val playbackController: PlaybackController,
    private val uploadQueueManager: UploadQueueManager,
    private val youTubeRepository: YouTubeMusicRepository
) : ViewModel() {

    private val _isLibraryLoaded = MutableStateFlow(false)
    val isLibraryLoaded: StateFlow<Boolean> = _isLibraryLoaded.asStateFlow()

    val sortOrder: StateFlow<LibrarySortOrder> = preferencesRepository.preferences
        .map { it.librarySortOrder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibrarySortOrder())

    val songs: StateFlow<List<Song>> =
        combine(repository.observeAllSongs(), sortOrder) { songs, order ->
            songs.sortedWith(order).also { _isLibraryLoaded.value = true }
        }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localSongs: StateFlow<List<Song>> = songs
        .map { allSongs -> allSongs.filter { it.providerId == "local" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<Album>> = repository.observeAllAlbums()
        .flowOn(kotlinx.coroutines.Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<Artist>> = repository.observeAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.observeCustomPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentPlayingSongId: StateFlow<String?> = playbackController.state
        .map { state -> state.mediaId.takeIf { state.isPlaying } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    enum class YouTubeDiscoveryMode(val label: String) {
        POPULAR("POPULAR"), TRENDING("TRENDING"), CHARTS("CHARTS")
    }

    private val _youTubeQuery = MutableStateFlow("")
    val youTubeQuery: StateFlow<String> = _youTubeQuery.asStateFlow()

    private val _youTubeDiscoveryMode = MutableStateFlow(YouTubeDiscoveryMode.POPULAR)
    val youTubeDiscoveryMode: StateFlow<YouTubeDiscoveryMode> = _youTubeDiscoveryMode.asStateFlow()

    private val _youTubeDiscoveryCategory = MutableStateFlow(com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryCategory.FEATURED)
    val youTubeDiscoveryCategory: StateFlow<com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryCategory> = _youTubeDiscoveryCategory.asStateFlow()

    private val _youTubeDiscoveryFilter = MutableStateFlow(com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS.first())
    val youTubeDiscoveryFilter: StateFlow<com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter> = _youTubeDiscoveryFilter.asStateFlow()

    private val _youTubeSearchFilter = MutableStateFlow(com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter.ALL)
    val youTubeSearchFilter: StateFlow<com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter> = _youTubeSearchFilter.asStateFlow()

    private val _youTubeDynamicChips = MutableStateFlow<List<com.necroware.terminusplayer.data.api.youtube.YouTubeFilterChip>>(emptyList())
    val youTubeDynamicChips: StateFlow<List<com.necroware.terminusplayer.data.api.youtube.YouTubeFilterChip>> = _youTubeDynamicChips.asStateFlow()

    private val _selectedDynamicChipParams = MutableStateFlow<String?>(null)
    val selectedDynamicChipParams: StateFlow<String?> = _selectedDynamicChipParams.asStateFlow()

    private val _youTubeTracks = MutableStateFlow<List<Song>>(emptyList())
    val youTubeTracks: StateFlow<List<Song>> = _youTubeTracks.asStateFlow()

    private val _isYouTubeLoading = MutableStateFlow(false)
    val isYouTubeLoading: StateFlow<Boolean> = _isYouTubeLoading.asStateFlow()

    private val _isYouTubeLoadingMore = MutableStateFlow(false)
    val isYouTubeLoadingMore: StateFlow<Boolean> = _isYouTubeLoadingMore.asStateFlow()

    private var youTubeContinuationToken: String? = null

    private val _hasMoreYouTubeTracks = MutableStateFlow(false)
    val hasMoreYouTubeTracks: StateFlow<Boolean> = _hasMoreYouTubeTracks.asStateFlow()

    private val _youTubeSuggestions = MutableStateFlow<List<String>>(emptyList())
    val youTubeSuggestions: StateFlow<List<String>> = _youTubeSuggestions.asStateFlow()

    private val _showSuggestions = MutableStateFlow(false)
    val showSuggestions: StateFlow<Boolean> = _showSuggestions.asStateFlow()

    private val _youTubeError = MutableStateFlow<String?>(null)
    val youTubeError: StateFlow<String?> = _youTubeError.asStateFlow()

    private var suggestionDebounceJob: kotlinx.coroutines.Job? = null

    fun setYouTubeQuery(query: String) {
        _youTubeQuery.value = query
        suggestionDebounceJob?.cancel()
        if (query.trim().length >= 2) {
            suggestionDebounceJob = viewModelScope.launch {
                kotlinx.coroutines.delay(220)
                val res = youTubeRepository.getSearchSuggestions(query)
                val suggestions = res.getOrDefault(emptyList())
                _youTubeSuggestions.value = suggestions
                _showSuggestions.value = suggestions.isNotEmpty()
            }
        } else {
            _youTubeSuggestions.value = emptyList()
            _showSuggestions.value = false
        }
    }

    fun clearYouTubeQuery() {
        _youTubeQuery.value = ""
        _showSuggestions.value = false
        _youTubeSuggestions.value = emptyList()
        _selectedDynamicChipParams.value = null
        _youTubeSearchFilter.value = com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter.ALL
        loadDiscoveryTracks(_youTubeDiscoveryFilter.value)
    }

    fun dismissSuggestions() {
        _showSuggestions.value = false
    }

    fun selectSearchSuggestion(suggestion: String) {
        _youTubeQuery.value = suggestion
        _showSuggestions.value = false
        searchYouTube(query = suggestion)
    }

    fun setSearchFilter(filter: com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter) {
        _youTubeSearchFilter.value = filter
        _selectedDynamicChipParams.value = null
        searchYouTube()
    }

    fun selectDynamicChip(chip: com.necroware.terminusplayer.data.api.youtube.YouTubeFilterChip) {
        _selectedDynamicChipParams.value = chip.params
        searchYouTube(chipParams = chip.params)
    }

    fun setYouTubeDiscoveryCategory(category: com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryCategory) {
        _youTubeDiscoveryCategory.value = category
        val firstFilter = com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS.firstOrNull { it.category == category }
            ?: com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS.first()
        setYouTubeDiscoveryFilter(firstFilter)
    }

    fun setYouTubeDiscoveryFilter(filter: com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter) {
        _youTubeDiscoveryFilter.value = filter
        _youTubeDiscoveryCategory.value = filter.category
        _youTubeQuery.value = ""
        _showSuggestions.value = false
        _selectedDynamicChipParams.value = null
        when (filter.id) {
            "popular" -> _youTubeDiscoveryMode.value = YouTubeDiscoveryMode.POPULAR
            "trending" -> _youTubeDiscoveryMode.value = YouTubeDiscoveryMode.TRENDING
            "charts" -> _youTubeDiscoveryMode.value = YouTubeDiscoveryMode.CHARTS
        }
        loadDiscoveryTracks(filter)
    }

    fun setYouTubeDiscoveryMode(mode: YouTubeDiscoveryMode) {
        _youTubeDiscoveryMode.value = mode
        val matchingFilter = com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS.firstOrNull { it.id == mode.name.lowercase() }
            ?: com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS.first()
        setYouTubeDiscoveryFilter(matchingFilter)
    }

    fun searchYouTube(query: String? = null, mode: YouTubeDiscoveryMode? = null, chipParams: String? = null) {
        suggestionDebounceJob?.cancel()
        _showSuggestions.value = false
        val q = query ?: _youTubeQuery.value
        if (q.isBlank()) {
            if (mode != null) setYouTubeDiscoveryMode(mode) else loadDiscoveryTracks(_youTubeDiscoveryFilter.value)
            return
        }
        val activeParams = chipParams ?: _selectedDynamicChipParams.value ?: _youTubeSearchFilter.value.params
        viewModelScope.launch {
            _isYouTubeLoading.value = true
            _youTubeError.value = null
            youTubeContinuationToken = null
            _hasMoreYouTubeTracks.value = false
            val result = youTubeRepository.searchSongsPaged(query = q, params = activeParams)
            _isYouTubeLoading.value = false
            result.onSuccess { paged ->
                _youTubeTracks.value = paged.songs
                youTubeContinuationToken = paged.continuationToken
                _hasMoreYouTubeTracks.value = !paged.continuationToken.isNullOrBlank()
                _youTubeDynamicChips.value = paged.dynamicFilterChips
                if (paged.songs.isEmpty()) {
                    _youTubeError.value = "No YouTube Music tracks found for \"$q\"."
                }
            }.onFailure { e ->
                _youTubeError.value = "Failed to query YouTube Music: ${e.message ?: "Network error"}"
            }
        }
    }

    private fun loadDiscoveryTracks(filter: com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter) {
        viewModelScope.launch {
            _isYouTubeLoading.value = true
            _youTubeError.value = null
            youTubeContinuationToken = null
            _hasMoreYouTubeTracks.value = false
            val result = youTubeRepository.getDiscoverySongsPaged(filter)
            _isYouTubeLoading.value = false
            result.onSuccess { paged ->
                _youTubeTracks.value = paged.songs
                youTubeContinuationToken = paged.continuationToken
                _hasMoreYouTubeTracks.value = !paged.continuationToken.isNullOrBlank()
                _youTubeDynamicChips.value = emptyList()
                if (paged.songs.isEmpty()) {
                    _youTubeError.value = "No tracks found for ${filter.label}."
                }
            }.onFailure { e ->
                _youTubeError.value = "Failed to load ${filter.label}: ${e.message ?: "Network error"}"
            }
        }
    }

    fun loadMoreYouTubeTracks() {
        if (_isYouTubeLoading.value || _isYouTubeLoadingMore.value) return
        val token = youTubeContinuationToken ?: return
        val q = _youTubeQuery.value
        viewModelScope.launch {
            _isYouTubeLoadingMore.value = true
            val result = if (q.isNotBlank()) {
                val activeParams = _selectedDynamicChipParams.value ?: _youTubeSearchFilter.value.params
                youTubeRepository.searchSongsPaged(query = q, params = activeParams, continuation = token)
            } else {
                youTubeRepository.getDiscoverySongsPaged(filter = _youTubeDiscoveryFilter.value, continuation = token)
            }
            _isYouTubeLoadingMore.value = false
            result.onSuccess { paged ->
                val currentIds = _youTubeTracks.value.map { it.id }.toSet()
                val newUnique = paged.songs.filter { it.id !in currentIds }
                _youTubeTracks.value = _youTubeTracks.value + newUnique
                youTubeContinuationToken = paged.continuationToken
                _hasMoreYouTubeTracks.value = !paged.continuationToken.isNullOrBlank()
            }.onFailure {
                // If continuation fails, keep existing buffer intact
            }
        }
    }

    fun loadYouTubePopular() {
        if (_youTubeTracks.value.isNotEmpty() || _isYouTubeLoading.value) return
        loadDiscoveryTracks(_youTubeDiscoveryFilter.value)
    }

    // The library destination can be disposed while opening Now Playing. Keep a
    // few prepared lists so returning to the same songs does not show a spinner
    // while SongList rebuilds its sections and fast-scroll indexes.
    private val preparedSongListCache = mutableListOf<PreparedSongList>()

    @Synchronized
    internal fun cachedPreparedSongList(songs: List<Song>, field: SortField): PreparedSongList? =
        preparedSongListCache.firstOrNull { it.sourceSongs === songs && it.sortField == field }

    @Synchronized
    internal fun rememberPreparedSongList(prepared: PreparedSongList) {
        preparedSongListCache.removeAll {
            it.sourceSongs === prepared.sourceSongs && it.sortField == prepared.sortField
        }
        preparedSongListCache.add(0, prepared)
        while (preparedSongListCache.size > 4) preparedSongListCache.removeAt(preparedSongListCache.lastIndex)
    }

    fun playSong(song: Song, queue: List<Song>) {
        if (song.providerId == "youtube") {
            viewModelScope.launch { youTubeRepository.ensureTracksPersisted(queue.filter { it.providerId == "youtube" }) }
        }
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackController.playSongsFromSongs(queue, index)
    }

    fun playNext(song: Song) {
        if (song.providerId == "youtube") {
            viewModelScope.launch { youTubeRepository.ensureTrackPersisted(song) }
        }
        playbackController.playSongNext(song)
        _actionMessage.value = "${song.title} added to play next."
    }

    fun toggleLike(songId: String) {
        viewModelScope.launch {
            val youTubeSong = _youTubeTracks.value.firstOrNull { it.id == songId }
            if (youTubeSong != null) {
                youTubeRepository.ensureTrackPersisted(youTubeSong)
            }
            val change = repository.toggleLike(songId)
            val action = if (change.isLiked) "Liked" else "Unliked"
            _actionMessage.value = when {
                !change.serverBacked -> "$action on this device."
                change.syncQueued -> "$action locally; server sync queued."
                else -> "$action synced with Navidrome."
            }
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) = viewModelScope.launch {
        val youTubeSong = _youTubeTracks.value.firstOrNull { it.id == songId }
        if (youTubeSong != null) {
            youTubeRepository.ensureTrackPersisted(youTubeSong)
        }
        _actionMessage.value = when (repository.addSongToPlaylist(playlistId, songId)) {
            MusicRepository.AddSongResult.ADDED -> "Added to playlist."
            MusicRepository.AddSongResult.ALREADY_PRESENT -> "Track is already in that playlist."
            MusicRepository.AddSongResult.LOCAL_ONLY_UNSUPPORTED -> "This local-only track cannot be added to a Navidrome playlist."
            MusicRepository.AddSongResult.NOT_FOUND -> "Track or playlist is no longer available."
        }
    }

    fun addSongsToPlaylist(playlistId: String, songIds: List<String>) = viewModelScope.launch {
        var added = 0
        var localOnly = 0
        var duplicates = 0
        songIds.distinct().forEach { songId ->
            val youTubeSong = _youTubeTracks.value.firstOrNull { it.id == songId }
            if (youTubeSong != null) {
                youTubeRepository.ensureTrackPersisted(youTubeSong)
            }
            when (repository.addSongToPlaylist(playlistId, songId)) {
                MusicRepository.AddSongResult.ADDED -> added++
                MusicRepository.AddSongResult.LOCAL_ONLY_UNSUPPORTED -> localOnly++
                MusicRepository.AddSongResult.ALREADY_PRESENT -> duplicates++
                MusicRepository.AddSongResult.NOT_FOUND -> Unit
            }
        }
        _actionMessage.value = if (localOnly > 0) {
            "Added $added songs; $localOnly local-only tracks cannot be added to a Navidrome playlist${if (duplicates > 0) "; $duplicates already present" else ""}."
        } else "Added $added songs${if (duplicates > 0) "; $duplicates already present" else ""}."
    }

    fun uploadSong(song: Song) = viewModelScope.launch {
        uploadSongs(listOf(song))
    }

    fun uploadSongs(songs: List<Song>) = viewModelScope.launch {
        try {
            val localOnly = songs.filter { it.providerId == "local" && it.navidromeId.isNullOrBlank() }.distinctBy { it.id }
            if (localOnly.isEmpty()) {
                _actionMessage.value = "No selected local-only tracks need uploading."
            } else {
                uploadQueueManager.enqueueUploads(localOnly)
                _actionMessage.value = "Queued ${localOnly.size} local-only ${if (localOnly.size == 1) "song" else "songs"} for upload."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue uploads: ${e.message ?: "Unknown error"}"
        }
    }

    fun downloadSong(song: Song) = viewModelScope.launch {
        if (song.providerId == "jamendo") {
            _actionMessage.value = "Jamendo tracks are streaming-only."
            return@launch
        }
        try {
            if (song.providerId == "youtube") {
                youTubeRepository.ensureTrackPersisted(song)
            }
            uploadQueueManager.enqueueDownloads(listOf(song))
            _actionMessage.value = "Queued ${song.title} for download. Track progress in Stats."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue download: ${e.message ?: "Unknown error"}"
        }
    }

    fun downloadSongs(songs: List<Song>) = viewModelScope.launch {
        val downloadable = songs.filter { it.providerId != "jamendo" }
        if (downloadable.isEmpty()) {
            _actionMessage.value = "Selected tracks cannot be downloaded."
            return@launch
        }
        try {
            val youTubeSongs = downloadable.filter { it.providerId == "youtube" }
            if (youTubeSongs.isNotEmpty()) {
                youTubeRepository.ensureTracksPersisted(youTubeSongs)
            }
            uploadQueueManager.enqueueDownloads(downloadable)
            _actionMessage.value = "Queued ${downloadable.size} ${if (downloadable.size == 1) "song" else "songs"} for download. Track progress in Stats."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue downloads: ${e.message ?: "Unknown error"}"
        }
    }

    fun dismissActionMessage() { _actionMessage.value = null }

    fun setSortField(field: SortField) = viewModelScope.launch {
        preferencesRepository.setSortOrder(sortOrder.value.copy(field = field))
    }

    fun setSortDirection(direction: SortDirection) = viewModelScope.launch {
        preferencesRepository.setSortOrder(sortOrder.value.copy(direction = direction))
    }
}

private fun List<Song>.sortedWith(order: LibrarySortOrder): List<Song> {
    val comparator: Comparator<Song> = when (order.field) {
        SortField.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        SortField.ARTIST -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist }
        SortField.ALBUM -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.album }
        SortField.DATE_ADDED -> compareBy { it.dateAdded }
        SortField.DURATION -> compareBy { it.duration }
    }
    val sorted = sortedWith(comparator)
    return if (order.direction == SortDirection.DESC) sorted.asReversed() else sorted
}
