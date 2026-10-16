package com.necroware.terminusplayer.ui.screens.settings

import com.necroware.terminusplayer.ui.components.TerminalActionText

import android.content.Intent
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.toArgb
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
import com.necroware.terminusplayer.data.prefs.PerformanceMode
import com.necroware.terminusplayer.data.prefs.TerminalCursorStyle
import com.necroware.terminusplayer.data.prefs.TerminalCursorBlink
import com.necroware.terminusplayer.data.prefs.TerminalHaptics
import com.necroware.terminusplayer.data.prefs.HudTelemetryMode
import com.necroware.terminusplayer.data.prefs.VisualizerStyle
import com.necroware.terminusplayer.data.prefs.ThemePresetId
import com.necroware.terminusplayer.data.prefs.ThemeColorRole
import com.necroware.terminusplayer.ui.components.TerminalBorder
import com.necroware.terminusplayer.ui.components.TerminalHeader
import com.necroware.terminusplayer.ui.components.TerminalSlider
import com.necroware.terminusplayer.ui.components.rememberTerminalHaptics
import kotlin.math.roundToInt
import com.necroware.terminusplayer.ui.theme.ThemePresets
import com.necroware.terminusplayer.ui.theme.color
import com.necroware.terminusplayer.ui.theme.themePresetById
import com.necroware.terminusplayer.ui.theme.withOverrides
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onNavigateToUpload: () -> Unit = {},
    onNavigateToGapFinder: () -> Unit = {},
    onNavigateToManageSources: () -> Unit = {}
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val effectiveMotionPreference by viewModel.effectiveMotionPreference.collectAsStateWithLifecycle()
    val importStatus by viewModel.importStatus.collectAsStateWithLifecycle()
    val downloadFolderError by viewModel.downloadFolderError.collectAsStateWithLifecycle()

    val haptic = rememberTerminalHaptics(prefs.terminalHaptics)
    var expandedSections by remember { mutableStateOf(setOf<String>()) }
    val toggleSection: (String) -> Unit = { section ->
        haptic(false)
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

    val downloadFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            viewModel.setDownloadFolderUri(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

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
            TerminalHeader(
                title = "SETTINGS",
                cursorStyle = prefs.cursorStyle,
                cursorBlink = prefs.cursorBlinkCadence,
                motionPreference = effectiveMotionPreference
            )
        }

        item {
            SettingsDropdown(
                label = "PERFORMANCE",
                expanded = expandedSections.contains("PERFORMANCE"),
                onToggle = { toggleSection("PERFORMANCE") },
                motionPreference = effectiveMotionPreference
            ) {
                PerformanceModeSection(
                    selected = prefs.performanceMode,
                    onSelect = viewModel::setPerformanceMode
                )
            }
        }

        item {
            SettingsDropdown(
                label = "THEME",
                expanded = expandedSections.contains("THEME"),
                onToggle = { toggleSection("THEME") },
                motionPreference = effectiveMotionPreference
            ) {
                ThemeGrid(selected = prefs.themeId, onSelect = viewModel::setTheme)
            }
        }

        item {
            SettingsDropdown(
                label = "CUSTOM COLORS",
                expanded = expandedSections.contains("CUSTOM COLORS"),
                onToggle = { toggleSection("CUSTOM COLORS") },
                motionPreference = effectiveMotionPreference
            ) {
                ThemeColorEditor(
                    themeId = prefs.themeId,
                    overrides = prefs.themeColorOverrides,
                    onSet = viewModel::setThemeColor,
                    onClear = viewModel::clearThemeColor,
                    onClearRoles = { viewModel.clearThemeColors(it) },
                    onResetAll = { viewModel.clearThemeColors() }
                )
            }
        }

        item {
            SettingsDropdown(
                label = "ANIMATIONS",
                expanded = expandedSections.contains("ANIMATIONS"),
                onToggle = { toggleSection("ANIMATIONS") },
                motionPreference = effectiveMotionPreference
            ) {
                MotionPreferenceSection(
                    selected = prefs.motionPreference,
                    onSelect = viewModel::setMotionPreference
                )
            }
        }

        item {
            SettingsDropdown(
                label = "CRT & AESTHETICS",
                expanded = expandedSections.contains("CRT & AESTHETICS"),
                onToggle = { toggleSection("CRT & AESTHETICS") },
                motionPreference = effectiveMotionPreference
            ) {
                CrtAestheticsSection(
                    scanlineIntensity = prefs.scanlineIntensity,
                    onIntensityChange = viewModel::setScanlineIntensity,
                    phosphorAfterglow = prefs.phosphorAfterglow,
                    onAfterglowChange = viewModel::setPhosphorAfterglow,
                    cursorStyle = prefs.cursorStyle,
                    onCursorStyleChange = viewModel::setCursorStyle,
                    cursorBlink = prefs.cursorBlinkCadence,
                    onCursorBlinkChange = viewModel::setCursorBlinkCadence,
                    adaptiveArtAccent = prefs.adaptiveArtAccent,
                    onAdaptiveArtAccentChange = viewModel::setAdaptiveArtAccent,
                    reactiveHudFlash = prefs.reactiveHudFlash,
                    onReactiveHudFlashChange = viewModel::setReactiveHudFlash,
                    visualizerStyle = prefs.visualizerStyle,
                    onVisualizerStyleChange = viewModel::setVisualizerStyle,
                    motionPreference = effectiveMotionPreference
                )
            }
        }

        item {
            SettingsDropdown(
                label = "HAPTICS",
                expanded = expandedSections.contains("HAPTICS"),
                onToggle = { toggleSection("HAPTICS") },
                motionPreference = effectiveMotionPreference
            ) {
                TerminalHapticsSection(
                    selected = prefs.terminalHaptics,
                    onSelect = viewModel::setTerminalHaptics
                )
            }
        }

        item {
            SettingsDropdown(
                label = "SERVER SETTINGS",
                expanded = expandedSections.contains("SERVER SETTINGS"),
                onToggle = { toggleSection("SERVER SETTINGS") },
                motionPreference = effectiveMotionPreference
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
                    Spacer(modifier = Modifier.height(10.dp))
                    ActionRow(
                        label = "[ DOWNLOAD FOLDER ]",
                        sublabel = prefs.downloadFolderUri?.let { "Custom folder selected" } ?: "Music/Terminus (default)",
                        onClick = { downloadFolderLauncher.launch(null) }
                    )
                    downloadFolderError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            SettingsDropdown(
                label = "EQUALIZER",
                expanded = expandedSections.contains("EQUALIZER"),
                onToggle = { toggleSection("EQUALIZER") },
                motionPreference = effectiveMotionPreference
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
                motionPreference = effectiveMotionPreference
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
                label = "STREAMING QUALITY",
                expanded = expandedSections.contains("STREAMING QUALITY"),
                onToggle = { toggleSection("STREAMING QUALITY") },
                motionPreference = effectiveMotionPreference
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow(
                        label = "Smart Bitrate",
                        sublabel = "Use independent bitrate ceilings on Wi-Fi vs mobile cellular data",
                        checked = prefs.smartBitrateEnabled,
                        onCheckedChange = viewModel::setSmartBitrateEnabled
                    )
                    if (prefs.smartBitrateEnabled) {
                        Text(
                            text = "WI-FI STREAMING QUALITY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        QualitySection(
                            selectedBitRate = prefs.maxBitRate,
                            onSelect = viewModel::setMaxBitRate
                        )
                        Text(
                            text = "CELLULAR DATA QUALITY CEILING",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        QualitySection(
                            selectedBitRate = prefs.cellularMaxBitRate,
                            onSelect = viewModel::setCellularMaxBitRate
                        )
                    } else {
                        QualitySection(
                            selectedBitRate = prefs.maxBitRate,
                            onSelect = viewModel::setMaxBitRate
                        )
                    }
                }
            }
        }

        item {
            SettingsDropdown(
                label = "PLAYBACK BEHAVIOR",
                expanded = expandedSections.contains("PLAYBACK BEHAVIOR"),
                onToggle = { toggleSection("PLAYBACK BEHAVIOR") },
                motionPreference = effectiveMotionPreference
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow(
                        label = "Pull up Now Playing from Library",
                        sublabel = "Automatically expand full player when tapping a song in Library",
                        checked = prefs.openPlayerOnLibraryTrackSelect,
                        onCheckedChange = viewModel::setOpenPlayerOnLibraryTrackSelect
                    )
                    ToggleRow(
                        label = "Pull up Now Playing from Playlists",
                        sublabel = "Automatically expand full player when tapping a song in Playlists",
                        checked = prefs.openPlayerOnPlaylistTrackSelect,
                        onCheckedChange = viewModel::setOpenPlayerOnPlaylistTrackSelect
                    )
                }
            }
        }

        item {
            SettingsDropdown(
                label = "HUD TELEMETRY",
                expanded = expandedSections.contains("HUD TELEMETRY"),
                onToggle = { toggleSection("HUD TELEMETRY") },
                motionPreference = effectiveMotionPreference
            ) {
                HudTelemetrySection(
                    selected = prefs.hudTelemetryMode,
                    onSelect = viewModel::setHudTelemetryMode
                )
            }
        }

        item {
            SettingsDropdown(
                label = "LIBRARY SORT",
                expanded = expandedSections.contains("LIBRARY SORT"),
                onToggle = { toggleSection("LIBRARY SORT") },
                motionPreference = effectiveMotionPreference
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
                motionPreference = effectiveMotionPreference
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
                    Swatch(listOf(preset.background, preset.surface, preset.accent, preset.secondaryAccent))
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
private fun Swatch(colors: List<Color>) {
    Row(modifier = Modifier.size(32.dp, 24.dp).clip(MaterialTheme.shapes.extraSmall)) {
        colors.forEach { color -> Box(modifier = Modifier.weight(1f).fillMaxHeight().background(color)) }
    }
}

private val COLOR_GROUPS = listOf(
    "GLOBAL" to listOf(ThemeColorRole.BACKGROUND, ThemeColorRole.SURFACE, ThemeColorRole.SURFACE_ELEVATED,
        ThemeColorRole.ACCENT, ThemeColorRole.SECONDARY_ACCENT, ThemeColorRole.TEXT_PRIMARY,
        ThemeColorRole.TEXT_SECONDARY, ThemeColorRole.TEXT_MUTED, ThemeColorRole.BORDER),
    "HOME / LIBRARY" to listOf(ThemeColorRole.HOME_BACKGROUND),
    "PLAYER" to listOf(ThemeColorRole.PLAYER_BACKGROUND),
    "NAVIGATION" to listOf(ThemeColorRole.NAVIGATION_BACKGROUND),
    "PLAYLISTS" to listOf(ThemeColorRole.PLAYLIST_SURFACE),
    "VISUALIZER" to listOf(ThemeColorRole.VISUALIZER_PRIMARY, ThemeColorRole.VISUALIZER_SECONDARY)
)

private fun ThemeColorRole.label() = name.replace('_', ' ')

@Composable
private fun ThemeColorEditor(
    themeId: ThemePresetId,
    overrides: Map<ThemeColorRole, String>,
    onSet: (ThemeColorRole, String) -> Unit,
    onClear: (ThemeColorRole) -> Unit,
    onClearRoles: (Set<ThemeColorRole>) -> Unit,
    onResetAll: () -> Unit
) {
    var editing by remember { mutableStateOf<ThemeColorRole?>(null) }
    val effective = themePresetById(themeId).withOverrides(overrides)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        COLOR_GROUPS.forEach { (group, roles) ->
            Text(group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            roles.forEach { role ->
                val color = overrides[role]?.let { com.necroware.terminusplayer.ui.theme.parseThemeColor(it) } ?: effective.color(role)
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { editing = role }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(role.label(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).background(color).clip(MaterialTheme.shapes.extraSmall))
                        Text(overrides[role] ?: "${color.toArgb().toUInt().toString(16).takeLast(6).uppercase()}",
                            modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (role in overrides) Text(" [RESET]", modifier = Modifier.clickable { onClear(role) }.padding(start = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            TerminalActionText("[ RESET $group ]", modifier = Modifier.clickable { onClearRoles(roles.toSet()) },
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TerminalActionText("[ RESTORE SELECTED PRESET ]", modifier = Modifier.clickable { onResetAll() }.padding(vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    editing?.let { role ->
        val base = overrides[role] ?: effective.color(role).toArgb().toUInt().toString(16).takeLast(6).uppercase()
        ThemeColorPickerDialog(role, base, onDismiss = { editing = null }, onApply = { onSet(role, it); editing = null })
    }
}

@Composable
private fun ThemeColorPickerDialog(role: ThemeColorRole, initial: String, onDismiss: () -> Unit, onApply: (String) -> Unit) {
    var hex by remember(role, initial) { mutableStateOf(initial.removePrefix("#").uppercase()) }
    val valid = hex.matches(Regex("(?:[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})"))
    val preview = if (valid) com.necroware.terminusplayer.ui.theme.parseThemeColor("#$hex") ?: Color.Transparent else Color.Transparent
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${role.label()} COLOR", color = MaterialTheme.colorScheme.primary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).background(preview))
                Text("SELECT A COLOR", style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("#39FF14", "#FFB000", "#FF4FD8", "#42E8F5", "#FFFFFF", "#202020").forEach { sample ->
                        Box(Modifier.size(30.dp).background(com.necroware.terminusplayer.ui.theme.parseThemeColor(sample) ?: Color.Transparent)
                            .clickable { hex = sample.removePrefix("#") })
                    }
                }
                OutlinedTextField(value = hex, onValueChange = { hex = it.removePrefix("#").take(8).uppercase() },
                    label = { Text("HEX (RRGGBB)") }, singleLine = true, isError = !valid)
                if (!valid) Text("Enter 6 or 8 hexadecimal digits", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall)
            }
        },
        confirmButton = { TextButton(onClick = { if (valid) onApply("#$hex") }, enabled = valid) { Text("APPLY") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } }
    )
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
                    TerminalActionText(
                        text = "[RESET] ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onReset() }
                    )
                    TerminalActionText(
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
                TerminalActionText(
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
            TerminalActionText(
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
                            MotionPreference.MEDIUM -> "Short, subtle screen transitions"
                            MotionPreference.OFF -> "Immediate screen changes with no movement"
                            MotionPreference.SYSTEM -> "Follow your Android animation setting"
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
private fun PerformanceModeSection(
    selected: PerformanceMode,
    onSelect: (PerformanceMode) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "PERFORMANCE CAPS OPTIONAL VISUAL MOTION. ANIMATIONS SETTING STILL APPLIES.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            PerformanceMode.entries.forEach { mode ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = (if (mode == selected) "> " else "  ") + mode.name.replace('_', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (mode == selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (mode) {
                            PerformanceMode.AUTO -> "Follow Android Battery Saver; otherwise use your animation setting"
                            PerformanceMode.FULL -> "Use your animation setting without a performance cap"
                            PerformanceMode.BALANCED -> "Limit Full animations to the Reduced level"
                            PerformanceMode.BATTERY_SAVER -> "Turn off optional animations while preserving playback controls"
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
            val options = listOf(
                null to "Original",
                320 to "320 kbps",
                256 to "256 kbps",
                192 to "192 kbps",
                128 to "128 kbps",
                96 to "96 kbps",
                64 to "64 kbps"
            )
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
                TerminalActionText(
                    text = "[ ASCENDING ]",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (direction == SortDirection.ASC) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.clickable { onDirectionChange(SortDirection.ASC) }
                )
                TerminalActionText(
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
            TerminalActionText(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
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
            TerminalActionText(
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

@Composable
private fun CrtAestheticsSection(
    scanlineIntensity: Float,
    onIntensityChange: (Float) -> Unit,
    phosphorAfterglow: Boolean,
    onAfterglowChange: (Boolean) -> Unit,
    cursorStyle: TerminalCursorStyle,
    onCursorStyleChange: (TerminalCursorStyle) -> Unit,
    cursorBlink: TerminalCursorBlink,
    onCursorBlinkChange: (TerminalCursorBlink) -> Unit,
    adaptiveArtAccent: Boolean,
    onAdaptiveArtAccentChange: (Boolean) -> Unit,
    reactiveHudFlash: Boolean,
    onReactiveHudFlashChange: (Boolean) -> Unit,
    visualizerStyle: VisualizerStyle,
    onVisualizerStyleChange: (VisualizerStyle) -> Unit,
    motionPreference: MotionPreference
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "SCANLINE INTENSITY",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "${(scanlineIntensity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                TerminalSlider(
                    value = scanlineIntensity,
                    onValueChange = onIntensityChange,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        ToggleRow(
            label = "Phosphor afterglow",
            sublabel = "Simulate CRT phosphor persistence and ambient glow",
            checked = phosphorAfterglow,
            onCheckedChange = onAfterglowChange
        )

        ToggleRow(
            label = "Adaptive neon art accent",
            sublabel = "Extract cyberpunk neon accent from current album art",
            checked = adaptiveArtAccent,
            onCheckedChange = onAdaptiveArtAccentChange
        )

        ToggleRow(
            label = "Reactive HUD state flash",
            sublabel = "Brief terminal alert flash when playback state changes (play/pause/stop)",
            checked = reactiveHudFlash,
            onCheckedChange = onReactiveHudFlashChange
        )

        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "AUDIO VISUALIZER STYLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                VisualizerStyle.entries.forEach { style ->
                    val isSelected = style == visualizerStyle
                    val (label, desc) = when (style) {
                        VisualizerStyle.BARS -> "SPECTRUM BARS" to "Multi-band spectrum analyzer with peak holds"
                        VisualizerStyle.OSCILLOSCOPE -> "CRT OSCILLOSCOPE" to "Continuous analog vector phosphor waveform"
                        VisualizerStyle.VU_METER -> "STEREO VU METER" to "Dual-channel calibrated LED peak monitor"
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVisualizerStyleChange(style) }
                            .padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = (if (isSelected) "> " else "  ") + label,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = desc,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "CURSOR STYLE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                TerminalCursorStyle.entries.forEach { style ->
                    val isSelected = style == cursorStyle
                    Text(
                        text = (if (isSelected) "> " else "  ") + "${style.name} [ ${style.glyph} ]",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCursorStyleChange(style) }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }

        TerminalBorder(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "CURSOR BLINK CADENCE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                TerminalCursorBlink.entries.forEach { blink ->
                    val isSelected = blink == cursorBlink
                    val desc = when (blink) {
                        TerminalCursorBlink.FAST -> "FAST (500ms)"
                        TerminalCursorBlink.SLOW -> "SLOW (1000ms)"
                        TerminalCursorBlink.SOLID -> "SOLID (no blink)"
                    }
                    Text(
                        text = (if (isSelected) "> " else "  ") + desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCursorBlinkChange(blink) }
                            .padding(vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalHapticsSection(
    selected: TerminalHaptics,
    onSelect: (TerminalHaptics) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "MECHANICAL & HAPTIC PROFILES",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            TerminalHaptics.entries.forEach { haptics ->
                val isSelected = haptics == selected
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(haptics) }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = (if (isSelected) "> " else "  ") + haptics.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (haptics) {
                            TerminalHaptics.CLICKY -> "Mechanical Cherry MX Blue click haptic on button press"
                            TerminalHaptics.TACTILE -> "Subtle bump on slider tick / track skip"
                            TerminalHaptics.OFF -> "No haptic feedback"
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
private fun HudTelemetrySection(
    selected: HudTelemetryMode,
    onSelect: (HudTelemetryMode) -> Unit
) {
    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column {
            Text(
                "NOW PLAYING HUD METRIC DENSITY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            HudTelemetryMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mode) }
                        .padding(vertical = 6.dp)
                ) {
                    Text(
                        text = (if (isSelected) "> " else "  ") + mode.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when (mode) {
                            HudTelemetryMode.STANDARD -> "Standard audio format, volume, and CPU readout"
                            HudTelemetryMode.EXPANDED -> "Live audio decoder, dynamic buffer health, audio session ID, and server ping"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
