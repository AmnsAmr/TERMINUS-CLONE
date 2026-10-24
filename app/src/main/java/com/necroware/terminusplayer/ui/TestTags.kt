package com.necroware.terminusplayer.ui

/**
 * Compose test tags, exposed as Android resource IDs (testTagsAsResourceId) so UiAutomator
 * Macrobenchmarks in :benchmark can find them with By.res(). The :benchmark module repeats these
 * strings; keep both in sync.
 */
object TestTags {
    const val MINI_PLAYER = "mini_player"
    const val MINI_PREVIOUS = "mini_previous"
    const val MINI_PLAY_PAUSE = "mini_play_pause"
    const val MINI_NEXT = "mini_next"

    /** Full-player artwork/visualizer; also the vertical drag handle that collapses the panel. */
    const val NOW_PLAYING_ART = "now_playing_art"
    const val TRANSPORT_PREVIOUS = "transport_previous"
    const val TRANSPORT_PLAY_PAUSE = "transport_play_pause"
    const val TRANSPORT_NEXT = "transport_next"
    const val TRANSPORT_SHUFFLE = "transport_shuffle"
    const val TRANSPORT_REPEAT = "transport_repeat"

    const val SONG_ROW = "song_row"
    const val ALBUM_ROW = "album_row"
    const val ALBUM_DETAIL_LIST = "album_detail_list"
    const val SETTINGS_LIST = "settings_list"

    fun motionOption(name: String) = "motion_option_$name"
    fun artStyleOption(name: String) = "art_style_option_$name"

    /** Collapsible Settings section header; non-alphanumerics become '_' ("CRT & AESTHETICS" -> CRT___AESTHETICS). */
    fun settingsSection(label: String) = "settings_section_" + label.replace(NonAlphanumeric, "_")

    private val NonAlphanumeric = Regex("[^A-Za-z0-9]")
}
