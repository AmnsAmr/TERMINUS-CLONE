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

/** Cancels the shared transfer worker and clears its queue from the system notification. */
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
