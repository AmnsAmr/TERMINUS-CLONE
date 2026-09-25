package com.necroware.terminusplayer.ui.screens.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistRouteParsingTest {
    @Test
    fun invalidOrStalePlaylistKindsReturnNoKind() {
        assertNull(parsePlaylistKind("RENAMED_KIND"))
        assertNull(parsePlaylistKind(""))
    }

    @Test
    fun knownPlaylistKindParses() {
        assertEquals(PlaylistKind.LIKED, parsePlaylistKind("LIKED"))
    }
}
