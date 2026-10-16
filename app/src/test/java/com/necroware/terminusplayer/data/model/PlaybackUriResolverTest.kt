package com.necroware.terminusplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackUriResolverTest {
    @Test
    fun validDownloadedFileWinsOverServerStream() {
        assertEquals(
            "content://download/1",
            resolveTrackPlaybackUri("content://download/1", true, "navidrome", "terminus://1", false, "https://server/stream/1")
        )
    }

    @Test
    fun staleDownloadFallsBackToServerStream() {
        assertEquals(
            "https://server/stream/1",
            resolveTrackPlaybackUri("content://missing/1", false, "navidrome", "terminus://1", false, "https://server/stream/1")
        )
    }

    @Test
    fun matchedLocalCopyFallsBackToServerWhenItsMediaStoreUriIsMissing() {
        assertEquals(
            "https://server/stream/1",
            resolveTrackPlaybackUri(null, false, "local", "content://missing/1", false, "https://server/stream/1")
        )
    }

    @Test
    fun localOnlyTrackUsesOnlyReadableLocalUri() {
        assertEquals(
            "content://music/1",
            resolveTrackPlaybackUri(null, false, "local", "content://music/1", true, null)
        )
        assertNull(resolveTrackPlaybackUri(null, false, "local", "content://missing/1", false, null))
    }
}
