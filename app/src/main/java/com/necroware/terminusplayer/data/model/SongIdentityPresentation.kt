package com.necroware.terminusplayer.data.model

import com.necroware.terminusplayer.data.database.entity.SongEntity

/**
 * Collapses a local file and its Navidrome row only when Room contains an
 * explicit, unique remote-ID association. Display metadata is not identity.
 */
fun collapseExplicitlyLinkedSongs(songs: List<SongEntity>): List<SongEntity> {
    val localRowsByServerId = songs.asSequence()
        .filter { it.providerId == "local" && !it.navidromeId.isNullOrBlank() }
        .groupBy { it.navidromeId.orEmpty() }
    val serverRowsById = songs.asSequence()
        .filter { it.providerId == "navidrome" }
        .groupBy { it.providerRemoteId }

    val serverIdsRepresentedByUniqueLocalRows = localRowsByServerId.mapNotNull { (serverId, localRows) ->
        if (localRows.size == 1 && serverRowsById[serverId].orEmpty().size == 1) serverId else null
    }.toHashSet()

    return songs.filterNot { song ->
        song.providerId == "navidrome" && song.providerRemoteId in serverIdsRepresentedByUniqueLocalRows
    }
}
