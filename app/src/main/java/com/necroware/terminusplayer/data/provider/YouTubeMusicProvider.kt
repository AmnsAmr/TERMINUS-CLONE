package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.api.youtube.YouTubeInnerTubeClient
import com.necroware.terminusplayer.data.api.youtube.YouTubeStreamResolver
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.SyncedLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeMusicProvider @Inject constructor(
    private val client: YouTubeInnerTubeClient,
    private val streamResolver: YouTubeStreamResolver
) : MediaProvider {

    override val providerId: String = "youtube"

    override suspend fun syncLibrary(): List<SongEntity> = emptyList()

    override suspend fun resolveStreamUrl(remoteId: String): String {
        val cleanId = remoteId.removePrefix("youtube:")
        if (cleanId.isBlank()) return ""
        return try {
            streamResolver.resolveStreamUrl(cleanId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    override suspend fun resolveDownloadUrl(remoteId: String, maxBitRate: Int?): String {
        val cleanId = remoteId.removePrefix("youtube:")
        if (cleanId.isBlank()) return ""
        return try {
            streamResolver.resolveAudioFormat(cleanId, maxBitRate).url
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ""
        }
    }

    override fun invalidateStream(remoteId: String) {
        val cleanId = remoteId.removePrefix("youtube:")
        if (cleanId.isNotBlank()) {
            streamResolver.invalidate(cleanId)
        }
    }

    override suspend fun resolveStreamUrls(remoteIds: List<String>): Map<String, String> = coroutineScope {
        if (remoteIds.isEmpty()) return@coroutineScope emptyMap()
        val uniqueIds = remoteIds.distinct()
        uniqueIds.map { id ->
            async(Dispatchers.IO) {
                val url = resolveStreamUrl(id)
                id to url
            }
        }.awaitAll()
            .filter { it.second.isNotBlank() }
            .toMap()
    }

    override suspend fun scrobble(remoteId: String) {
        // No-op for YouTube Music
    }

    override suspend fun toggleLike(remoteId: String, isLiked: Boolean) {
        // Stored locally in Room LikedSongDao
    }

    override suspend fun getLyrics(remoteId: String): SyncedLyrics? {
        val cleanId = remoteId.removePrefix("youtube:")
        if (cleanId.isBlank()) return null
        return try {
            client.getLyrics(cleanId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun ping(): Long? = null
}
