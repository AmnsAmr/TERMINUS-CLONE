package com.necroware.terminusplayer.ui.screens.settings

import com.necroware.terminusplayer.ui.components.TerminalActionText
import com.necroware.terminusplayer.ui.components.TerminalToggleButton

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import com.necroware.terminusplayer.ui.TestTags
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.necroware.terminusplayer.data.prefs.PlaybackArtStyle
import com.necroware.terminusplayer.data.prefs.EqualizerSettings
import com.necroware.terminusplayer.data.prefs.EqualizerPreset
import com.necroware.terminusplayer.data.prefs.PreampMode
import com.necroware.terminusplayer.data.prefs.PRESET_ID_CUSTOM
import com.necroware.terminusplayer.playback.AudioOutputDeviceManager
import com.necroware.terminusplayer.ui.components.visualizer.WarpCycleIntervals
import com.necroware.terminusplayer.ui.components.visualizer.WarpPresets
import com.necroware.terminusplayer.ui.components.visualizer.warpPresetIndex
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

    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(SettingsCategory.ALL) }

    val allSettingItems = listOf(
        // AUDIO & PLAYBACK
        SettingItemDef(
            id = "EQUALIZER",
            category = SettingsCategory.AUDIO,
            symbol = "~",
            label = "EQUALIZER",
            sublabel = "Graphic EQ, presets, preamp & output device routing",
            keywords = listOf("eq", "equalizer", "bass", "treble", "mid", "frequency", "preamp", "preset", "flat", "loudness", "speaker", "headphones", "bluetooth", "usb"),
            badge = if (prefs.equalizer.enabled) "ON / ${(prefs.equalizer.presets.firstOrNull { it.id == prefs.equalizer.activePresetId }?.name ?: "CUSTOM").uppercase()}" else "OFF",
            content = {
                EqualizerSection(
                    equalizer = prefs.equalizer,
                    onEnabledChange = viewModel::setEqualizerEnabled,
                    onBandChange = viewModel::setEqualizerBand,
                    onReset = viewModel::resetEqualizerBands,
                    onSelectPreset = viewModel::selectEqualizerPreset,
                    onSavePreset = viewModel::saveEqualizerPreset,
                    onRenamePreset = viewModel::renameEqualizerPreset,
                    onDeletePreset = viewModel::deleteEqualizerPreset,
                    onPreampModeChange = viewModel::setPreampMode,
                    onManualPreampChange = viewModel::setManualPreampDb,
                    onOutputDeviceAssignmentChange = viewModel::setOutputDevicePresetAssignment
                )
            }
        ),
        SettingItemDef(
            id = "CROSSFADE",
            category = SettingsCategory.AUDIO,
            symbol = "<>",
            label = "CROSSFADE",
            sublabel = "Gapless playback & track crossfade duration",
            keywords = listOf("crossfade", "fade", "gapless", "overlap", "seconds", "transition", "audio"),
            badge = if (prefs.crossfade.enabled) "ON / ${prefs.crossfade.durationMs / 1000}s" else "OFF",
            content = {
                CrossfadeSection(
                    enabled = prefs.crossfade.enabled,
                    durationMs = prefs.crossfade.durationMs,
                    onEnabledChange = viewModel::setCrossfadeEnabled,
                    onDurationChange = viewModel::setCrossfadeDurationMs
                )
            }
        ),
        SettingItemDef(
            id = "PLAYBACK BEHAVIOR",
            category = SettingsCategory.AUDIO,
            symbol = ">",
            label = "PLAYBACK BEHAVIOR",
            sublabel = "Tap actions & waveform seek bar",
            keywords = listOf("playback", "waveform", "seek", "bar", "library", "playlist", "now playing", "expand", "auto"),
            badge = if (prefs.waveformSeekBarEnabled) "WAVEFORM" else "BLOCKS",
            content = {
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
                    ToggleRow(
                        label = "Waveform Seek Bar",
                        sublabel = "Render high-resolution audio waveform instead of classic segmented block bar",
                        checked = prefs.waveformSeekBarEnabled,
                        onCheckedChange = viewModel::setWaveformSeekBarEnabled
                    )
                }
            }
        ),
        SettingItemDef(
            id = "STREAMING QUALITY",
            category = SettingsCategory.AUDIO,
            symbol = "^",
            label = "STREAMING QUALITY",
            sublabel = "Bitrate ceilings on Wi-Fi and mobile data",
            keywords = listOf("streaming", "quality", "bitrate", "wifi", "cellular", "data", "kbps", "smart bitrate"),
            badge = prefs.maxBitRate?.let { "${it}k" } ?: "MAX",
            content = {
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
        ),
        SettingItemDef(
            id = "DOWNLOAD SETTINGS",
            category = SettingsCategory.AUDIO,
            symbol = "v",
            label = "DOWNLOAD SETTINGS",
            sublabel = "Offline caching quality & download directory",
            keywords = listOf("download", "offline", "storage", "folder", "directory", "cache", "bitrate", "quality"),
            badge = prefs.downloadMaxBitRate?.let { "${it}k" } ?: "MAX",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "DOWNLOAD QUALITY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    QualitySection(
                        selectedBitRate = prefs.downloadMaxBitRate,
                        onSelect = viewModel::setDownloadMaxBitRate
                    )
                    Spacer(modifier = Modifier.height(4.dp))
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
        ),

        // DISPLAY & CRT
        SettingItemDef(
            id = "THEME",
            category = SettingsCategory.DISPLAY,
            symbol = "*",
            label = "THEME",
            sublabel = "Terminal retro color schemes & presets",
            keywords = listOf("theme", "color", "palette", "retro", "amber", "green", "matrix", "cyberpunk", "dark"),
            badge = themePresetById(prefs.themeId).label.uppercase(),
            content = {
                ThemeGrid(selected = prefs.themeId, onSelect = viewModel::setTheme)
            }
        ),
        SettingItemDef(
            id = "CUSTOM COLORS",
            category = SettingsCategory.DISPLAY,
            symbol = "#",
            label = "CUSTOM COLORS",
            sublabel = "Per-element color overrides & custom palette",
            keywords = listOf("custom", "colors", "hex", "rgb", "overrides", "palette", "accent", "surface", "background", "picker"),
            badge = if (prefs.themeColorOverrides.isNotEmpty()) "${prefs.themeColorOverrides.size} OVERRIDES" else null,
            content = {
                ThemeColorEditor(
                    themeId = prefs.themeId,
                    overrides = prefs.themeColorOverrides,
                    onSet = viewModel::setThemeColor,
                    onClear = viewModel::clearThemeColor,
                    onClearRoles = { viewModel.clearThemeColors(it) },
                    onResetAll = { viewModel.clearThemeColors() }
                )
            }
        ),
        SettingItemDef(
            id = "CRT & AESTHETICS",
            category = SettingsCategory.DISPLAY,
            symbol = "+",
            label = "CRT & AESTHETICS",
            sublabel = "Scanlines, phosphor glow, cursor style & art visualizers",
            keywords = listOf("crt", "scanline", "phosphor", "afterglow", "cursor", "blink", "accent", "hud", "flash", "visualizer", "cassette", "vinyl", "reel", "vhs", "warp"),
            badge = "${(prefs.scanlineIntensity * 100).roundToInt()}% SCAN",
            content = {
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
                    playbackArtStyle = prefs.playbackArtStyle,
                    onPlaybackArtStyleChange = viewModel::setPlaybackArtStyle,
                    warpPresetLabel = WarpPresets[warpPresetIndex(prefs.warpPresetId)].label,
                    warpAutoCycle = prefs.warpAutoCycle,
                    onWarpAutoCycleChange = viewModel::setWarpAutoCycle,
                    warpCycleSeconds = prefs.warpCycleSeconds,
                    onWarpCycleSecondsChange = viewModel::setWarpCycleSeconds,
                    warpPresetLocked = prefs.warpPresetLocked,
                    onWarpPresetLockedChange = viewModel::setWarpPresetLocked,
                    motionPreference = effectiveMotionPreference
                )
            }
        ),
        SettingItemDef(
            id = "ANIMATIONS",
            category = SettingsCategory.DISPLAY,
            symbol = ">>",
            label = "ANIMATIONS",
            sublabel = "Motion transitions & animation speeds",
            keywords = listOf("motion", "animation", "transition", "speed", "full", "medium", "off", "system", "fluid"),
            badge = prefs.motionPreference.name,
            content = {
                MotionPreferenceSection(
                    selected = prefs.motionPreference,
                    onSelect = viewModel::setMotionPreference
                )
            }
        ),
        SettingItemDef(
            id = "HUD TELEMETRY",
            category = SettingsCategory.DISPLAY,
            symbol = "::",
            label = "HUD TELEMETRY",
            sublabel = "Audio monitor metric density on Now Playing",
            keywords = listOf("hud", "telemetry", "monitor", "density", "expanded", "standard", "cpu", "buffer", "session", "ping"),
            badge = prefs.hudTelemetryMode.name,
            content = {
                HudTelemetrySection(
                    selected = prefs.hudTelemetryMode,
                    onSelect = viewModel::setHudTelemetryMode
                )
            }
        ),
        SettingItemDef(
            id = "HAPTICS",
            category = SettingsCategory.DISPLAY,
            symbol = "!",
            label = "HAPTICS",
            sublabel = "Mechanical Cherry MX switch & tactile profiles",
            keywords = listOf("haptics", "vibration", "click", "tactile", "cherry", "feedback", "switch"),
            badge = prefs.terminalHaptics.name,
            content = {
                TerminalHapticsSection(
                    selected = prefs.terminalHaptics,
                    onSelect = viewModel::setTerminalHaptics
                )
            }
        ),

        // SERVER & CLOUD
        SettingItemDef(
            id = "SERVER SETTINGS",
            category = SettingsCategory.SERVER,
            symbol = "@",
            label = "SERVER SETTINGS",
            sublabel = "Subsonic / Navidrome connection, upload & GapFinder",
            keywords = listOf("server", "navidrome", "subsonic", "url", "password", "username", "upload", "gapfinder", "sources", "cloud"),
            badge = if (prefs.serverUrl.isNotBlank()) "CONFIGURED" else "NOT SET",
            content = {
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
        ),

        // LIBRARY & STORAGE
        SettingItemDef(
            id = "LIBRARY SORT",
            category = SettingsCategory.LIBRARY,
            symbol = "^v",
            label = "LIBRARY SORT",
            sublabel = "Sort songs by title, artist, date added or album",
            keywords = listOf("sort", "order", "direction", "title", "artist", "album", "date", "library", "ascending", "descending"),
            badge = "${prefs.librarySortOrder.field.name} / ${if (prefs.librarySortOrder.direction == SortDirection.ASC) "ASC" else "DESC"}",
            content = {
                SortSection(
                    field = prefs.librarySortOrder.field,
                    direction = prefs.librarySortOrder.direction,
                    onFieldChange = viewModel::setSortField,
                    onDirectionChange = viewModel::setSortDirection
                )
            }
        ),
        SettingItemDef(
            id = "IMPORT",
            category = SettingsCategory.LIBRARY,
            symbol = "<-",
            label = "IMPORT",
            sublabel = "Add local files & load M3U/M3U8 playlists",
            keywords = listOf("import", "add", "files", "playlist", "m3u", "m3u8", "local", "audio", "scan"),
            badge = when (val s = importStatus) {
                is ImportStatus.FilesDone -> "${s.count} FILES"
                is ImportStatus.PlaylistDone -> "${s.matched}/${s.total}"
                else -> null
            },
            content = {
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
        ),

        // SYSTEM & ENGINE
        SettingItemDef(
            id = "PERFORMANCE",
            category = SettingsCategory.SYSTEM,
            symbol = "$",
            label = "PERFORMANCE",
            sublabel = "Balanced, high performance, or battery saver mode",
            keywords = listOf("performance", "power", "battery", "saver", "balanced", "cpu", "thermal", "efficiency"),
            badge = prefs.performanceMode.name,
            content = {
                PerformanceModeSection(
                    selected = prefs.performanceMode,
                    onSelect = viewModel::setPerformanceMode
                )
            }
        )
    )

    val visibleItems = if (searchQuery.isNotBlank()) {
        allSettingItems.filter { item ->
            item.label.contains(searchQuery, ignoreCase = true) ||
            item.sublabel.contains(searchQuery, ignoreCase = true) ||
            item.keywords.any { it.contains(searchQuery, ignoreCase = true) }
        }
    } else if (selectedCategory != SettingsCategory.ALL) {
        allSettingItems.filter { it.category == selectedCategory }
    } else {
        allSettingItems
    }

    val visibleSectionIds = visibleItems.map { it.id }.toSet()
    val allVisibleExpanded = visibleSectionIds.isNotEmpty() && visibleSectionIds.all { it in expandedSections }

    LazyColumn(
        modifier = Modifier.testTag(TestTags.SETTINGS_LIST).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TerminalHeader(
                    title = "SETTINGS",
                    cursorStyle = prefs.cursorStyle,
                    cursorBlink = prefs.cursorBlinkCadence,
                    motionPreference = effectiveMotionPreference
                )

                if (visibleSectionIds.isNotEmpty()) {
                    TerminalActionText(
                        text = if (allVisibleExpanded) "[ - COLLAPSE ALL ]" else "[ + EXPAND ALL ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            haptic(false)
                            expandedSections = if (allVisibleExpanded) {
                                expandedSections - visibleSectionIds
                            } else {
                                expandedSections + visibleSectionIds
                            }
                        }
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            "FILTER SETTINGS (e.g. eq, theme, bitrate)...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    leadingIcon = {
                        Text(
                            "//",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            TerminalActionText(
                                text = "[ X ]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { searchQuery = "" }
                                    .padding(end = 12.dp)
                            )
                        }
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.2f)
                    )
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsCategory.entries.forEach { category ->
                        val isSelected = selectedCategory == category
                        val count = if (category == SettingsCategory.ALL) allSettingItems.size else allSettingItems.count { it.category == category }
                        TerminalCategoryChip(
                            symbol = category.symbol,
                            label = category.title,
                            count = count,
                            selected = isSelected,
                            onClick = {
                                haptic(false)
                                selectedCategory = category
                            }
                        )
                    }
                }
            }
        }

        if (searchQuery.isNotBlank()) {
            if (visibleItems.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "// NO SETTINGS MATCH \"$searchQuery\"",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TerminalActionText(
                            text = "[ CLEAR FILTER ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { searchQuery = "" }
                        )
                    }
                }
            } else {
                item {
                    Text(
                        text = "// FOUND ${visibleItems.size} MATCHING SECTION${if (visibleItems.size == 1) "" else "S"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                items(visibleItems, key = { it.id }) { itemDef ->
                    SettingsDropdown(
                        label = itemDef.label,
                        expanded = expandedSections.contains(itemDef.id) || visibleItems.size == 1,
                        onToggle = { toggleSection(itemDef.id) },
                        motionPreference = effectiveMotionPreference,
                        symbol = itemDef.symbol,
                        sublabel = itemDef.sublabel,
                        badge = itemDef.badge,
                        content = itemDef.content
                    )
                }
            }
        } else if (selectedCategory != SettingsCategory.ALL) {
            item {
                CategoryHeaderDivider(
                    category = selectedCategory,
                    count = visibleItems.size
                )
            }
            items(visibleItems, key = { it.id }) { itemDef ->
                SettingsDropdown(
                    label = itemDef.label,
                    expanded = expandedSections.contains(itemDef.id),
                    onToggle = { toggleSection(itemDef.id) },
                    motionPreference = effectiveMotionPreference,
                    symbol = itemDef.symbol,
                    sublabel = itemDef.sublabel,
                    badge = itemDef.badge,
                    content = itemDef.content
                )
            }
        } else {
            val categories = SettingsCategory.entries.filter { it != SettingsCategory.ALL }
            categories.forEach { category ->
                val categoryItems = allSettingItems.filter { it.category == category }
                if (categoryItems.isNotEmpty()) {
                    item(key = "header_${category.id}") {
                        CategoryHeaderDivider(
                            category = category,
                            count = categoryItems.size
                        )
                    }
                    items(categoryItems, key = { it.id }) { itemDef ->
                        SettingsDropdown(
                            label = itemDef.label,
                            expanded = expandedSections.contains(itemDef.id),
                            onToggle = { toggleSection(itemDef.id) },
                            motionPreference = effectiveMotionPreference,
                            symbol = itemDef.symbol,
                            sublabel = itemDef.sublabel,
                            badge = itemDef.badge,
                            content = itemDef.content
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
    equalizer: EqualizerSettings,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSavePreset: (String, List<Float>) -> Unit,
    onRenamePreset: (String, String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onPreampModeChange: (PreampMode) -> Unit,
    onManualPreampChange: (Float) -> Unit,
    onOutputDeviceAssignmentChange: (String, String?) -> Unit
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var newPresetName by remember { mutableStateOf("") }
    var presetToRename by remember { mutableStateOf<EqualizerPreset?>(null) }
    var renamePresetName by remember { mutableStateOf("") }
    var presetToDelete by remember { mutableStateOf<EqualizerPreset?>(null) }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("SAVE EQ PRESET", style = MaterialTheme.typography.titleSmall) },
            text = {
                Column {
                    Text(
                        "Enter a name for this custom preset:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newPresetName,
                        onValueChange = { newPresetName = it },
                        singleLine = true,
                        placeholder = { Text("e.g. Car Audio / Rock") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newPresetName.trim()
                        if (name.isNotBlank()) {
                            onSavePreset(name, equalizer.bandGainsDb.map { it.toFloat() })
                            newPresetName = ""
                            showSaveDialog = false
                        }
                    },
                    enabled = newPresetName.isNotBlank()
                ) { Text("SAVE") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("CANCEL") }
            }
        )
    }

    presetToRename?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToRename = null },
            title = { Text("RENAME PRESET", style = MaterialTheme.typography.titleSmall) },
            text = {
                OutlinedTextField(
                    value = renamePresetName,
                    onValueChange = { renamePresetName = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = renamePresetName.trim()
                        if (name.isNotBlank()) {
                            onRenamePreset(preset.id, name)
                            presetToRename = null
                        }
                    },
                    enabled = renamePresetName.isNotBlank()
                ) { Text("RENAME") }
            },
            dismissButton = {
                TextButton(onClick = { presetToRename = null }) { Text("CANCEL") }
            }
        )
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("DELETE PRESET", style = MaterialTheme.typography.titleSmall) },
            text = { Text("Delete preset '${preset.name}'?", style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePreset(preset.id)
                    presetToDelete = null
                }) { Text("DELETE") }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) { Text("CANCEL") }
            }
        )
    }

    val isEnabled = equalizer.enabled

    TerminalBorder(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "5-BAND EQ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (!isEnabled) {
                        Text(
                            text = "[OFF]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TerminalActionText(
                        text = "[RESET] ",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable {
                            if (!isEnabled) onEnabledChange(true)
                            onReset()
                        }
                    )
                    TerminalToggleButton(
                        checked = isEnabled,
                        onToggle = { onEnabledChange(!isEnabled) }
                    )
                }
            }

            // Presets Row
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PRESETS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TerminalActionText(
                        text = "[ + SAVE AS NEW ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            newPresetName = ""
                            showSaveDialog = true
                        }
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    equalizer.presets.forEach { preset ->
                        val isSelected = equalizer.activePresetId == preset.id
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.secondary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier.clickable {
                                if (!isEnabled) onEnabledChange(true)
                                onSelectPreset(preset.id)
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = if (isSelected) "> ${preset.name.uppercase()}" else preset.name.uppercase(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (!preset.isBuiltIn && preset.id != PRESET_ID_CUSTOM) {
                                    Text(
                                        text = " [R]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.clickable {
                                            presetToRename = preset
                                            renamePresetName = preset.name
                                        }.padding(start = 4.dp)
                                    )
                                    Text(
                                        text = " [X]",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.clickable {
                                            presetToDelete = preset
                                        }.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5 Band Sliders
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.graphicsLayer { alpha = if (isEnabled) 1.0f else 0.55f }
            ) {
                EQ_BAND_LABELS.forEachIndexed { index, label ->
                    val gainDb = equalizer.bandGainsDb.getOrElse(index) { 0 }
                    Column {
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
                            onValueChange = { fraction ->
                                if (!isEnabled) onEnabledChange(true)
                                onBandChange(index, ((fraction * 24f) - 12f).toInt())
                            },
                            originFraction = 0.5f,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // Preamp Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PREAMP",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TerminalActionText(
                            text = if (equalizer.preampMode == PreampMode.AUTO) "[ > AUTO < ]" else "[ AUTO ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (equalizer.preampMode == PreampMode.AUTO) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onPreampModeChange(PreampMode.AUTO) }
                        )
                        TerminalActionText(
                            text = if (equalizer.preampMode == PreampMode.MANUAL) "[ > MANUAL < ]" else "[ MANUAL ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (equalizer.preampMode == PreampMode.MANUAL) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { onPreampModeChange(PreampMode.MANUAL) }
                        )
                    }
                }

                if (equalizer.preampMode == PreampMode.AUTO) {
                    Text(
                        text = "Auto-compensation active (-0.5 dB below max boost). Safety limiter idle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val manualPreamp = equalizer.manualPreampDb
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Manual Pre-Gain",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "${if (manualPreamp > 0) "+" else ""}${String.format(java.util.Locale.US, "%.1f", manualPreamp)}dB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    TerminalSlider(
                        value = ((manualPreamp + 12f) / 18f).coerceIn(0f, 1f),
                        onValueChange = { fraction -> onManualPreampChange((fraction * 18f) - 12f) },
                        originFraction = 12f / 18f,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Per-Output Auto-Switching Section
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "AUTO-SWITCH PER OUTPUT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Assign presets to automatically activate when audio route changes:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val devices = listOf(
                    AudioOutputDeviceManager.DEVICE_ID_SPEAKER to "Built-in Speaker",
                    AudioOutputDeviceManager.DEVICE_ID_WIRED to "Wired Headphones",
                    AudioOutputDeviceManager.DEVICE_ID_USB to "USB Audio"
                )

                devices.forEach { (deviceKey, deviceName) ->
                    val assignedPresetId = equalizer.outputDeviceAssignments[deviceKey]
                    val assignedPresetName = equalizer.presets.firstOrNull { it.id == assignedPresetId }?.name ?: "Default / Unassigned"

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        TerminalActionText(
                            text = "[ $assignedPresetName ]",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (assignedPresetId != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable {
                                val allPresets = listOf(null) + equalizer.presets.map { it.id }
                                val currentIdx = allPresets.indexOf(assignedPresetId)
                                val nextIdx = (currentIdx + 1) % allPresets.size
                                onOutputDeviceAssignmentChange(deviceKey, allPresets[nextIdx])
                            }
                        )
                    }
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
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onEnabledChange(!enabled) },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Fade between tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 12.dp)
                )
                TerminalToggleButton(
                    checked = enabled
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
    TerminalBorder(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
            ) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    sublabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TerminalToggleButton(
                checked = checked
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
                        .testTag(TestTags.motionOption(preference.name))
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

private enum class SettingsCategory(
    val id: String,
    val title: String,
    val symbol: String,
    val description: String
) {
    ALL("ALL", "ALL", "::", "All configuration sections"),
    AUDIO("AUDIO", "AUDIO & PLAYBACK", "~", "Audio engine, equalizer, crossfade & streaming"),
    DISPLAY("DISPLAY", "DISPLAY & CRT", "*", "Themes, phosphor glow, scanlines & visualizers"),
    SERVER("SERVER", "SERVER & CLOUD", "@", "Subsonic server, upload & cloud synchronization"),
    LIBRARY("LIBRARY", "LIBRARY & STORAGE", "=", "Sorting, local file importing & playlist management"),
    SYSTEM("SYSTEM", "SYSTEM & ENGINE", "#", "Performance modes & runtime diagnostics")
}

private data class SettingItemDef(
    val id: String,
    val category: SettingsCategory,
    val symbol: String,
    val label: String,
    val sublabel: String,
    val keywords: List<String>,
    val badge: String? = null,
    val content: @Composable () -> Unit
)

@Composable
private fun CategoryHeaderDivider(
    category: SettingsCategory,
    count: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp, bottom = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "// ${category.symbol} ${category.title}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = "[ $count ]",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = category.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        Box(
            modifier = Modifier
                .padding(top = 6.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
        )
    }
}

@Composable
private fun TerminalCategoryChip(
    symbol: String,
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.4f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "($count)",
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun SettingsDropdown(
    label: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    motionPreference: MotionPreference,
    symbol: String = ">",
    sublabel: String = "",
    badge: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
            )
            .background(
                if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.04f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
            )
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .testTag(TestTags.settingsSection(label))
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .border(
                                1.dp,
                                if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                            .background(
                                if (expanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = symbol,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Column(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 8.dp)
                            .weight(1f, fill = false)
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (expanded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Bold
                        )
                        if (sublabel.isNotBlank()) {
                            Text(
                                text = sublabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (badge != null) {
                        Box(
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = badge,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1
                            )
                        }
                    }

                    TerminalActionText(
                        text = if (expanded) "[ - ]" else "[ + ]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (motionPreference == MotionPreference.OFF) {
                if (expanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        content()
                    }
                }
            } else {
                AnimatedVisibility(visible = expanded) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        content()
                    }
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
    playbackArtStyle: PlaybackArtStyle,
    onPlaybackArtStyleChange: (PlaybackArtStyle) -> Unit,
    warpPresetLabel: String,
    warpAutoCycle: Boolean,
    onWarpAutoCycleChange: (Boolean) -> Unit,
    warpCycleSeconds: Int,
    onWarpCycleSecondsChange: (Int) -> Unit,
    warpPresetLocked: Boolean,
    onWarpPresetLockedChange: (Boolean) -> Unit,
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
            sublabel = "Extract dominant color from album art (active in Full & Balanced performance modes)",
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
                    "NOW PLAYING ART",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                PlaybackArtStyle.entries.forEach { style ->
                    val isSelected = style == playbackArtStyle
                    val (label, desc) = when (style) {
                        PlaybackArtStyle.STANDARD -> "ALBUM ART" to "Cover artwork for the current track"
                        PlaybackArtStyle.CASSETTE -> "CASSETTE" to "Animated tape deck with track label"
                        PlaybackArtStyle.REEL_TO_REEL -> "REEL-TO-REEL" to "Spinning open-reel tape machine"
                        PlaybackArtStyle.VINYL -> "VINYL" to "Spinning record"
                        PlaybackArtStyle.VHS -> "VHS" to "Video cassette with track label"
                        PlaybackArtStyle.VISUALIZER -> "WARP VISUALIZER" to
                            "Audio-reactive feedback visuals (Full motion only; pauses in power saving)"
                    }
                    Column(
                        modifier = Modifier
                            .testTag(TestTags.artStyleOption(style.name))
                            .fillMaxWidth()
                            .clickable { onPlaybackArtStyleChange(style) }
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

        if (playbackArtStyle == PlaybackArtStyle.VISUALIZER) {
            ToggleRow(
                label = "VISUALIZER AUTO-CYCLE",
                sublabel = "Blend to the next preset on a strong beat after the interval. Tap the visualizer for the next preset.",
                checked = warpAutoCycle,
                onCheckedChange = onWarpAutoCycleChange
            )
            if (warpAutoCycle) {
                TerminalBorder(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("INTERVAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        WarpCycleIntervals.forEach { seconds ->
                            val isSelected = seconds == warpCycleSeconds
                            Text(
                                text = if (isSelected) "[ ${seconds}s ]" else "  ${seconds}s  ",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier
                                    .clickable { onWarpCycleSecondsChange(seconds) }
                                    .padding(vertical = 6.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
            ToggleRow(
                label = "LOCK PRESET: $warpPresetLabel",
                sublabel = "Keep this preset: no auto-cycle, and taps on the visualizer do not change it",
                checked = warpPresetLocked,
                onCheckedChange = onWarpPresetLockedChange
            )
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
