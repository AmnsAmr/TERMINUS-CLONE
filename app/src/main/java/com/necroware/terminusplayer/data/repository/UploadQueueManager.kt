package com.necroware.terminusplayer.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.sync.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueDao: UploadQueueDao
) {
    suspend fun enqueueUploads(songs: List<Song>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = songs.mapIndexed { index, song ->
            UploadQueueEntity(
                songId = song.id,
                uriString = song.uriString,
                state = "WAITING",
                attemptCount = 0,
                addedAtEpochMs = now + index,
                title = song.title
            )
        }
        uploadQueueDao.insertAll(entities)
        startWorker()
    }

    suspend fun retryUpload(songId: String) = withContext(Dispatchers.IO) {
        uploadQueueDao.resetForRetry(songId)
        startWorker()
    }

    suspend fun retryAllFailed() = withContext(Dispatchers.IO) {
        val all = uploadQueueDao.getAll()
        all.filter { it.state == "PERMANENT_FAILURE" || it.state == "CONVERSION_FAILED" }.forEach {
            uploadQueueDao.resetForRetry(it.songId)
        }
        startWorker()
    }

    suspend fun cancelAllUploads() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        uploadQueueDao.clearAll()
    }

    suspend fun removeFromQueue(songId: String) = withContext(Dispatchers.IO) {
        uploadQueueDao.removeIfNotUploading(songId)
    }

    fun schedulePendingUploads() = startWorker()

    suspend fun resumePendingUploads() = withContext(Dispatchers.IO) {
        val hasPendingWork = uploadQueueDao.getAll().any {
            it.state == "WAITING" || it.state == "FAILED" || it.state == "UPLOADING" || it.state == "CONVERTING"
        }
        if (hasPendingWork) {
            val request = uploadWorkRequest()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }

    private fun startWorker() {
        val request = uploadWorkRequest()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    private fun uploadWorkRequest() = OneTimeWorkRequestBuilder<UploadWorker>()
        .setConstraints(
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
        )
        .build()

    companion object {
        const val UNIQUE_WORK_NAME = "UploadQueueWorker"
    }
}
