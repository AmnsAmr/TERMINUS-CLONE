package com.necroware.terminusplayer.data.repository

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import android.net.Uri
import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.sync.UploadWorker
import com.necroware.terminusplayer.sync.TransferNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadQueueManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueDao: UploadQueueDao,
    private val userPreferencesRepository: UserPreferencesRepository
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
        if (entities.isNotEmpty()) {
            uploadQueueDao.insertIfAbsent(entities)
            startWorker()
        }
    }

    suspend fun enqueueUploadFile(uri: Uri, title: String) = withContext(Dispatchers.IO) {
        uploadQueueDao.insertIfAbsent(listOf(
            UploadQueueEntity(
                songId = "upload-file:$uri",
                uriString = uri.toString(),
                state = "WAITING",
                attemptCount = 0,
                addedAtEpochMs = System.currentTimeMillis(),
                title = title
            )
        ))
        startWorker()
    }

    suspend fun enqueueDownloads(songs: List<Song>, force: Boolean = false) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val entities = songs.distinctBy { it.id }
            .filter { it.providerId == "navidrome" || !it.navidromeId.isNullOrBlank() }
            .mapIndexed { index, song ->
            UploadQueueEntity(
                songId = downloadQueueId(song.id),
                remoteSongId = song.id,
                uriString = song.uriString,
                transferType = TRANSFER_DOWNLOAD,
                state = "WAITING",
                attemptCount = 0,
                addedAtEpochMs = now + index,
                title = "${song.artist} - ${song.title}",
                forceDownload = force
            )
        }
        if (entities.isNotEmpty()) {
            val existing = uploadQueueDao.getAll().associateBy { it.songId }
            val eligible = entities.filter { queued ->
                val previous = existing[queued.songId]
                previous == null || (force && previous.state in setOf("SUCCESS", "ALREADY_EXISTS", "PERMANENT_FAILURE", "CONVERSION_FAILED"))
            }
            val replacements = eligible.filter { it.songId in existing }
            val newItems = eligible.filterNot { it.songId in existing }
            if (newItems.isNotEmpty()) uploadQueueDao.insertIfAbsent(newItems)
            if (replacements.isNotEmpty()) uploadQueueDao.insertAll(replacements)
            if (eligible.isNotEmpty()) {
            startWorker()
            }
        }
    }

    suspend fun retryUpload(songId: String) = withContext(Dispatchers.IO) {
        uploadQueueDao.resetForRetry(songId)
        startWorker()
    }

    suspend fun retryAllFailed() = withContext(Dispatchers.IO) {
        val all = uploadQueueDao.getAll()
        all.filter { it.state == "PERMANENT_FAILURE" || it.state == "CONVERSION_FAILED" || it.state == "QUOTA_EXCEEDED" }.forEach {
            uploadQueueDao.resetForRetry(it.songId)
        }
        startWorker()
    }

    suspend fun cancelAllUploads() = withContext(Dispatchers.IO) {
        userPreferencesRepository.setTransfersPaused(false)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        // Keep terminal rows as deduplication markers. Re-adding the same songs
        // after a cancellation must not queue tracks that already reached the server.
        uploadQueueDao.clearUnfinished()
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
            .apply {
                cancel(TransferNotifications.NOTIFICATION_ID)
                cancel(TransferNotifications.QUEUE_NOTIFICATION_ID)
            }
    }

    suspend fun cancelAllTransfers() = cancelAllUploads()

    suspend fun pauseTransfers() = withContext(Dispatchers.IO) {
        userPreferencesRepository.setTransfersPaused(true)
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        val pendingCount = uploadQueueDao.getAll().count {
            it.state in setOf("WAITING", "FAILED", "UPLOADING", "DOWNLOADING", "CONVERTING")
        }
        context.getSystemService(Context.NOTIFICATION_SERVICE)?.let { service ->
            val manager = service as android.app.NotificationManager
            manager.cancel(TransferNotifications.NOTIFICATION_ID)
            if (pendingCount > 0) TransferNotifications.postQueued(context, pendingCount, "Transfers paused. Resume from the app when ready.", paused = true)
            else manager.cancel(TransferNotifications.QUEUE_NOTIFICATION_ID)
        }
    }

    suspend fun resumeTransfers() = withContext(Dispatchers.IO) {
        userPreferencesRepository.setTransfersPaused(false)
        startWorker()
    }

    suspend fun removeFromQueue(songId: String) = withContext(Dispatchers.IO) {
        uploadQueueDao.removeIfNotUploading(songId)
    }

    suspend fun schedulePendingUploads() = withContext(Dispatchers.IO) { startWorker() }

    suspend fun resumePendingUploads() = withContext(Dispatchers.IO) {
        if (userPreferencesRepository.transfersPaused.first()) return@withContext
        val hasPendingWork = uploadQueueDao.getAll().any {
            it.state == "WAITING" || it.state == "FAILED" || it.state == "UPLOADING" || it.state == "DOWNLOADING" || it.state == "CONVERTING"
        }
        if (hasPendingWork) {
            startWorker(ExistingWorkPolicy.KEEP)
        }
    }

    private suspend fun startWorker(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        if (userPreferencesRepository.transfersPaused.first()) return
        val workManager = WorkManager.getInstance(context)
        val wasRunning = workManager.getWorkInfosForUniqueWork(UNIQUE_WORK_NAME).get()
            .any { it.state == androidx.work.WorkInfo.State.RUNNING }
        val request = uploadWorkRequest()
        workManager.enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            policy,
            request
        )
        if (!wasRunning) {
            val pendingCount = uploadQueueDao.getAll().count {
                it.state == "WAITING" || it.state == "FAILED" || it.state == "UPLOADING" || it.state == "DOWNLOADING"
            }
            if (pendingCount > 0) TransferNotifications.postQueued(context, pendingCount)
        }
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
        const val TRANSFER_UPLOAD = "UPLOAD"
        const val TRANSFER_DOWNLOAD = "DOWNLOAD"
        fun downloadQueueId(songId: String) = "download:$songId"
    }
}
