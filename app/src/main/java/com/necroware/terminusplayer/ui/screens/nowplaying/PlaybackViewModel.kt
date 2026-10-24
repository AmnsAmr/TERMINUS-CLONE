package com.necroware.terminusplayer.ui.screens.nowplaying

import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.necroware.terminusplayer.data.prefs.HudTelemetryMode
import com.necroware.terminusplayer.data.prefs.PlaybackArtStyle
import com.necroware.terminusplayer.data.prefs.SystemPowerSaverState
import com.necroware.terminusplayer.data.prefs.shouldDeferOptionalWork
import com.necroware.terminusplayer.data.prefs.UserPreferences
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.playback.AudioSpectrum
import com.necroware.terminusplayer.playback.NowPlayingState
import com.necroware.terminusplayer.playback.QueueNeighbour
import com.necroware.terminusplayer.playback.QueueState
import com.necroware.terminusplayer.playback.RemovedQueueItem
import com.necroware.terminusplayer.ui.components.SwipeNeighbours
import com.necroware.terminusplayer.ui.components.SwipeTrack
import com.necroware.terminusplayer.playback.SpectrumFrame
import com.necroware.terminusplayer.playback.PlaybackController
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.Playlist
import com.necroware.terminusplayer.util.EXTERNAL_AUDIO_MEDIA_ID_PREFIX
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import javax.inject.Inject

@HiltViewModel
class PlaybackViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val repository: MusicRepository,
    private val preferencesRepository: UserPreferencesRepository,
    audioSpectrum: AudioSpectrum,
    powerSaverState: SystemPowerSaverState
) : ViewModel() {

    /**
     * Live audio spectrum for visualizers. Collect it only while a visualizer is visible,
     * playback is running and motion is FULL; analysis runs only while collected.
     */
    val spectrum: StateFlow<SpectrumFrame> = audioSpectrum.frames

    /** True when performance mode or Android's power saver asks optional work to wait. */
    val deferOptionalWork: StateFlow<Boolean> =
        combine(preferencesRepository.preferences, powerSaverState.enabled) { prefs, powerSaver ->
            shouldDeferOptionalWork(prefs.performanceMode, powerSaver)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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

    @OptIn(ExperimentalCoroutinesApi::class)
    val serverPingMs: StateFlow<Long?> = preferencesRepository.preferences
        .map { it.hudTelemetryMode == HudTelemetryMode.EXPANDED && it.serverUrl.isNotBlank() }
        .distinctUntilChanged()
        .flatMapLatest { shouldPing ->
            flow {
                if (!shouldPing) {
                    emit(null)
                } else {
                    while (true) {
                        emit(repository.pingServer())
                        delay(15_000L)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    /**
     * Previous and next tracks for swipe-to-skip, in playback order (shuffle and repeat applied), with
     * artwork resolved the same way as the current track's so the cover that slides in is the one
     * that will show.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val swipeNeighbours: StateFlow<SwipeNeighbours> = nowPlaying
        .map { it.previous to it.next }
        .distinctUntilChanged()
        .mapLatest { (previous, next) -> SwipeNeighbours(previous?.toSwipeTrack(), next?.toSwipeTrack()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SwipeNeighbours())

    private suspend fun QueueNeighbour.toSwipeTrack(): SwipeTrack {
        val artUri = artworkUri?.takeIf { it.isNotBlank() }
            ?: repository.getSong(mediaId)?.uriString?.takeIf { it.isNotBlank() }
            ?: mediaId.takeIf { it.startsWith(EXTERNAL_AUDIO_MEDIA_ID_PREFIX) }?.removePrefix(EXTERNAL_AUDIO_MEDIA_ID_PREFIX)
            ?: if (mediaId.startsWith("terminus://")) mediaId else "terminus://$mediaId"
        return SwipeTrack(id = mediaId, title = title, artist = artist, artUri = artUri)
    }

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

    /**
     * The queue for the queue sheet, in playback order. Built from the controller only while the
     * sheet collects it, and rebuilt when the queue, the current item or shuffle changes.
     */
    @OptIn(FlowPreview::class)
    val queue: StateFlow<QueueState> = controller.queueVersion
        // One change (e.g. a removal) reports several player events; rebuild once per burst.
        .debounce(QUEUE_REBUILD_DEBOUNCE_MS)
        .mapLatest { controller.queueSource() }
        .mapLatest { source -> withContext(Dispatchers.Default) { source?.toState() ?: QueueState() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), QueueState())

    private val _lastRemoved = MutableStateFlow<RemovedQueueItem?>(null)

    /** The item a swipe just removed, while its UNDO is offered; null otherwise. */
    val lastRemoved: StateFlow<RemovedQueueItem?> = _lastRemoved
    private var undoTimeout: Job? = null

    fun moveQueueItem(fromIndex: Int, toIndex: Int) = controller.moveQueueItem(fromIndex, toIndex)

    fun playQueueItem(index: Int) = controller.playQueueItem(index)

    fun removeQueueItem(index: Int) {
        val removed = controller.removeQueueItem(index) ?: return
        _lastRemoved.value = removed
        undoTimeout?.cancel()
        undoTimeout = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            if (_lastRemoved.value === removed) _lastRemoved.value = null
        }
    }

    fun undoQueueRemoval() {
        val removed = _lastRemoved.value ?: return
        undoTimeout?.cancel()
        _lastRemoved.value = null
        controller.restoreQueueItem(removed)
    }

    /** Remembers the warp visualizer preset last shown; written only when it changed. */
    fun setWarpPresetId(id: String) {
        viewModelScope.launch { preferencesRepository.setWarpPresetId(id) }
    }
    fun skipToNext() = controller.skipToNext()
    fun skipToPrevious() = controller.skipToPrevious()

    /** Skip committed by a swipe: always changes track, since the neighbour's cover is already showing. */
    fun swipeSkip(toNext: Boolean) {
        if (toNext) controller.skipToNextItem() else controller.skipToPreviousItem()
    }
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

/** How long a swipe-removed queue item can be restored. */
private const val UNDO_WINDOW_MS = 4_000L

/** Groups the player events of one queue change into one rebuild. */
private const val QUEUE_REBUILD_DEBOUNCE_MS = 32L
