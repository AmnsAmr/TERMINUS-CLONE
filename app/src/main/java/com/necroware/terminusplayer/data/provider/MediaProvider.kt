package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.SyncedLyrics

interface MediaProvider {
    val providerId: String

    suspend fun syncLibrary(): List<SongEntity>

    suspend fun resolveStreamUrl(remoteId: String): String

    suspend fun resolveStreamUrls(remoteIds: List<String>): Map<String, String> {
        return remoteIds.associateWith { resolveStreamUrl(it) }
    }

    suspend fun resolveDownloadUrl(remoteId: String, maxBitRate: Int? = null): String = resolveStreamUrl(remoteId)

    fun invalidateStream(remoteId: String) {}

    suspend fun scrobble(remoteId: String)

    suspend fun toggleLike(remoteId: String, isLiked: Boolean)

    suspend fun getLyrics(remoteId: String): SyncedLyrics?
    suspend fun ping(): Long? = null
}

class ProviderSyncException(
    message: String,
    val retryable: Boolean
) : Exception(message)
