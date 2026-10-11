package com.necroware.terminusplayer.data.repository

import com.necroware.terminusplayer.util.TransferProgress

interface DownloadTransferRepository {
    suspend fun downloadSong(
        songId: String,
        force: Boolean = false,
        onProgress: ((TransferProgress) -> Unit)? = null
    ): String

    suspend fun cleanupInterruptedDownload(partialUri: String)
}
