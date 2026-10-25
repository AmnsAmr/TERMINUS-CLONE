package com.necroware.terminusplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.necroware.terminusplayer.MainActivity
import com.necroware.terminusplayer.data.prefs.CrossfadeSettings
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.StatsRepository
import com.necroware.terminusplayer.util.albumIdOrNull
import com.necroware.terminusplayer.util.localFolderPathOrNull
import com.necroware.terminusplayer.util.providerIdOrNull
import com.necroware.terminusplayer.util.streamOffsetMsOrZero
import com.necroware.terminusplayer.util.toMediaItem
import com.necroware.terminusplayer.util.withStreamUriAndOffset
import com.necroware.terminusplayer.util.EXTERNAL_AUDIO_MEDIA_ID_PREFIX
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject
    lateinit var statsRepository: StatsRepository

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var musicRepository: MusicRepository

    @Inject
    lateinit var audioSpectrum: AudioSpectrum

    private var mediaSession: MediaSession? = null
    private lateinit var player: ExoPlayer

    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e("MusicService", "Unhandled coroutine exception", throwable)
    }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val playEventScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val playEventJobs = mutableSetOf<Job>()
    private val playEventJobsLock = Any()
    private var serviceDestroying = false
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)

    private val equalizerAudioProcessor = TerminusEqualizerAudioProcessor()
    private val audioOutputDeviceManager by lazy { AudioOutputDeviceManager(this) }
    private var crossfadeSettings = CrossfadeSettings()
    private var fadeInJob: Job? = null
    private var fadeTickerJob: Job? = null
    private var savePositionJob: Job? = null
    private var qualityRefreshJob: Job? = null

    private val activePlayTracker = ActivePlayTracker()

    private val analyticsListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            flushCurrentTrack(completedAtEnd = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            startTracking(mediaItem?.mediaMetadata, mediaItem?.mediaId)
            if (crossfadeSettings.enabled && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                startFadeIn()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                flushCurrentTrack(completedAtEnd = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            audioSpectrum.setPlaying(isPlaying)
            activePlayTracker.onIsPlayingChanged(
                isPlaying = isPlaying,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                nowEpochMs = System.currentTimeMillis()
            )
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val extractorsFactory = androidx.media3.extractor.DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this, httpDataSourceFactory)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)

        // Same audio sink Media3 builds by default, plus a pass-through tee that feeds the
        // visualizer spectrum. The tee copies samples unchanged, so output is bit-identical.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(equalizerAudioProcessor, TeeAudioProcessor(audioSpectrum)))
                .build()
        }

        player = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
        
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(10_000_000L)
        player.addListener(analyticsListener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                mediaSession?.setSessionExtras(android.os.Bundle().apply {
                    putInt("audio_session_id", audioSessionId)
                })
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val initialExtras = android.os.Bundle().apply {
            putInt("audio_session_id", player.audioSessionId)
        }
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .setExtras(initialExtras)
            .build()

        serviceScope.launch {
            var lastMaxBitRate: Int? = null
            var hasObservedConfig = false
            preferencesRepository.serverConnectionConfig.collect { config ->
                if (!config.isLoaded) return@collect
                if (hasObservedConfig && lastMaxBitRate != config.maxBitRate) {
                    val selectedBitRate = config.maxBitRate
                    qualityRefreshJob?.cancel()
                    qualityRefreshJob = serviceScope.launch {
                        refreshQueueForStreamQuality(selectedBitRate)
                    }
                }
                lastMaxBitRate = config.maxBitRate
                hasObservedConfig = true
            }
        }

        audioOutputDeviceManager.start()

        serviceScope.launch {
            var lastExcludedFolders: Set<String>? = null
            combine(preferencesRepository.preferences, audioOutputDeviceManager.currentOutputDevice) { prefs, device ->
                prefs to device
            }.collect { (prefs, device) ->
                val assignedPresetId = prefs.equalizer.outputDeviceAssignments[device.id]
                val effectivePreset = if (assignedPresetId != null) {
                    prefs.equalizer.presets.firstOrNull { it.id == assignedPresetId }
                } else {
                    prefs.equalizer.presets.firstOrNull { it.id == prefs.equalizer.activePresetId }
                }

                val effectiveGainsDb = effectivePreset?.gainsDb ?: prefs.equalizer.bandGainsDb.map { it.toFloat() }
                val effectivePresetName = effectivePreset?.name ?: "Custom"

                equalizerAudioProcessor.setParameters(
                    EqualizerParameters(
                        enabled = prefs.equalizer.enabled,
                        gainsDb = effectiveGainsDb,
                        preampMode = prefs.equalizer.preampMode,
                        manualPreampDb = prefs.equalizer.manualPreampDb
                    )
                )

                val eqStatusLabel = if (prefs.equalizer.enabled) {
                    "${effectivePresetName.uppercase()} (${device.displayName.uppercase()})"
                } else {
                    "OFF"
                }

                mediaSession?.setSessionExtras(android.os.Bundle().apply {
                    putInt("audio_session_id", player.audioSessionId)
                    putString("eq_status_label", eqStatusLabel)
                    putString("eq_preset_name", effectivePresetName)
                    putString("audio_output_device", device.displayName)
                })

                if (lastExcludedFolders != prefs.excludedFolders) {
                    lastExcludedFolders = prefs.excludedFolders
                    val excludedFolders = prefs.excludedFolders
                    mainScope.launch { removeExcludedLocalItems(excludedFolders) }
                }

                val crossfadeChanged = crossfadeSettings != prefs.crossfade
                crossfadeSettings = prefs.crossfade
                if (crossfadeChanged) {
                    mainScope.launch {
                        if (prefs.crossfade.enabled) startFadeTicker() else stopFading()
                    }
                }
            }
        }

        // Restore last session
        serviceScope.launch {
            val prefs = preferencesRepository.preferences.first()
            val lastId = prefs.lastPlayedSongId
            if (lastId != null) {
                val songs = musicRepository.observeAllSongs().first()
                // Preferences may still contain a pre-namespacing song ID after
                // a database migration. Resolve it only when unambiguous, then
                // persist the new database identity for the next restore.
                val song = songs.find { it.id == lastId }
                    ?: songs.singleOrNull { it.providerRemoteId == lastId }
                if (song != null) {
                    if (song.id != lastId) {
                        preferencesRepository.setLastPlayed(song.id, prefs.lastPlayedPositionMs)
                    }
                    val uri = musicRepository.getSongUri(song.id)
                    val item = song.toMediaItem().buildUpon()
                        .setUri(android.net.Uri.parse(uri ?: song.uriString))
                        .build()
                    withContext(Dispatchers.Main) {
                        if (player.mediaItemCount == 0) {
                            player.setMediaItem(item, prefs.lastPlayedPositionMs)
                            player.prepare()
                        }
                    }
                }
            }
        }

        startSavePositionTicker()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player ?: return
        if (!player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        synchronized(playEventJobsLock) { serviceDestroying = true }
        flushCurrentTrack()
        synchronized(playEventJobsLock) {
            if (playEventJobs.isEmpty()) playEventScope.cancel()
        }
        audioSpectrum.setPlaying(false)
        fadeInJob?.cancel()
        fadeTickerJob?.cancel()
        savePositionJob?.cancel()
        qualityRefreshJob?.cancel()
        audioOutputDeviceManager.stop()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        serviceScope.cancel()
        mainScope.cancel()
        super.onDestroy()
    }

    private fun startTracking(metadata: MediaMetadata?, mediaId: String?) {
        activePlayTracker.start(
            songId = mediaId,
            artist = metadata?.artist?.toString().orEmpty(),
            album = metadata?.albumTitle?.toString().orEmpty(),
            albumId = metadata?.albumIdOrNull() ?: "",
            durationMs = player.duration,
            isPlaying = player.isPlaying,
            nowElapsedMs = SystemClock.elapsedRealtime(),
            nowEpochMs = System.currentTimeMillis()
        )
    }

    private fun removeExcludedLocalItems(excludedFolders: Set<String>) {
        if (excludedFolders.isEmpty()) return
        val originalItems = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        val currentIndex = player.currentMediaItemIndex
        val currentItem = originalItems.getOrNull(currentIndex)
        val currentMediaId = currentItem?.mediaId
        val currentWasExcluded = currentItem?.mediaMetadata
            ?.localFolderPathOrNull()
            ?.let { it in excludedFolders } == true
        val wasPlaying = player.playWhenReady
        val currentPosition = player.currentPosition
        val remainingItems = originalItems.filter { item ->
            val folderPath = item.mediaMetadata.localFolderPathOrNull()
            folderPath == null || folderPath !in excludedFolders
        }
        if (remainingItems.size == originalItems.size) return
        if (remainingItems.isEmpty()) {
            player.clearMediaItems()
            return
        }

        val nextIndex = currentMediaId?.let { id -> remainingItems.indexOfFirst { it.mediaId == id } }
            ?.takeIf { it >= 0 }
            ?: if (currentWasExcluded) {
                val nextMediaId = originalItems.drop(currentIndex + 1).firstNotNullOfOrNull { item ->
                    val folderPath = item.mediaMetadata.localFolderPathOrNull()
                    item.mediaId.takeIf { folderPath == null || folderPath !in excludedFolders }
                }
                nextMediaId?.let { id -> remainingItems.indexOfFirst { it.mediaId == id } }
                    ?.takeIf { it >= 0 }
            } else {
                null
            }

        val startIndex = nextIndex ?: 0
        player.setMediaItems(remainingItems, startIndex, if (currentWasExcluded) 0L else currentPosition)
        player.prepare()
        player.playWhenReady = wasPlaying && nextIndex != null
    }

    private data class QueueSnapshot(
        val items: List<MediaItem>,
        val mediaIds: List<String>,
        val currentMediaId: String?,
        val currentPositionMs: Long,
        val playWhenReady: Boolean
    )

    private suspend fun refreshQueueForStreamQuality(maxBitRate: Int?) {
        val snapshot = withContext(Dispatchers.Main.immediate) {
            val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
            QueueSnapshot(
                items = items,
                mediaIds = items.map { it.mediaId },
                currentMediaId = player.currentMediaItem?.mediaId,
                currentPositionMs = player.currentPosition +
                    (player.currentMediaItem?.mediaMetadata?.let { it.streamOffsetMsOrZero() } ?: 0L),
                playWhenReady = player.playWhenReady
            )
        }
        val hasRemoteStreams = snapshot.items.any {
            val provider = it.mediaMetadata.providerIdOrNull()
            provider == "navidrome" || provider == "youtube" || provider == "jamendo"
        }
        if (snapshot.currentMediaId == null || !hasRemoteStreams) return

        val streamUris = musicRepository.getSongUris(snapshot.items.map { it.mediaId }).toMutableMap()
        val currentItem = snapshot.items.firstOrNull { it.mediaId == snapshot.currentMediaId }
        val currentOffsetMs = if (
            maxBitRate != null && currentItem?.mediaMetadata?.providerIdOrNull() == "navidrome"
        ) {
            (snapshot.currentPositionMs / 1000L) * 1000L
        } else {
            0L
        }
        if (currentOffsetMs > 0L) {
            musicRepository.getSongUri(snapshot.currentMediaId, currentOffsetMs)?.let {
                streamUris[snapshot.currentMediaId] = it
            }
        }
        val resolvedItems = snapshot.items.map { item ->
            val uri = streamUris[item.mediaId] ?: return@map item
            when (item.mediaMetadata.providerIdOrNull()) {
                "navidrome" -> item.withStreamUriAndOffset(
                    uri,
                    if (item.mediaId == snapshot.currentMediaId) currentOffsetMs else 0L
                )
                "youtube", "jamendo" -> item.buildUpon().setUri(Uri.parse(uri)).build()
                else -> item
            }
        }

        val anyUriChanged = snapshot.items.zip(resolvedItems).any { (old, new) ->
            old.localConfiguration?.uri != new.localConfiguration?.uri
        }
        if (!anyUriChanged) return

        withContext(Dispatchers.Main.immediate) {
            if (player.mediaItemCount != snapshot.items.size ||
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId } != snapshot.mediaIds ||
                player.currentMediaItem?.mediaId != snapshot.currentMediaId
            ) return@withContext

            val currentIndex = player.currentMediaItemIndex
            player.setMediaItems(
                resolvedItems,
                currentIndex,
                if (currentOffsetMs > 0L) 0L else snapshot.currentPositionMs
            )
            player.prepare()
            player.playWhenReady = snapshot.playWhenReady
        }
    }

    private fun flushCurrentTrack(completedAtEnd: Boolean = false) {
        val finished = activePlayTracker.finish(SystemClock.elapsedRealtime(), completedAtEnd) ?: return

        val job = playEventScope.launch(start = CoroutineStart.LAZY) {
            try {
                statsRepository.recordPlay(
                    songId = finished.songId,
                    artist = finished.artist,
                    album = finished.album,
                    albumId = finished.albumId,
                    startedAtEpochMs = finished.startedAtEpochMs,
                    msPlayed = finished.activeMs,
                    completed = finished.completed
                )
                if (finished.completed) {
                    musicRepository.scrobble(finished.songId)
                }
            } finally {
                val completedJob = coroutineContext[Job]
                synchronized(playEventJobsLock) {
                    completedJob?.let(playEventJobs::remove)
                    if (serviceDestroying && playEventJobs.isEmpty()) playEventScope.cancel()
                }
            }
        }
        synchronized(playEventJobsLock) { playEventJobs += job }
        job.start()
    }

    private fun startSavePositionTicker() {
        savePositionJob?.cancel()
        savePositionJob = serviceScope.launch {
            while (isActive) {
                delay(5000)
                val (currentId, pos) = withContext(Dispatchers.Main) {
                    player.currentMediaItem?.mediaId to player.currentPosition
                }
                if (currentId != null && !currentId.startsWith(EXTERNAL_AUDIO_MEDIA_ID_PREFIX)) {
                    preferencesRepository.setLastPlayed(currentId, pos)
                }
            }
        }
    }

    private fun startFadeIn() {
        fadeInJob?.cancel()
        val durationMs = crossfadeSettings.durationMs.coerceAtLeast(200)
        fadeInJob = mainScope.launch {
            val steps = 20
            val stepDelayMs = (durationMs / steps).coerceAtLeast(10).toLong()
            player.volume = 0f
            for (step in 1..steps) {
                delay(stepDelayMs)
                player.volume = (step.toFloat() / steps).coerceIn(0f, 1f)
            }
            player.volume = 1f
            fadeInJob = null
        }
    }

    private fun startFadeTicker() {
        fadeTickerJob?.cancel()
        fadeTickerJob = mainScope.launch {
            while (isActive) {
                delay(250)
                val cf = crossfadeSettings
                if (!cf.enabled) continue
                if (!player.isPlaying || player.mediaItemCount == 0) continue

                val durationMs = player.duration
                if (durationMs <= 0) continue
                val remainingMs = durationMs - player.currentPosition
                val fadeWindowMs = cf.durationMs.coerceAtLeast(200).toLong()

                if (fadeInJob == null) {
                    player.volume = if (remainingMs in 0..fadeWindowMs && player.hasNextMediaItem()) {
                        (remainingMs.toFloat() / fadeWindowMs).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                }
            }
        }
    }

    private fun stopFading() {
        fadeInJob?.cancel()
        fadeInJob = null
        fadeTickerJob?.cancel()
        fadeTickerJob = null
        player.volume = 1f
    }
}
