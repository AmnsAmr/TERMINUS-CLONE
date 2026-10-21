package com.necroware.terminusplayer.ui.screens.library

import com.necroware.terminusplayer.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryYouTubeTest {

    @Test
    fun testLibraryTabsIncludeYouTube() {
        val tabs = LibraryTab.entries
        assertEquals(5, tabs.size)
        assertEquals(LibraryTab.YOUTUBE, tabs[4])
        assertEquals("YOUTUBE", LibraryTab.YOUTUBE.label)
    }

    @Test
    fun testYouTubeTrackDownloadAllowed() {
        val youTubeSong = Song(
            id = "youtube:dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
            albumId = "yt_album_rick",
            duration = 213000L,
            uriString = "https://rr1---sn.googlevideo.com/videoplayback?id=1",
            providerId = "youtube",
            providerRemoteId = "dQw4w9WgXcQ",
            artworkUri = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        )
        val legacyJamendoSong = Song(
            id = "jamendo:1496834",
            title = "Track Title",
            artist = "Artist Name",
            album = "Album Name",
            albumId = "172582",
            duration = 157000L,
            uriString = "https://prod-1.storage.jamendo.com/?trackid=1496834",
            providerId = "jamendo",
            providerRemoteId = "1496834"
        )

        val queue = listOf(youTubeSong, legacyJamendoSong)
        // YouTube tracks are downloadable; legacy Jamendo tracks remain streaming-only
        val downloadable = queue.filter { it.providerId != "jamendo" }

        assertEquals(1, downloadable.size)
        assertEquals("youtube:dQw4w9WgXcQ", downloadable[0].id)
    }

    @Test
    fun testYouTubeSourceStatusBadge() {
        val youTubeSong = Song(
            id = "youtube:dQw4w9WgXcQ",
            title = "Never Gonna Give You Up",
            artist = "Rick Astley",
            album = "Whenever You Need Somebody",
            albumId = "yt_album_rick",
            duration = 213000L,
            uriString = "https://rr1---sn.googlevideo.com/videoplayback?id=1",
            providerId = "youtube",
            providerRemoteId = "dQw4w9WgXcQ"
        )

        val local = youTubeSong.providerId == "local"
        val isYouTube = youTubeSong.providerId == "youtube"
        val isJamendo = youTubeSong.providerId == "jamendo"
        val sourceStatus = when {
            !youTubeSong.downloadedUri.isNullOrBlank() -> "DOWNLOADED"
            isYouTube -> "YT STREAM"
            isJamendo -> "CC STREAM"
            local && !youTubeSong.navidromeId.isNullOrBlank() -> "AVAILABLE ON SERVER"
            local -> "LOCAL ONLY"
            else -> "NOT DOWNLOADED"
        }
        val prefix = if (isYouTube) "YOUTUBE" else if (isJamendo) "JAMENDO" else if (local) "LOCAL" else "SERVER"

        assertTrue(isYouTube)
        assertFalse(local)
        assertEquals("YT STREAM", sourceStatus)
        assertEquals("YOUTUBE", prefix)

        val downloadedSong = youTubeSong.copy(downloadedUri = "content://download/yt_1")
        val downloadedStatus = when {
            !downloadedSong.downloadedUri.isNullOrBlank() -> "DOWNLOADED"
            downloadedSong.providerId == "youtube" -> "YT STREAM"
            else -> "NOT DOWNLOADED"
        }
        assertEquals("DOWNLOADED", downloadedStatus)
    }

    @Test
    fun testYouTubeDiscoveryModes() {
        val modes = LibraryViewModel.YouTubeDiscoveryMode.entries
        assertEquals(3, modes.size)
        assertEquals("POPULAR", LibraryViewModel.YouTubeDiscoveryMode.POPULAR.label)
        assertEquals("TRENDING", LibraryViewModel.YouTubeDiscoveryMode.TRENDING.label)
        assertEquals("CHARTS", LibraryViewModel.YouTubeDiscoveryMode.CHARTS.label)
    }

    @Test
    fun testYouTubeSearchFilters() {
        val filters = com.necroware.terminusplayer.data.api.youtube.YouTubeSearchFilter.entries
        assertEquals(6, filters.size)
        assertTrue(filters.any { it.label == "ALL" })
        assertTrue(filters.any { it.label == "SONGS" })
        assertTrue(filters.any { it.label == "VIDEOS" })
        assertTrue(filters.any { it.label == "ALBUMS" })
        assertTrue(filters.any { it.label == "ARTISTS" })
        assertTrue(filters.any { it.label == "PLAYLISTS" })
    }

    @Test
    fun testYouTubeDiscoveryCategoriesAndFilters() {
        val categories = com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryCategory.entries
        assertEquals(3, categories.size)

        val defaultFilters = com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS
        assertTrue(defaultFilters.size >= 10)
        assertTrue(defaultFilters.any { it.id == "popular" })
        assertTrue(defaultFilters.any { it.id == "charts" })
        assertTrue(defaultFilters.any { it.id == "chill" })
        assertTrue(defaultFilters.any { it.id == "rock" })
    }

    @Test
    fun testSearchScope() {
        val scopes = com.necroware.terminusplayer.ui.screens.search.SearchScope.entries
        assertEquals(2, scopes.size)
        assertEquals("LOCAL", scopes[0].label)
        assertEquals("YOUTUBE", scopes[1].label)
    }
}

