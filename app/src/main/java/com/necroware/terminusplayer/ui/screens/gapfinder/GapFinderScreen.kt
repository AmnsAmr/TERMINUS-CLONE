package com.necroware.terminusplayer.ui.screens.gapfinder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.ui.components.TerminalBorder

@Composable
fun GapFinderScreen(
    viewModel: GapFinderViewModel = hiltViewModel(),
    onBack: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val error by viewModel.errorMessage.collectAsStateWithLifecycle()

    var thresholdStr by remember { mutableStateOf(settings?.threshold?.toString() ?: "0.0") }
    LaunchedEffect(settings?.threshold) {
        settings?.threshold?.let { thresholdStr = it.toString() }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            text = "> GAPFINDER_",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        
        Text(
            text = "< BACK",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onBack() }
        )

        val currentSettings = settings
        if (currentSettings != null) {
            val s = currentSettings
            TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SETTINGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth().clickable { viewModel.updateSettings(!s.enabled, s.threshold) }) {
                        Text("Enabled", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        Text(if (s.enabled) "[ON]" else "[OFF]", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    OutlinedTextField(
                        value = thresholdStr,
                        onValueChange = { thresholdStr = it },
                        label = { Text("Threshold") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { viewModel.updateSettings(s.enabled, thresholdStr.toFloatOrNull() ?: s.threshold) }) {
                        Text("SAVE SETTINGS")
                    }
                }
            }
        } else {
            TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                Text("Loading settings or unavailable...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STATUS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                status?.let { st ->
                    Text("Running: ${st.running}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Progress: ${st.progress}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Text("Track: ${st.currentTrack ?: "None"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Button(onClick = { viewModel.runGapFinder() }) {
                    Text("RUN GAPFINDER")
                }
            }
        }

        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}
