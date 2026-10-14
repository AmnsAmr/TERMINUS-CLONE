package com.necroware.terminusplayer.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Cancels pending transfers from the system notification while retaining completed history. */
@AndroidEntryPoint
class CancelUploadNotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var uploadQueueManager: UploadQueueManager

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action == ACTION_CANCEL_UPLOADS || action == ACTION_PAUSE_TRANSFERS || action == ACTION_RESUME_TRANSFERS) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    when (action) {
                        ACTION_CANCEL_UPLOADS -> uploadQueueManager.cancelAllUploads()
                        ACTION_PAUSE_TRANSFERS -> uploadQueueManager.pauseTransfers()
                        ACTION_RESUME_TRANSFERS -> uploadQueueManager.resumeTransfers()
                    }
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_UPLOADS = "com.necroware.terminusplayer.action.CANCEL_UPLOADS"
        const val ACTION_PAUSE_TRANSFERS = "com.necroware.terminusplayer.action.PAUSE_TRANSFERS"
        const val ACTION_RESUME_TRANSFERS = "com.necroware.terminusplayer.action.RESUME_TRANSFERS"
    }
}
