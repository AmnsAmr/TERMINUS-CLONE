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
import javax.inject.Inject

enum class LibraryTab(val label: String) {
    SONGS("SONGS"), LOCAL_MUSIC("LOCAL MUSIC"), ALBUMS("ALBUMS"), ARTISTS("ARTISTS")
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val preferencesRepository: UserPreferencesRepository,
    private val playbackController: PlaybackController,
    private val uploadQueueManager: UploadQueueManager
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
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackController.playSongsFromSongs(queue, index)
    }

    fun playNext(song: Song) {
        playbackController.playSongNext(song)
        _actionMessage.value = "${song.title} added to play next."
    }

    fun toggleLike(songId: String) {
        viewModelScope.launch {
            val change = repository.toggleLike(songId)
            val action = if (change.isLiked) "Liked" else "Unliked"
            _actionMessage.value = if (change.syncQueued) {
                "$action locally; server sync queued."
            } else {
                "$action on this device."
            }
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) = viewModelScope.launch {
        _actionMessage.value = when (repository.addSongToPlaylist(playlistId, songId)) {
            MusicRepository.AddSongResult.ADDED -> "Added to playlist. Server sync will run when connected."
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
        try {
            uploadQueueManager.enqueueDownloads(listOf(song))
            _actionMessage.value = "Queued ${song.title} for download. Track progress in Stats."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue download: ${e.message ?: "Unknown error"}"
        }
    }

    fun downloadSongs(songs: List<Song>) = viewModelScope.launch {
        try {
            uploadQueueManager.enqueueDownloads(songs)
            _actionMessage.value = "Queued ${songs.size} songs for download. Track progress in Stats."
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
