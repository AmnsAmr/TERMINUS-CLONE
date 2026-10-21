package com.necroware.terminusplayer.data.api.youtube

import com.necroware.terminusplayer.data.prefs.ServerConnectionConfig
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeAudioFormatTest {

    private val innerTubeClient = mockk<YouTubeInnerTubeClient>()
    private val prefsRepo = mockk<UserPreferencesRepository>()

    private val sampleFormats = listOf(
        YouTubeAudioFormat(
            itag = 139,
            mimeType = "audio/mp4; codecs=\"mp4a.40.5\"",
            bitrate = 48000L,
            contentLength = 1_000_000L,
            durationMs = 200_000L,
            url = "https://rr1.googlevideo.com/videoplayback?itag=139"
        ),
        YouTubeAudioFormat(
            itag = 140,
            mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            bitrate = 128000L,
            contentLength = 3_000_000L,
            durationMs = 200_000L,
            url = "https://rr1.googlevideo.com/videoplayback?itag=140"
        ),
        YouTubeAudioFormat(
            itag = 251,
            mimeType = "audio/webm; codecs=\"opus\"",
            bitrate = 160000L,
            contentLength = 3_800_000L,
            durationMs = 200_000L,
            url = "https://rr1.googlevideo.com/videoplayback?itag=251"
        )
    )

    @Before
    fun setUp() {
        every { prefsRepo.serverConnectionConfig } returns MutableStateFlow(
            ServerConnectionConfig(maxBitRate = null, isLoaded = true)
        )
        coEvery { prefsRepo.awaitServerConnectionConfig() } returns ServerConnectionConfig(
            maxBitRate = null,
            isLoaded = true
        )
    }

    @Test
    fun picksHighestQualityFormatWhenNoCapSet() = runTest {
        coEvery { innerTubeClient.getAudioFormats("test_id") } returns sampleFormats
        val resolver = YouTubeStreamResolver(innerTubeClient, prefsRepo)

        val resolved = resolver.resolveStreamUrl("test_id")
        assertNotNull(resolved)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=251", resolved)
    }

    @Test
    fun picksFormatWithinBitrateCeilingWhenCapIs128() = runTest {
        every { prefsRepo.serverConnectionConfig } returns MutableStateFlow(
            ServerConnectionConfig(maxBitRate = 128, isLoaded = true)
        )
        coEvery { prefsRepo.awaitServerConnectionConfig() } returns ServerConnectionConfig(
            maxBitRate = 128,
            isLoaded = true
        )
        coEvery { innerTubeClient.getAudioFormats("test_id") } returns sampleFormats
        val resolver = YouTubeStreamResolver(innerTubeClient, prefsRepo)

        val resolved = resolver.resolveStreamUrl("test_id")
        assertNotNull(resolved)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=140", resolved)
    }

    @Test
    fun picksDataSaverFormatWhenCapIs64() = runTest {
        every { prefsRepo.serverConnectionConfig } returns MutableStateFlow(
            ServerConnectionConfig(maxBitRate = 64, isLoaded = true)
        )
        coEvery { prefsRepo.awaitServerConnectionConfig() } returns ServerConnectionConfig(
            maxBitRate = 64,
            isLoaded = true
        )
        coEvery { innerTubeClient.getAudioFormats("test_id") } returns sampleFormats
        val resolver = YouTubeStreamResolver(innerTubeClient, prefsRepo)

        val resolved = resolver.resolveStreamUrl("test_id")
        assertNotNull(resolved)
        assertEquals("https://rr1.googlevideo.com/videoplayback?itag=139", resolved)
    }

    @Test
    fun cachesResolvedUrlInMemory() = runTest {
        coEvery { innerTubeClient.getAudioFormats("test_id") } returns sampleFormats
        val resolver = YouTubeStreamResolver(innerTubeClient, prefsRepo)

        val url1 = resolver.resolveStreamUrl("test_id")
        val url2 = resolver.resolveStreamUrl("test_id")

        assertEquals(url1, url2)
        io.mockk.coVerify(exactly = 1) { innerTubeClient.getAudioFormats("test_id") }
    }

    @Test
    fun concurrentResolutionsDeduplicateInFlightRequests() = runTest {
        coEvery { innerTubeClient.getAudioFormats("test_concurrent") } coAnswers {
            kotlinx.coroutines.delay(50)
            sampleFormats
        }
        val resolver = YouTubeStreamResolver(innerTubeClient, prefsRepo)

        val deferred1 = async { resolver.resolveStreamUrl("test_concurrent") }
        val deferred2 = async { resolver.resolveStreamUrl("test_concurrent") }

        val res1 = deferred1.await()
        val res2 = deferred2.await()

        assertEquals(res1, res2)
        io.mockk.coVerify(exactly = 1) { innerTubeClient.getAudioFormats("test_concurrent") }
    }

    @Test
    fun resolveAudioFormatPicksFormatMatchingRequestedBitrate() = runTest {
        coEvery { innerTubeClient.getAudioFormats("test_id") } returns sampleFormats
        val resolver = YouTubeStreamResolver(innerTubeClient, prefsRepo)

        val format128 = resolver.resolveAudioFormat("test_id", 128)
        assertEquals(140, format128.itag)

        val formatOriginal = resolver.resolveAudioFormat("test_id", null)
        assertEquals(251, formatOriginal.itag)

        // When 320 is requested but highest available is 160k (itag 251), selects 251
        val format320 = resolver.resolveAudioFormat("test_id", 320)
        assertEquals(251, format320.itag)

        // When 32 is requested but lowest available is 48k (itag 139), gracefully selects 139
        val format32 = resolver.resolveAudioFormat("test_id", 32)
        assertEquals(139, format32.itag)
    }

    @Test
    fun youTubeTrackToSongMapsCorrectMetadata() {
        val ytTrack = YouTubeTrack(
            videoId = "vid123",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            album = "A Night at the Opera",
            durationMs = 354_000L,
            artworkUri = "https://i.ytimg.com/vi/vid123/hqdefault.jpg"
        )

        val song = ytTrack.toSong()

        assertEquals("youtube:vid123", song.id)
        assertEquals("youtube", song.providerId)
        assertEquals("vid123", song.providerRemoteId)
        assertEquals("Bohemian Rhapsody", song.title)
        assertEquals("Queen", song.artist)
        assertEquals("A Night at the Opera", song.album)
        assertEquals("yt_album_${"A Night at the Opera".hashCode()}", song.albumId)
        assertEquals(354_000L, song.duration)
        assertEquals("https://i.ytimg.com/vi/vid123/hqdefault.jpg", song.artworkUri)
        assertEquals("https://www.youtube.com/watch?v=vid123", song.uriString)
    }
}
