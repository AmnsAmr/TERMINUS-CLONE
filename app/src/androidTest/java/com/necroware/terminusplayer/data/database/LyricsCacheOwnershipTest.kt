package com.necroware.terminusplayer.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.necroware.terminusplayer.data.database.entity.LyricsCacheEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricsCacheOwnershipTest {
    @Test
    fun deletingDownloadReferenceKeepsSharedServerLyrics() = runBlocking {
        withDatabase { db ->
            val cache = db.lyricsCacheDao()
            cache.put(LyricsCacheEntity("track:navidrome:42", "lyrics", null, 1L))
            // Downloaded-file lyrics use the server identity and have no local-only ownership row.
            cache.removeFileReference("content://downloads/42")
            cache.pruneOrphanedLocalLyrics()
            assertNotNull(cache.get("track:navidrome:42"))
        }
    }

    @Test
    fun confirmedDeletionPrunesUnsharedLocalOnlyLyrics() = runBlocking {
        withDatabase { db ->
            val cache = db.lyricsCacheDao()
            cache.putLocalOnly(
                LyricsCacheEntity("local:file-1", "lyrics", null, 1L),
                "content://media/audio/1"
            )
            cache.removeFileReference("content://media/audio/1")
            cache.pruneOrphanedLocalLyrics()
            assertNull(cache.get("local:file-1"))
        }
    }

    @Test
    fun deletingSongRowDuringMediaScanDoesNotDeleteLyricsCache() = runBlocking {
        withDatabase { db ->
            val songDao = db.songDao()
            val cache = db.lyricsCacheDao()
            val song = SongEntity(
                remoteId = "local:1", providerId = "local", providerRemoteId = "1",
                title = "Song", artist = "Artist", album = "Album", albumId = "album",
                duration = 60_000L, uriString = "content://media/audio/1", dateAdded = 1L
            )
            songDao.upsertAll(listOf(song))
            cache.putLocalOnly(
                LyricsCacheEntity("local:file-1", "lyrics", null, 1L),
                song.uriString
            )

            songDao.deleteByIds(listOf(song.remoteId)) // A scan only updates library rows.
            assertNotNull(cache.get("local:file-1"))
            val referenceCount = db.openHelper.writableDatabase.query(
                "SELECT fileIdentity FROM lyrics_cache_files WHERE fileIdentity = '${song.uriString}'"
            ).use { cursor -> if (cursor.moveToFirst()) 1 else 0 }
            assertTrue(referenceCount == 1)
        }
    }

    private suspend fun withDatabase(block: suspend (TerminusDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TerminusDatabase::class.java
        ).build()
        try {
            block(db)
        } finally {
            db.close()
        }
    }
}
