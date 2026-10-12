package com.necroware.terminusplayer.data.model

import com.necroware.terminusplayer.data.database.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsResolverRulesTest {
    @Test
    fun lrcTimestampsArePreservedAndPlainTextIsAValidFallback() {
        val synced = parseLyricsText("[00:01.20]First\n[00:02.5]Second")!!
        assertEquals(listOf(1_200L, 2_500L), synced.lines.map { it.startMs })
        assertEquals("[00:01.20]First\n[00:02.50]Second", synced.toLyricsCacheFields().second)
        assertEquals(listOf(0L, 0L), parseLyricsText("Verse\nLine")!!.lines.map { it.startMs })
        assertNull(parseLyricsText("  "))
    }

    @Test
    fun stableServerIdentityIsSharedByServerAndMappedLocalFile() {
        val server = song(id = "navidrome:42", provider = "navidrome", providerRemoteId = "42")
        val local = song(id = "local:99", provider = "local", uri = "content://media/99", navidromeId = "42")
        assertEquals(lyricsCacheIdentity(server).cacheKey, lyricsCacheIdentity(local).cacheKey)
        assertNull(lyricsCacheIdentity(local).localFileIdentity)
    }

    @Test
    fun localOnlyFilesHaveIndependentCacheOwnership() {
        val one = song(id = "local:1", provider = "local", uri = "content://media/1")
        val two = song(id = "local:2", provider = "local", uri = "content://media/2")
        assertNotEquals(lyricsCacheIdentity(one).cacheKey, lyricsCacheIdentity(two).cacheKey)
        assertEquals("content://media/1", lyricsCacheIdentity(one).localFileIdentity)
    }

    @Test
    fun metadataFallbackRejectsSameTitleArtistFromDifferentRecordings() {
        val studio = song(id = "local:1", provider = "local", uri = "content://media/1")
        assertTrue(sameRecordingByMetadata(studio, studio.copy(remoteId = "navidrome:1", providerId = "navidrome", providerRemoteId = "1")))
        assertFalse(sameRecordingByMetadata(studio, studio.copy(album = "Live at Somewhere")))
        assertFalse(sameRecordingByMetadata(studio, studio.copy(title = "Track (Live)")))
        assertFalse(sameRecordingByMetadata(studio, studio.copy(duration = studio.duration + 15_000L)))
        assertFalse(lrclibResultMatches(studio, "Track (Live)", "Artist", studio.duration))
        assertFalse(lrclibResultMatches(studio, "Track", "Artist", studio.duration + 20_000L))
        assertFalse(lrclibResultMatches(studio, "Track", "Artist", null))
        assertTrue(lrclibResultMatches(studio, "track", "ARTIST", studio.duration + 1_000L))
        val duplicateRemote = studio.copy(remoteId = "navidrome:2", providerId = "navidrome", providerRemoteId = "2")
        assertNull(uniqueRecordingMatch(studio, listOf(duplicateRemote, duplicateRemote.copy(remoteId = "navidrome:3", providerRemoteId = "3"))))
    }

    private fun song(
        id: String,
        provider: String,
        providerRemoteId: String = id,
        uri: String = "terminus://$id",
        navidromeId: String? = null
    ) = SongEntity(
        remoteId = id,
        providerId = provider,
        providerRemoteId = providerRemoteId,
        title = "Track",
        artist = "Artist",
        album = "Album",
        albumId = "album",
        duration = 180_000L,
        uriString = uri,
        dateAdded = 1L,
        navidromeId = navidromeId
    )
}
