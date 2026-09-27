package com.necroware.terminusplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.necroware.terminusplayer.data.database.entity.LyricsCacheEntity

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: LyricsCacheEntity)
}
