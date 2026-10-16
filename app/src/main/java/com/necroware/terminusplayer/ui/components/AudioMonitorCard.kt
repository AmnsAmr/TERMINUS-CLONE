package com.necroware.terminusplayer.ui.components

import android.content.Context
import android.media.AudioManager
import android.os.Process
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.necroware.terminusplayer.data.prefs.MotionPreference
import kotlinx.coroutines.Dispatchers
import com.necroware.terminusplayer.data.prefs.HudTelemetryMode
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private data class AudioDeviceReadout(val audioDevice: String, val cpuPercent: Int)

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

    val volumePercent by produceState(initialValue = 0, context) {
        while (true) {
            value = withContext(Dispatchers.IO) { readVolumePercent(context) }
            delay(2000)
        }
    }

    val deviceReadout by produceState(initialValue = AudioDeviceReadout("—", 0), context) {
        var lastCpuTimeMs = Process.getElapsedCpuTime()
        var lastWallTimeMs = System.currentTimeMillis()
        while (true) {
            delay(2000)
            val (readout, cpuTimeMs, wallTimeMs) = withContext(Dispatchers.IO) {
                val nextCpuTimeMs = Process.getElapsedCpuTime()
                val nextWallTimeMs = System.currentTimeMillis()
                val cpuDelta = (nextCpuTimeMs - lastCpuTimeMs).coerceAtLeast(0L)
                val wallDelta = (nextWallTimeMs - lastWallTimeMs).coerceAtLeast(1L)
                val percent = ((cpuDelta.toFloat() / wallDelta) * 100).toInt().coerceIn(0, 100)
                Triple(AudioDeviceReadout(audioOutputDeviceLabel(context), percent), nextCpuTimeMs, nextWallTimeMs)
            }
            lastCpuTimeMs = cpuTimeMs
            lastWallTimeMs = wallTimeMs
            value = readout
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
                MonitorRow("server ping", serverPingMs?.let { "${it}ms" } ?: "OFFLINE / LOCAL", "VOL $volumePercent%")
                MonitorRow("device", deviceReadout.audioDevice, "CPU: ${deviceReadout.cpuPercent}%")
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

                MonitorRow("device", deviceReadout.audioDevice, "VOL $volumePercent%")
                MonitorRow("cpu", "${deviceReadout.cpuPercent}%", "")
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
    val barCount = 12
    val animationSpecs = remember(barCount) {
        List(barCount) { index ->
            tween<Float>(
                durationMillis = (400..800).random(),
                delayMillis = (0..200).random(),
                easing = FastOutSlowInEasing
            )
        }
    }
    val infiniteTransition = rememberInfiniteTransition(label = "spectrum")
    
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        repeat(barCount) { index ->
            val displayHeight = if (isPlaying) {
                val heightPercent by infiniteTransition.animateFloat(
                    initialValue = 0.1f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = animationSpecs[index],
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar_$index"
                )
                heightPercent
            } else {
                0.1f
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(displayHeight)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
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
