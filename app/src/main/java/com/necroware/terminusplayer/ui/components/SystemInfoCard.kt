package com.necroware.terminusplayer.ui.components

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.necroware.terminusplayer.util.toHoursMinutes
import kotlinx.coroutines.delay

private data class DeviceReadout(val usedRamGb: Float, val totalRamGb: Float, val batteryTempC: Float?)

/** Compact listening stats for Home. Kept static so this card does no periodic device polling. */
@Composable
fun SystemInfoCard(
    trackCount: Int,
    likedCount: Int,
    weekPlays: Int,
    weekMsPlayed: Long,
    modifier: Modifier = Modifier
) {
    TerminalBorder(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("LIBRARY", trackCount.toString(), Modifier.weight(1f))
                Metric("LIKED", likedCount.toString(), Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("THIS WEEK", "$weekPlays plays", Modifier.weight(1f))
                Metric("LISTEN TIME", weekMsPlayed.toHoursMinutes(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

/** Hardware readouts are collected only while this collapsed-by-default card is visible. */
@Composable
fun DeviceStatusCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val readout by produceState<DeviceReadout?>(initialValue = null, context) {
        while (true) {
            value = readDeviceInfo(context)
            delay(10_000L)
        }
    }

    TerminalBorder(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "> DEVICE STATUS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Metric("RAM", readout?.let { "%.1f / %.1f GB".format(it.usedRamGb, it.totalRamGb) } ?: "READING…", Modifier.weight(1f))
                Metric("BATTERY TEMP", readout?.batteryTempC?.let { "%.1f°C".format(it) } ?: "—", Modifier.weight(1f))
            }
            Text(
                "Temperature is read from the battery sensor.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun readDeviceInfo(context: Context): DeviceReadout {
    val memoryInfo = ActivityManager.MemoryInfo()
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memoryInfo)
    val totalGb = memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
    val usedGb = ((memoryInfo.totalMem - memoryInfo.availMem).coerceAtLeast(0L)) / (1024.0 * 1024.0 * 1024.0)
    val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val temperatureTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
    return DeviceReadout(usedGb.toFloat(), totalGb.toFloat(), temperatureTenths.takeIf { it >= 0 }?.div(10f))
}
