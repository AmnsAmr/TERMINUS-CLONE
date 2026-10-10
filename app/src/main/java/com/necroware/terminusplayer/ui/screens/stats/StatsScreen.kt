package com.necroware.terminusplayer.ui.screens.stats

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
                text = "${if (showDownloadEvents) "▾" else "▸"} DOWNLOAD ACTIVITY · ${state.downloadEvents.size}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().clickable { showDownloadEvents = !showDownloadEvents }.padding(vertical = 8.dp)
            )
        }
        if (showDownloadEvents && state.downloadEvents.isEmpty()) {
            item { TerminalState("NO DOWNLOAD ACTIVITY", "Downloads will appear here while they run.") }
        }
        if (showDownloadEvents) {
            itemsIndexed(state.downloadEvents.asReversed().take(50), key = { index, _ -> "download-$index" }) { _, event ->
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "${event.status}: ${event.songTitle}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (event.status == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(event.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
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
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                    if (state.uploadQueue.any { it.state == "PERMANENT_FAILURE" || it.state == "CONVERSION_FAILED" }) {
                        Text("[ RETRY FAILED ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.retryAllFailedUploads() })
                    }
                    Text("[ CLEAR QUEUE ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { viewModel.cancelAllUploads() })
                }
            }
            items(state.uploadQueue.asReversed().take(50), key = { "upload-${it.songId}" }) { event ->
                TerminalBorder(Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${event.state}: ${event.title}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (event.state.contains("FAIL")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (event.state == "PERMANENT_FAILURE" || event.state == "CONVERSION_FAILED") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("[ RETRY ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.retryFailedUpload(event.songId) })
                                    Text("[ CONVERT & RETRY ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { viewModel.retryWithConversion(event.songId) })
                                }
                            }
                        }
                        Text("Attempt: ${event.attemptCount}/3", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!event.errorMessage.isNullOrBlank()) {
                            Text("Reason: ${event.errorMessage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
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
                progress = if (maxPlays > 0) (item.playCount.toFloat() / maxPlays).coerceIn(0f, 1f) else 0f,
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
    Text(
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
