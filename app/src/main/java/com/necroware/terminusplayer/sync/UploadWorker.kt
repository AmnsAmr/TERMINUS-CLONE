package com.necroware.terminusplayer.sync

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.necroware.terminusplayer.data.repository.NavidromeUploadRepository
import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import com.necroware.terminusplayer.data.repository.DownloadTransferRepository
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import com.necroware.terminusplayer.data.repository.DuplicateUploadException
import com.necroware.terminusplayer.data.repository.PermanentTransferException
import com.necroware.terminusplayer.data.repository.UploadQuotaExceededException
import com.necroware.terminusplayer.util.TransferProgress
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val uploadRepository: NavidromeUploadRepository,
    private val uploadQueueDao: UploadQueueDao,
    private val downloadRepository: DownloadTransferRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        TransferNotifications.createChannel(context)
        notificationManager.cancel(TransferNotifications.QUEUE_NOTIFICATION_ID)
        uploadQueueDao.resetUploadingToWaiting()

        val initialAll = uploadQueueDao.getAll()
        
        // 1. Pre-check for duplicate files
        var pendingItems = initialAll.filter { it.state == "WAITING" || (it.state == "FAILED" && it.attemptCount < 3) }
        var quotaReached = false
        if (pendingItems.isNotEmpty()) {
            val initialNotification = TransferNotifications.builder(
                context,
                "Preparing transfers",
                "Checking ${pendingItems.size} queued songs…",
                -1,
                false
            ).build()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
            } else {
                setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
            }
        }
        val hasPendingUploads = pendingItems.any { it.transferType == UploadQueueManager.TRANSFER_UPLOAD }
        if (hasPendingUploads) {
            val quota = uploadRepository.quota()
            if (quota != null && quota.total > 0L && quota.used >= quota.total) {
                val message = "Upload quota is full. Free space on the server, then retry these songs."
                uploadQueueDao.markPendingUploadsQuotaExceeded(message)
                quotaReached = true
                pendingItems = pendingItems.filter { it.transferType != UploadQueueManager.TRANSFER_UPLOAD }
            }
        }

        if (pendingItems.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            if (quotaReached) {
                TransferNotifications.postFinished(context, "Uploads paused · quota full", "${initialAll.count { it.transferType == UploadQueueManager.TRANSFER_UPLOAD && it.state in setOf("WAITING", "FAILED", "UPLOADING") }} songs are blocked. Free server space, then retry.")
            } else {
                notificationManager.cancel(TransferNotifications.QUEUE_NOTIFICATION_ID)
            }
            return Result.success()
        }

        val pendingUris = pendingItems.filter { it.transferType == UploadQueueManager.TRANSFER_UPLOAD }.map { Uri.parse(it.uriString) }
        val rejectedNames = try {
            uploadRepository.checkBulkUploads(pendingUris)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the Room rows waiting and let WorkManager retry this
            // preflight instead of leaving a failed worker with a stuck queue.
            pendingItems.filter { it.transferType == UploadQueueManager.TRANSFER_UPLOAD }.forEach {
                uploadQueueDao.updateStateWithError(it.songId, "WAITING", it.attemptCount, "Duplicate check failed: ${e.message ?: "server unavailable"}")
            }
            notificationManager.cancel(NOTIFICATION_ID)
            TransferNotifications.postQueued(context, pendingItems.size, "Duplicate check failed · retry scheduled\n${e.message ?: "Server unavailable"}")
            return Result.retry()
        }
        
        var alreadyExistsCount = 0
        if (rejectedNames.isNotEmpty()) {
            for (item in pendingItems) {
                if (item.transferType != UploadQueueManager.TRANSFER_UPLOAD) continue
                val name = uploadRepository.getFileName(Uri.parse(item.uriString))
                if (name != null && name in rejectedNames) {
                    uploadQueueDao.updateState(item.songId, "ALREADY_EXISTS", item.attemptCount)
                    alreadyExistsCount++
                }
            }
        }

        var total = pendingItems.size
        var completedCount = alreadyExistsCount
        var failedCount = 0
        val failureSummaries = mutableListOf<String>()

        var currentItem = uploadQueueDao.getNext()

        while (currentItem != null) {
            val item = currentItem
            total = maxOf(total, completedCount + failedCount + uploadQueueDao.countPending())
            val queuePosition = completedCount + failedCount + 1
            val isDownload = item.transferType == UploadQueueManager.TRANSFER_DOWNLOAD
            val activeState = if (isDownload) "DOWNLOADING" else "UPLOADING"
            val claimed = uploadQueueDao.markTransferStarted(item.songId, activeState, item.attemptCount, item.totalBytes, System.currentTimeMillis())
            if (claimed == 0) {
                currentItem = uploadQueueDao.getNext()
                continue
            }
            val operation = if (isDownload) "Downloading" else "Uploading"
            if (isDownload && item.partialUri != null) {
                try {
                    downloadRepository.cleanupInterruptedDownload(item.partialUri)
                    uploadQueueDao.clearPartialUri(item.songId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    uploadQueueDao.updateStateWithError(item.songId, "PERMANENT_FAILURE", item.attemptCount + 1, "Could not remove incomplete download: ${e.message ?: "unknown error"}")
                    failedCount++
                    currentItem = uploadQueueDao.getNext()
                    continue
                }
            }
            setProgressAsync(workDataOf(
                KEY_SONG_ID to item.songId,
                KEY_PROGRESS to -1,
                KEY_BYTES to 0L,
                KEY_TOTAL_BYTES to (item.totalBytes ?: -1L)
            ))
            updateNotification(notificationManager, operation, "$queuePosition / $total songs\n${item.title}\nStarting transfer…", false)

            try {
                runTrackedTransfer(item.songId, notificationManager, operation, item.title, queuePosition, total) { reportProgress ->
                    if (isDownload) {
                        val remoteSongId = item.remoteSongId ?: throw IOException("Download is missing its server track ID")
                        downloadRepository.downloadSong(remoteSongId, item.forceDownload, reportProgress)
                    } else {
                        uploadRepository.upload(Uri.parse(item.uriString), reportProgress)
                    }
                }
                uploadQueueDao.clearPartialUri(item.songId)
                uploadQueueDao.updateState(item.songId, "SUCCESS", item.attemptCount + 1)
                completedCount++
            } catch (e: CancellationException) {
                // A user stop keeps this item queued so it can be resumed later.
                uploadQueueDao.markInterrupted(item.songId, "Transfer cancelled; queued to resume")
                notificationManager.cancel(NOTIFICATION_ID)
                throw e
            } catch (e: UploadQuotaExceededException) {
                val message = "Upload quota is full. Free space on the server, then retry these songs."
                uploadQueueDao.markPendingUploadsQuotaExceeded(message)
                quotaReached = true
            } catch (e: Exception) {
                uploadQueueDao.clearPartialUri(item.songId)
                val msg = e.message ?: ""
                if (!isDownload && e is DuplicateUploadException) {
                    uploadQueueDao.updateStateWithError(item.songId, "ALREADY_EXISTS", item.attemptCount, msg)
                    completedCount++
                } else if (e is PermanentTransferException) {
                    val attempts = item.attemptCount + 1
                    uploadQueueDao.updateStateWithError(item.songId, "PERMANENT_FAILURE", attempts, msg)
                    failedCount++
                    failureSummaries += "${item.title}: ${msg.ifBlank { "Transfer failed" }}"
                } else {
                    val nextAttempt = item.attemptCount + 1
                    if (nextAttempt >= 3) {
                        uploadQueueDao.updateStateWithError(item.songId, "PERMANENT_FAILURE", nextAttempt, msg)
                        failedCount++
                        failureSummaries += "${item.title}: ${msg.ifBlank { "Transfer failed" }}"
                    } else {
                        uploadQueueDao.updateStateWithError(item.songId, "FAILED", nextAttempt, msg)
                        updateNotification(notificationManager, "$operation retrying", "$queuePosition / $total songs\n${item.title}\nAttempt $nextAttempt of 3 · ${msg.ifBlank { "Temporary transfer error" }}", false)
                        delay(1000L shl (nextAttempt - 1))
                        // Do not continue here, let it fetch the next item. Since the current one is still FAILED and attempt < 3, it will be fetched again if it's the oldest.
                    }
                }
            }
            
            currentItem = uploadQueueDao.getNext()
        }

        val finalTitle = when {
            quotaReached -> "Uploads paused · quota full"
            failedCount == 0 -> "Transfers complete"
            else -> "Transfers finished with errors"
        }
        val finalBody = buildString {
            if (quotaReached) append("Quota reached; remaining uploads are blocked. Free server space, then retry.\n")
            append("$completedCount completed · $failedCount failed")
            failureSummaries.take(2).forEach { append("\n").append(it) }
        }
        
        updateNotification(notificationManager, finalTitle, finalBody, true)
        
        uploadQueueDao.trimHistory()
        
        return Result.success()
    }

    private suspend fun runTrackedTransfer(
        songId: String,
        notificationManager: NotificationManager,
        operation: String,
        title: String,
        position: Int,
        total: Int,
        transfer: suspend ((TransferProgress) -> Unit) -> Unit
    ) = coroutineScope {
        val progressChannel = Channel<TransferProgress>(Channel.UNLIMITED)
        val progressJob = launch {
            for (progress in progressChannel) {
                val now = System.currentTimeMillis()
                uploadQueueDao.updateProgress(songId, progress.bytesTransferred, progress.totalBytes, progress.partialUri, now)
                val percent = progress.totalBytes?.takeIf { it > 0L }
                    ?.let { ((progress.bytesTransferred * 100L) / it).toInt().coerceIn(0, 100) }
                setProgressAsync(workDataOf(
                    KEY_SONG_ID to songId,
                    KEY_PROGRESS to (percent ?: -1),
                    KEY_BYTES to progress.bytesTransferred,
                    KEY_TOTAL_BYTES to (progress.totalBytes ?: -1L)
                ))
                val progressLabel = percent?.let { "$it%" } ?: "${formatBytes(progress.bytesTransferred)} transferred"
                val byteLabel = progress.totalBytes?.let { "${formatBytes(progress.bytesTransferred)} / ${formatBytes(it)}" }
                    ?: "${formatBytes(progress.bytesTransferred)} sent or received"
                updateNotification(
                    notificationManager,
                    operation,
                    "$position / $total songs\n$title\n$progressLabel · $byteLabel",
                    false,
                    percent ?: -1
                )
            }
        }
        try {
            transfer { progress -> progressChannel.trySend(progress) }
        } finally {
            progressChannel.close()
            progressJob.join()
        }
    }

    private fun formatBytes(bytes: Long): String = android.text.format.Formatter.formatShortFileSize(context, bytes)

    private fun updateNotification(notificationManager: NotificationManager, title: String, text: String, finished: Boolean, progress: Int = -1) {
        if (finished) {
            TransferNotifications.postFinished(context, title, text)
            return
        }
        val notification = TransferNotifications.builder(context, title, text, progress, finished).build()
        notificationManager.notify(TransferNotifications.NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = TransferNotifications.CHANNEL_ID
        const val NOTIFICATION_ID = TransferNotifications.NOTIFICATION_ID
        const val KEY_SONG_ID = "upload_song_id"
        const val KEY_PROGRESS = "upload_progress"
        const val KEY_BYTES = "transfer_bytes"
        const val KEY_TOTAL_BYTES = "transfer_total_bytes"
    }
}
