package com.necroware.terminusplayer.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.model.PlaylistDownloadPlan
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class PlaylistDownloadDialogState(
    val playlistId: String? = null,
    val isLoading: Boolean = false,
    val plan: PlaylistDownloadPlan? = null,
    val error: String? = null
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val uploadQueueManager: UploadQueueManager
) : ViewModel() {

    val customPlaylists: StateFlow<List<Playlist>> = repository.observeCustomPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val accountInfo = repository.playlistAccountInfo

    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage = _actionMessage.asStateFlow()
    private val _downloadDialogState = MutableStateFlow(PlaylistDownloadDialogState())
    val downloadDialogState = _downloadDialogState.asStateFlow()

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

    
    fun inspectPlaylistDownloads(id: String, expectedTrackCount: Int = 0, isRemote: Boolean = false) = viewModelScope.launch {
        _downloadDialogState.value = PlaylistDownloadDialogState(playlistId = id, isLoading = true)
        try {
            val songs = repository.getSongsForPlaylist(id)
            val plan = repository.getPlaylistDownloadPlan(
                songs,
                expectedTrackCount = if (isRemote) expectedTrackCount.coerceAtLeast(songs.size) else songs.size
            )
            _downloadDialogState.value = PlaylistDownloadDialogState(playlistId = id, plan = plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _downloadDialogState.value = PlaylistDownloadDialogState(
                playlistId = id,
                error = "Could not check playlist downloads: ${e.message ?: "Unknown error"}"
            )
        }
    }

    fun downloadPlaylist(id: String, force: Boolean = false) = viewModelScope.launch {
        try {
            // Recheck just before queueing because files may have changed since the dialog opened.
            val playlistSongs = repository.getSongsForPlaylist(id)
            val remoteCount = repository.getRemotePlaylistSongCount(id)
            val plan = repository.getPlaylistDownloadPlan(playlistSongs, remoteCount.coerceAtLeast(playlistSongs.size))
            val songsToQueue = if (force) plan.serverSongs else plan.downloadableSongs
            if (songsToQueue.isEmpty()) {
                _actionMessage.value = if (plan.unavailableCount > 0) {
                    "No downloadable tracks are available; ${plan.unavailableCount} tracks have no local or server source."
                } else if (plan.unresolvedTrackCount > 0) {
                    "Some playlist entries are not cached yet. Refresh the playlist while online before downloading them."
                } else {
                    "All available tracks in this playlist are already offline."
                }
                return@launch
            }
            uploadQueueManager.enqueueDownloads(songsToQueue, force)
            _actionMessage.value = "Queued ${songsToQueue.size} of ${plan.serverSongs.size} server tracks for ${if (force) "re-download" else "download"}. Track progress in Stats."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue playlist downloads: ${e.message ?: "Unknown error"}"
        }
    }

    fun dismissDownloadDialog() {
        _downloadDialogState.value = PlaylistDownloadDialogState()
    }

    fun dismissActionMessage() { _actionMessage.value = null }
}
