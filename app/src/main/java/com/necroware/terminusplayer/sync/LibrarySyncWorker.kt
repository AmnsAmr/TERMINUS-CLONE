package com.necroware.terminusplayer.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

@HiltWorker
class LibrarySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: MusicRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val result = musicRepository.syncLibrary()
            when {
                result.retryableFailures.isNotEmpty() -> Result.retry()
                result.failedProviders.isNotEmpty() -> Result.failure()
                else -> Result.success()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
