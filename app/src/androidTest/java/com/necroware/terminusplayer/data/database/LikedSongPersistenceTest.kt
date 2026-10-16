package com.necroware.terminusplayer.data.database

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.necroware.terminusplayer.data.database.entity.LikedSongEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LikedSongPersistenceTest {
    @Test
    fun serverRefreshPreservesLocalLikesAndPendingOfflineUnlikes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "liked-song-persistence-test.db"
        context.deleteDatabase(databaseName)
        var db = Room.databaseBuilder(context, TerminusDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
            var dao = db.likedSongDao()
            dao.like(LikedSongEntity("local:1", 10L))
            dao.like(
                LikedSongEntity(
                    songId = "navidrome:2",
                    likedAt = 20L,
                    ownership = LikedSongEntity.OWNERSHIP_SERVER,
                    remoteSongId = "2",
                    updatedAt = 20L
                )
            )
            dao.like(
                LikedSongEntity(
                    songId = "navidrome:3",
                    likedAt = 30L,
                    isLiked = false,
                    ownership = LikedSongEntity.OWNERSHIP_SERVER,
                    remoteSongId = "3",
                    syncPending = true,
                    updatedAt = 31L
                )
            )
            db.close()
            db = Room.databaseBuilder(context, TerminusDatabase::class.java, databaseName)
                .allowMainThreadQueries()
                .build()
            dao = db.likedSongDao()

            dao.clearServerManagedLikes()
            dao.insertSyncedLikes(
                listOf(
                    LikedSongEntity(
                        songId = "navidrome:3",
                        likedAt = 32L,
                        ownership = LikedSongEntity.OWNERSHIP_SERVER,
                        remoteSongId = "3",
                        updatedAt = 32L
                    ),
                    LikedSongEntity(
                        songId = "navidrome:4",
                        likedAt = 40L,
                        ownership = LikedSongEntity.OWNERSHIP_SERVER,
                        remoteSongId = "4",
                        updatedAt = 40L
                    )
                )
            )

            assertTrue(dao.isLiked("local:1"))
            assertFalse(dao.isLiked("navidrome:2"))
            assertFalse(dao.isLiked("navidrome:3"))
            assertTrue(dao.isLiked("navidrome:4"))
            assertEquals(31L, dao.getLike("navidrome:3")?.updatedAt)
            assertEquals(listOf("navidrome:4", "local:1"), dao.getLikedIdsMostRecentFirst())

            dao.deleteSyncedUnlike("navidrome:3", 31L)
            assertEquals(null, dao.getLike("navidrome:3"))

            // A delayed response for an older request must not acknowledge a newer desired state.
            dao.like(
                LikedSongEntity(
                    songId = "navidrome:5",
                    likedAt = 50L,
                    ownership = LikedSongEntity.OWNERSHIP_SERVER,
                    remoteSongId = "5",
                    syncPending = true,
                    updatedAt = 53L
                )
            )
            dao.markLikeSynced("navidrome:5", expectedUpdatedAt = 52L)
            assertTrue(dao.getLike("navidrome:5")?.syncPending == true)
            assertEquals(53L, dao.getLike("navidrome:5")?.updatedAt)
        } finally {
            db.close()
            context.deleteDatabase(databaseName)
        }
    }
}
