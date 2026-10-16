package com.necroware.terminusplayer.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.necroware.terminusplayer.data.database.entity.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongIdentityLinkTest {
    @Test
    fun explicitLinkingCannotStealARemoteIdentityFromAnotherLocalFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, TerminusDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            db.songDao().upsertAll(
                listOf(
                    song("local:first", "local", "first"),
                    song("local:second", "local", "second"),
                    song("navidrome:remote-1", "navidrome", "remote-1"),
                    song("navidrome:remote-2", "navidrome", "remote-2")
                )
            )
            assertEquals(1, db.songDao().linkLocalSongToNavidrome("local:first", "remote-1"))
            assertEquals(0, db.songDao().linkLocalSongToNavidrome("local:second", "remote-1"))
            assertEquals(0, db.songDao().linkLocalSongToNavidrome("local:first", "remote-2"))
            assertEquals("remote-1", db.songDao().getById("local:first")?.navidromeId)
            assertEquals(null, db.songDao().getById("local:second")?.navidromeId)
        } finally {
            db.close()
        }
    }

    private fun song(id: String, provider: String, providerRemoteId: String) = SongEntity(
        remoteId = id,
        providerId = provider,
        providerRemoteId = providerRemoteId,
        title = "Recording",
        artist = "Artist",
        album = "Album",
        albumId = "album",
        duration = 60_000L,
        uriString = if (provider == "local") "content://music/$providerRemoteId" else "terminus://$providerRemoteId",
        dateAdded = 1L
    )
}
