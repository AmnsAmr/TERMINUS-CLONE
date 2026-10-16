package com.necroware.terminusplayer.data.model

/** Chooses only verified local files, then falls back to the Navidrome stream. */
fun resolveTrackPlaybackUri(
    downloadedUri: String?,
    downloadedAvailable: Boolean,
    providerId: String,
    localUri: String,
    localAvailable: Boolean,
    serverStreamUri: String?
): String? = when {
    downloadedAvailable && !downloadedUri.isNullOrBlank() -> downloadedUri
    providerId == "local" && localAvailable -> localUri
    else -> serverStreamUri
}
