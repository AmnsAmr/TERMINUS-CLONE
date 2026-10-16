package com.necroware.terminusplayer.data.model

/** Exact ordered reconciliation for Navidrome's index-based playlist update API. */
data class PlaylistSyncPlan(
    val nameToSet: String?,
    val songIdsToAdd: List<String>,
    val songIndicesToRemove: List<Int>
) {
    val isEmpty: Boolean get() = nameToSet == null && songIdsToAdd.isEmpty() && songIndicesToRemove.isEmpty()
}

fun planPlaylistSync(
    currentName: String,
    desiredName: String,
    currentSongIds: List<String>,
    desiredSongIds: List<String>
): PlaylistSyncPlan {
    val contentChanged = currentSongIds != desiredSongIds
    return PlaylistSyncPlan(
        nameToSet = desiredName.takeIf { it != currentName },
        songIdsToAdd = desiredSongIds.takeIf { contentChanged }.orEmpty(),
        songIndicesToRemove = if (contentChanged) currentSongIds.indices.reversed().toList() else emptyList()
    )
}
