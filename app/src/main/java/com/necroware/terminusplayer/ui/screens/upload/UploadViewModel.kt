package com.necroware.terminusplayer.ui.screens.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.necroware.terminusplayer.data.api.custom.UploadQuotaResponse
import com.necroware.terminusplayer.data.repository.NavidromeUploadRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import com.necroware.terminusplayer.data.repository.UploadQueueManager
import com.necroware.terminusplayer.data.database.dao.UploadQueueDao
import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.database.entity.UploadQueueEntity
import com.necroware.terminusplayer.sync.UploadWorker
import com.necroware.terminusplayer.data.repository.toSong
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadRepository: NavidromeUploadRepository,
    private val musicRepository: MusicRepository,
    private val songDao: com.necroware.terminusplayer.data.database.dao.SongDao,
    private val uploadQueueDao: UploadQueueDao,
    private val uploadQueueManager: UploadQueueManager
) : ViewModel() {
    private val _quota = MutableStateFlow<UploadQuotaResponse?>(null)
    val quota = _quota.asStateFlow()

    private val _uploadStatus = MutableStateFlow("")
    val uploadStatus = _uploadStatus.asStateFlow()
    
    private val _recommendedSongs = MutableStateFlow<List<Song>>(emptyList())
    val recommendedSongs = _recommendedSongs.asStateFlow()

    private val _queue = MutableStateFlow<List<UploadQueueEntity>>(emptyList())
    val queue = _queue.asStateFlow()

    private val _activeProgress = MutableStateFlow<Pair<String, Int>?>(null)
    val activeProgress = _activeProgress.asStateFlow()

    init { 
        fetchQuota() 

        viewModelScope.launch {
            uploadQueueDao.observeAll().collect { _queue.value = it }
        }

        viewModelScope.launch {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkFlow(UploadQueueManager.UNIQUE_WORK_NAME)
                .collect { workInfos ->
                    val running = workInfos.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    val songId = running?.progress?.getString(UploadWorker.KEY_SONG_ID)
                    val progress = running?.progress?.getInt(UploadWorker.KEY_PROGRESS, -1) ?: -1
                    _activeProgress.value = if (songId != null && progress >= 0) songId to progress else null
                }
        }
        
        viewModelScope.launch {
            combine(
                songDao.observeAllSongs(),
                uploadQueueDao.observeAll()
            ) { allRawSongs, queue ->
                val serverSongs = allRawSongs.filter { it.providerId == "navidrome" }
                val localSongs = allRawSongs.filter { it.providerId == "local" }

                val serverTracks = serverSongs.asSequence()
                    .map { it.title.lowercase(java.util.Locale.ROOT) to it.artist.lowercase(java.util.Locale.ROOT) }
                    .toHashSet()
                val notOnServer = localSongs.filter { localSong ->
                    (localSong.title.lowercase(java.util.Locale.ROOT) to localSong.artist.lowercase(java.util.Locale.ROOT)) !in serverTracks
                }.map { it.toSong() }

                val queuedIds = queue.map { it.songId }.toSet()
                notOnServer.filter { it.id !in queuedIds }
            }.flowOn(Dispatchers.Default).collect {
                _recommendedSongs.value = it
            }
        }
    }

    private fun fetchQuota() = viewModelScope.launch {
        _quota.value = uploadRepository.quota()
    }

    fun uploadFile(uri: Uri) = viewModelScope.launch {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
                }
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                ?: "Selected audio file"
            val songId = "upload-file:${uri}"
            uploadQueueDao.insert(
                UploadQueueEntity(
                    songId = songId,
                    uriString = uri.toString(),
                    state = "WAITING",
                    attemptCount = 0,
                    addedAtEpochMs = System.currentTimeMillis(),
                    title = name
                )
            )
            uploadQueueManager.schedulePendingUploads()
            _uploadStatus.value = "Added $name to the upload queue."
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uploadStatus.value = "Could not queue file: ${e.message ?: "Unknown error"}"
        }
    }

    fun retryUpload(songId: String) = viewModelScope.launch { uploadQueueManager.retryUpload(songId) }
    fun retryAllFailed() = viewModelScope.launch { uploadQueueManager.retryAllFailed() }
    fun removeFromQueue(songId: String) = viewModelScope.launch { uploadQueueManager.removeFromQueue(songId) }
    fun cancelAllUploads() = viewModelScope.launch { uploadQueueManager.cancelAllUploads() }
    
    fun uploadRecommended() {
        val songsToUpload = recommendedSongs.value
        if (songsToUpload.isNotEmpty()) {
            viewModelScope.launch {
                uploadQueueManager.enqueueUploads(songsToUpload)
                _uploadStatus.value = "Queued ${songsToUpload.size} recommended songs."
            }
        }
    }
}
