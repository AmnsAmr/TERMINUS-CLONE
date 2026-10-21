package com.necroware.terminusplayer.ui.screens.nowplaying

import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier

/**
 * Modifiers the persistent player panel attaches to Now Playing so it can morph
 * between the mini bar and the full screen. Every field defaults to a no-op, so the
 * screen renders normally when no panel drives it.
 */
@Immutable
data class NowPlayingTransition(
    /** Collapse gesture handle, attached to the artwork as the old drag was. */
    val drag: Modifier = Modifier,
    /** Artwork bounds tracking and shared-bounds transform. */
    val art: Modifier = Modifier,
    /** Title bounds tracking and shared-bounds transform. */
    val title: Modifier = Modifier,
    /** Everything else; fades in as the panel opens. */
    val content: Modifier = Modifier
) {
    companion object {
        val None = NowPlayingTransition()
    }
}
