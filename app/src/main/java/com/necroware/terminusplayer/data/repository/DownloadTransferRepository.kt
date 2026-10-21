package com.necroware.terminusplayer.data.repository

import com.necroware.terminusplayer.util.TransferProgress

interface DownloadTransferRepository {
    suspend fun downloadSong(
        songId: String,
        force: Boolean = false,
        maxBitRate: Int? = null,
        onProgress: ((TransferProgress) -> Unit)? = null
    ): String

    suspend fun downloadSong(
        songId: String,
        force: Boolean,
        onProgress: ((TransferProgress) -> Unit)?
    ): String = downloadSong(songId = songId, force = force, maxBitRate = null, onProgress = onProgress)

    suspend fun cleanupInterruptedDownload(partialUri: String)
}
