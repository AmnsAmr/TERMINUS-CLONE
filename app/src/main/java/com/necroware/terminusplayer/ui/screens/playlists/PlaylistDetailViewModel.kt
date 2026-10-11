package com.necroware.terminusplayer.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import com.necroware.terminusplayer.playback.PlaybackController
import com.necroware.terminusplayer.util.toMediaItems
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Route arg is either a [PlaylistKind] name (LIKED/RECENT/MOST_PLAYED) or
 *  "custom:<id>" for a user-imported playlist — see [Destination.PlaylistDetail]. */
data class PlaylistDetailUiState(
    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val kind: PlaylistKind? = null,
    val title: String = "",
    val emptyMessage: String = "",
    val songs: List<Song> = emptyList()
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MusicRepository,
    private val playbackController: PlaybackController,
    private val uploadQueueManager: UploadQueueManager
) : ViewModel() {

    private val rawArg: String = savedStateHandle.get<String>("kind").orEmpty()
    private val customPlaylistId: String? = rawArg.removePrefix("custom:")
        .takeIf { rawArg.startsWith("custom:") && it.isNotBlank() }
    private val remotePlaylistId: String? = rawArg.removePrefix("remote:")
        .takeIf { rawArg.startsWith("remote:") && it.isNotBlank() }
    private val kind: PlaylistKind? = if (customPlaylistId == null && remotePlaylistId == null) parsePlaylistKind(rawArg) else null

    private val _uiState = MutableStateFlow(PlaylistDetailUiState(kind = kind))
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val _actionMessage = MutableStateFlow<String?>(null)
    val actionMessage: StateFlow<String?> = _actionMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeCustomPlaylists().collect { _playlists.value = it }
        }
        viewModelScope.launch {
            val playlistId = customPlaylistId
            val resolvedPlaylistId = remotePlaylistId?.let { "server:$it" } ?: playlistId
            if (resolvedPlaylistId != null) {
                val songs = repository.getSongsForPlaylist(resolvedPlaylistId)
                val deduplicatedSongs = songs.groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                    .map { (_, group) -> group.find { it.providerId == "local" } ?: group.first() }
                
                val name = repository.getPlaylistName(resolvedPlaylistId)
                val remoteTrackCount = repository.getRemotePlaylistSongCount(resolvedPlaylistId)
                _uiState.value = PlaylistDetailUiState(
                    isLoading = false,
                    kind = null,
                    title = name,
                    emptyMessage = when {
                        remotePlaylistId != null && remoteTrackCount > 0 -> "[ connect to the server to fetch these tracks ]"
                        remotePlaylistId != null -> "[ this server playlist has no tracks ]"
                        else -> "[ nothing matched when this was imported ]"
                    },
                    songs = deduplicatedSongs
                )
            } else {
                val resolvedKind = kind
                if (resolvedKind == null) {
                    _uiState.value = PlaylistDetailUiState(
                        isLoading = false,
                        title = "Unavailable",
                        emptyMessage = "[ this playlist link is no longer valid ]",
                        errorMessage = "Playlist details are unavailable."
                    )
                    return@launch
                }
                val songs = when (resolvedKind) {
                    PlaylistKind.LIKED -> repository.getLikedSongs()
                    PlaylistKind.RECENT -> repository.getRecentlyPlayed(limit = 100)
                    PlaylistKind.MOST_PLAYED -> repository.getMostPlayed(limit = 100)
                }
                val deduplicatedSongs = songs.groupBy { "${it.title.trim().lowercase()}|${it.artist.trim().lowercase()}" }
                    .map { (_, group) -> group.find { it.providerId == "local" } ?: group.first() }

                _uiState.value = PlaylistDetailUiState(
                    isLoading = false,
                    kind = resolvedKind,
                    title = resolvedKind.title,
                    emptyMessage = when (resolvedKind) {
                        PlaylistKind.LIKED -> "[ nothing liked yet — tap the heart on Now Playing ]"
                        PlaylistKind.RECENT -> "[ nothing played yet ]"
                        PlaylistKind.MOST_PLAYED -> "[ nothing played yet ]"
                    },
                    songs = deduplicatedSongs
                )
            }
        }
    }

    fun playAll() {
        val songs = _uiState.value.songs
        if (songs.isNotEmpty()) playbackController.playSongs(songs.toMediaItems(), 0)
    }

    
    fun downloadAll(force: Boolean = false) = viewModelScope.launch {
        try {
            val songs = _uiState.value.songs.filter { it.providerId == "navidrome" }
            uploadQueueManager.enqueueDownloads(songs, force)
            _actionMessage.value = "Queued ${songs.size} downloads. Track progress in Stats."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue downloads: ${e.message ?: "Unknown error"}"
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

    fun uploadSong(song: Song) = viewModelScope.launch {
        try {
            uploadQueueManager.enqueueUploads(listOf(song))
            _actionMessage.value = "Queued ${song.title} for upload. Track progress in Stats."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue upload: ${e.message ?: "Unknown error"}"
        }
    }

    fun dismissActionMessage() { _actionMessage.value = null }

    fun playFrom(song: Song) {
        val songs = _uiState.value.songs
        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackController.playSongs(songs.toMediaItems(), index)
    }

    fun playNext(song: Song) = playbackController.playSongNext(song)

    fun toggleLike(song: Song) {
        viewModelScope.launch {
            val change = repository.toggleLike(song.id)
            _uiState.value = _uiState.value.copy(
                songs = _uiState.value.songs.map { current ->
                    if (current.id == song.id) current.copy(isLiked = change.isLiked) else current
                }
            )
        }
    }

    fun addSongToPlaylist(playlistId: String, songId: String) = viewModelScope.launch {
        repository.addSongToPlaylist(playlistId, songId)
    }
}

internal fun parsePlaylistKind(rawArg: String): PlaylistKind? =
    PlaylistKind.values().firstOrNull { it.name == rawArg }
