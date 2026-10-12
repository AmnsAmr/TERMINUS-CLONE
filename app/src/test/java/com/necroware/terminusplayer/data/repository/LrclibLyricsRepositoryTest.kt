package com.necroware.terminusplayer.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.necroware.terminusplayer.data.api.lyrics.LrclibApiService
import com.necroware.terminusplayer.data.database.dao.LyricsCacheDao
import com.necroware.terminusplayer.data.database.entity.LyricsCacheEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.LyricsCacheIdentity
import com.necroware.terminusplayer.data.model.LyricLine
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.squareup.moshi.Moshi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibLyricsRepositoryTest {
    @Test
    fun offlineSkipsLrclibNetworkRequest() = runBlocking {
        val (repository, api, _) = createRepository(online = false)
        assertNull(repository.fetchFromLrclib(song, LyricsCacheIdentity("track:test:1", null)))
        coVerify(exactly = 0) { api.getLyrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.searchLyrics(any(), any()) }
    }

    @Test
    fun cachedLyricsAreReturnedWithoutNetwork() = runBlocking {
        val (repository, api, cache) = createRepository(online = false)
        val identity = LyricsCacheIdentity("track:test:1", null)
        coEvery { cache.get(identity.cacheKey) } returns LyricsCacheEntity(identity.cacheKey, "cached line", null, 1L)

        assertEquals("cached line", repository.cachedForSong(song, identity)?.lines?.single()?.text)
        coVerify(exactly = 0) { api.getLyrics(any(), any(), any(), any()) }
    }

    @Test
    fun lrclibFallbackPersistsSynchronizedAndPlainLyrics() = runBlocking {
        val (repository, api, cache) = createRepository(online = true)
        assertTrue(repository.isOnline())
        val identity = LyricsCacheIdentity("track:test:1", null)
        coEvery { cache.get(identity.cacheKey) } returns null
        coEvery { api.getLyrics("Track", "Artist", "Album", 180L) } returns
            """{"trackName":"Track","artistName":"Artist","duration":180,"plainLyrics":"Plain fallback","syncedLyrics":"[00:01.20]Timed line"}"""
                .toResponseBody("application/json".toMediaType())

        val result = repository.fetchFromLrclib(song, identity)!!
        assertEquals(1_200L, result.lines.single().startMs)
        coVerify {
            cache.put(match {
                it.cacheKey == identity.cacheKey &&
                    it.plainLyrics == "Plain fallback" &&
                    it.syncedLyrics == "[00:01.20]Timed line"
            })
        }
        coVerify(exactly = 0) { api.searchLyrics(any(), any()) }
    }

    @Test
    fun emptyResultIsAMissAndIncorrectVersionIsRejected() = runBlocking {
        val (repository, api, cache) = createRepository(online = true)
        assertTrue(repository.isOnline())
        val identity = LyricsCacheIdentity("track:test:1", null)
        coEvery { cache.get(identity.cacheKey) } returns null
        coEvery { api.getLyrics(any(), any(), any(), any()) } returns
            "{}".toResponseBody("application/json".toMediaType())
        coEvery { api.searchLyrics(any(), any()) } returns
            """[{"trackName":"Track (Live)","artistName":"Artist","duration":180,"plainLyrics":"Wrong version"}]"""
                .toResponseBody("application/json".toMediaType())

        assertNull(repository.fetchFromLrclib(song, identity))
        coVerify { cache.put(match { it.cacheKey == identity.cacheKey && it.plainLyrics == null && it.syncedLyrics == null }) }
    }

    @Test
    fun plainLyricsCannotReplaceExistingSynchronizedLyrics() = runBlocking {
        val (repository, _, cache) = createRepository(online = true)
        val identity = LyricsCacheIdentity("track:test:1", null)
        coEvery { cache.get(identity.cacheKey) } returns LyricsCacheEntity(
            identity.cacheKey, "fallback", "[00:01.00]Timed", 1L
        )

        val result = repository.save(identity, SyncedLyrics(listOf(LyricLine(0L, "New plain text"))))
        assertEquals("Timed", result.lines.single().text)
        coVerify(exactly = 0) { cache.put(any()) }
    }

    private fun createRepository(online: Boolean): Triple<LrclibLyricsRepository, LrclibApiService, LyricsCacheDao> {
        val api = mockk<LrclibApiService>()
        val cache = mockk<LyricsCacheDao>(relaxed = true)
        val context = mockk<Context>()
        val connectivity = mockk<ConnectivityManager>()
        val network = mockk<Network>()
        val capabilities = mockk<NetworkCapabilities>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
        every { connectivity.activeNetwork } returns network
        every { connectivity.getNetworkCapabilities(network) } returns capabilities
        every { capabilities.hasCapability(any()) } answers {
            online && firstArg<Int>() in setOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET,
                NetworkCapabilities.NET_CAPABILITY_VALIDATED
            )
        }
        return Triple(LrclibLyricsRepository(api, cache, Moshi.Builder().build(), context), api, cache)
    }

    private val song = SongEntity(
        remoteId = "track:1", providerId = "test", providerRemoteId = "1",
        title = "Track", artist = "Artist", album = "Album", albumId = "album",
        duration = 180_000L, uriString = "test://track/1", dateAdded = 1L
    )
}
