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
