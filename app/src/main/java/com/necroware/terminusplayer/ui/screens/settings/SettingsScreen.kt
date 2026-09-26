package com.necroware.terminusplayer.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.necroware.terminusplayer.data.prefs.SortDirection
import com.necroware.terminusplayer.data.prefs.SortField
import com.necroware.terminusplayer.data.prefs.MotionPreference
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalSlider
import com.necroware.terminusplayer.ui.theme.ThemePresets
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToUpload: () -> Unit = {},
    onNavigateToGapFinder: () -> Unit = {},
    onNavigateToManageSources: () -> Unit = {}
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()

    var expandedSections by remember { mutableStateOf(setOf<String>()) }
    val toggleSection: (String) -> Unit = { section ->
        expandedSections = if (expandedSections.contains(section)) {
            expandedSections - section
        } else {
            expandedSections + section
        }
    }


    val addFilesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> viewModel.importFiles(uris) }

    val importPlaylistLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::importPlaylist) }

    // Auto-clear the status line a few seconds after a finished import so
    // it doesn't linger indefinitely as stale state.
    LaunchedEffect(importStatus) {
        if (importStatus !is ImportStatus.Idle && importStatus !is ImportStatus.Running) {
            delay(4000)
            viewModel.dismissImportStatus()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            Text(
                text = "> SETTINGS_",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            SettingsDropdown(
                label = "THEME",
                expanded = expandedSections.contains("THEME"),
                onToggle = { toggleSection("THEME") },
                motionPreference = prefs.motionPreference
            ) {
                ThemeGrid(selected = prefs.themeId, onSelect = viewModel::setTheme)
            }
        }

        item {
            SettingsDropdown(
                label = "ANIMATIONS",
                expanded = expandedSections.contains("ANIMATIONS"),
                onToggle = { toggleSection("ANIMATIONS") },
                motionPreference = prefs.motionPreference
            ) {
                MotionPreferenceSection(
                    selected = prefs.motionPreference,
                    onSelect = viewModel::setMotionPreference
                )
            }
        }

        item {
            SettingsDropdown(
                label = "SERVER SETTINGS",
                expanded = expandedSections.contains("SERVER SETTINGS"),
                onToggle = { toggleSection("SERVER SETTINGS") },
                motionPreference = prefs.motionPreference
            ) {
                Column {
                    ServerSettingsSection(
                        serverUrl = prefs.serverUrl,
                        username = prefs.username,
                        password = prefs.password,
                        onSave = { url, user, pass ->
                            viewModel.setServerSettings(url, user, pass)
                        }
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    ActionRow(label = "[ UPLOAD ]", sublabel = "Upload files to server", onClick = onNavigateToUpload)
                    Spacer(modifier = Modifier.height(10.dp))
                    ActionRow(label = "[ GAPFINDER ]", sublabel = "Configure GapFinder", onClick = onNavigateToGapFinder)
                    Spacer(modifier = Modifier.height(10.dp))
                    ActionRow(label = "[ MANAGE SOURCES ]", sublabel = "Include or exclude local audio folders", onClick = onNavigateToManageSources)
                }
            }
        }

        item {
            SettingsDropdown(
                label = "EQUALIZER",
                expanded = expandedSections.contains("EQUALIZER"),
                onToggle = { toggleSection("EQUALIZER") },
                motionPreference = prefs.motionPreference
            ) {
                EqualizerSection(
                    enabled = prefs.equalizer.enabled,
                    bandGains = prefs.equalizer.bandGainsDb,
                    onEnabledChange = viewModel::setEqualizerEnabled,
                    onBandChange = viewModel::setEqualizerBand,
                    onReset = viewModel::resetEqualizerBands
                )
            }
        }

        item {
            SettingsDropdown(
                label = "CROSSFADE",
                expanded = expandedSections.contains("CROSSFADE"),
                onToggle = { toggleSection("CROSSFADE") },
                motionPreference = prefs.motionPreference
            ) {
                CrossfadeSection(
                    enabled = prefs.crossfade.enabled,
                    durationMs = prefs.crossfade.durationMs,
                    onEnabledChange = viewModel::setCrossfadeEnabled,
                    onDurationChange = viewModel::setCrossfadeDurationMs
                )
            }
        }

        item {
            SettingsDropdown(
                label = "CODEC",
                expanded = expandedSections.contains("CODEC"),
                onToggle = { toggleSection("CODEC") },
                motionPreference = prefs.motionPreference
            ) {
                ToggleRow(
                    label = "Prefer hardware decoder",
                    sublabel = "Falls back to software automatically if unsupported",
                    checked = prefs.preferHardwareDecoder,
                    onCheckedChange = viewModel::setPreferHardwareDecoder
                )
            }
        }

        item {
            SettingsDropdown(
                label = "STREAMING QUALITY",
                expanded = expandedSections.contains("STREAMING QUALITY"),
                onToggle = { toggleSection("STREAMING QUALITY") },
                motionPreference = prefs.motionPreference
            ) {
                QualitySection(
                    selectedBitRate = prefs.maxBitRate,
                    onSelect = viewModel::setMaxBitRate
                )
            }
        }

        item {
            SettingsDropdown(
                label = "LIBRARY SORT",
                expanded = expandedSections.contains("LIBRARY SORT"),
                onToggle = { toggleSection("LIBRARY SORT") },
                motionPreference = prefs.motionPreference
            ) {
                SortSection(
                    field = prefs.librarySortOrder.field,
                    direction = prefs.librarySortOrder.direction,
                    onFieldChange = viewModel::setSortField,
                    onDirectionChange = viewModel::setSortDirection
                )
            }
        }

        item {
            SettingsDropdown(
                label = "IMPORT",
                expanded = expandedSections.contains("IMPORT"),
                onToggle = { toggleSection("IMPORT") },
                motionPreference = prefs.motionPreference
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionRow(
                        label = "[ ADD FILES ]",
                        sublabel = "Copy audio files into your library"
                    ) { addFilesLauncher.launch(arrayOf("audio/*")) }
                    ActionRow(
                        label = "[ IMPORT PLAYLIST ]",
                        sublabel = "Load an .m3u/.m3u8 playlist"
                    ) {
                        importPlaylistLauncher.launch(
                            arrayOf("audio/x-mpegurl", "audio/mpegurl", "application/octet-stream", "*/*")
                        )
                    }

                    val statusText = when (val s = importStatus) {
                        ImportStatus.Idle -> null
                        ImportStatus.Running -> "[ working... ]"
                        is ImportStatus.FilesDone -> "[ imported ${s.count} file${if (s.count == 1) "" else "s"} ]"
                        is ImportStatus.PlaylistDone -> "[ matched ${s.matched} / ${s.total} tracks ]"
                        is ImportStatus.Failed -> "[ ${s.message} ]"
                    }
                    statusText?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ThemeGrid(selected: ThemePresetId, onSelect: (ThemePresetId) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.height(300.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(ThemePresets, key = { it.id }) { preset ->
            val isSelected = preset.id == selected
            TerminalBorder(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(preset.id) }
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(preset.background, preset.accent)
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        Text(
                            text = preset.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isSelected) "ACTIVE" else " ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Swatch(background: Color, accent: Color) {
    Row(modifier = Modifier.size(24.dp).clip(MaterialTheme.shapes.extraSmall)) {
        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(background))
        Box(modifier = Modifier.weight(1f).fillMaxHeight().background(accent))
    }
}

private val EQ_BAND_LABELS = listOf("60", "230", "910", "3.6k", "14k")

@Composable
private fun EqualizerSection(
    enabled: Boolean,
    bandGains: List<Int>,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (Int, Int) -> Unit,
    onReset: () -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "5-BAND EQ",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row {
                    Text(
                        text = "[RESET] ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onReset() }
                    )
                    Text(
                        text = if (enabled) "[ON]" else "[OFF]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onEnabledChange(!enabled) }
                    )
                }
            }

            EQ_BAND_LABELS.forEachIndexed { index, label ->
                val gainDb = bandGains.getOrElse(index) { 0 }
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${label}Hz",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${if (gainDb > 0) "+" else ""}${gainDb}dB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TerminalSlider(
                        value = (gainDb + 12) / 24f,
                        onValueChange = { fraction -> onBandChange(index, ((fraction * 24f) - 12f).toInt()) },
                        originFraction = 0.5f,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CrossfadeSection(
    enabled: Boolean,
    durationMs: Int,
    onEnabledChange: (Boolean) -> Unit,
    onDurationChange: (Int) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fade between tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (enabled) "[ON]" else "[OFF]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onEnabledChange(!enabled) }
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DURATION",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "%.1fs".format(durationMs / 1000f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TerminalSlider(
                value = ((durationMs - 1000) / 11000f).coerceIn(0f, 1f),
                onValueChange = { fraction -> onDurationChange((1000 + fraction * 11000).toInt()) },
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, sublabel: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = if (checked) "[ON]" else "[OFF]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MotionPreferenceSection(
    selected: MotionPreference,
    onSelect: (MotionPreference) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            MotionPreference.entries.forEach { preference ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(preference) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = (if (preference == selected) "> " else "  ") + preference.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preference == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (preference) {
                            MotionPreference.FULL -> "Playback visuals and full screen transitions"
                            MotionPreference.REDUCED -> "No looping visuals; shorter screen transitions"
                            MotionPreference.OFF -> "Apply screen changes without optional motion"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun QualitySection(
    selectedBitRate: Int?,
    onSelect: (Int?) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            val options = listOf(null to "Original", 320 to "320 kbps", 128 to "128 kbps", 64 to "64 kbps")
            options.forEach { (bitRate, label) ->
                Text(
                    text = (if (bitRate == selectedBitRate) "> " else "  ") + label,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bitRate == selectedBitRate) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(bitRate) }
                        .padding(vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SortSection(
    field: SortField,
    direction: SortDirection,
    onFieldChange: (SortField) -> Unit,
    onDirectionChange: (SortDirection) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            SortField.entries.forEach { candidate ->
                Text(
                    text = (if (candidate == field) "> " else "  ") + candidate.name.replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (candidate == field) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFieldChange(candidate) }
                        .padding(vertical = 6.dp)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "[ ASCENDING ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (direction == SortDirection.ASC) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clickable { onDirectionChange(SortDirection.ASC) }
                )
                Text(
                    text = "[ DESCENDING ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (direction == SortDirection.DESC) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clickable { onDirectionChange(SortDirection.DESC) }
                )
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, sublabel: String, onClick: () -> Unit) {
    TerminalBorder(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            Text(
                sublabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServerSettingsSection(
    serverUrl: String,
    username: String,
    password: String,
    onSave: (String, String, String) -> Unit
) {
    var url by remember { mutableStateOf(serverUrl) }
    var user by remember { mutableStateOf(username) }
    var pass by remember { mutableStateOf(password) }
    LaunchedEffect(serverUrl) { url = serverUrl }
    LaunchedEffect(username) { user = username }
    LaunchedEffect(password) { pass = password }

    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Server URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
            Button(
                onClick = { onSave(url, user, pass) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("SAVE & SYNC")
            }
        }
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    motionPreference: MotionPreference,
    content: @Composable () -> Unit
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionLabel(label)
            Text(
                text = if (expanded) "[ - ]" else "[ + ]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        if (motionPreference == MotionPreference.OFF) {
            if (expanded) {
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    content()
                }
            }
        } else {
            AnimatedVisibility(visible = expanded) {
                Box(modifier = Modifier.padding(top = 8.dp)) {
                    content()
                }
            }
        }
    }
}
