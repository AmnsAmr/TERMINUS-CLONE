package com.necroware.terminusplayer.ui.screens.playlists

import androidx.compose.ui.geometry.Rect

/**
 * Captures the screen bounds of a clicked playlist's cover art and title
 * in [PlaylistsScreen] so [PlaylistDetailScreen] can animate the cover growing
 * and the title moving smoothly into place, matching the playback transition.
 */
object PlaylistMotionState {
    var lastClickedPlaylistId: String? = null
    var originArtBounds: Rect? = null
    var originTitleBounds: Rect? = null

    fun recordOrigin(playlistId: String, artBounds: Rect?, titleBounds: Rect?) {
        lastClickedPlaylistId = playlistId
        originArtBounds = artBounds
        originTitleBounds = titleBounds
    }

    fun clear() {
        lastClickedPlaylistId = null
        originArtBounds = null
        originTitleBounds = null
    }
}
