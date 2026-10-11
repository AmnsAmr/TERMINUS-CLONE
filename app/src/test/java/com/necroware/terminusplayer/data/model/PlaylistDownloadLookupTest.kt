package com.necroware.terminusplayer.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistDownloadLookupTest {
    @Test
    fun lookupKeysAreRestrictedToUniquePlaylistSongIdsAndMatchingKeys() {
        val server = song("navidrome:1", "navidrome", "server-1", "Echo")
        val local = song("local:2", "local", "local-2", "Echo").copy(navidromeId = "server-1")
        val keys = playlistDownloadLookupKeys(listOf(server, local, server))

        assertEquals(listOf("navidrome:1", "local:2"), keys.songIds)
        assertEquals(listOf("server-1"), keys.navidromeIds)
        assertEquals(listOf("Echo"), keys.titles)
    }

    @Test
    fun largePlaylistLookupInputsAreDeduplicatedAndSplitBelowSqliteBindLimit() {
        val ids = (0 until 1_805).map { "song-$it" } + "song-0"
        val batches = sqliteQueryBatches(ids)

        assertEquals(listOf(900, 900, 5), batches.map { it.size })
        assertEquals(ids.dropLast(1), batches.flatten())
    }

    private fun song(id: String, provider: String, remoteId: String, title: String) = Song(
        id = id,
        providerId = provider,
        providerRemoteId = remoteId,
        title = title,
        artist = "Artist",
        album = "Album",
        albumId = "album-id",
        duration = 1_000,
        uriString = "content://music/$id"
    )
}
