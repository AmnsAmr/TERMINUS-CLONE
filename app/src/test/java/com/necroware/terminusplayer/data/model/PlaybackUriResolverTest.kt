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

    @Test
    fun jamendoTrackUsesServerStreamWhenNoDownloadExists() {
        assertEquals(
            "https://prod-1.storage.jamendo.com/?trackid=123",
            resolveTrackPlaybackUri(null, false, "jamendo", "", false, "https://prod-1.storage.jamendo.com/?trackid=123")
        )
    }

    @Test
    fun validDownloadedFileWinsOverJamendoStream() {
        assertEquals(
            "content://download/jamendo_123",
            resolveTrackPlaybackUri("content://download/jamendo_123", true, "jamendo", "", false, "https://prod-1.storage.jamendo.com/?trackid=123")
        )
    }

    @Test
    fun youTubeTrackUsesServerStreamWhenNoDownloadExists() {
        assertEquals(
            "https://rr1---sn.googlevideo.com/videoplayback?id=123",
            resolveTrackPlaybackUri(null, false, "youtube", "", false, "https://rr1---sn.googlevideo.com/videoplayback?id=123")
        )
    }

    @Test
    fun validDownloadedFileWinsOverYouTubeStream() {
        assertEquals(
            "content://download/youtube_123",
            resolveTrackPlaybackUri("content://download/youtube_123", true, "youtube", "", false, "https://rr1---sn.googlevideo.com/videoplayback?id=123")
        )
    }
}
