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
class PlaylistSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val musicRepository: MusicRepository
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        if (musicRepository.syncPendingPlaylists()) Result.success() else Result.retry()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        Result.retry()
    }
}
