package com.necroware.terminusplayer.data.model

import com.necroware.terminusplayer.data.database.entity.SongEntity
import java.security.MessageDigest
import java.util.Locale

data class LyricsCacheIdentity(
    val cacheKey: String,
    /** Non-null only when this cache row belongs exclusively to one local-only file. */
    val localFileIdentity: String?
)

/** Use server/provider IDs whenever possible; local-only files remain file-specific. */
fun lyricsCacheIdentity(song: SongEntity): LyricsCacheIdentity {
    val navidromeId = song.navidromeId?.takeIf(String::isNotBlank)
        ?: song.providerRemoteId.takeIf { song.providerId == "navidrome" && it.isNotBlank() }
    if (navidromeId != null) return LyricsCacheIdentity("track:navidrome:${digest(navidromeId)}", null)
    if (song.providerId != "local") {
        return LyricsCacheIdentity("track:${song.providerId}:${digest(song.providerRemoteId)}", null)
    }
    val uri = song.uriString
    val localFingerprint = listOf(
        uri,
        normalize(song.title),
        normalize(song.artist),
        normalize(song.album),
        song.duration.toString()
    ).joinToString("\u0000")
    return LyricsCacheIdentity("local:${digest(localFingerprint)}", uri)
}

private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray())
    .joinToString("") { "%02x".format(it) }
