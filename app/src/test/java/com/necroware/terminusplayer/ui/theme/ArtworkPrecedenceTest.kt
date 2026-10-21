package com.necroware.terminusplayer.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkPrecedenceTest {

    private fun resolveTargetArtUri(
        pendingArtworkUri: String? = null,
        nowPlayingArtworkUri: String? = null,
        currentSongUri: String? = null,
        mediaId: String? = null
    ): String? {
        return pendingArtworkUri?.takeIf { it.isNotBlank() }
            ?: nowPlayingArtworkUri?.takeIf { it.isNotBlank() }
            ?: currentSongUri?.takeIf { it.isNotBlank() }
            ?: mediaId?.let { if (it.startsWith("terminus://")) it else "terminus://$it" }
    }

    private fun resolveArtModelType(uriString: String): String {
        return when {
            uriString.isBlank() || uriString.startsWith("https://www.youtube.com/watch") || uriString.startsWith("http://www.youtube.com/watch") -> "null"
            uriString.startsWith("http://") || uriString.startsWith("https://") -> "remote_url"
            uriString.startsWith("terminus://") -> "navidrome_sentinel"
            else -> "local_audio_uri"
        }
    }

    @Test
    fun jamendoArtworkTakesPrecedenceOverAudioStreamUri() {
        val streamUrl = "https://prod-1.storage.jamendo.com/?trackid=123&format=mp32"
        val artUrl = "https://usercontent.jamendo.com?type=album&id=456&width=300"

        val target = resolveTargetArtUri(
            nowPlayingArtworkUri = artUrl,
            currentSongUri = streamUrl,
            mediaId = "jamendo:123"
        )

        // Artwork URL must win over audio stream URL
        assertEquals(artUrl, target)
        assertEquals("remote_url", resolveArtModelType(target!!))
    }

    @Test
    fun localTrackFallsBackToAudioUriWhenArtworkUriNull() {
        val localAudioUri = "content://media/external/audio/media/100"

        val target = resolveTargetArtUri(
            nowPlayingArtworkUri = null,
            currentSongUri = localAudioUri,
            mediaId = "local:100"
        )

        assertEquals(localAudioUri, target)
        assertEquals("local_audio_uri", resolveArtModelType(target!!))
    }

    @Test
    fun navidromeTrackFallsBackToSentinelWhenBothUrisNull() {
        val target = resolveTargetArtUri(
            nowPlayingArtworkUri = null,
            currentSongUri = null,
            mediaId = "server_song_99"
        )

        assertEquals("terminus://server_song_99", target)
        assertEquals("navidrome_sentinel", resolveArtModelType(target!!))
    }

    @Test
    fun pendingSongArtworkWinsDuringTrackTransition() {
        val currentArt = "https://usercontent.jamendo.com/old_art.jpg"
        val pendingArt = "https://usercontent.jamendo.com/new_art.jpg"

        val target = resolveTargetArtUri(
            pendingArtworkUri = pendingArt,
            nowPlayingArtworkUri = currentArt,
            currentSongUri = "https://prod-1.storage.jamendo.com/old_stream.mp3",
            mediaId = "jamendo:1"
        )

        assertEquals(pendingArt, target)
    }

    @Test
    fun youTubeWatchUrlIsNotUsedAsArtModel() {
        val watchUrl = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        assertEquals("null", resolveArtModelType(watchUrl))

        val thumbUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"
        assertEquals("remote_url", resolveArtModelType(thumbUrl))
    }
}
