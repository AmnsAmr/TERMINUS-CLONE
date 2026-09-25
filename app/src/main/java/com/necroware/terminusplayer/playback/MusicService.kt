package com.necroware.terminusplayer.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.necroware.terminusplayer.MainActivity
import com.necroware.terminusplayer.data.prefs.CrossfadeSettings
import com.necroware.terminusplayer.data.prefs.EqualizerSettings
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.StatsRepository
import com.necroware.terminusplayer.util.albumIdOrNull
import com.necroware.terminusplayer.util.toMediaItem
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject
    lateinit var statsRepository: StatsRepository

    @Inject
    lateinit var preferencesRepository: UserPreferencesRepository

    @Inject
    lateinit var musicRepository: MusicRepository

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

    private val equalizerController = EqualizerController()
    private var equalizerSettings = EqualizerSettings()
    private var crossfadeSettings = CrossfadeSettings()
    private var fadeInJob: Job? = null
    private var fadeTickerJob: Job? = null
    private var savePositionJob: Job? = null

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
            activePlayTracker.onIsPlayingChanged(
                isPlaying = isPlaying,
                nowElapsedMs = SystemClock.elapsedRealtime(),
                nowEpochMs = System.currentTimeMillis()
            )
        }
    }

    override fun onCreate() {
        super.onCreate()

        val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(this)
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setHandleAudioBecomingNoisy(true)
            .build()
        
        player.preloadConfiguration = ExoPlayer.PreloadConfiguration(10_000_000L)
        player.addListener(analyticsListener)
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
                equalizerController.attachToSession(audioSessionId)
                equalizerController.setEnabled(equalizerSettings.enabled)
                equalizerController.applyBandGains(equalizerSettings.bandGainsDb)
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()

        serviceScope.launch {
            preferencesRepository.preferences.collect { prefs ->
                equalizerSettings = prefs.equalizer
                equalizerController.setEnabled(prefs.equalizer.enabled)
                equalizerController.applyBandGains(prefs.equalizer.bandGainsDb)

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
        fadeInJob?.cancel()
        fadeTickerJob?.cancel()
        savePositionJob?.cancel()
        equalizerController.release()
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
                val currentId = player.currentMediaItem?.mediaId
                if (currentId != null) {
                    val pos = withContext(Dispatchers.Main) { player.currentPosition }
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
