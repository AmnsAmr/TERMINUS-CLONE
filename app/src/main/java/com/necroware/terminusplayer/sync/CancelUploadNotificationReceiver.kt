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

/** Stops the worker from the system notification and leaves queued items available to resume. */
@AndroidEntryPoint
class CancelUploadNotificationReceiver : BroadcastReceiver() {
    @Inject lateinit var uploadQueueManager: UploadQueueManager

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_CANCEL_UPLOADS) {
            val pendingResult = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    uploadQueueManager.cancelAllUploads()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_CANCEL_UPLOADS = "com.necroware.terminusplayer.action.CANCEL_UPLOADS"
    }
}
