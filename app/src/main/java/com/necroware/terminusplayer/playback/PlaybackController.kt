package com.necroware.terminusplayer.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
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
import com.necroware.terminusplayer.data.repository.MusicRepository
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
import javax.inject.Inject
import javax.inject.Singleton

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
    private val musicRepository: MusicRepository
) {

    private var controller: MediaController? = null
    private var connectionFuture: ListenableFuture<MediaController>? = null
    private val readyCallbacks = mutableListOf<() -> Unit>()
    private val playbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queueResolutionJob: Job? = null

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
    }

    private val _state = MutableStateFlow(NowPlayingState())
    val state: StateFlow<NowPlayingState> = _state

    private val listener = object : Player.Listener {
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
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
                bitrateKbps = format?.bitrateKbpsOrNegative() ?: -1
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
                _state.value = _state.value.copy(isConnected = true, connectionError = null)
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
        queueResolutionJob?.cancel()
        queueResolutionJob = playbackScope.launch {
            try {
                val resolvedUris = musicRepository.getSongUris(items.map { it.mediaId })
                val resolvedItems = items.map { item ->
                    resolvedUris[item.mediaId]?.let { item.buildUpon().setUri(Uri.parse(it)).build() } ?: item
                }
                withContext(Dispatchers.Main.immediate) {
                    controller?.apply {
                        setMediaItems(resolvedItems, startIndex, 0L)
                        prepare()
                        play()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(connectionError = e.message ?: "Unable to prepare playback")
            }
        }
    }

    fun togglePlayPause() {
        controller?.apply {
            if (isPlaying) pause() else play()
        }
    }

    fun skipToNext() = controller?.seekToNext()
    fun skipToPrevious() = controller?.seekToPrevious()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

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

    fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    fun durationMs(): Long = controller?.duration?.coerceAtLeast(0L) ?: 0L

    private fun updateFromController() {
        val c = controller ?: return
        val metadata = c.mediaMetadata
        _state.value = _state.value.copy(
            mediaId = c.currentMediaItem?.mediaId,
            title = metadata.title?.toString().orEmpty(),
            artist = metadata.artist?.toString().orEmpty(),
            album = metadata.albumTitle?.toString().orEmpty(),
            albumId = metadata.albumIdOrNull() ?: "",
            artworkUri = metadata.artworkUri?.toString(),
            sizeBytes = metadata.sizeBytesOrZero(),
            durationMs = c.duration.coerceAtLeast(0L),
            isPlaying = c.isPlaying,
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = c.repeatMode
        )
    }
}
