package com.necroware.terminusplayer.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class LikeSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: MusicRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val providerId = inputData.getString(KEY_PROVIDER_ID) ?: return Result.failure()
        val providerRemoteId = inputData.getString(KEY_PROVIDER_REMOTE_ID) ?: return Result.failure()
        val isLiked = inputData.getBoolean(KEY_IS_LIKED, false)
        return try {
            musicRepository.syncLike(providerId, providerRemoteId, isLiked)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val KEY_PROVIDER_ID = "provider_id"
        const val KEY_PROVIDER_REMOTE_ID = "provider_remote_id"
        const val KEY_IS_LIKED = "is_liked"
    }
}
