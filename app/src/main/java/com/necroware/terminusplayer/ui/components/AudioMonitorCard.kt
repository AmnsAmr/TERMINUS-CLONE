package com.necroware.terminusplayer.ui.components

import android.content.Context
import android.media.AudioManager
import android.os.Process
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.necroware.terminusplayer.data.prefs.MotionPreference
import kotlinx.coroutines.Dispatchers
import com.necroware.terminusplayer.data.prefs.HudTelemetryMode
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private data class AudioMonitorReadout(val volumePercent: Int, val audioDevice: String, val cpuPercent: Int)

@Composable
fun AudioMonitorCard(
    codecLabel: String,
    bitDepthLabel: String,
    kbpsLabel: String,
    sizeLabel: String,
    isLossless: Boolean,
    repeatMode: Int,
    modifier: Modifier = Modifier,
    isPlaying: Boolean = false,
    motionPreference: MotionPreference = MotionPreference.FULL,
    telemetryMode: HudTelemetryMode = HudTelemetryMode.STANDARD,
    sampleRateLabel: String = "—",
    audioSessionId: Int = -1,
    channelCount: Int = 2,
    bufferRemainingSec: Long = 0L,
    serverPingMs: Long? = null,
    preferHardwareDecoder: Boolean = true
) {
    val context = LocalContext.current

    val isFullMotion = motionPreference == MotionPreference.FULL
    val readout by produceState(
        initialValue = AudioMonitorReadout(0, "—", 0),
        key1 = isFullMotion
    ) {
        var lastCpuTimeMs = Process.getElapsedCpuTime()
        var lastWallTimeMs = System.currentTimeMillis()
        val intervalMs = if (isFullMotion) 3000L else 6000L
        while (true) {
            val volume = withContext(Dispatchers.IO) { readVolumePercent(context) }
            val (device, cpu) = if (isFullMotion) {
                withContext(Dispatchers.IO) {
                    val nextCpuTimeMs = Process.getElapsedCpuTime()
                    val nextWallTimeMs = System.currentTimeMillis()
                    val cpuDelta = (nextCpuTimeMs - lastCpuTimeMs).coerceAtLeast(0L)
                    val wallDelta = (nextWallTimeMs - lastWallTimeMs).coerceAtLeast(1L)
                    val percent = ((cpuDelta.toFloat() / wallDelta) * 100).toInt().coerceIn(0, 100)
                    lastCpuTimeMs = nextCpuTimeMs
                    lastWallTimeMs = nextWallTimeMs
                    Pair(audioOutputDeviceLabel(context), percent)
                }
            } else {
                Pair(withContext(Dispatchers.IO) { audioOutputDeviceLabel(context) }, 0)
            }
            value = AudioMonitorReadout(volume, device, cpu)
            delay(intervalMs)
        }
    }

    TerminalBorder(modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (telemetryMode == HudTelemetryMode.EXPANDED) "HUD TELEMETRY [DEV/HACKER]" else "AUDIO MONITOR",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (isLossless) "LOSSLESS" else "LOSSY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (telemetryMode == HudTelemetryMode.EXPANDED) {
                val decoderName = if (preferHardwareDecoder) "HW.DSP ($codecLabel)" else "c2.android.${codecLabel.lowercase()}.decoder"
                val channelLabel = when (channelCount) {
                    1 -> "1ch (MONO)"
                    2 -> "2ch (STEREO)"
                    6 -> "5.1 SURROUND"
                    else -> if (channelCount > 0) "${channelCount}ch" else "—"
                }
                MonitorRow("decoder", decoderName, "")
                MonitorRow("fmt", codecLabel, "bit: $bitDepthLabel-bit")
                MonitorRow("rate", sampleRateLabel, "kbps: $kbpsLabel")
                MonitorRow("session", if (audioSessionId > 0) "#$audioSessionId" else "—", channelLabel)
                MonitorRow("buffer", "+${bufferRemainingSec}s", "size: $sizeLabel")
                MonitorRow("server ping", serverPingMs?.let { "${it}ms" } ?: "OFFLINE / LOCAL", "VOL ${readout.volumePercent}%")
                MonitorRow("device", readout.audioDevice, "CPU: ${readout.cpuPercent}%")
            } else {
                MonitorRow("fmt", codecLabel, "bit: $bitDepthLabel")
                MonitorRow("kbps", kbpsLabel, "size: $sizeLabel")

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outline)
                )

                MonitorRow("device", readout.audioDevice, "VOL ${readout.volumePercent}%")
                MonitorRow("cpu", "${readout.cpuPercent}%", "")
            }

            SimulatedSpectrum(
                isPlaying = isPlaying && motionPreference == MotionPreference.FULL,
                modifier = Modifier.padding(top = 8.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "LOOP " + when (repeatMode) {
                        Player.REPEAT_MODE_ALL -> "ALL"
                        Player.REPEAT_MODE_ONE -> "ONE"
                        else -> "OFF"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SimulatedSpectrum(isPlaying: Boolean, modifier: Modifier = Modifier) {
    val barColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    if (!isPlaying) {
        Canvas(modifier = modifier.fillMaxWidth().height(24.dp)) {
            val barCount = 12
            val spacing = 2.dp.toPx()
            val totalSpacing = spacing * (barCount - 1)
            val barWidth = (size.width - totalSpacing) / barCount
            val barHeight = size.height * 0.12f
            for (i in 0 until barCount) {
                val left = i * (barWidth + spacing)
                drawRect(
                    color = barColor,
                    topLeft = Offset(left, size.height - barHeight),
                    size = Size(barWidth, barHeight)
                )
            }
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "spectrum")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(modifier = modifier.fillMaxWidth().height(24.dp)) {
        val barCount = 12
        val spacing = 2.dp.toPx()
        val totalSpacing = spacing * (barCount - 1)
        val barWidth = (size.width - totalSpacing) / barCount
        for (i in 0 until barCount) {
            val left = i * (barWidth + spacing)
            val wave = (kotlin.math.sin(phase + i * 0.75f) * 0.45f + 0.55f) *
                       (kotlin.math.cos(phase * 1.25f + i * 1.15f) * 0.35f + 0.65f)
            val fraction = wave.coerceIn(0.12f, 0.98f)
            val barHeight = size.height * fraction
            drawRect(
                color = barColor,
                topLeft = Offset(left, size.height - barHeight),
                size = Size(barWidth, barHeight)
            )
        }
    }
}

@Composable
private fun MonitorRow(label: String, value: String, trailing: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(modifier = Modifier.weight(1f, fill = true)) {
            Text(
                text = "$label: ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = value.ifBlank { "—" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing.isNotBlank()) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun readVolumePercent(context: Context): Int {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return ((current.toFloat() / max) * 100).toInt()
}

@Suppress("DEPRECATION")
private fun audioOutputDeviceLabel(context: Context): String {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "—"
    return when {
        audioManager.isBluetoothA2dpOn -> "Bluetooth"
        audioManager.isWiredHeadsetOn -> "Wired"
        else -> "Speaker"
    }
}
