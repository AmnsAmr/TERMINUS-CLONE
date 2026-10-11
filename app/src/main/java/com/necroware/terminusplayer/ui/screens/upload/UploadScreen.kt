package com.necroware.terminusplayer.ui.screens.upload

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalState
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity

@Composable
fun UploadScreen(
    viewModel: UploadViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val quota by viewModel.quota.collectAsStateWithLifecycle()
    val status by viewModel.uploadStatus.collectAsStateWithLifecycle()
    val recommendedSongs by viewModel.recommendedSongs.collectAsStateWithLifecycle()
    val queue by viewModel.queue.collectAsStateWithLifecycle()

    val fileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.uploadFile(it) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
        Text(
            text = "> UPLOAD_",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 20.dp)
        )
        }
        item {
        Text(
            text = "< BACK",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onBack() }.padding(vertical = 4.dp)
        )
        }

        quota?.let {
            item {
            TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                Column {
                    Text("QUOTA", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Used: ${it.used} bytes\nTotal: ${it.total} bytes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            }
        }
        
        if (recommendedSongs.isNotEmpty()) {
            item {
            TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "RECOMMENDED TO UPLOAD",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${recommendedSongs.size} local tracks are not on the server.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "[ UPLOAD ALL ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { viewModel.uploadRecommended() }
                    )
                }
            }
            }
        }

        item {
            TerminalBorder(modifier = Modifier.fillMaxWidth().clickable { fileLauncher.launch(arrayOf("audio/*")) }) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("[ SELECT FILE TO UPLOAD ]", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text("Uploads run in the background and can be stopped from the notification.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (status.isNotBlank()) item {
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("> QUEUE_", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text("${queue.count { it.state == "UPLOADING" || it.state == "DOWNLOADING" }} active · ${queue.count { it.state == "WAITING" || it.state == "FAILED" }} pending · ${queue.count { it.state == "PERMANENT_FAILURE" || it.state == "CONVERSION_FAILED" }} failed", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    if (queue.any { it.state == "PERMANENT_FAILURE" || it.state == "CONVERSION_FAILED" }) {
                        Text("[ RETRY FAILED ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { viewModel.retryAllFailed() }.padding(4.dp))
                    }
                    if (queue.isNotEmpty()) {
                        Text("[ CLEAR ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable { viewModel.cancelAllUploads() }.padding(4.dp))
                    }
                }
            }
        }

        if (queue.isEmpty()) {
            item { TerminalState("QUEUE EMPTY", "Choose a file or add recommended tracks to start.") }
        } else {
            items(queue, key = { it.songId }) { item ->
                UploadQueueRow(
                    item = item,
                    onRetry = { viewModel.retryUpload(item.songId) },
                    onRemove = { viewModel.removeFromQueue(item.songId) }
                )
            }
        }
    }
}

@Composable
private fun UploadQueueRow(
    item: UploadQueueEntity,
    onRetry: () -> Unit,
    onRemove: () -> Unit
) {
    val isFailure = item.state == "PERMANENT_FAILURE" || item.state == "CONVERSION_FAILED"
    val stateColor = when {
        isFailure -> MaterialTheme.colorScheme.error
        item.state == "UPLOADING" -> MaterialTheme.colorScheme.primary
        item.state == "SUCCESS" || item.state == "ALREADY_EXISTS" -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 2)
            Text(
                text = when (item.state) {
                    "UPLOADING" -> "UPLOADING · attempt ${item.attemptCount + 1} of 3"
                    "DOWNLOADING" -> "DOWNLOADING · attempt ${item.attemptCount + 1} of 3"
                    "CONVERTING" -> "WAITING IN QUEUE"
                    "WAITING" -> "WAITING IN QUEUE"
                    "FAILED" -> "RETRYING · attempt ${item.attemptCount} of 3"
                    "SUCCESS" -> "UPLOAD COMPLETE"
                    "ALREADY_EXISTS" -> "ALREADY ON SERVER"
                    "CONVERSION_FAILED" -> "UPLOAD FAILED"
                    "PERMANENT_FAILURE" -> "UPLOAD FAILED"
                    else -> item.state
                },
                style = MaterialTheme.typography.labelSmall,
                color = stateColor
            )
            if (item.state == "UPLOADING" || item.state == "DOWNLOADING") {
                val total = item.totalBytes?.takeIf { it > 0L }
                val fraction = total?.let { (item.bytesTransferred.toFloat() / it).coerceIn(0f, 1f) }
                if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                    val percent = (fraction * 100).toInt()
                    Text("$percent% · ${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, item.bytesTransferred)} / ${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, total ?: 0L)}", style = MaterialTheme.typography.bodySmall, color = stateColor)
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text("${android.text.format.Formatter.formatShortFileSize(androidx.compose.ui.platform.LocalContext.current, item.bytesTransferred)} transferred · total unknown", style = MaterialTheme.typography.bodySmall, color = stateColor)
                }
            }
            item.errorMessage?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (isFailure || item.state != "UPLOADING") {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (isFailure) {
                        Text("[ RETRY ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 8.dp))
                    }
                    Text("[ REMOVE ]", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(onClick = onRemove).padding(vertical = 8.dp))
                }
            }
        }
    }
}
