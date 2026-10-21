package com.necroware.terminusplayer.data.prefs

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

data class LibrarySortOrder(
    val field: SortField = SortField.TITLE,
    val direction: SortDirection = SortDirection.ASC
)

/** 5-band graphic EQ, gains in dB clamped to [-12, 12]. Bands correspond to
 *  roughly 60Hz / 230Hz / 910Hz / 3.6kHz / 14kHz, matching a typical Android
 *  android.media.audiofx.Equalizer's 5-band layout. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    val bandGainsDb: List<Int> = List(5) { 0 }
)

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

data class UserPreferences(
    val themeId: ThemePresetId = ThemePresetId.TERMINAL,
    val themeColorOverrides: Map<ThemeColorRole, String> = emptyMap(),
    val librarySortOrder: LibrarySortOrder = LibrarySortOrder(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val crossfade: CrossfadeSettings = CrossfadeSettings(),
    val preferHardwareDecoder: Boolean = true,
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
