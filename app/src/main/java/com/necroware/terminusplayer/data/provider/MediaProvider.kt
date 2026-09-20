package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.database.entity.SongEntity

interface MediaProvider {
    val providerId: String

    suspend fun syncLibrary(): List<SongEntity>

    suspend fun resolveStreamUrl(remoteId: String): String

    suspend fun scrobble(remoteId: String)

    suspend fun toggleLike(remoteId: String, isLiked: Boolean)
}
