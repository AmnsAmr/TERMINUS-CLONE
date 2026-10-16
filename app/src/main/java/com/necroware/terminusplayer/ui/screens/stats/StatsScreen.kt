package com.necroware.terminusplayer.ui.screens.stats

import com.necroware.terminusplayer.ui.components.TerminalActionText

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.repository.StatsRange
import com.necroware.terminusplayer.data.repository.TopCategory
import com.necroware.terminusplayer.data.repository.TopCategoryItem
import com.necroware.terminusplayer.ui.components.BarDatum
import com.necroware.terminusplayer.ui.components.BlockyBarChart
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun StatsScreen(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDownloadEvents by remember { mutableStateOf(false) }
    var showUploadEvents by remember { mutableStateOf(false) }
    val uploadPositions = remember(state.uploadQueue) {
        state.uploadQueue.filter { it.state in setOf("WAITING", "FAILED", "UPLOADING") }
            .mapIndexed { index, item -> item.songId to index + 1 }.toMap()
    }
    val downloadPositions = remember(state.downloadQueue) {
        state.downloadQueue.filter { it.state in setOf("WAITING", "FAILED", "DOWNLOADING") }
            .mapIndexed { index, item -> item.songId to index + 1 }.toMap()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "> STATS_",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 20.dp)
            )
        }

        if (state.isLoading) {
            item {
                TerminalState(
                    title = "LOADING STATS",
                    detail = "Collecting listening activity…"
                )
            }
        }

        if (state.insights.isNotEmpty()) {
            items(state.insights) { insight ->
                TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = insight,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                StatsRange.entries.forEach { range ->
                    FilterChip(
                        selected = range == state.range,
                        onClick = { viewModel.selectRange(range) },
                        label = { Text(range.label) }
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryTile(
                    label = "TOTAL PLAYS",
                    value = state.totalPlays.toString(),
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    label = "LISTENING TIME",
                    value = formatDuration(state.totalMsPlayed),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryTile(
                    label = "AVG SESSION",
                    value = if (state.sessionStats.totalSessions > 0) formatDuration(state.sessionStats.avgSessionMs) else "—",
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    label = "SESSIONS · LONGEST",
                    value = "${state.sessionStats.totalSessions} · ${if (state.sessionStats.totalSessions > 0) formatDuration(state.sessionStats.longestSessionMs) else "—"}",
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            TerminalBorder(Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when (state.range) {
                        StatsRange.TODAY -> "LISTENING TIMELINE (HOUR)"
                        StatsRange.MONTH -> "ARTIST DISTRIBUTION (PIE)"
                        StatsRange.YEAR -> "MONTHLY LISTENING (AREA)"
                        StatsRange.ALL_TIME -> "ALL-TIME DISTRIBUTION (SCATTER)"
                        StatsRange.WEEK -> "PLAYS BY DAY (BAR)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )

                when (state.range) {
                    StatsRange.TODAY -> {
                        if (state.playsByHour.isNotEmpty()) {
                            val countByHour = state.playsByHour.associate { it.hourOfDay to it.playCount }
                            val fullDayValues = (0..23).map { hour -> (countByHour[hour] ?: 0).toFloat() }
                            com.necroware.terminusplayer.ui.components.LineChart(values = fullDayValues)
                        } else if (!state.isLoading) {
                            EmptyChartMessage()
                        }
                    }
                    StatsRange.MONTH -> {
                        if (state.monthlyArtistDistribution.isNotEmpty()) {
                            com.necroware.terminusplayer.ui.components.PieChart(
                                slices = state.monthlyArtistDistribution.map {
                                    com.necroware.terminusplayer.ui.components.PieSlice(it.first, it.second.toFloat())
                                }
                            )
                        } else if (!state.isLoading) {
                            EmptyChartMessage()
                        }
                    }
                    StatsRange.YEAR -> {
                        if (state.yearlyMonthlyTotals.isNotEmpty()) {
                            com.necroware.terminusplayer.ui.components.AreaChart(
                                points = state.yearlyMonthlyTotals.map {
                                    com.necroware.terminusplayer.ui.components.AreaPoint(monthLabelShort(it.first), it.second.toFloat())
                                }
                            )
                        } else if (!state.isLoading) {
                            EmptyChartMessage()
                        }
                    }
                    StatsRange.ALL_TIME -> {
                        if (state.playsByDay.isNotEmpty()) {
                            com.necroware.terminusplayer.ui.components.ScatterPlot(
                                values = state.playsByDay.map { it.playCount.toFloat() }
                            )
                        } else if (!state.isLoading) {
                            EmptyChartMessage()
                        }
                    }
                    StatsRange.WEEK -> {
                        if (state.playsByDay.isNotEmpty()) {
                            val barData = state.playsByDay.map { day ->
                                BarDatum(
                                    label = dayFormat().format(Date(day.dayEpoch)),
                                    value = day.playCount.toFloat()
                                )
                            }
                            BlockyBarChart(data = barData)
                        } else if (!state.isLoading) {
                            EmptyChartMessage()
                        }
                    }
                }
            }
            }
        }

        item {
            Text(
                text = "${if (showDownloadEvents) "▾" else "▸"} DOWNLOAD QUEUE · ${state.downloadQueue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { showDownloadEvents = !showDownloadEvents }.padding(vertical = 8.dp)
            )
        }
        if (showDownloadEvents && state.downloadQueue.isEmpty()) {
            item { TerminalState("DOWNLOAD QUEUE EMPTY", "Downloads you start will appear here.") }
        }
        if (showDownloadEvents && state.downloadQueue.isNotEmpty()) {
            item { TransferQueueActions(state.downloadQueue, state.transfersPaused, viewModel::pauseTransfers, viewModel::resumeTransfers, viewModel::cancelAllTransfers, viewModel::retryAllFailedUploads) }
            itemsIndexed(state.downloadQueue.asReversed().take(50), key = { _, row -> "download-${row.songId}" }) { index, event ->
                TransferQueueRow(event, downloadPositions[event.songId] ?: 0, state.transferWorkerState, "DOWNLOAD", onRetry = { viewModel.retryFailedDownload(event.songId) }, onRemove = { viewModel.removeTransfer(event.songId) })
            }
        }

        item {
            Text(
                text = "${if (showUploadEvents) "▾" else "▸"} UPLOAD QUEUE · ${state.uploadQueue.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { showUploadEvents = !showUploadEvents }.padding(vertical = 8.dp)
            )
        }
        if (showUploadEvents && state.uploadQueue.isEmpty()) {
            item { TerminalState("UPLOAD QUEUE EMPTY", "Uploads you start will appear here.") }
        }
        if (showUploadEvents && state.uploadQueue.isNotEmpty()) {
            item { TransferQueueActions(state.uploadQueue, state.transfersPaused, viewModel::pauseTransfers, viewModel::resumeTransfers, viewModel::cancelAllTransfers, viewModel::retryAllFailedUploads) }
            itemsIndexed(state.uploadQueue.asReversed().take(50), key = { _, row -> "upload-${row.songId}" }) { index, event ->
                TransferQueueRow(event, uploadPositions[event.songId] ?: 0, state.transferWorkerState, "UPLOAD", onRetry = { viewModel.retryFailedUpload(event.songId) }, onRemove = { viewModel.removeTransfer(event.songId) })
            }
        }

        item {
            Column {
                Text(
                    text = "TOP CATEGORIES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    TopCategory.entries.forEach { category ->
                        FilterChip(
                            selected = category == state.category,
                            onClick = { viewModel.selectCategory(category) },
                            label = { Text(category.label) }
                        )
                    }
                }
            }
        }

        if (state.topCategoryItems.isEmpty() && !state.isLoading) {
            item {
                TerminalState(
                    title = "NO LISTENING DATA",
                    detail = "Plays in this range will appear here."
                )
            }
        }

        itemsIndexed(state.topCategoryItems, key = { _, item -> item.id }) { index, item ->
            TopCategoryRow(item, index + 1, state.topCategoryItems.firstOrNull()?.playCount ?: 0)
        }
    }
}

@Composable
private fun TransferQueueActions(
    queue: List<com.necroware.terminusplayer.data.database.entity.UploadQueueEntity>,
    paused: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        if (queue.any { it.state == "PERMANENT_FAILURE" || it.state == "CONVERSION_FAILED" || it.state == "QUOTA_EXCEEDED" }) {
            TerminalActionText("[ RETRY FAILED ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onRetryFailed))
        }
        if (paused && queue.any { it.state in setOf("WAITING", "FAILED", "UPLOADING", "DOWNLOADING", "CONVERTING") }) {
            TerminalActionText("[ RESUME ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onResume))
        } else if (!paused && queue.any { it.state in setOf("WAITING", "FAILED", "UPLOADING", "DOWNLOADING", "CONVERTING") }) {
            TerminalActionText("[ PAUSE ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onPause))
        }
        if (queue.any { it.state in setOf("WAITING", "UPLOADING", "DOWNLOADING", "FAILED", "CONVERTING") }) {
            TerminalActionText("[ CANCEL TRANSFERS ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable(onClick = onCancel))
        }
    }
}

@Composable
private fun TransferQueueRow(
    item: com.necroware.terminusplayer.data.database.entity.UploadQueueEntity,
    queuePosition: Int,
    workerState: androidx.work.WorkInfo.State?,
    operation: String,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val active = item.state == "UPLOADING" || item.state == "DOWNLOADING"
    val isFailure = item.state == "PERMANENT_FAILURE" || item.state == "CONVERSION_FAILED" || item.state == "QUOTA_EXCEEDED"
    val totalBytes = item.totalBytes?.takeIf { it > 0L }
    val progress = totalBytes?.let { (item.bytesTransferred.toFloat() / it.toFloat()).coerceIn(0f, 1f) }
    val context = LocalContext.current
    val transferWord = if (operation == "UPLOAD") "sent" else "received"
    val elapsed = item.startedAtEpochMs?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0L) / 1000L) }
    val quietFor = item.lastProgressAtEpochMs?.let { ((System.currentTimeMillis() - it).coerceAtLeast(0L) / 1000L) }

    TerminalBorder(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isFailure) {
                    TerminalActionText("[ RETRY ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(onClick = onRetry))
                }
            }
            Text(
                text = when {
                    item.state == "WAITING" && workerState == androidx.work.WorkInfo.State.ENQUEUED -> "WAITING · worker/network constraint pending${if (queuePosition > 0) " · position $queuePosition" else ""}"
                    item.state == "WAITING" && item.phase == "INTERRUPTED" -> "INTERRUPTED · queued to resume"
                    item.state == "WAITING" -> "WAITING IN QUEUE${if (queuePosition > 0) " · position $queuePosition" else ""}"
                    item.state == "UPLOADING" -> "UPLOADING${if (queuePosition > 0) " · song $queuePosition" else ""} · attempt ${item.attemptCount + 1} of 3"
                    item.state == "DOWNLOADING" -> "DOWNLOADING${if (queuePosition > 0) " · song $queuePosition" else ""} · attempt ${item.attemptCount + 1} of 3"
                    item.state == "FAILED" -> "RETRYING · attempt ${item.attemptCount} of 3"
                    item.state == "SUCCESS" -> if (operation == "UPLOAD") "UPLOAD COMPLETE" else "DOWNLOAD COMPLETE"
                    item.state == "ALREADY_EXISTS" -> "ALREADY ON SERVER"
                    item.state == "QUOTA_EXCEEDED" -> "BLOCKED · SERVER QUOTA FULL"
                    isFailure -> "$operation FAILED · ${item.attemptCount} attempts"
                    else -> item.state
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
            if (active) {
                if (progress != null) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(progress * 100).toInt()}% · ${android.text.format.Formatter.formatShortFileSize(context, item.bytesTransferred)} / ${android.text.format.Formatter.formatShortFileSize(context, totalBytes ?: 0L)}", style = MaterialTheme.typography.bodySmall)
                } else {
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("${android.text.format.Formatter.formatShortFileSize(context, item.bytesTransferred)} $transferWord · total size unknown", style = MaterialTheme.typography.bodySmall)
                }
            } else if (item.bytesTransferred > 0L) {
                val size = item.totalBytes?.let { " / ${android.text.format.Formatter.formatShortFileSize(context, it)}" }.orEmpty()
                Text("${android.text.format.Formatter.formatShortFileSize(context, item.bytesTransferred)} $transferWord$size", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.startedAtEpochMs?.let { startedAt ->
                val startedLabel = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(startedAt))
                val timeLabel = if (active && elapsed != null) "Elapsed ${elapsed}s" else "Started $startedLabel"
                Text("$timeLabel · attempt ${item.attemptCount}/3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item.lastProgressAtEpochMs?.let {
                Text("Last progress update: ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (active && item.bytesTransferred > 0L && quietFor != null && quietFor >= 120L) {
                Text("No byte progress for ${quietFor / 60} min; it may be waiting on the network or server. Cause unknown.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            item.errorMessage?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = if (isFailure) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!active) {
                TerminalActionText("[ REMOVE ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.clickable(onClick = onRemove).padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, value: String, modifier: Modifier = Modifier) {
    TerminalBorder(modifier = modifier.fillMaxWidth()) {
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TopCategoryRow(item: TopCategoryItem, rank: Int, maxPlays: Int) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("#${rank.toString().padStart(2, '0')}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text("${item.playCount}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (maxPlays > 0) (item.playCount.toFloat() / maxPlays).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

private fun dayFormat(): SimpleDateFormat = SimpleDateFormat("EEE", Locale.getDefault())

@Composable
private fun EmptyChartMessage() {
    TerminalActionText(
        text = "[ no plays recorded in this range yet ]",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** "2026-01" -> "Jan" */
private fun monthLabelShort(monthLabel: String): String {
    val parts = monthLabel.split("-")
    val monthIndex = parts.getOrNull(1)?.toIntOrNull() ?: return monthLabel
    val names = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    return names.getOrElse(monthIndex - 1) { monthLabel }
}

private fun formatDuration(ms: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
