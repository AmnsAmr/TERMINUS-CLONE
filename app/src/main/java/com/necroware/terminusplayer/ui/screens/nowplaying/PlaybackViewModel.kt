package com.necroware.terminusplayer.ui.screens.nowplaying

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.PlaybackArtStyle
import com.necroware.terminusplayer.data.prefs.UserPreferences
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.playback.NowPlayingState
import com.necroware.terminusplayer.playback.PlaybackController
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.util.EXTERNAL_AUDIO_MEDIA_ID_PREFIX
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val repository: MusicRepository,
    private val preferencesRepository: UserPreferencesRepository
) : ViewModel() {

    private val _sleepTimerRemainingMs = MutableStateFlow<Long?>(null)
    val sleepTimerRemainingMs: StateFlow<Long?> = _sleepTimerRemainingMs.asStateFlow()
    private val _isSleepCollapsing = MutableStateFlow(false)
    val isSleepCollapsing: StateFlow<Boolean> = _isSleepCollapsing.asStateFlow()
    private var initialVolumeForFade: Float? = null

    val preferences: StateFlow<UserPreferences> = preferencesRepository.preferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UserPreferences())

    private val _pendingSong = MutableStateFlow<Song?>(null)
    val pendingSong: StateFlow<Song?> = _pendingSong.asStateFlow()
    private val _pendingPlaybackError = MutableStateFlow<String?>(null)
    val pendingPlaybackError: StateFlow<String?> = _pendingPlaybackError.asStateFlow()
    private var queueAppliedForPendingSong = false
    private var latestPlayRequest = 0L
    private var sleepTimerJob: Job? = null

    val nowPlaying: StateFlow<NowPlayingState> = controller.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NowPlayingState())

    val artStyle: StateFlow<PlaybackArtStyle> = preferencesRepository.preferences
        .map { it.playbackArtStyle }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlaybackArtStyle.STANDARD)

    val bufferedPositionMs: StateFlow<Long> = flow {
        while (true) {
            emit(controller.bufferedPositionMs())
            delay(if (nowPlaying.value.isPlaying) 1000L else 3000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val serverPingMs: StateFlow<Long?> = flow {
        while (true) {
            emit(repository.pingServer())
            delay(10_000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Media3's MediaController doesn't preserve a MediaItem's local content
     * URI (see MusicRepository.getSongUri for why), so the current track's
     * real file URI — needed for reliable per-file album art — is resolved
     * via a repository lookup whenever the track changes, rather than read
     * directly off nowPlaying.
     */
    val currentSongUri: StateFlow<String?> = nowPlaying
        .map { it.mediaId }
        .distinctUntilChanged()
        .map { songId ->
            songId?.let {
                repository.getSong(it)?.uriString
                    ?: it.takeIf { id -> id.startsWith(EXTERNAL_AUDIO_MEDIA_ID_PREFIX) }
                        ?.removePrefix(EXTERNAL_AUDIO_MEDIA_ID_PREFIX)
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val currentLyrics: StateFlow<SyncedLyrics?> = nowPlaying
        .map { it.mediaId }
        .distinctUntilChanged()
        .map { songId -> songId?.let { repository.getLyrics(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Only actively polls while something is collecting positionMs (i.e.
    // Now Playing is visible) — WhileSubscribed stops the underlying flow
    // ~5s after the last collector goes away, instead of ticking in the
    // background for the whole app session.
    val positionMs: StateFlow<Long> = flow {
        while (true) {
            emit(controller.currentPositionMs())
            delay(if (nowPlaying.value.isPlaying) 500L else 2000L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isCurrentLiked: StateFlow<Boolean> =
        combine(nowPlaying, repository.observeLikedIds()) { playing, likedIds ->
            val currentId = playing.mediaId
            currentId != null && currentId in likedIds
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val playlists: StateFlow<List<Playlist>> = repository.observeCustomPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _playlistActionMessage = MutableStateFlow<String?>(null)
    val playlistActionMessage: StateFlow<String?> = _playlistActionMessage.asStateFlow()

    init {
        controller.connect()
        viewModelScope.launch {
            controller.state.collect { state ->
                val selected = _pendingSong.value ?: return@collect
                if (queueAppliedForPendingSong && state.mediaId == selected.id) {
                    _pendingSong.value = null
                    _pendingPlaybackError.value = null
                    queueAppliedForPendingSong = false
                } else if (state.connectionError != null) {
                    _pendingPlaybackError.value = state.connectionError
                }
            }
        }
    }

    fun togglePlayPause() = controller.togglePlayPause()
    fun skipToNext() = controller.skipToNext()
    fun skipToPrevious() = controller.skipToPrevious()
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun toggleShuffle() = controller.toggleShuffle()
    fun cycleRepeatMode() = controller.cycleRepeatMode()
    fun setVolume(volume: Float) = controller.setVolume(volume)
    fun currentVolume(): Float = controller.currentVolume()

    fun startSleepTimer(minutes: Int) {
        if (minutes <= 0) return
        sleepTimerJob?.cancel()
        initialVolumeForFade?.let { controller.setVolume(it) }
        val initialVolume = controller.currentVolume().coerceAtLeast(0.05f)
        initialVolumeForFade = initialVolume

        sleepTimerJob = viewModelScope.launch {
            val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
            while (true) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                _sleepTimerRemainingMs.value = remaining
                if (remaining <= 60_000L) {
                    val volumeFraction = (remaining.toFloat() / 60_000f).coerceIn(0f, 1f)
                    controller.setVolume(initialVolume * volumeFraction)
                }
                delay(minOf(remaining, 1_000L))
            }
            _sleepTimerRemainingMs.value = 0L
            _isSleepCollapsing.value = true
            delay(380L)
            controller.stopPlayback()
            controller.setVolume(initialVolume)
            _isSleepCollapsing.value = false
            _sleepTimerRemainingMs.value = null
            initialVolumeForFade = null
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        initialVolumeForFade?.let { controller.setVolume(it) }
        initialVolumeForFade = null
        _isSleepCollapsing.value = false
        _sleepTimerRemainingMs.value = null
    }

    override fun onCleared() {
        super.onCleared()
        initialVolumeForFade?.let { controller.setVolume(it) }
    }

    fun toggleCurrentLike() {
        val currentId = nowPlaying.value.mediaId ?: return
        viewModelScope.launch { repository.toggleLike(currentId) }
    }

    fun addSongToPlaylist(songId: String, playlistId: String) {
        viewModelScope.launch {
            try {
                _playlistActionMessage.value = when (repository.addSongToPlaylist(playlistId, songId)) {
                    MusicRepository.AddSongResult.ADDED -> "Added to playlist."
                    MusicRepository.AddSongResult.ALREADY_PRESENT -> "This track is already in that playlist."
                    MusicRepository.AddSongResult.LOCAL_ONLY_UNSUPPORTED -> "This track cannot be added to that server playlist."
                    MusicRepository.AddSongResult.NOT_FOUND -> "Track or playlist is no longer available."
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _playlistActionMessage.value = "Could not add track: ${e.message ?: "Unknown error"}"
            }
        }
    }

    fun clearPlaylistActionMessage() {
        _playlistActionMessage.value = null
    }

    fun playQueue(items: List<androidx.media3.common.MediaItem>, startIndex: Int) {
        controller.playSongs(items, startIndex)
    }

    fun playSongQueue(songs: List<Song>, startIndex: Int, onPlayerPresented: () -> Unit = {}) {
        val request = ++latestPlayRequest
        val selected = songs.getOrNull(startIndex)
        _pendingSong.value = songs.getOrNull(startIndex)
        _pendingPlaybackError.value = null
        queueAppliedForPendingSong = false
        // Publish the selected song and present the player before asynchronous
        // URI resolution. Playback still starts only after the controller has
        // atomically installed the newest requested queue.
        onPlayerPresented()
        controller.playSongsFromSongs(songs, startIndex) {
            if (request != latestPlayRequest) return@playSongsFromSongs
            queueAppliedForPendingSong = true
            if (selected != null && controller.state.value.mediaId == selected.id) {
                _pendingSong.value = null
                _pendingPlaybackError.value = null
                queueAppliedForPendingSong = false
            }
        }
    }

    fun clearPendingPlaybackSelection() {
        _pendingSong.value = null
        _pendingPlaybackError.value = null
        queueAppliedForPendingSong = false
    }

    fun playExternalAudio(uri: Uri) = controller.playExternalAudio(uri)
}
