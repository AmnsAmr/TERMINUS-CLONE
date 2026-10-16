package com.necroware.terminusplayer.data.model

import com.necroware.terminusplayer.data.database.entity.SongEntity
import java.util.Locale

/** Conservative metadata fallback for linking a local file to a server recording. */
fun normalizedRecordingMetadata(song: SongEntity): String? {
    val title = normalizeRecordingTag(song.title)
    val artist = normalizeRecordingTag(song.artist)
    val album = normalizeRecordingTag(song.album)
    if (title.isBlank() || artist.isBlank() || album.isBlank() || song.duration <= 0L) return null
    return "$title\u0000$artist\u0000$album"
}

fun sameRecordingByMetadata(left: SongEntity, right: SongEntity): Boolean {
    if (normalizedRecordingMetadata(left) == null || normalizedRecordingMetadata(left) != normalizedRecordingMetadata(right)) return false
    val toleranceMs = maxOf(3_000L, (maxOf(left.duration, right.duration) * 0.02).toLong())
    return kotlin.math.abs(left.duration - right.duration) <= toleranceMs
}

fun uniqueRecordingMatch(localSong: SongEntity, candidates: List<SongEntity>): SongEntity? =
    candidates.filter { sameRecordingByMetadata(localSong, it) }.singleOrNull()

/** Requires uniqueness from both sides so iteration order can never choose an ID collision. */
fun unambiguousRecordingMatches(
    localSongs: List<SongEntity>,
    remoteSongs: List<SongEntity>,
    alreadyMatchedRemoteIds: Set<String> = emptySet()
): Map<String, SongEntity> {
    // Index the server library once. Filtering every remote track for every local
    // track made a library refresh O(local × server), which could starve the
    // playback and Compose dispatchers on large libraries.
    val remotesByMetadata = remoteSongs.asSequence()
        .filter { it.providerRemoteId !in alreadyMatchedRemoteIds }
        .mapNotNull { remote -> normalizedRecordingMetadata(remote)?.let { it to remote } }
        .groupBy({ it.first }, { it.second })

    val candidatesByLocal = localSongs.associate { local ->
        val metadata = normalizedRecordingMetadata(local)
        local.remoteId to if (metadata == null) emptyList() else {
            remotesByMetadata[metadata].orEmpty().filter { sameRecordingDuration(local, it) }
        }
    }
    val localCountByRemote = candidatesByLocal.values.flatten().groupingBy { it.providerRemoteId }.eachCount()
    return candidatesByLocal.mapNotNull { (localId, candidates) ->
        candidates.singleOrNull()?.takeIf { localCountByRemote[it.providerRemoteId] == 1 }?.let { localId to it }
    }.toMap()
}

private fun sameRecordingDuration(left: SongEntity, right: SongEntity): Boolean {
    if (left.duration <= 0L || right.duration <= 0L) return false
    val toleranceMs = maxOf(3_000L, (maxOf(left.duration, right.duration) * 0.02).toLong())
    return kotlin.math.abs(left.duration - right.duration) <= toleranceMs
}

fun lrclibResultMatches(song: SongEntity, title: String, artist: String, durationMs: Long?): Boolean {
    if (normalizeRecordingTag(title) != normalizeRecordingTag(song.title) ||
        normalizeRecordingTag(artist) != normalizeRecordingTag(song.artist)
    ) return false
    if (song.duration <= 0L) return durationMs == null || durationMs <= 0L
    if (durationMs == null || durationMs <= 0L) return false
    val toleranceMs = maxOf(3_000L, (song.duration * 0.02).toLong())
    return kotlin.math.abs(durationMs - song.duration) <= toleranceMs
}

private fun normalizeRecordingTag(value: String): String = value.lowercase(Locale.ROOT)
    .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")
