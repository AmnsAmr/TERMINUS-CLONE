package com.necroware.terminusplayer.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

/** Best-effort lyric warmup runs independently so its errors cannot fail a completed download. */
@HiltWorker
class LyricsCacheWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val repository: MusicRepository
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val songId = inputData.getString(KEY_SONG_ID) ?: return Result.failure(
            workDataOf(KEY_ERROR to "Missing song identity")
        )
        return try {
            repository.warmLyricsCache(songId)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Lyrics cache warmup failed")))
        }
    }

    companion object {
        const val KEY_SONG_ID = "lyrics_song_id"
        const val KEY_ERROR = "lyrics_cache_error"
    }
}
