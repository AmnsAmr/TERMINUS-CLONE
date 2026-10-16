package com.necroware.terminusplayer.data.repository

/** Links an uploaded local library row to a server row after the server indexes it. */
interface UploadedTrackReconciler {
    suspend fun reconcileUploadedSong(songId: String): Boolean
}
