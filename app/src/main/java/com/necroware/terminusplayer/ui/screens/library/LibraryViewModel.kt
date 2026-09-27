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
import com.necroware.terminusplayer.data.repository.NavidromeUploadRepository
import com.necroware.terminusplayer.data.model.Playlist
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.necroware.terminusplayer.playback.PlaybackController
import com.necroware.terminusplayer.util.toMediaItems
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
    private val uploadRepository: NavidromeUploadRepository
) : ViewModel() {

    private val sortOrder: StateFlow<LibrarySortOrder> = preferencesRepository.preferences
        .map { it.librarySortOrder }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibrarySortOrder())

    val songs: StateFlow<List<Song>> =
        combine(repository.observeAllSongs(), sortOrder) { songs, order -> songs.sortedWith(order) }
            .flowOn(kotlinx.coroutines.Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localSongs: StateFlow<List<Song>> = songs
        .map { allSongs -> allSongs.filter { it.providerId == "local" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val albums: StateFlow<List<Album>> = repository.observeAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val artists: StateFlow<List<Artist>> = repository.observeAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<Playlist>> = repository.observeCustomPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    fun playSong(song: Song, queue: List<Song>) {
        val index = queue.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackController.playSongs(queue.toMediaItems(), index)
    }

    fun toggleLike(songId: String) {
        viewModelScope.launch { repository.toggleLike(songId) }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) = viewModelScope.launch {
        repository.addSongToPlaylist(playlistId, songId)
        _actionMessage.value = "Added to playlist. Server sync will run when connected."
    }

    fun uploadSong(song: Song) = viewModelScope.launch {
        _actionMessage.value = "Uploading ${song.title}..."
        try {
            val fileName = uploadRepository.upload(Uri.parse(song.uriString))
            _actionMessage.value = "Uploaded $fileName."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Upload failed: ${e.message ?: "unknown error"}"
        }
    }

    fun downloadSong(song: Song) = viewModelScope.launch {
        _actionMessage.value = "Downloading ${song.title}..."
        try {
            repository.downloadSong(song.id)
            _actionMessage.value = "Downloaded ${song.title}."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Download failed: ${e.message ?: "unknown error"}"
        }
    }

    fun dismissActionMessage() { _actionMessage.value = null }
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
