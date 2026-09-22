package com.necroware.terminusplayer.data.provider

import com.necroware.terminusplayer.data.api.subsonic.SubsonicApiService
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.LyricLine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import java.security.MessageDigest

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

        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            return emptyList()
        }

        val salt = generateSalt()
        val token = generateToken(password, salt)

        val response = apiService.search(
            query = "",
            songCount = 10000,
            user = username,
            token = token,
            salt = salt
        )

        if (response.response.status != "ok") {
            throw Exception("Subsonic API error: ${response.response.error?.message}")
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
        val prefs = prefsRepo.preferences.first()
        val rawUrl = prefs.serverUrl.trimEnd('/')
        val serverUrl = if (rawUrl.isNotBlank() && !rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            "http://$rawUrl"
        } else {
            rawUrl
        }
        val username = prefs.username
        val password = prefs.password

        val salt = generateSalt()
        val token = generateToken(password, salt)

        var url = "$serverUrl/rest/stream?id=$remoteId&u=$username&t=$token&s=$salt&v=1.16.1&c=Terminus&f=json"
        if (prefs.maxBitRate != null) {
            url += "&maxBitRate=${prefs.maxBitRate}"
        }
        return url
    }

    override suspend fun scrobble(remoteId: String) {
        val prefs = prefsRepo.preferences.first()
        val username = prefs.username
        val password = prefs.password

        if (username.isBlank() || password.isBlank()) return

        val salt = generateSalt()
        val token = generateToken(password, salt)

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

        val salt = generateSalt()
        val token = generateToken(password, salt)

        if (isLiked) {
            apiService.star(id = remoteId, user = username, token = token, salt = salt)
        } else {
            apiService.unstar(id = remoteId, user = username, token = token, salt = salt)
        }
    }

    private fun generateSalt(): String {
        val allowedChars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..6).map { allowedChars.random() }.joinToString("")
    }

    private fun generateToken(password: String, salt: String): String {
        val md = MessageDigest.getInstance("MD5")
        val input = password + salt
        val hashBytes = md.digest(input.toByteArray())
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    override suspend fun getLyrics(remoteId: String): SyncedLyrics? {
        val prefs = prefsRepo.preferences.first()
        val username = prefs.username
        val password = prefs.password

        if (username.isBlank() || password.isBlank()) return null

        val salt = generateSalt()
        val token = generateToken(password, salt)

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
        } catch (e: Exception) {
            null
        }
    }
}
