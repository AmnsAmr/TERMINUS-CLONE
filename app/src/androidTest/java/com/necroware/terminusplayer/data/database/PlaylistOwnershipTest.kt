package com.necroware.terminusplayer.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.necroware.terminusplayer.data.database.entity.PlaylistEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistSongEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistOwnershipTest {
    @Test
    fun mixedLocalPlaylistSurvivesRestartAndServerSnapshotOperationsStayScoped() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "mixed-playlist-ownership-test.db"
        context.deleteDatabase(databaseName)
        val localPlaylistId = "local-owned-id"
        val serverPlaylistId = "server:remote-1"

        var db = Room.databaseBuilder(context, TerminusDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            db.songDao().upsertAll(
                listOf(
                    song("navidrome:1", "navidrome", "1"),
                    song("local:phone-1", "local", "phone-1"),
                    song("navidrome:2", "navidrome", "2")
                )
            )
            db.playlistDao().insertPlaylistWithSongs(
                PlaylistEntity(id = localPlaylistId, name = "Mixed", createdAt = 1L),
                listOf(
                    PlaylistSongEntity(localPlaylistId, "navidrome:1", 0),
                    PlaylistSongEntity(localPlaylistId, "local:phone-1", 1),
                    PlaylistSongEntity(localPlaylistId, "navidrome:2", 2),
                    PlaylistSongEntity(localPlaylistId, "navidrome:1", 3)
                )
            )
            db.playlistDao().insertPlaylistWithSongs(
                PlaylistEntity(
                    id = serverPlaylistId,
                    name = "Server list",
                    createdAt = 2L,
                    serverPlaylistId = "remote-1",
                    ownership = PlaylistEntity.OWNERSHIP_SERVER
                ),
                listOf(PlaylistSongEntity(serverPlaylistId, "navidrome:1", 0))
            )
        } finally {
            db.close()
        }

        db = Room.databaseBuilder(context, TerminusDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.playlistDao()
            assertEquals(
                listOf("navidrome:1", "local:phone-1", "navidrome:2", "navidrome:1"),
                dao.getSongsForPlaylist(localPlaylistId).map { it.remoteId }
            )
            assertEquals(PlaylistEntity.OWNERSHIP_LOCAL, dao.getPlaylist(localPlaylistId)?.ownership)

            assertTrue(
                dao.replaceServerPlaylistSnapshot(
                    serverPlaylistId,
                    listOf(
                        PlaylistSongEntity(serverPlaylistId, "navidrome:2", 0, syncedToServer = true),
                        PlaylistSongEntity(serverPlaylistId, "navidrome:1", 1, syncedToServer = true),
                        PlaylistSongEntity(serverPlaylistId, "navidrome:1", 2, syncedToServer = true)
                    ),
                    songCount = 3,
                    signature = "remote-snapshot",
                    checkedAt = 100L,
                    name = "Server list"
                )
            )
            assertFalse(
                dao.replaceServerPlaylistSnapshot(
                    localPlaylistId,
                    emptyList(),
                    songCount = 0,
                    signature = "must-not-apply",
                    checkedAt = 101L,
                    name = "Mixed"
                )
            )
            assertFalse(dao.purgeServerPlaylist(localPlaylistId))
            assertEquals(
                listOf("navidrome:1", "local:phone-1", "navidrome:2", "navidrome:1"),
                dao.getSongsForPlaylist(localPlaylistId).map { it.remoteId }
            )
            assertEquals(
                listOf("navidrome:2", "navidrome:1", "navidrome:1"),
                dao.getSongsForPlaylist(serverPlaylistId).map { it.remoteId }
            )
            assertTrue(dao.purgeServerPlaylist(serverPlaylistId))
            assertEquals(null, dao.getPlaylist(serverPlaylistId))
            assertEquals(4, dao.getSongsForPlaylist(localPlaylistId).size)
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun song(id: String, provider: String, providerRemoteId: String) = SongEntity(
        remoteId = id,
        providerId = provider,
        providerRemoteId = providerRemoteId,
        title = id,
        artist = "Artist",
        album = "Album",
        albumId = "album",
        duration = 60_000L,
        uriString = if (provider == "local") "content://music/$providerRemoteId" else "terminus://$providerRemoteId",
        dateAdded = 1L,
        navidromeId = null
    )
}
