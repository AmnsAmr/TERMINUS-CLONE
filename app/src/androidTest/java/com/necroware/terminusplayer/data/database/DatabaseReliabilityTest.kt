package com.necroware.terminusplayer.data.database

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.necroware.terminusplayer.data.database.entity.PlaylistEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistSongEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseReliabilityTest {
    @Test
    fun concurrentLikeTogglesAreSerialized() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TerminusDatabase::class.java
        ).build()
        try {
            val results = listOf(
                async(Dispatchers.IO) { database.likedSongDao().toggle("local:1", 1L) },
                async(Dispatchers.IO) { database.likedSongDao().toggle("local:1", 2L) }
            ).awaitAll()

            assertEquals(setOf(true, false), results.toSet())
            assertFalse(database.likedSongDao().isLiked("local:1"))
        } finally {
            database.close()
        }
    }

    @Test
    fun playlistInsertChunksLargeListsAndRollsBackOnDuplicate() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TerminusDatabase::class.java
        ).build()
        try {
            val dao = database.playlistDao()
            val songs = (0 until 1_200).map { PlaylistSongEntity("large", "song:$it", it) }
            dao.insertPlaylistWithSongs(PlaylistEntity("large", "Large", 1L), songs)
            assertEquals(1_200, database.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM playlist_songs WHERE playlistId = 'large'"
            ).use { it.moveToFirst(); it.getInt(0) })

            try {
                dao.insertPlaylistWithSongs(
                    PlaylistEntity("duplicate", "Duplicate", 2L),
                    listOf(
                        PlaylistSongEntity("duplicate", "same-song", 0),
                        PlaylistSongEntity("duplicate", "same-song", 1)
                    )
                )
                throw AssertionError("Duplicate playlist membership should fail")
            } catch (_: SQLiteConstraintException) {
                // The @Transaction wrapper must roll the playlist row back with its songs.
            }
            assertEquals(0, database.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM playlists WHERE id = 'duplicate'"
            ).use { it.moveToFirst(); it.getInt(0) })
            assertTrue(database.openHelper.writableDatabase.query(
                "SELECT COUNT(*) FROM playlist_songs WHERE playlistId = 'duplicate'"
            ).use { it.moveToFirst(); it.getInt(0) } == 0)
        } finally {
            database.close()
        }
    }
}
