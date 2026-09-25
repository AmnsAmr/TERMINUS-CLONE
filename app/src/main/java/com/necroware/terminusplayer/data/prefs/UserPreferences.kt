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
    WIZ
}

enum class SortField { TITLE, ARTIST, ALBUM, DATE_ADDED, DURATION }
enum class SortDirection { ASC, DESC }

enum class PlaybackArtStyle {
    STANDARD,
    CASSETTE,
    REEL_TO_REEL,
    VINYL,
    VHS
}

enum class MotionPreference { FULL, REDUCED, OFF }

internal fun parseMotionPreference(value: String?): MotionPreference =
    value?.let { candidate -> MotionPreference.entries.firstOrNull { it.name == candidate } }
        ?: MotionPreference.FULL

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

data class UserPreferences(
    val themeId: ThemePresetId = ThemePresetId.TERMINAL,
    val librarySortOrder: LibrarySortOrder = LibrarySortOrder(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val crossfade: CrossfadeSettings = CrossfadeSettings(),
    val preferHardwareDecoder: Boolean = true,
    val playbackArtStyle: PlaybackArtStyle = PlaybackArtStyle.STANDARD,
    val motionPreference: MotionPreference = MotionPreference.FULL,
    val lastPlayedSongId: String? = null,
    val lastPlayedPositionMs: Long = 0L,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val excludedFolders: Set<String> = emptySet(),
    val hasSetupDefaultExcludes: Boolean = false,
    val maxBitRate: Int? = null
)
