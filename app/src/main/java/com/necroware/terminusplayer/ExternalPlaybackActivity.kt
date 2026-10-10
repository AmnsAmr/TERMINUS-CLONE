package com.necroware.terminusplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.prefs.UserPreferences
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.playback.NowPlayingState
import com.necroware.terminusplayer.playback.PlaybackController
import com.necroware.terminusplayer.ui.components.BlockSeekBar
import com.necroware.terminusplayer.ui.components.CompactTransportControls
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.theme.TerminusTheme
import com.necroware.terminusplayer.ui.theme.themePresetById
import com.necroware.terminusplayer.util.toMinutesSeconds
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import javax.inject.Inject

@AndroidEntryPoint
class ExternalPlaybackActivity : ComponentActivity() {

    @Inject lateinit var playbackController: PlaybackController
    @Inject lateinit var preferencesRepository: UserPreferencesRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureCompactWindow()
        if (!handleAudioIntent(intent)) {
            finish()
            return
        }

        setContent {
            val preferences by preferencesRepository.preferences.collectAsStateWithLifecycle(
                initialValue = UserPreferences()
            )
            TerminusTheme(preset = themePresetById(preferences.themeId)) {
                val playback by playbackController.state.collectAsStateWithLifecycle()
                CompactExternalPlayer(
                    playback = playback,
                    controller = playbackController,
                    onClose = {
                        playbackController.stopPlayback()
                        finish()
                    }
                )
            }
        }
        // Compose may replace the decor content after the initial window setup.
        // Reapply sizing once the content view is attached so Android keeps the compact bounds.
        window.decorView.post { configureCompactWindow() }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { configureCompactWindow() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAudioIntent(intent)
    }

    private fun handleAudioIntent(intent: Intent?): Boolean {
        val incomingIntent = intent ?: return false
        val uri = when (incomingIntent.action) {
            Intent.ACTION_VIEW -> incomingIntent.data ?: incomingIntent.externalStreamUri()
            Intent.ACTION_SEND -> incomingIntent.externalStreamUri() ?: incomingIntent.data
            else -> null
        } ?: return false

        if (uri.scheme !in setOf("content", "file", "http", "https")) return false

        if (incomingIntent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // Temporary grants are common and remain usable for the active open.
            }
        }
        playbackController.playExternalAudio(uri)
        return true
    }

    private fun configureCompactWindow() {
        val widthDp = (resources.configuration.screenWidthDp - 48).coerceAtLeast(240).coerceAtMost(280)
        val heightDp = 172
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setDimAmount(0.28f)
        window.setGravity(Gravity.CENTER)
        window.attributes = window.attributes.apply {
            width = (widthDp * resources.displayMetrics.density).toInt()
            height = (heightDp * resources.displayMetrics.density).toInt()
            gravity = Gravity.CENTER
            dimAmount = 0.28f
        }
    }
}

@Suppress("DEPRECATION")
private fun Intent.externalStreamUri(): Uri? =
    (getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        ?: clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

@Composable
private fun CompactExternalPlayer(
    playback: NowPlayingState,
    controller: PlaybackController,
    onClose: () -> Unit
) {
    var positionMs by remember(playback.mediaId) { mutableLongStateOf(controller.currentPositionMs()) }
    LaunchedEffect(playback.mediaId, playback.isPlaying) {
        while (isActive) {
            positionMs = controller.currentPositionMs()
            delay(if (playback.isPlaying) 450L else 1200L)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 8.dp
    ) {
        TerminalBorder(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("TERMINUS_", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                            .clickable(role = Role.Button, onClick = onClose)
                            .semantics { contentDescription = "Stop playback and close player" },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    Text(
                        playback.title.ifBlank { "Opening audio…" },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        playback.artist.ifBlank { "OPENED FROM FILES" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                val duration = playback.durationMs.coerceAtLeast(0L)
                BlockSeekBar(
                    positionMs = positionMs,
                    durationMs = duration,
                    onSeek = { target ->
                        positionMs = target
                        controller.seekTo(target)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(positionMs.coerceAtLeast(0L).toMinutesSeconds(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(duration.toMinutesSeconds(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CompactTransportControls(
                    isPlaying = playback.isPlaying,
                    onTogglePlayPause = controller::togglePlayPause,
                    onSkipNext = controller::skipToNext,
                    onSkipPrevious = controller::skipToPrevious,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp)
                )

                if (!playback.connectionError.isNullOrBlank()) {
                    Text(playback.connectionError.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
