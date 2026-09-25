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

    @Query("SELECT songId FROM liked_songs")
    fun observeLikedIds(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM liked_songs")
    fun observeLikedSongCount(): Flow<Int>

    @Query("SELECT songId FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getLikedIdsMostRecentFirst(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun like(entity: LikedSongEntity)

    @Query("DELETE FROM liked_songs WHERE songId = :songId")
    suspend fun unlike(songId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE songId = :songId)")
    suspend fun isLiked(songId: String): Boolean

    @Transaction
    suspend fun toggle(songId: String, likedAt: Long): Boolean {
        val newState = !isLiked(songId)
        if (newState) like(LikedSongEntity(songId, likedAt)) else unlike(songId)
        return newState
    }
}
