package com.necroware.terminusplayer.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.model.PlaylistOfflineAvailability
import com.necroware.terminusplayer.data.model.PlaylistDownloadPlan
import com.necroware.terminusplayer.data.model.playlistOfflineAvailabilityFromResult
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
    val currentPlaylistId: String? = null,
    val emptyMessage: String = "",
    val songs: List<Song> = emptyList(),
    val offlineAvailability: PlaylistOfflineAvailability = PlaylistOfflineAvailability.Checking
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
    private var offlineCheckRequestId = 0L

    init {
        viewModelScope.launch {
            repository.observeCustomPlaylists().collect { _playlists.value = it }
        }
        viewModelScope.launch {
            val playlistId = customPlaylistId
            val resolvedPlaylistId = remotePlaylistId?.let { "server:$it" } ?: playlistId
            if (resolvedPlaylistId != null) {
                val cachedSongs = repository.getCachedSongsForPlaylist(resolvedPlaylistId)
                val name = repository.getPlaylistName(resolvedPlaylistId)
                val remoteTrackCount = repository.getRemotePlaylistSongCount(resolvedPlaylistId)
                _uiState.value = PlaylistDetailUiState(
                    isLoading = cachedSongs.isEmpty() && (remoteTrackCount > 0 || remotePlaylistId != null),
                    kind = null,
                    title = name,
                    currentPlaylistId = resolvedPlaylistId,
                    emptyMessage = when {
                        remotePlaylistId != null && remoteTrackCount > 0 -> "[ connect to the server to fetch these tracks ]"
                        remotePlaylistId != null -> "[ this server playlist has no tracks ]"
                        else -> "[ nothing matched when this was imported ]"
                    },
                    songs = cachedSongs,
                    offlineAvailability = PlaylistOfflineAvailability.Checking
                )

                if (cachedSongs.isNotEmpty()) {
                    checkOfflineAvailability(resolvedPlaylistId, cachedSongs, remoteTrackCount)
                }
                viewModelScope.launch {
                    val refreshError = try {
                        repository.refreshPlaylistSongsFromServer(resolvedPlaylistId)
                        null
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e
                    }
                    val refreshedSongs = repository.getCachedSongsForPlaylist(resolvedPlaylistId)
                    val refreshedCount = repository.getRemotePlaylistSongCount(resolvedPlaylistId)
                    val current = _uiState.value
                    if (current.currentPlaylistId == resolvedPlaylistId) {
                        _uiState.value = current.copy(
                            isLoading = false,
                            songs = refreshedSongs,
                            errorMessage = if (refreshedSongs.isEmpty() && refreshedCount > 0 && refreshError != null) {
                                "Could not refresh this playlist: ${refreshError.message ?: "server unavailable"}"
                            } else null,
                            offlineAvailability = PlaylistOfflineAvailability.Checking
                        )
                        checkOfflineAvailability(resolvedPlaylistId, refreshedSongs, refreshedCount)
                    }
                }
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
                    songs = deduplicatedSongs,
                    offlineAvailability = if (deduplicatedSongs.isEmpty()) {
                        PlaylistOfflineAvailability.Ready(emptyPlaylistDownloadPlan())
                    } else PlaylistOfflineAvailability.Checking
                )
                checkOfflineAvailability(null, deduplicatedSongs, deduplicatedSongs.size)
            }
        }
    }

    private fun checkOfflineAvailability(playlistId: String?, songs: List<Song>, expectedCount: Int) {
        val requestId = ++offlineCheckRequestId
        if (songs.isEmpty()) {
            val current = _uiState.value
            if (current.currentPlaylistId == playlistId && current.songs.isEmpty()) {
                _uiState.value = current.copy(offlineAvailability = PlaylistOfflineAvailability.Ready(emptyPlaylistDownloadPlan()))
            }
            return
        }
        viewModelScope.launch {
            val result = try {
                Result.success(repository.getPlaylistDownloadPlan(songs, expectedCount))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            val current = _uiState.value
            if (requestId == offlineCheckRequestId && current.currentPlaylistId == playlistId && current.songs.map { it.id } == songs.map { it.id }) {
                _uiState.value = current.copy(offlineAvailability = playlistOfflineAvailabilityFromResult(result))
            }
        }
    }

    fun retryOfflineAvailability() = viewModelScope.launch {
        val current = _uiState.value
        val playlistId = current.currentPlaylistId
        val songIds = current.songs.map { it.id }
        val requestId = ++offlineCheckRequestId
        _uiState.value = current.copy(offlineAvailability = PlaylistOfflineAvailability.Checking)
        val expectedCount = try {
            playlistId?.let { repository.getRemotePlaylistSongCount(it) } ?: current.songs.size
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (requestId == offlineCheckRequestId && _uiState.value.currentPlaylistId == playlistId && _uiState.value.songs.map { it.id } == songIds) {
                _uiState.value = _uiState.value.copy(
                    offlineAvailability = PlaylistOfflineAvailability.Failed(e.message ?: "Could not check playlist tracks.")
                )
            }
            return@launch
        }
        if (requestId == offlineCheckRequestId && _uiState.value.currentPlaylistId == playlistId && _uiState.value.songs.map { it.id } == songIds) {
            checkOfflineAvailability(playlistId, current.songs, expectedCount)
        }
    }

    private fun emptyPlaylistDownloadPlan() = PlaylistDownloadPlan(
        emptyList(), emptyList(), 0, 0, emptySet(), emptySet()
    )

    fun playAll(songs: List<Song> = _uiState.value.songs) {
        if (songs.isNotEmpty()) playbackController.playSongs(songs.toMediaItems(), 0)
    }

    fun downloadAll(force: Boolean = false) = viewModelScope.launch {
        try {
            val current = _uiState.value
            val plan = (current.offlineAvailability as? PlaylistOfflineAvailability.Ready)?.plan
            if (plan == null) {
                _actionMessage.value = "Check offline availability before downloading this playlist."
                return@launch
            }
            _uiState.value = current.copy(offlineAvailability = PlaylistOfflineAvailability.Checking)
            val refreshedPlan = try {
                val expectedTrackCount = current.currentPlaylistId
                    ?.let { repository.getRemotePlaylistSongCount(it) }
                    ?.coerceAtLeast(current.songs.size)
                    ?: current.songs.size
                repository.getPlaylistDownloadPlan(current.songs, expectedTrackCount)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_uiState.value.currentPlaylistId == current.currentPlaylistId && _uiState.value.songs.map { it.id } == current.songs.map { it.id }) {
                    _uiState.value = _uiState.value.copy(
                        offlineAvailability = PlaylistOfflineAvailability.Failed(e.message ?: "Could not recheck offline availability.")
                    )
                }
                _actionMessage.value = "Could not recheck offline availability: ${e.message ?: "Unknown error"}"
                return@launch
            }
            val latest = _uiState.value
            if (latest.currentPlaylistId == current.currentPlaylistId && latest.songs.map { it.id } == current.songs.map { it.id }) {
                _uiState.value = latest.copy(offlineAvailability = PlaylistOfflineAvailability.Ready(refreshedPlan))
            }
            val songs = if (force) refreshedPlan.serverSongs else refreshedPlan.downloadableSongs
            if (songs.isEmpty()) {
                _actionMessage.value = when {
                    refreshedPlan.unresolvedTrackCount > 0 -> "${refreshedPlan.unresolvedTrackCount} playlist entries are not loaded yet; refresh while online before downloading them."
                    refreshedPlan.unavailableCount > 0 -> "No downloadable tracks are available; ${refreshedPlan.unavailableCount} tracks have no local or server source."
                    refreshedPlan.alreadyOfflineCount == current.songs.size && current.songs.isNotEmpty() -> "All tracks in this playlist are already available offline."
                    else -> "No tracks in this playlist are available for download."
                }
                return@launch
            }
            uploadQueueManager.enqueueDownloads(songs, force)
            _actionMessage.value = "Queued ${songs.size} ${if (force) "server tracks for re-download" else "missing downloads"}. Track progress in Stats."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue downloads: ${e.message ?: "Unknown error"}"
        }
    }
    
    fun downloadSong(song: Song) = viewModelScope.launch {
        try {
            val plan = repository.getPlaylistDownloadPlan(listOf(song))
            if (plan.downloadableSongs.isEmpty()) {
                _actionMessage.value = if (plan.alreadyOfflineCount > 0) "${song.title} is already available offline."
                else "${song.title} is not available from the server."
            } else {
                uploadQueueManager.enqueueDownloads(plan.downloadableSongs)
                _actionMessage.value = "Queued ${song.title} for download. Track progress in Stats."
            }
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

    fun playFrom(song: Song, songs: List<Song> = _uiState.value.songs) {
        val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackController.playSongs(songs.toMediaItems(), index)
    }

    fun downloadSelected(songs: List<Song>) = viewModelScope.launch {
        try {
            if (_uiState.value.offlineAvailability !is PlaylistOfflineAvailability.Ready) {
                _actionMessage.value = "Check offline availability before downloading selected tracks."
                return@launch
            }
            val plan = repository.getPlaylistDownloadPlan(songs.distinctBy { it.id })
            if (plan.downloadableSongs.isEmpty()) {
                _actionMessage.value = when {
                    plan.unresolvedTrackCount > 0 -> "Some selected tracks are not loaded yet; refresh the playlist while online."
                    plan.alreadyOfflineCount == songs.size && songs.isNotEmpty() -> "All selected tracks are already available offline."
                    plan.unavailableCount > 0 -> "${plan.unavailableCount} selected tracks are unavailable for download."
                    else -> "No selected tracks are currently downloadable."
                }
            } else {
                uploadQueueManager.enqueueDownloads(plan.downloadableSongs)
                _actionMessage.value = buildString {
                    append("Queued ${plan.downloadableSongs.size} of ${songs.size} selected tracks; ${plan.alreadyOfflineCount} already offline")
                    if (plan.unavailableCount > 0) append("; ${plan.unavailableCount} unavailable")
                    append(". Track progress in Stats.")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue selected downloads: ${e.message ?: "Unknown error"}"
        }
    }

    fun addSongsToPlaylist(playlistId: String, songIds: List<String>) = viewModelScope.launch {
        try {
            val uniqueIds = songIds.distinct()
            var addedCount = 0
            for (songId in uniqueIds) if (repository.addSongToPlaylist(playlistId, songId)) addedCount++
            _actionMessage.value = when {
                addedCount == uniqueIds.size -> "Added $addedCount selected tracks."
                addedCount == 0 -> "Those tracks are already in that playlist."
                else -> "Added $addedCount tracks; ${uniqueIds.size - addedCount} were already there."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not add selected tracks: ${e.message ?: "Unknown error"}"
        }
    }

    fun uploadSelected(songs: List<Song>) = viewModelScope.launch {
        try {
            val localSongs = songs.filter { it.providerId == "local" }.distinctBy { it.id }
            if (localSongs.isEmpty()) {
                _actionMessage.value = "No selected local tracks can be uploaded."
            } else {
                uploadQueueManager.enqueueUploads(localSongs)
                _actionMessage.value = "Queued ${localSongs.size} selected local tracks for upload. Track progress in Stats."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not queue selected uploads: ${e.message ?: "Unknown error"}"
        }
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
        try {
            _actionMessage.value = if (repository.addSongToPlaylist(playlistId, songId)) {
                "Added track to playlist."
            } else {
                "Track is already in that playlist."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not add track: ${e.message ?: "Unknown error"}"
        }
    }
}

internal fun parsePlaylistKind(rawArg: String): PlaylistKind? =
    PlaylistKind.values().firstOrNull { it.name == rawArg }
