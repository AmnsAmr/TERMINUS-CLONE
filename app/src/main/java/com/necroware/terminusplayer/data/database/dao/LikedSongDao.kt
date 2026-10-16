package com.necroware.terminusplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.necroware.terminusplayer.data.database.entity.LikedSongEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LikedSongDao {

    @Query("SELECT songId FROM liked_songs WHERE isLiked = 1")
    fun observeLikedIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM liked_songs WHERE isLiked = 1")
    fun observeLikedSongCount(): Flow<Int>

    @Query("SELECT songId FROM liked_songs WHERE isLiked = 1 ORDER BY likedAt DESC")
    suspend fun getLikedIdsMostRecentFirst(): List<String>

    @Query("SELECT * FROM liked_songs WHERE songId = :songId LIMIT 1")
    suspend fun getLike(songId: String): LikedSongEntity?

    @Query("SELECT * FROM liked_songs WHERE syncPending = 1 ORDER BY updatedAt ASC")
    suspend fun getPendingLikes(): List<LikedSongEntity>

    @Query("SELECT * FROM liked_songs WHERE ownership = 'LOCAL' AND isLiked = 1")
    suspend fun getLocalLikedRows(): List<LikedSongEntity>

    @Query("DELETE FROM liked_songs WHERE ownership = 'SERVER' AND syncPending = 0")
    suspend fun clearServerManagedLikes()

    @Query("UPDATE liked_songs SET syncPending = 0 WHERE songId = :songId AND updatedAt = :expectedUpdatedAt AND isLiked = 1")
    suspend fun markLikeSynced(songId: String, expectedUpdatedAt: Long)

    @Query("DELETE FROM liked_songs WHERE songId = :songId AND updatedAt = :expectedUpdatedAt AND isLiked = 0 AND syncPending = 1")
    suspend fun deleteSyncedUnlike(songId: String, expectedUpdatedAt: Long)

    @Query("UPDATE liked_songs SET ownership = 'SERVER', remoteSongId = :remoteSongId, syncPending = 1, updatedAt = :updatedAt WHERE songId = :songId AND ownership = 'LOCAL' AND isLiked = 1")
    suspend fun promoteLocalLike(songId: String, remoteSongId: String, updatedAt: Long)

    @Query("UPDATE liked_songs SET likedAt = :likedAt, isLiked = 1, ownership = 'SERVER', remoteSongId = :remoteSongId, syncPending = 0, updatedAt = :updatedAt WHERE songId = :songId AND syncPending = 0")
    suspend fun applyServerSnapshotLike(songId: String, remoteSongId: String, likedAt: Long, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSyncedLikes(entities: List<LikedSongEntity>)

    @Query("DELETE FROM liked_songs WHERE songId = :songId")
    suspend fun deleteLikeRow(songId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(entity: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE songId = :songId")
    suspend fun unlike(songId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE songId = :songId AND isLiked = 1)")
    suspend fun isLiked(songId: String): Boolean

    @Transaction
    suspend fun toggle(songId: String, likedAt: Long): Boolean {
        val newState = !isLiked(songId)
        if (newState) like(LikedSongEntity(songId, likedAt)) else unlike(songId)
        return newState
    }
}
