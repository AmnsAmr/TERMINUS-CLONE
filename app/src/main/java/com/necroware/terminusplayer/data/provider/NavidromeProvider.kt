package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.api.subsonic.SubsonicApiService
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.LyricLine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import java.io.IOException

@Singleton
class NavidromeProvider @Inject constructor(
    private val apiService: SubsonicApiService,
    private val prefsRepo: UserPreferencesRepository
) : MediaProvider {

    override val providerId: String = "navidrome"

    override suspend fun syncLibrary(): List<SongEntity> {
        val prefs = prefsRepo.preferences.first()
        val serverUrl = prefs.serverUrl
        val username = prefs.username
        val password = prefs.password

        if (serverUrl.isBlank() && username.isBlank() && password.isBlank()) {
            return emptyList()
        }

        if (username.isBlank() || password.isBlank() || parseNavidromeBaseUrl(serverUrl) == null) {
            throw ProviderSyncException("Navidrome server settings are incomplete or invalid", retryable = false)
        }

        val salt = newSubsonicSalt()
        val token = buildSubsonicToken(password, salt)

        val response = try {
            apiService.search(
                query = "",
                songCount = 10000,
                user = username,
                token = token,
                salt = salt
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: retrofit2.HttpException) {
            throw ProviderSyncException(
                "Navidrome request failed with HTTP ${e.code()}",
                retryable = e.code() != 401 && e.code() != 403
            )
        }

        if (response.response.status != "ok") {
            val error = response.response.error
            val permanent = error?.code == 40 || error?.code == 50 || error?.code == 60
            throw ProviderSyncException(
                "Subsonic API error: ${error?.message ?: "unknown error"}",
                retryable = !permanent
            )
        }

        val songs = response.response.searchResult3?.song ?: emptyList()

        return songs.map { song ->
            SongEntity(
                remoteId = song.id,
                providerId = providerId,
                title = song.title,
                artist = song.artist ?: "Unknown Artist",
                album = song.album ?: "Unknown Album",
                albumId = song.albumId ?: "",
                duration = (song.duration?.toLong() ?: 0L) * 1000L,
                uriString = "terminus://${song.id}",
                trackNumber = song.track ?: 0,
                year = song.year ?: 0,
                folderPath = song.path ?: "",
                sizeBytes = song.size ?: 0L,
                dateAdded = System.currentTimeMillis()
            )
        }
    }

    override suspend fun resolveStreamUrl(remoteId: String): String {
        return resolveStreamUrls(listOf(remoteId)).values.first()
    }

    override suspend fun resolveStreamUrls(remoteIds: List<String>): Map<String, String> {
        if (remoteIds.isEmpty()) return emptyMap()
        val config = prefsRepo.serverConnectionConfig.value.takeIf { it.isLoaded }
            ?: prefsRepo.awaitServerConnectionConfig()
        val serverUrl = parseNavidromeBaseUrl(config.serverUrl)
            ?: throw IOException("Navidrome server URL is missing or invalid")
        val username = config.username
        val password = config.password
        if (username.isBlank() || password.isBlank()) throw IOException("Navidrome credentials are missing")
        val bitRate = config.maxBitRate
        if (bitRate != null && bitRate !in setOf(64, 128, 320)) {
            throw IOException("Configured stream bitrate is invalid")
        }

        val salt = newSubsonicSalt()
        val token = buildSubsonicToken(password, salt)
        val basePathUrl = serverUrl.newBuilder()
            .addPathSegments("rest/stream")
            .addQueryParameter("u", username)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", "1.16.1")
            .addQueryParameter("c", "Terminus")
            .addQueryParameter("f", "json")
            .apply { bitRate?.let { addQueryParameter("maxBitRate", it.toString()) } }
            .build()

        return remoteIds.associateWith { id ->
            basePathUrl.newBuilder().addQueryParameter("id", id).build().toString()
        }
    }

    override suspend fun scrobble(remoteId: String) {
        val prefs = prefsRepo.preferences.first()
        val username = prefs.username
        val password = prefs.password

        if (username.isBlank() || password.isBlank()) return

        val salt = newSubsonicSalt()
        val token = buildSubsonicToken(password, salt)

        apiService.scrobble(
            id = remoteId,
            time = System.currentTimeMillis(),
            submission = true,
            user = username,
            token = token,
            salt = salt
        )
    }

    override suspend fun toggleLike(remoteId: String, isLiked: Boolean) {
        val prefs = prefsRepo.preferences.first()
        val username = prefs.username
        val password = prefs.password

        if (username.isBlank() || password.isBlank()) return

        val salt = newSubsonicSalt()
        val token = buildSubsonicToken(password, salt)

        if (isLiked) {
            apiService.star(id = remoteId, user = username, token = token, salt = salt)
        } else {
            apiService.unstar(id = remoteId, user = username, token = token, salt = salt)
        }
    }

    override suspend fun getLyrics(remoteId: String): SyncedLyrics? {
        val prefs = prefsRepo.preferences.first()
        val username = prefs.username
        val password = prefs.password

        if (username.isBlank() || password.isBlank()) return null

        val salt = newSubsonicSalt()
        val token = buildSubsonicToken(password, salt)

        return try {
            val response = apiService.getLyricsBySongId(
                id = remoteId,
                user = username,
                token = token,
                salt = salt
            )
            val data = response.response
            if (data.status != "ok") return null
            
            // Navidrome might return lyricsList as an array of structuredLyrics objects directly, 
            // or as a Map containing a "structuredLyrics" key depending on the version/spec.
            val structuredLyricsList = when (val listObj = data.lyricsList) {
                is List<*> -> listObj // Navidrome direct array
                is Map<*, *> -> listObj["structuredLyrics"] as? List<*> // Standard OpenSubsonic Map
                else -> null
            }
            
            val linesList = structuredLyricsList?.filterIsInstance<Map<*, *>>()?.flatMap { (it["line"] as? List<*>) ?: emptyList() }
            val mappedLines = linesList?.filterIsInstance<Map<*, *>>()?.mapNotNull { 
                val text = it["value"] as? String ?: return@mapNotNull null
                
                val startVal = it["start"]
                val startMs = try {
                    startVal.toString().toDouble().toLong()
                } catch (e: Exception) {
                    0L
                }
                
                LyricLine(startMs, text)
            }
            
            if (!mappedLines.isNullOrEmpty() && mappedLines.any { it.startMs > 0L }) {
                return SyncedLyrics(mappedLines)
            }
            
            // Fallback to unstructured lyrics if synced lyrics aren't available
            val unstructured = (data.lyrics as? Map<*, *>)?.get("value") as? String
            if (!unstructured.isNullOrBlank()) {
                val lines = unstructured.split("\n").map { LyricLine(0L, it) }
                return SyncedLyrics(lines)
            }

            return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
