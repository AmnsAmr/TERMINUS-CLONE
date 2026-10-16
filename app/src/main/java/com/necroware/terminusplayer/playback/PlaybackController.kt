package com.necroware.terminusplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.ListenableFuture
import com.necroware.terminusplayer.util.albumIdOrNull
import com.necroware.terminusplayer.util.bitrateKbpsOrNegative
import com.necroware.terminusplayer.util.isLosslessFormat
import com.necroware.terminusplayer.util.sizeBytesOrZero
import com.necroware.terminusplayer.util.toBitDepthLabel
import com.necroware.terminusplayer.util.toCodecLabel
import com.necroware.terminusplayer.util.toSampleRateLabel
import com.necroware.terminusplayer.util.selectedAudioFormat
import com.necroware.terminusplayer.util.toAudioFormatLabel
import com.necroware.terminusplayer.util.providerIdOrNull
import com.necroware.terminusplayer.util.streamOffsetMsOrZero
import com.necroware.terminusplayer.util.withStreamUriAndOffset
import com.necroware.terminusplayer.util.toMediaItem
import com.necroware.terminusplayer.util.EXTERNAL_AUDIO_MEDIA_ID_PREFIX
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import androidx.media3.common.C
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.atomic.AtomicLong

data class NowPlayingState(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumId: String = "",
    val artworkUri: String? = null,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val shuffleEnabled: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val audioFormatLabel: String = "—",
    val codecLabel: String = "—",
    val sampleRateLabel: String = "—",
    val isLossless: Boolean = false,
    val bitDepthLabel: String = "—",
    val bitrateKbps: Int = -1,
    val channelCount: Int = 2,
    val audioSessionId: Int = -1,
    val sizeBytes: Long = 0L,
    val isConnected: Boolean = false,
    val connectionError: String? = null
)

/**
 * Wraps a Media3 [MediaController] connected to [MusicService].
 * Exposes playback state as a StateFlow for Compose screens to collect.
 * Position is NOT polled continuously here — screens should poll on their
 * own coroutine while visible to avoid unnecessary background work.
 */
@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicRepository: MusicRepository,
    private val preferencesRepository: UserPreferencesRepository
) {

    private var controller: MediaController? = null
    private var connectionFuture: ListenableFuture<MediaController>? = null
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queueResolutionJob: Job? = null
    private val queueRequestGeneration = AtomicLong(0L)
    private var seekResolutionJob: Job? = null

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (this@PlaybackController.controller === controller) {
                controller.removeListener(listener)
                this@PlaybackController.controller = null
                _state.value = _state.value.copy(
                    isConnected = false,
                    isPlaying = false,
                    connectionError = "Playback service disconnected"
                )
            }
        }

        override fun onExtrasChanged(controller: MediaController, extras: android.os.Bundle) {
            val sessionId = extras.getInt("audio_session_id", -1)
            if (sessionId > 0) {
                _state.value = _state.value.copy(audioSessionId = sessionId)
            }
        }
    }

    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state

    private val listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            _state.value = _state.value.copy(
                isPlaying = false,
                connectionError = error.message ?: "Unable to play this audio file"
            )
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            updateFromController()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            updateFromController()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            updateFromController()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            _state.value = _state.value.copy(shuffleEnabled = shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            _state.value = _state.value.copy(repeatMode = repeatMode)
        }

        override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
            val format = tracks.selectedAudioFormat()
            _state.value = _state.value.copy(
                audioFormatLabel = format?.toAudioFormatLabel() ?: "—",
                codecLabel = format?.toCodecLabel() ?: "—",
                sampleRateLabel = format?.toSampleRateLabel() ?: "—",
                isLossless = format?.isLosslessFormat() ?: false,
                bitDepthLabel = format?.toBitDepthLabel() ?: "—",
                bitrateKbps = format?.bitrateKbpsOrNegative() ?: -1,
                channelCount = format?.channelCount ?: 2
            )
        }
    }

    fun connect(onReady: () -> Unit = {}) {
        if (controller != null) {
            onReady()
            return
        }
        readyCallbacks += onReady
        if (connectionFuture != null) return
        val sessionToken = SessionToken(context, ComponentName(context, MusicService::class.java))
        val future = MediaController.Builder(context, sessionToken)
            .setListener(controllerListener)
            .buildAsync()
        connectionFuture = future
        future.addListener({
            try {
                val connectedController = future.get()
                connectionFuture = null
                controller = connectedController
                connectedController.addListener(listener)
                val initialSessionId = connectedController.sessionExtras.getInt("audio_session_id", -1)
                _state.value = _state.value.copy(
                    isConnected = true,
                    connectionError = null,
                    audioSessionId = if (initialSessionId > 0) initialSessionId else _state.value.audioSessionId
                )
                updateFromController()
                readyCallbacks.toList().also { readyCallbacks.clear() }.forEach { it() }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                connectionFailed("Playback service connection was interrupted")
            } catch (e: Exception) {
                connectionFailed(e.cause?.message ?: e.message ?: "Unable to connect to playback service")
            }
        }, MoreExecutors.directExecutor())
    }

    fun reconnect(onReady: () -> Unit = {}) = connect(onReady)

    private fun connectionFailed(message: String) {
        connectionFuture = null
        readyCallbacks.clear()
        controller = null
        _state.value = _state.value.copy(isConnected = false, connectionError = message)
    }

    fun release() {
        connectionFuture?.cancel(true)
        connectionFuture = null
        readyCallbacks.clear()
        queueResolutionJob?.cancel()
        queueResolutionJob = null
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        _state.value = _state.value.copy(isConnected = false, isPlaying = false)
    }

    fun playSongs(items: List<MediaItem>, startIndex: Int) {
        val generation = queueRequestGeneration.incrementAndGet()
        _state.value = _state.value.copy(connectionError = null)
        playSongsWithItems({ items }, startIndex, generation = generation)
    }

    fun playSongsFromSongs(songs: List<Song>, startIndex: Int, onQueueStarted: () -> Unit = {}) {
        val generation = queueRequestGeneration.incrementAndGet()
        _state.value = _state.value.copy(connectionError = null)
        playSongsWithItems(
            itemsProvider = { withContext(Dispatchers.Default) { songs.map { it.toMediaItem() } } },
            startIndex = startIndex,
            songsForResolution = songs,
            generation = generation,
            onQueueStarted = onQueueStarted
        )
    }

    fun playExternalAudio(uri: Uri) {
        val generation = queueRequestGeneration.incrementAndGet()
        connect {
            queueResolutionJob?.cancel()
            queueResolutionJob = playbackScope.launch {
                try {
                    val item = createExternalAudioItem(uri)
                    if (generation != queueRequestGeneration.get()) return@launch
                    withContext(Dispatchers.Main.immediate) {
                        if (generation != queueRequestGeneration.get()) return@withContext
                        _state.value = _state.value.copy(connectionError = null)
                        controller?.apply {
                            setMediaItem(item)
                            prepare()
                            play()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(connectionError = e.message ?: "Unable to open this audio file")
                }
            }
        }
    }

    private suspend fun createExternalAudioItem(uri: Uri): MediaItem = withContext(Dispatchers.IO) {
        musicRepository.getSongByUri(uri.toString())?.let { song ->
            return@withContext song.copy(uriString = uri.toString()).toMediaItem()
        }

        var displayName: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                }
            }
        } catch (_: Exception) {
            // Some providers do not support metadata queries; the URI still can be played.
        }
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "Unknown audio"
        val title = (displayName ?: fallbackName).substringAfterLast('/').substringBeforeLast('.', displayName ?: fallbackName)
            .ifBlank { "Unknown audio" }
        val metadata = MediaMetadata.Builder().setTitle(title).build()
        MediaItem.Builder()
            .setMediaId(EXTERNAL_AUDIO_MEDIA_ID_PREFIX + uri)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /** Inserts a track immediately after the current item without interrupting playback. */
    fun playSongNext(song: Song) {
        playbackScope.launch {
            try {
                val resolvedUri = musicRepository.getSongUris(listOf(song.id))[song.id] ?: song.uriString
                val item = withContext(Dispatchers.Default) {
                    song.copy(uriString = resolvedUri).toMediaItem()
                }
                withContext(Dispatchers.Main.immediate) {
                    val player = controller ?: return@withContext
                    val currentIndex = player.currentMediaItemIndex
                    if (player.mediaItemCount == 0) {
                        player.playWhenReady = false
                        player.setMediaItem(item)
                        player.prepare()
                        return@withContext
                    }

                    if (currentIndex >= 0 && currentIndex < player.mediaItemCount &&
                        player.getMediaItemAt(currentIndex).mediaId == song.id
                    ) return@withContext

                    val existingIndex = (0 until player.mediaItemCount)
                        .firstOrNull { player.getMediaItemAt(it).mediaId == song.id }
                    if (existingIndex == currentIndex + 1) return@withContext
                    if (existingIndex != null) player.removeMediaItem(existingIndex)

                    val updatedCurrentIndex = player.currentMediaItemIndex
                    val insertIndex = if (updatedCurrentIndex == C.INDEX_UNSET) {
                        player.mediaItemCount
                    } else {
                        (updatedCurrentIndex + 1).coerceAtMost(player.mediaItemCount)
                    }
                    player.addMediaItem(insertIndex, item)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(connectionError = e.message ?: "Unable to queue track")
            }
        }
    }

    private fun playSongsWithItems(
        itemsProvider: suspend () -> List<MediaItem>,
        startIndex: Int,
        songsForResolution: List<Song>? = null,
        generation: Long,
        onQueueStarted: () -> Unit = {}
    ) {
        connect {
            queueResolutionJob?.cancel()
            queueResolutionJob = playbackScope.launch {
                try {
                    val items = itemsProvider()
                    if (items.isEmpty()) return@launch
                    val safeStartIndex = startIndex.coerceIn(0, items.lastIndex)
                    val resolvedUris = if (songsForResolution != null) {
                        musicRepository.resolvePlaybackUris(songsForResolution)
                    } else {
                        musicRepository.getSongUris(items.map { it.mediaId })
                    }
                    val resolvedItems = items.map { item ->
                        resolvedUris[item.mediaId]?.let { item.buildUpon().setUri(Uri.parse(it)).build() } ?: item
                    }
                    if (generation != queueRequestGeneration.get()) return@launch
                    withContext(Dispatchers.Main.immediate) {
                        if (generation != queueRequestGeneration.get()) return@withContext
                        val activeController = controller ?: return@withContext
                        _state.value = _state.value.copy(connectionError = null)
                        activeController.setMediaItems(resolvedItems, safeStartIndex, 0L)
                        activeController.prepare()
                        activeController.play()
                        onQueueStarted()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(connectionError = e.message ?: "Unable to prepare playback")
                }
            }
        }
    }

    fun togglePlayPause() {
        controller?.apply {
            if (isPlaying) pause() else play()
        }
    }

    fun stopPlayback() {
        controller?.apply {
            stop()
            clearMediaItems()
        }
        _state.value = _state.value.copy(
            mediaId = null,
            title = "",
            artist = "",
            album = "",
            artworkUri = null,
            durationMs = 0L,
            positionMs = 0L,
            isPlaying = false,
            connectionError = null
        )
    }

    fun setVolume(volume: Float) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        val targetVolume = (volume * maxVolume).toInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVolume, 0)
    }

    fun currentVolume(): Float {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVolume = audioManager.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        if (maxVolume == 0) return 1f
        val current = audioManager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        return current.toFloat() / maxVolume
    }

    fun skipToNext() = controller?.seekToNext()
    fun skipToPrevious() = controller?.seekToPrevious()
    fun seekTo(positionMs: Long) {
        val targetPositionMs = positionMs.coerceAtLeast(0L)
        val activeController = controller ?: return
        val currentItem = activeController.currentMediaItem ?: return
        if (currentItem.mediaMetadata.providerIdOrNull() != "navidrome") {
            activeController.seekTo(targetPositionMs)
            return
        }

        seekResolutionJob?.cancel()
        seekResolutionJob = playbackScope.launch {
            val maxBitRate = preferencesRepository.preferences.first().maxBitRate
            if (maxBitRate == null) {
                withContext(Dispatchers.Main.immediate) {
                    if (controller === activeController && controller?.currentMediaItem?.mediaId == currentItem.mediaId) {
                        activeController.seekTo(targetPositionMs)
                    }
                }
                return@launch
            }

            val snapshot = withContext(Dispatchers.Main.immediate) {
                if (controller !== activeController || activeController.currentMediaItem?.mediaId != currentItem.mediaId) {
                    return@withContext null
                }
                val items = (0 until activeController.mediaItemCount).map(activeController::getMediaItemAt)
                Triple(items, activeController.currentMediaItemIndex, activeController.playWhenReady)
            } ?: return@launch

            val streamOffsetMs = (targetPositionMs / 1000L) * 1000L
            val uri = musicRepository.getSongUri(currentItem.mediaId, streamOffsetMs) ?: return@launch
            if (!uri.startsWith("http://") && !uri.startsWith("https://")) return@launch
            val resolvedItems = snapshot.first.map { item ->
                if (item.mediaId == currentItem.mediaId) {
                    item.withStreamUriAndOffset(uri, streamOffsetMs)
                } else {
                    item
                }
            }

            withContext(Dispatchers.Main.immediate) {
                if (controller !== activeController ||
                    activeController.currentMediaItem?.mediaId != currentItem.mediaId ||
                    (0 until activeController.mediaItemCount).map { activeController.getMediaItemAt(it).mediaId } !=
                    snapshot.first.map { it.mediaId }
                ) return@withContext

                activeController.setMediaItems(resolvedItems, snapshot.second, 0L)
                activeController.prepare()
                activeController.playWhenReady = snapshot.third
            }
        }
    }

    fun toggleShuffle() {
        controller?.apply { shuffleModeEnabled = !shuffleModeEnabled }
    }

    fun cycleRepeatMode() {
        controller?.apply {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun currentPositionMs(): Long = controller?.let { player ->
        player.currentPosition + (player.currentMediaItem?.mediaMetadata?.streamOffsetMsOrZero() ?: 0L)
    } ?: 0L
    fun durationMs(): Long = controller?.duration?.coerceAtLeast(0L) ?: 0L
    fun bufferedPositionMs(): Long = controller?.bufferedPosition ?: 0L

    @UnstableApi
    private fun updateFromController() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        val itemMetadata = c.currentMediaItem?.mediaMetadata
        val actualDurationMs = c.duration
        val streamOffsetMs = itemMetadata?.streamOffsetMsOrZero() ?: 0L
        val knownDurationMs = itemMetadata?.durationMs
            ?: metadata.durationMs
            ?: 0L
        _state.value = _state.value.copy(
            mediaId = c.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumId = metadata.albumIdOrNull() ?: "",
            artworkUri = metadata.artworkUri?.toString(),
            sizeBytes = metadata.sizeBytesOrZero(),
            durationMs = when {
                knownDurationMs > 0L -> knownDurationMs
                actualDurationMs > 0L -> actualDurationMs + streamOffsetMs
                else -> 0L
            },
            isPlaying = c.isPlaying,
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode
        )
    }
}
