package com.necroware.terminusplayer.data.model

/** A missing plan is never interpreted as an offline-success result. */
sealed interface PlaylistOfflineAvailability {
    data object Checking : PlaylistOfflineAvailability
    data class Ready(val plan: PlaylistDownloadPlan) : PlaylistOfflineAvailability
    data class Failed(val message: String) : PlaylistOfflineAvailability
}

fun playlistOfflineAvailabilityFromResult(
    result: Result<PlaylistDownloadPlan>
): PlaylistOfflineAvailability = result.fold(
    onSuccess = PlaylistOfflineAvailability::Ready,
    onFailure = { PlaylistOfflineAvailability.Failed(it.message ?: "Could not check local files and downloads.") }
)
