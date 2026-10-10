package com.necroware.terminusplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UploadQueueDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<UploadQueueEntity>)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: UploadQueueEntity)

    @Query("SELECT * FROM upload_queue ORDER BY addedAtEpochMs ASC")
    fun observeAll(): Flow<List<UploadQueueEntity>>
    
    @Query("SELECT * FROM upload_queue ORDER BY addedAtEpochMs ASC")
    suspend fun getAll(): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue WHERE state IN ('WAITING', 'FAILED') AND attemptCount < 3 ORDER BY addedAtEpochMs ASC LIMIT 1")
    suspend fun getNext(): UploadQueueEntity?

    @Query("UPDATE upload_queue SET state = :state, attemptCount = :attemptCount WHERE songId = :songId")
    suspend fun updateState(songId: String, state: String, attemptCount: Int)

    @Query("UPDATE upload_queue SET state = :state, attemptCount = :attemptCount, forceConvert = :forceConvert WHERE songId = :songId")
    suspend fun updateStateWithForceConvert(songId: String, state: String, attemptCount: Int, forceConvert: Boolean)

    @Query("UPDATE upload_queue SET state = :state, attemptCount = :attemptCount, errorMessage = :errorMessage WHERE songId = :songId")
    suspend fun updateStateWithError(songId: String, state: String, attemptCount: Int, errorMessage: String?)

    @Query("UPDATE upload_queue SET state = 'WAITING' WHERE state IN ('UPLOADING', 'CONVERTING')")
    suspend fun resetUploadingToWaiting()
    
    @Query("DELETE FROM upload_queue WHERE state IN ('SUCCESS', 'ALREADY_EXISTS') AND songId NOT IN (SELECT songId FROM upload_queue WHERE state IN ('SUCCESS', 'ALREADY_EXISTS') ORDER BY addedAtEpochMs DESC LIMIT 50)")
    suspend fun trimHistory()

    @Query("DELETE FROM upload_queue")
    suspend fun clearAll()

    @Query("DELETE FROM upload_queue WHERE songId = :songId AND state != 'UPLOADING'")
    suspend fun removeIfNotUploading(songId: String)
}
