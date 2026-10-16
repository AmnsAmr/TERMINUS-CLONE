package com.necroware.terminusplayer.data.model

import com.necroware.terminusplayer.data.database.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingIdentityMatchingTest {
    @Test
    fun exactSingleLocalAndServerMatchAssociates() {
        val local = song("local:1", "local", "local-file")
        val remote = song("navidrome:1", "navidrome", "server-file")
        assertEquals("navidrome:1", unambiguousRecordingMatches(listOf(local), listOf(remote))["local:1"]?.remoteId)
    }

    @Test
    fun twoLocalCandidatesDoNotArbitrarilyClaimOneServerId() {
        val locals = listOf(song("local:1", "local", "file-1"), song("local:2", "local", "file-2"))
        val remote = song("navidrome:1", "navidrome", "server-file")
        assertTrue(unambiguousRecordingMatches(locals, listOf(remote)).isEmpty())
    }

    @Test
    fun twoRemoteCandidatesDoNotArbitrarilyClaimOneLocalTrack() {
        val local = song("local:1", "local", "file-1")
        val remotes = listOf(song("navidrome:1", "navidrome", "server-1"), song("navidrome:2", "navidrome", "server-2"))
        assertTrue(unambiguousRecordingMatches(listOf(local), remotes).isEmpty())
    }

    private fun song(id: String, providerId: String, providerRemoteId: String) = SongEntity(
        remoteId = id,
        providerId = providerId,
        providerRemoteId = providerRemoteId,
        title = "Same recording",
        artist = "Artist",
        album = "Album",
        albumId = "album",
        duration = 180_000L,
        uriString = "content://$providerRemoteId",
        dateAdded = 1L
    )
}
