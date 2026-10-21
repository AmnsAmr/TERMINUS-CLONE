package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.api.youtube.YouTubeAudioFormat
import com.necroware.terminusplayer.data.api.youtube.YouTubeInnerTubeClient
import com.necroware.terminusplayer.data.api.youtube.YouTubeStreamResolver
import com.necroware.terminusplayer.data.api.youtube.YouTubeTrack
import com.necroware.terminusplayer.data.database.dao.SongDao
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.LyricLine
import com.necroware.terminusplayer.data.repository.YouTubeMusicRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeMusicProviderTest {

    private val innerTubeClient = mockk<YouTubeInnerTubeClient>()
    private val streamResolver = mockk<YouTubeStreamResolver>()
    private val songDao = mockk<SongDao>(relaxed = true)

    private val sampleTrack = Song(
        id = "youtube:vid555",
        title = "Synthwave Horizon",
        artist = "Retro Synth",
        album = "Neon Grid",
        albumId = "yt_album_neon",
        duration = 210_000L,
        uriString = "https://www.youtube.com/watch?v=vid555",
        providerId = "youtube",
        providerRemoteId = "vid555",
        artworkUri = "https://i.ytimg.com/vi/vid555/hqdefault.jpg"
    )

    private lateinit var provider: YouTubeMusicProvider
    private lateinit var repository: YouTubeMusicRepository

    @Before
    fun setUp() {
        provider = YouTubeMusicProvider(innerTubeClient, streamResolver)
        repository = YouTubeMusicRepository(innerTubeClient, songDao)
    }

    @Test
    fun providerIdIsYouTube() {
        assertEquals("youtube", provider.providerId)
    }

    @Test
    fun syncLibraryReturnsEmptyList() = runTest {
        val result = provider.syncLibrary()
        assertTrue(result.isEmpty())
    }

    @Test
    fun resolveStreamUrlsDelegatesToStreamResolver() = runTest {
        coEvery { streamResolver.resolveStreamUrl("vid555") } returns "https://rr1.googlevideo.com/555"
        coEvery { streamResolver.resolveStreamUrl("vid777") } returns "https://rr1.googlevideo.com/777"

        val resolved = provider.resolveStreamUrls(listOf("vid555", "vid777"))

        assertEquals(2, resolved.size)
        assertEquals("https://rr1.googlevideo.com/555", resolved["vid555"])
        assertEquals("https://rr1.googlevideo.com/777", resolved["vid777"])
    }

    @Test
    fun resolveDownloadUrlDelegatesToStreamResolverWithMaxBitRate() = runTest {
        val format = YouTubeAudioFormat(
            itag = 140,
            mimeType = "audio/mp4",
            bitrate = 128000L,
            contentLength = 3000L,
            durationMs = 200000L,
            url = "https://rr1.googlevideo.com/download140"
        )
        coEvery { streamResolver.resolveAudioFormat("vid555", 128) } returns format

        val url = provider.resolveDownloadUrl("youtube:vid555", 128)
        assertEquals("https://rr1.googlevideo.com/download140", url)
    }

    @Test
    fun invalidateStreamDelegatesToStreamResolver() {
        io.mockk.every { streamResolver.invalidate("vid555") } returns Unit

        provider.invalidateStream("youtube:vid555")

        io.mockk.verify(exactly = 1) { streamResolver.invalidate("vid555") }
    }

    @Test
    fun getLyricsFetchesAndParsesPlainLyrics() = runTest {
        val sampleLyrics = SyncedLyrics(
            lines = listOf(
                LyricLine(startMs = 0L, text = "First line of song"),
                LyricLine(startMs = 0L, text = "Second line of song"),
                LyricLine(startMs = 0L, text = "Third line")
            )
        )
        coEvery { innerTubeClient.getLyrics("vid555") } returns sampleLyrics

        val lyrics = provider.getLyrics("youtube:vid555")

        assertNotNull(lyrics)
        assertEquals(3, lyrics!!.lines.size)
        assertEquals("First line of song", lyrics.lines[0].text)
        assertEquals(0L, lyrics.lines[0].startMs)
        assertEquals("Second line of song", lyrics.lines[1].text)
        assertEquals("Third line", lyrics.lines[2].text)
    }

    @Test
    fun getLyricsReturnsNullWhenLyricsMissingOrBlank() = runTest {
        coEvery { innerTubeClient.getLyrics("vid555") } returns null

        val lyrics = provider.getLyrics("vid555")
        assertNull(lyrics)
    }

    @Test
    fun repositorySearchSongsQueriesClientAndMapsToSongs() = runTest {
        coEvery { innerTubeClient.searchSongs("daft punk") } returns listOf(sampleTrack)

        val result = repository.searchSongs("daft punk")

        assertTrue(result.isSuccess)
        val songs = result.getOrThrow()
        assertEquals(1, songs.size)
        val song = songs.first()
        assertEquals("youtube:vid555", song.id)
        assertEquals("youtube", song.providerId)
        assertEquals("vid555", song.providerRemoteId)
        assertEquals("Synthwave Horizon", song.title)
        assertEquals("Retro Synth", song.artist)
        assertEquals("https://i.ytimg.com/vi/vid555/hqdefault.jpg", song.artworkUri)
        assertEquals(210_000L, song.duration)
    }

    @Test
    fun repositoryDiscoveryModesQueryAppropriateMethods() = runTest {
        coEvery { innerTubeClient.getPopularSongs() } returns listOf(sampleTrack)
        coEvery { innerTubeClient.getTrendingSongs() } returns listOf(sampleTrack)
        coEvery { innerTubeClient.getCharts() } returns listOf(sampleTrack)

        val popular = repository.getPopularSongs().getOrThrow()
        assertEquals(1, popular.size)

        val trending = repository.getTrendingSongs().getOrThrow()
        assertEquals(1, trending.size)

        val charts = repository.getCharts().getOrThrow()
        assertEquals(1, charts.size)
    }

    @Test
    fun repositoryEnsureTrackPersistedInsertsIntoDaoWhenMissing() = runTest {
        val song = Song(
            id = "youtube:vid555",
            providerId = "youtube",
            providerRemoteId = "vid555",
            title = "Synthwave Horizon",
            artist = "Retro Synth",
            album = "Neon Grid",
            albumId = "MPREb_neon",
            duration = 210_000L,
            uriString = "https://www.youtube.com/watch?v=vid555",
            artworkUri = "https://i.ytimg.com/vi/vid555/hqdefault.jpg"
        )

        coEvery { songDao.getById("youtube:vid555") } returns null

        repository.ensureTrackPersisted(song)

        coVerify(exactly = 1) {
            songDao.insertSong(match {
                it.remoteId == "youtube:vid555" &&
                    it.providerId == "youtube" &&
                    it.providerRemoteId == "vid555" &&
                    it.artworkUri == "https://i.ytimg.com/vi/vid555/hqdefault.jpg" &&
                    it.duration == 210_000L
            })
        }
    }

    @Test
    fun repositorySearchSongsPagedReturnsPagedResult() = runTest {
        val searchResult = com.necroware.terminusplayer.data.api.youtube.YouTubeSearchResult(
            songs = listOf(sampleTrack),
            continuationToken = "token_continuation_abc"
        )
        coEvery { innerTubeClient.searchSongsPaged("cyberpunk", null, null) } returns searchResult

        val result = repository.searchSongsPaged("cyberpunk")
        assertTrue(result.isSuccess)
        val data = result.getOrThrow()
        assertEquals(1, data.songs.size)
        assertEquals("token_continuation_abc", data.continuationToken)
    }

    @Test
    fun repositoryGetSearchSuggestionsReturnsList() = runTest {
        coEvery { innerTubeClient.getSearchSuggestions("daft") } returns listOf("daft punk", "daft punk get lucky")

        val result = repository.getSearchSuggestions("daft")
        assertTrue(result.isSuccess)
        val suggestions = result.getOrThrow()
        assertEquals(2, suggestions.size)
        assertEquals("daft punk", suggestions[0])
    }

    @Test
    fun repositoryGetDiscoverySongsPagedUsesFilterBrowseId() = runTest {
        val chartFilter = com.necroware.terminusplayer.data.api.youtube.YouTubeDiscoveryFilter.DEFAULT_FILTERS.first { it.id == "charts" }
        coEvery { innerTubeClient.browsePlaylistSongs(chartFilter.browseId!!) } returns listOf(sampleTrack)

        val result = repository.getDiscoverySongsPaged(chartFilter)
        assertTrue(result.isSuccess)
        val paged = result.getOrThrow()
        assertEquals(1, paged.songs.size)
        assertEquals("youtube:vid555", paged.songs[0].id)
    }
}

