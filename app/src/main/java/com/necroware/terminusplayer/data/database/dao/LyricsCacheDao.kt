package com.necroware.terminusplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.necroware.terminusplayer.data.database.entity.LyricsCacheEntity
import com.necroware.terminusplayer.data.database.entity.LyricsCacheFileEntity

@Dao
interface LyricsCacheDao {
    @Query("SELECT * FROM lyrics_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun get(cacheKey: String): LyricsCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: LyricsCacheEntity)

    @Transaction
    suspend fun putLocalOnly(entry: LyricsCacheEntity, fileIdentity: String) {
        put(entry)
        putFileReference(LyricsCacheFileEntity(fileIdentity, entry.cacheKey))
        pruneOrphanedLocalLyrics()
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putFileReference(reference: LyricsCacheFileEntity)

    @Query("DELETE FROM lyrics_cache_files WHERE fileIdentity = :fileIdentity")
    suspend fun removeFileReference(fileIdentity: String)

    @Query("DELETE FROM lyrics_cache WHERE cacheKey LIKE 'local:%' AND cacheKey NOT IN (SELECT cacheKey FROM lyrics_cache_files)")
    suspend fun pruneOrphanedLocalLyrics()

    @Transaction
    suspend fun cleanupFileReference(fileIdentity: String) {
        removeFileReference(fileIdentity)
        pruneOrphanedLocalLyrics()
    }

    @Query("SELECT EXISTS(SELECT 1 FROM lyrics_cache_files WHERE fileIdentity = :fileIdentity)")
    suspend fun hasFileReference(fileIdentity: String): Boolean
}
