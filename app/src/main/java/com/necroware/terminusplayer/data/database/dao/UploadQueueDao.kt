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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(items: List<UploadQueueEntity>)

    @Query("SELECT * FROM upload_queue ORDER BY addedAtEpochMs ASC")
    fun observeAll(): Flow<List<UploadQueueEntity>>
    
    @Query("SELECT * FROM upload_queue ORDER BY addedAtEpochMs ASC")
    suspend fun getAll(): List<UploadQueueEntity>

    @Query("SELECT * FROM upload_queue WHERE state IN ('WAITING', 'FAILED') AND attemptCount < 3 ORDER BY addedAtEpochMs ASC LIMIT 1")
    suspend fun getNext(): UploadQueueEntity?

    @Query("SELECT COUNT(*) FROM upload_queue WHERE state IN ('WAITING', 'FAILED', 'UPLOADING', 'DOWNLOADING', 'CONVERTING')")
    suspend fun countPending(): Int

    @Query("UPDATE upload_queue SET state = :state, attemptCount = :attemptCount, phase = :state WHERE songId = :songId")
    suspend fun updateState(songId: String, state: String, attemptCount: Int)

    @Query("UPDATE upload_queue SET state = 'WAITING', attemptCount = 0, forceConvert = 0, bytesTransferred = 0, totalBytes = NULL, startedAtEpochMs = NULL, lastProgressAtEpochMs = NULL, phase = 'QUEUED', errorMessage = NULL WHERE songId = :songId")
    suspend fun resetForRetry(songId: String)

    @Query("UPDATE upload_queue SET state = :state, attemptCount = :attemptCount, errorMessage = :errorMessage, phase = CASE WHEN :state = 'FAILED' THEN 'RETRYING' WHEN :state = 'ALREADY_EXISTS' THEN 'ALREADY_EXISTS' WHEN :state = 'WAITING' THEN 'WAITING' ELSE 'FAILED' END WHERE songId = :songId")
    suspend fun updateStateWithError(songId: String, state: String, attemptCount: Int, errorMessage: String?)

    @Query("UPDATE upload_queue SET state = :state, phase = 'TRANSFERRING', attemptCount = :attemptCount, errorMessage = NULL, bytesTransferred = 0, totalBytes = :totalBytes, startedAtEpochMs = COALESCE(startedAtEpochMs, :now), lastProgressAtEpochMs = :now WHERE songId = :songId")
    suspend fun markTransferStarted(songId: String, state: String, attemptCount: Int, totalBytes: Long?, now: Long): Int

    @Query("UPDATE upload_queue SET bytesTransferred = :bytesTransferred, totalBytes = :totalBytes, partialUri = COALESCE(:partialUri, partialUri), lastProgressAtEpochMs = :now, phase = 'TRANSFERRING' WHERE songId = :songId")
    suspend fun updateProgress(songId: String, bytesTransferred: Long, totalBytes: Long?, partialUri: String?, now: Long)

    @Query("UPDATE upload_queue SET partialUri = NULL WHERE songId = :songId")
    suspend fun clearPartialUri(songId: String)

    @Query("UPDATE upload_queue SET phase = :phase WHERE songId = :songId")
    suspend fun updatePhase(songId: String, phase: String)

    @Query("UPDATE upload_queue SET state = 'WAITING', phase = 'INTERRUPTED', errorMessage = :errorMessage WHERE songId = :songId")
    suspend fun markInterrupted(songId: String, errorMessage: String)

    @Query("UPDATE upload_queue SET state = 'WAITING', phase = 'INTERRUPTED', errorMessage = 'Transfer interrupted; queued to resume' WHERE state IN ('UPLOADING', 'DOWNLOADING', 'CONVERTING')")
    suspend fun resetUploadingToWaiting()
    
    @Query("DELETE FROM upload_queue WHERE state IN ('SUCCESS', 'ALREADY_EXISTS') AND songId NOT IN (SELECT songId FROM upload_queue WHERE state IN ('SUCCESS', 'ALREADY_EXISTS') ORDER BY addedAtEpochMs DESC LIMIT 50)")
    suspend fun trimHistory()

    @Query("DELETE FROM upload_queue")
    suspend fun clearAll()

    @Query("DELETE FROM upload_queue WHERE songId = :songId AND state NOT IN ('UPLOADING', 'DOWNLOADING')")
    suspend fun removeIfNotUploading(songId: String)
}
