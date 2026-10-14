package com.necroware.terminusplayer.sync

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.necroware.terminusplayer.MainActivity

object TransferNotifications {
    const val CHANNEL_ID = "upload_channel"
    const val NOTIFICATION_ID = 1001
    const val QUEUE_NOTIFICATION_ID = 1004
    private const val CANCEL_REQUEST_CODE = 1002
    private const val OPEN_APP_REQUEST_CODE = 1003
    private const val PAUSE_REQUEST_CODE = 1005

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Transfers", NotificationManager.IMPORTANCE_LOW))
        }
    }

    fun postQueued(context: Context, pendingCount: Int, detail: String? = null, paused: Boolean = false) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(
            QUEUE_NOTIFICATION_ID,
            builder(
                context,
                when {
                    paused -> "Transfers paused"
                    detail != null -> "Transfer retry scheduled"
                    else -> "Transfers queued"
                },
                detail ?: "$pendingCount songs queued · waiting for network or Android scheduling",
                -1,
                false,
                paused
            ).build()
        )
    }

    fun postFinished(context: Context, title: String, text: String) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        createChannel(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(QUEUE_NOTIFICATION_ID, builder(context, title, text, -1, true).build())
        manager.cancel(NOTIFICATION_ID)
    }

    fun builder(context: Context, title: String, text: String, progress: Int, finished: Boolean, paused: Boolean = false): NotificationCompat.Builder {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(if (title.contains("download", ignoreCase = true)) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_upload)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setSubText("TERMINUS · TRANSFERS")
            .setContentIntent(PendingIntent.getActivity(
                context,
                OPEN_APP_REQUEST_CODE,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            ))

        if (!finished) {
            val cancelIntent = Intent(context, CancelUploadNotificationReceiver::class.java)
                .setAction(CancelUploadNotificationReceiver.ACTION_CANCEL_UPLOADS)
            val cancelPendingIntent = PendingIntent.getBroadcast(
                context,
                CANCEL_REQUEST_CODE,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "CANCEL TRANSFERS", cancelPendingIntent)
            val controlIntent = Intent(context, CancelUploadNotificationReceiver::class.java)
                .setAction(if (paused) CancelUploadNotificationReceiver.ACTION_RESUME_TRANSFERS else CancelUploadNotificationReceiver.ACTION_PAUSE_TRANSFERS)
            val controlPendingIntent = PendingIntent.getBroadcast(
                context,
                PAUSE_REQUEST_CODE,
                controlIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (paused) "RESUME" else "PAUSE",
                controlPendingIntent
            )
            if (progress >= 0) builder.setProgress(100, progress, false) else builder.setProgress(0, 0, true)
        }
        return builder
    }
}
