package com.necroware.terminusplayer.ui.screens.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.data.model.PlaylistOfflineAvailability
import com.necroware.terminusplayer.data.model.PlaylistDownloadPlan
import com.necroware.terminusplayer.data.model.SongTransferStatus
import com.necroware.terminusplayer.data.model.playlistOfflineAvailabilityFromResult
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import com.necroware.terminusplayer.playback.PlaybackController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Route arg is either a [PlaylistKind] name (LIKED/RECENT/MOST_PLAYED) or
 *  "custom:<id>" for a user-imported playlist — see [Destination.PlaylistDetail]. */
@Immutable
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

@Immutable
data class UploadMatchResolutionState(
    val localSong: Song,
    val candidates: List<Song> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
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
    private val _uploadMatchResolution = MutableStateFlow<UploadMatchResolutionState?>(null)
    val uploadMatchResolution: StateFlow<UploadMatchResolutionState?> = _uploadMatchResolution.asStateFlow()
    private val _transferStatuses = MutableStateFlow<Map<String, SongTransferStatus>>(emptyMap())
    val transferStatuses: StateFlow<Map<String, SongTransferStatus>> = _transferStatuses.asStateFlow()
    val currentPlayingSongId: StateFlow<String?> = playbackController.state
        .map { state -> state.mediaId.takeIf { state.isPlaying } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private var offlineCheckRequestId = 0L

    init {
        viewModelScope.launch {
            uploadQueueManager.observeSongTransferStatuses().collect { statuses ->
                _transferStatuses.value = statuses
            }
        }
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
                _uiState.value = PlaylistDetailUiState(
                    isLoading = false,
                    kind = resolvedKind,
                    title = resolvedKind.title,
                    emptyMessage = when (resolvedKind) {
                        PlaylistKind.LIKED -> "[ nothing liked yet — tap the heart on Now Playing ]"
                        PlaylistKind.RECENT -> "[ nothing played yet ]"
                        PlaylistKind.MOST_PLAYED -> "[ nothing played yet ]"
                    },
                    songs = songs,
                    offlineAvailability = if (songs.isEmpty()) {
                        PlaylistOfflineAvailability.Ready(emptyPlaylistDownloadPlan())
                    } else PlaylistOfflineAvailability.Checking
                )
                checkOfflineAvailability(null, songs, songs.size)
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
        if (songs.isNotEmpty()) playbackController.playSongsFromSongs(songs, 0)
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

    fun retryTransfer(song: Song) = viewModelScope.launch {
        try {
            uploadQueueManager.retrySongTransfer(song.id)
            _actionMessage.value = "Retry queued for ${song.title}."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not retry transfer: ${e.message ?: "Unknown error"}"
        }
    }

    fun requestUploadMatchResolution(song: Song) = viewModelScope.launch {
        _uploadMatchResolution.value = UploadMatchResolutionState(song)
        try {
            val candidates = repository.getUploadMatchCandidates(song.id)
            _uploadMatchResolution.value = UploadMatchResolutionState(
                localSong = song,
                candidates = candidates,
                isLoading = false,
                errorMessage = if (candidates.isEmpty()) "No unlinked matching Navidrome tracks are cached. Refresh the server library and retry." else null
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uploadMatchResolution.value = UploadMatchResolutionState(song, isLoading = false, errorMessage = e.message ?: "Could not load candidates")
        }
    }

    fun confirmUploadMatch(serverSong: Song) = viewModelScope.launch {
        val resolution = _uploadMatchResolution.value ?: return@launch
        try {
            repository.linkUploadedLocalSong(resolution.localSong.id, serverSong.id)
            _uiState.value = _uiState.value.copy(
                songs = _uiState.value.songs.map { song ->
                    if (song.id == resolution.localSong.id) song.copy(navidromeId = serverSong.providerRemoteId) else song
                }
            )
            _uploadMatchResolution.value = null
            _actionMessage.value = "Linked ${resolution.localSong.title} to ${serverSong.title} on Navidrome."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _uploadMatchResolution.value = resolution.copy(errorMessage = e.message ?: "Could not save this match")
        }
    }

    fun dismissUploadMatchResolution() { _uploadMatchResolution.value = null }

    fun dismissActionMessage() { _actionMessage.value = null }

    fun playFrom(song: Song, songs: List<Song> = _uiState.value.songs, clickedIndex: Int? = null) {
        val index = clickedIndex?.takeIf { it in songs.indices }
            ?: songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
        playbackController.playSongsFromSongs(songs, index)
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
            var localOnlyCount = 0
            var duplicateCount = 0
            for (songId in uniqueIds) {
                when (repository.addSongToPlaylist(playlistId, songId)) {
                    MusicRepository.AddSongResult.ADDED -> addedCount++
                    MusicRepository.AddSongResult.LOCAL_ONLY_UNSUPPORTED -> localOnlyCount++
                    MusicRepository.AddSongResult.ALREADY_PRESENT -> duplicateCount++
                    MusicRepository.AddSongResult.NOT_FOUND -> Unit
                }
            }
            _actionMessage.value = when {
                localOnlyCount > 0 -> "Added $addedCount tracks; $localOnlyCount local-only tracks cannot be added to a Navidrome playlist${if (duplicateCount > 0) "; $duplicateCount already present" else ""}."
                duplicateCount == uniqueIds.size -> "Those tracks are already in that playlist."
                else -> "Added $addedCount selected tracks${if (duplicateCount > 0) "; $duplicateCount already present" else ""}."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not add selected tracks: ${e.message ?: "Unknown error"}"
        }
    }

    fun uploadSelected(songs: List<Song>) = viewModelScope.launch {
        try {
            val localSongs = songs.filter { it.providerId == "local" && it.navidromeId.isNullOrBlank() }.distinctBy { it.id }
            if (localSongs.isEmpty()) {
                _actionMessage.value = "No selected local-only tracks need uploading."
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
            _actionMessage.value = when (repository.addSongToPlaylist(playlistId, songId)) {
                MusicRepository.AddSongResult.ADDED -> "Added track to playlist."
                MusicRepository.AddSongResult.ALREADY_PRESENT -> "Track is already in that playlist."
                MusicRepository.AddSongResult.LOCAL_ONLY_UNSUPPORTED -> "This local-only track cannot be added to a Navidrome playlist."
                MusicRepository.AddSongResult.NOT_FOUND -> "Track or playlist is no longer available."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not add track: ${e.message ?: "Unknown error"}"
        }
    }

    fun removeSongsFromCurrentPlaylist(songIds: List<String>) = viewModelScope.launch {
        val playlistId = _uiState.value.currentPlaylistId ?: return@launch
        try {
            val removedCount = repository.removeSongsFromPlaylist(playlistId, songIds)
            val updatedSongs = _uiState.value.songs.filterNot { it.id in songIds }
            _uiState.value = _uiState.value.copy(songs = updatedSongs)
            checkOfflineAvailability(playlistId, updatedSongs, updatedSongs.size)
            _actionMessage.value = "Removed $removedCount ${if (removedCount == 1) "track" else "tracks"} from playlist."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not remove tracks: ${e.message ?: "Unknown error"}"
        }
    }

    fun movePlaylistEntry(fromIndex: Int, toIndex: Int) = viewModelScope.launch {
        val playlistId = _uiState.value.currentPlaylistId ?: return@launch
        try {
            repository.movePlaylistEntry(playlistId, fromIndex, toIndex)
            val updatedSongs = repository.getCachedSongsForPlaylist(playlistId)
            _uiState.value = _uiState.value.copy(songs = updatedSongs)
            checkOfflineAvailability(playlistId, updatedSongs, updatedSongs.size)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not reorder tracks: ${e.message ?: "Unknown error"}"
        }
    }

    fun resolvePlaylistConflict(keepLocalEdits: Boolean) = viewModelScope.launch {
        val playlistId = _uiState.value.currentPlaylistId ?: return@launch
        try {
            repository.resolvePlaylistConflict(playlistId, keepLocalEdits)
            val songs = repository.getCachedSongsForPlaylist(playlistId)
            _uiState.value = _uiState.value.copy(
                songs = songs,
                errorMessage = null
            )
            checkOfflineAvailability(playlistId, songs, songs.size)
            _actionMessage.value = if (keepLocalEdits) {
                "Kept device edits. Navidrome sync is queued against the saved server version."
            } else {
                "Replaced this playlist with the current Navidrome version."
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not resolve playlist conflict: ${e.message ?: "Server unavailable"}"
        }
    }

    fun keepMissingServerPlaylistLocally() = viewModelScope.launch {
        val playlistId = _uiState.value.currentPlaylistId ?: return@launch
        try {
            repository.resolveMissingServerPlaylistAsLocal(playlistId)
            _actionMessage.value = "Kept this playlist on this device. Navidrome will not recreate it."
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _actionMessage.value = "Could not keep local playlist: ${e.message ?: "Unknown error"}"
        }
    }
}

internal fun parsePlaylistKind(rawArg: String): PlaylistKind? =
    PlaylistKind.values().firstOrNull { it.name == rawArg }
