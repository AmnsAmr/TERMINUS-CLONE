package com.necroware.terminusplayer.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: MusicRepository
) : ViewModel() {

    val customPlaylists: StateFlow<List<Playlist>> = repository.observeCustomPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()

    init { refreshRemotePlaylists() }

    fun refreshRemotePlaylists() = viewModelScope.launch {
        try {
            repository.refreshRemotePlaylists()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Existing server playlists stay available from Room while offline.
        }
    }

    fun createPlaylist(name: String) = viewModelScope.launch {
        try {
            repository.createPlaylist(name)
            _actionMessage.value = "Playlist created. Server sync will run when connected."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = e.message ?: "Couldn't create playlist."
        }
    }

    fun deletePlaylist(id: String) = viewModelScope.launch {
        try {
            repository.deletePlaylist(id)
            _actionMessage.value = "Playlist deleted. Server sync will run when connected."
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _actionMessage.value = "Couldn't delete playlist."
        }
    }

    
    fun downloadPlaylist(id: String, force: Boolean = false) = viewModelScope.launch {
        _actionMessage.value = "Starting playlist download..."
        try {
            val songs = repository.getSongsForPlaylist(id)
            songs.forEach { song ->
                try { repository.downloadSong(song.id, force) } catch (_: Exception) {}
            }
            _actionMessage.value = "Playlist download complete."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Playlist download failed."
        }
    }

    fun dismissActionMessage() { _actionMessage.value = null }
}
