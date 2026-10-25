package com.necroware.terminusplayer.data.prefs

import androidx.compose.runtime.Immutable
import com.squareup.moshi.JsonClass

enum class ThemePresetId {
    TERMINAL,
    VECTOR,
    REBECCA,
    DUNE,
    HEX,
    LUCY,
    MAINE,
    FLATLINE,
    WIZ,
    AMBER_CRT,
    CYBERPUNK,
    DRACULA,
    OCEAN_BLUE,
    MONOCHROME,
    SOLARIZED_DARK,
    TERMINAL_GREEN
}

/** Typed palette roles persisted as sparse hexadecimal overrides. */
enum class ThemeColorRole {
    BACKGROUND, SURFACE, SURFACE_ELEVATED, ACCENT, SECONDARY_ACCENT,
    TEXT_PRIMARY, TEXT_SECONDARY, TEXT_MUTED, BORDER,
    HOME_BACKGROUND, PLAYER_BACKGROUND, NAVIGATION_BACKGROUND,
    PLAYLIST_SURFACE, VISUALIZER_PRIMARY, VISUALIZER_SECONDARY
}

enum class SortField { TITLE, ARTIST, ALBUM, DATE_ADDED, DURATION }
enum class SortDirection { ASC, DESC }

enum class PlaybackArtStyle {
    STANDARD,
    CASSETTE,
    REEL_TO_REEL,
    VINYL,
    VHS,
    /** Audio-reactive MilkDrop-style warp visualizer in place of artwork. */
    VISUALIZER
}

enum class MotionPreference { FULL, MEDIUM, OFF, SYSTEM }

enum class PerformanceMode { AUTO, FULL, BALANCED, BATTERY_SAVER }

/** Combines the user's animation choice with the app's current power budget. */
fun effectiveMotionPreference(
    motionPreference: MotionPreference,
    performanceMode: PerformanceMode,
    systemPowerSaverEnabled: Boolean
): MotionPreference {
    val activeMode = when (performanceMode) {
        PerformanceMode.AUTO -> if (systemPowerSaverEnabled) PerformanceMode.BATTERY_SAVER else PerformanceMode.FULL
        else -> performanceMode
    }

    return when (activeMode) {
        PerformanceMode.AUTO, PerformanceMode.FULL -> motionPreference
        PerformanceMode.BALANCED -> when (motionPreference) {
            MotionPreference.FULL -> MotionPreference.MEDIUM
            else -> motionPreference
        }
        PerformanceMode.BATTERY_SAVER -> MotionPreference.OFF
    }
}

fun shouldDeferOptionalWork(mode: PerformanceMode, systemPowerSaverEnabled: Boolean): Boolean =
    when (mode) {
        PerformanceMode.AUTO -> systemPowerSaverEnabled
        PerformanceMode.BATTERY_SAVER -> true
        PerformanceMode.FULL, PerformanceMode.BALANCED -> false
    }

internal fun parseMotionPreference(value: String?): MotionPreference =
    when (value) {
        // REDUCED was the previous name for the medium motion setting.
        "REDUCED" -> MotionPreference.MEDIUM
        else -> value?.let { candidate -> MotionPreference.entries.firstOrNull { it.name == candidate } }
            ?: MotionPreference.FULL
    }

@Immutable
data class LibrarySortOrder(
    val field: SortField = SortField.TITLE,
    val direction: SortDirection = SortDirection.ASC
)

enum class PreampMode { AUTO, MANUAL }

const val PRESET_ID_FLAT = "built_in_flat"
const val PRESET_ID_BASS = "built_in_bass"
const val PRESET_ID_VOCAL = "built_in_vocal"
const val PRESET_ID_TREBLE = "built_in_treble"
const val PRESET_ID_LOUDNESS = "built_in_loudness"
const val PRESET_ID_CUSTOM = "custom"

@Immutable
@JsonClass(generateAdapter = true)
data class EqualizerPreset(
    val id: String,
    val name: String,
    val gainsDb: List<Float>,
    val isBuiltIn: Boolean = false
)

val BUILT_IN_PRESETS = listOf(
    EqualizerPreset(PRESET_ID_FLAT, "Flat", listOf(0f, 0f, 0f, 0f, 0f), isBuiltIn = true),
    EqualizerPreset(PRESET_ID_BASS, "Bass", listOf(6f, 4f, 0f, 0f, -1f), isBuiltIn = true),
    EqualizerPreset(PRESET_ID_VOCAL, "Vocal", listOf(-2f, 0f, 3f, 2f, -1f), isBuiltIn = true),
    EqualizerPreset(PRESET_ID_TREBLE, "Treble", listOf(-1f, 0f, 1f, 4f, 6f), isBuiltIn = true),
    EqualizerPreset(PRESET_ID_LOUDNESS, "Loudness", listOf(5f, 2f, -2f, 2f, 5f), isBuiltIn = true)
)

/** 5-band graphic EQ, gains in dB clamped to [-12, 12]. Bands correspond to
 *  60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz bell peaking filters in
 *  TerminusEqualizerAudioProcessor. */
@Immutable
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Int> = List(5) { 0 },
    val presets: List<EqualizerPreset> = BUILT_IN_PRESETS,
    val activePresetId: String = PRESET_ID_CUSTOM,
    val preampMode: PreampMode = PreampMode.AUTO,
    val manualPreampDb: Float = 0f,
    val outputDeviceAssignments: Map<String, String> = emptyMap()
)

@Immutable
data class CrossfadeSettings(
    val enabled: Boolean = false,
    val durationMs: Int = 4000
)

enum class TerminalCursorStyle(val glyph: String) {
    UNDERSCORE("_"),
    BLOCK("█"),
    VERTICAL("|")
}

enum class TerminalCursorBlink(val intervalMs: Long?) {
    FAST(500L),
    SLOW(1000L),
    SOLID(null)
}

enum class TerminalHaptics { CLICKY, TACTILE, OFF }

enum class HudTelemetryMode { STANDARD, EXPANDED }

enum class VisualizerStyle(val label: String) {
    BARS("SPECTRUM BARS"),
    OSCILLOSCOPE("OSCILLOSCOPE BEAM"),
    VU_METER("DOT-MATRIX VU")
}

@Immutable
data class UserPreferences(
    val themeId: ThemePresetId = ThemePresetId.TERMINAL,
    val themeColorOverrides: Map<ThemeColorRole, String> = emptyMap(),
    val librarySortOrder: LibrarySortOrder = LibrarySortOrder(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val crossfade: CrossfadeSettings = CrossfadeSettings(),
    val playbackArtStyle: PlaybackArtStyle = PlaybackArtStyle.STANDARD,
    val motionPreference: MotionPreference = MotionPreference.FULL,
    val performanceMode: PerformanceMode = PerformanceMode.FULL,
    val scanlineIntensity: Float = 0.0f,
    val phosphorAfterglow: Boolean = false,
    val cursorStyle: TerminalCursorStyle = TerminalCursorStyle.UNDERSCORE,
    val cursorBlinkCadence: TerminalCursorBlink = TerminalCursorBlink.SLOW,
    val adaptiveArtAccent: Boolean = false,
    val terminalHaptics: TerminalHaptics = TerminalHaptics.TACTILE,
    val smartBitrateEnabled: Boolean = false,
    val cellularMaxBitRate: Int? = 128,
    val openPlayerOnLibraryTrackSelect: Boolean = false,
    val openPlayerOnPlaylistTrackSelect: Boolean = false,
    val reactiveHudFlash: Boolean = true,
    /** Warp visualizer: preset last shown (WarpPreset.id). */
    val warpPresetId: String = "NEBULA",
    /** Warp visualizer: switch presets on a timer / strong beat. */
    val warpAutoCycle: Boolean = true,
    /** Warp visualizer auto-cycle interval: 15, 30 or 60 s. */
    val warpCycleSeconds: Int = 30,
    /** Warp visualizer: keep the current preset; no auto-cycle and taps do not change it. */
    val warpPresetLocked: Boolean = false,
    val visualizerStyle: VisualizerStyle = VisualizerStyle.BARS,
    val hudTelemetryMode: HudTelemetryMode = HudTelemetryMode.STANDARD,
    val lastPlayedSongId: String? = null,
    val lastPlayedPositionMs: Long = 0L,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val excludedFolders: Set<String> = emptySet(),
    val hasSetupDefaultExcludes: Boolean = false,
    val maxBitRate: Int? = null,
    val downloadMaxBitRate: Int? = null,
    val downloadFolderUri: String? = null
)
