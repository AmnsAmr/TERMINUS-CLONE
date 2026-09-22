package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.SyncedLyrics

interface MediaProvider {
    val providerId: String

    suspend fun syncLibrary(): List<SongEntity>

    suspend fun resolveStreamUrl(remoteId: String): String

    suspend fun scrobble(remoteId: String)

    suspend fun toggleLike(remoteId: String, isLiked: Boolean)

    suspend fun getLyrics(remoteId: String): SyncedLyrics?
}
