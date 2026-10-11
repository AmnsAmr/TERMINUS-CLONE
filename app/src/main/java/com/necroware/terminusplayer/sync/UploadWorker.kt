package com.necroware.terminusplayer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.necroware.terminusplayer.data.repository.NavidromeUploadRepository
import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.necroware.terminusplayer.MainActivity

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val uploadRepository: NavidromeUploadRepository,
    private val uploadQueueDao: UploadQueueDao
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createChannel(notificationManager)
        uploadQueueDao.resetUploadingToWaiting()

        val initialAll = uploadQueueDao.getAll()
        
        // 1. Pre-check for duplicate files
        val pendingItems = initialAll.filter { it.state == "WAITING" || (it.state == "FAILED" && it.attemptCount < 3) }
        if (pendingItems.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            return Result.success()
        }

        val initialNotification = buildOngoingNotification(
            "Preparing upload",
            "Checking ${pendingItems.size} queued files…",
            -1
        ).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
        } else {
            setForeground(ForegroundInfo(NOTIFICATION_ID, initialNotification))
        }

        val pendingUris = pendingItems.map { Uri.parse(it.uriString) }
        val rejectedNames = try {
            uploadRepository.checkBulkUploads(pendingUris)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keep the Room rows waiting and let WorkManager retry this
            // preflight instead of leaving a failed worker with a stuck queue.
            notificationManager.cancel(NOTIFICATION_ID)
            return Result.retry()
        }
        
        var alreadyExistsCount = 0
        if (rejectedNames.isNotEmpty()) {
            for (item in pendingItems) {
                val name = uploadRepository.getFileName(Uri.parse(item.uriString))
                if (name != null && name in rejectedNames) {
                    uploadQueueDao.updateState(item.songId, "ALREADY_EXISTS", item.attemptCount)
                    alreadyExistsCount++
                }
            }
        }

        val total = pendingItems.size
        var completedCount = alreadyExistsCount
        var failedCount = 0

        var currentItem = uploadQueueDao.getNext()

        while (currentItem != null) {
            val item = currentItem
            uploadQueueDao.updateState(item.songId, "UPLOADING", item.attemptCount)
            updateNotification(notificationManager, "Uploading music", "${completedCount + failedCount + 1} / $total files\n\nUploading ${item.title}...", false)

            try {
                val uploadUri = Uri.parse(item.uriString)
                uploadQueueDao.updateState(item.songId, "UPLOADING", item.attemptCount)
                uploadRepository.upload(uploadUri) { progress ->
                    setProgressAsync(workDataOf(KEY_SONG_ID to item.songId, KEY_PROGRESS to progress))
                    updateNotification(notificationManager, "Uploading music", "${completedCount + failedCount + 1} / $total files\n\nUploading ${item.title}\n$progress%", false, progress)
                }
                uploadQueueDao.updateState(item.songId, "SUCCESS", item.attemptCount + 1)
                completedCount++
            } catch (e: CancellationException) {
                // A user stop keeps this item queued so it can be resumed later.
                uploadQueueDao.updateState(item.songId, "WAITING", item.attemptCount)
                notificationManager.cancel(NOTIFICATION_ID)
                throw e
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (msg.contains("already exists", ignoreCase = true) || msg.contains("Server did not accept", ignoreCase = true)) {
                    // It was rejected immediately, mark as ALREADY_EXISTS or PERMANENT_FAILURE without retrying
                    uploadQueueDao.updateStateWithError(item.songId, "ALREADY_EXISTS", item.attemptCount, msg)
                    completedCount++
                } else {
                    val nextAttempt = item.attemptCount + 1
                    if (nextAttempt >= 3) {
                        uploadQueueDao.updateStateWithError(item.songId, "PERMANENT_FAILURE", nextAttempt, msg)
                        failedCount++
                    } else {
                        uploadQueueDao.updateStateWithError(item.songId, "FAILED", nextAttempt, msg)
                        updateNotification(notificationManager, "Upload failed", "Retrying ${item.title} ($nextAttempt/3)", false)
                        delay(2000)
                        // Do not continue here, let it fetch the next item. Since the current one is still FAILED and attempt < 3, it will be fetched again if it's the oldest.
                    }
                }
            }
            
            currentItem = uploadQueueDao.getNext()
        }

        val finalTitle = if (failedCount == 0) "Music upload complete" else "Upload finished"
        val finalBody = if (failedCount == 0) "$completedCount files uploaded (including duplicates)" else "$completedCount uploaded • $failedCount failed"
        
        updateNotification(notificationManager, finalTitle, finalBody, true)
        
        uploadQueueDao.trimHistory()
        
        return Result.success()
    }

    private fun updateNotification(notificationManager: NotificationManager, title: String, text: String, finished: Boolean, progress: Int = -1) {
        val builder = buildOngoingNotification(title, text, progress, finished)
        notificationManager.notify(NOTIFICATION_ID, builder.build())
    }

    private fun buildOngoingNotification(title: String, text: String, progress: Int = -1, finished: Boolean = false): NotificationCompat.Builder {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSubText("TERMINUS · UPLOAD QUEUE")
            .setContentIntent(openAppPendingIntent())

        if (!finished) {
            val cancelIntent = Intent(context, CancelUploadNotificationReceiver::class.java)
                .setAction(CancelUploadNotificationReceiver.ACTION_CANCEL_UPLOADS)
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                CANCEL_REQUEST_CODE,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL QUEUE", cancelPendingIntent)
        }
            
        if (!finished) {
            if (progress >= 0) {
                builder.setProgress(100, progress, false)
            } else {
                builder.setProgress(0, 0, true)
            }
        }
        
        return builder
    }

    private fun createChannel(notificationManager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Uploads", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val CHANNEL_ID = "upload_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_SONG_ID = "upload_song_id"
        const val KEY_PROGRESS = "upload_progress"
        private const val CANCEL_REQUEST_CODE = 1002
        private const val OPEN_APP_REQUEST_CODE = 1003
    }
}
