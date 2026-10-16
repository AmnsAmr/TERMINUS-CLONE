package com.necroware.terminusplayer.data.model

import com.necroware.terminusplayer.data.database.entity.SongEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SongIdentityPresentationTest {
    @Test
    fun keepsSameTitleLocalAndServerSongsVisibleWhenTheyAreNotLinked() {
        val local = song("local:1", "local", "file-1")
        val server = song("navidrome:1", "navidrome", "server-1")

        assertEquals(listOf(local, server), collapseExplicitlyLinkedSongs(listOf(local, server)))
    }

    @Test
    fun collapsesOnlyAnExplicitUniqueLocalServerAssociation() {
        val local = song("local:1", "local", "file-1", navidromeId = "server-1")
        val server = song("navidrome:1", "navidrome", "server-1")

        assertEquals(listOf(local), collapseExplicitlyLinkedSongs(listOf(local, server)))
    }

    @Test
    fun keepsServerRowsWhenTheAssociationIsAmbiguous() {
        val firstLocal = song("local:1", "local", "file-1", navidromeId = "server-1")
        val secondLocal = song("local:2", "local", "file-2", navidromeId = "server-1")
        val server = song("navidrome:1", "navidrome", "server-1")

        assertEquals(listOf(firstLocal, secondLocal, server), collapseExplicitlyLinkedSongs(listOf(firstLocal, secondLocal, server)))
    }

    private fun song(id: String, providerId: String, providerRemoteId: String, navidromeId: String? = null) = SongEntity(
        remoteId = id,
        providerId = providerId,
        providerRemoteId = providerRemoteId,
        title = "Same title",
        artist = "Same artist",
        album = "Album",
        albumId = "album",
        duration = 180_000L,
        uriString = "content://$providerRemoteId",
        dateAdded = 1L,
        navidromeId = navidromeId
    )
}
