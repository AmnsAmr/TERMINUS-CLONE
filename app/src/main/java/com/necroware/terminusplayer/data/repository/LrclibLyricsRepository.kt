package com.necroware.terminusplayer.data.repository

import com.necroware.terminusplayer.data.api.lyrics.LrclibApiService
import com.necroware.terminusplayer.data.database.dao.LyricsCacheDao
import com.necroware.terminusplayer.data.database.entity.LyricsCacheEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.LyricLine
import com.necroware.terminusplayer.data.model.SyncedLyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LrclibLyricsRepository @Inject constructor(
    private val api: LrclibApiService,
    private val cacheDao: LyricsCacheDao
) {
    suspend fun getLyrics(song: SongEntity): SyncedLyrics? {
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val key = cacheKey(song)
        val cached = cacheDao.get(key)
        val now = System.currentTimeMillis()
        val positiveTtl = 90L * DAY_MS
        val negativeTtl = 7L * DAY_MS
        if (cached != null && now - cached.fetchedAtEpochMs <
            if (cached.plainLyrics == null && cached.syncedLyrics == null) negativeTtl else positiveTtl
        ) return cached.asLyrics()

        val lookup = withTimeoutOrNull(2_200L) {
            try {
                val exact = try {
                    api.getLyrics(song.title, song.artist, song.album, (song.duration + 500L) / 1_000L)
                        .use { JSONObject(it.string()) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // An exact miss is expected; the bounded search can still find variants.
                }
                val record = exact?.takeIf { matches(it, song) } ?: findSearchMatch(song)
                LookupResult.Success(record?.let(::lyricsFields))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                LookupResult.Failure
            }
        }

        // A timeout or service outage is not a confirmed miss. Keep any stale
        // result available and retry on the next request.
        if (lookup == null || lookup === LookupResult.Failure) return cached?.asLyrics()
        val fetched = (lookup as LookupResult.Success).lyrics
        val entry = LyricsCacheEntity(key, fetched?.first, fetched?.second, now)
        cacheDao.put(entry)
        return entry.asLyrics()
    }

    private suspend fun findSearchMatch(song: SongEntity): JSONObject? {
        val results = api.searchLyrics(song.title, song.artist).use { JSONArray(it.string()) }
        return (0 until results.length())
            .asSequence()
            .mapNotNull { results.optJSONObject(it) }
            .firstOrNull { matches(it, song) }
    }

    private fun matches(record: JSONObject, song: SongEntity): Boolean {
        val title = normalize(record.optString("trackName"))
        val artist = normalize(record.optString("artistName"))
        if (title.isBlank() || artist.isBlank() || title != normalize(song.title) || artist != normalize(song.artist)) return false
        val resultDurationMs = (record.optDouble("duration", Double.NaN) * 1_000.0).toLong()
        return resultDurationMs > 0L && kotlin.math.abs(resultDurationMs - song.duration) <=
            maxOf(3_000L, (song.duration * 0.02).toLong())
    }

    private fun lyricsFields(record: JSONObject): Pair<String?, String?> {
        return record.optString("plainLyrics").takeUnless { it.isBlank() || it == "null" } to
            record.optString("syncedLyrics").takeUnless { it.isBlank() || it == "null" }
    }

    private fun LyricsCacheEntity.asLyrics(): SyncedLyrics? {
        val timed = syncedLyrics?.let(::parseLrc).orEmpty()
        val lines = if (timed.isNotEmpty()) timed else plainLyrics
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.map { LyricLine(0L, it) }
            ?.toList()
            .orEmpty()
        return lines.takeIf { it.isNotEmpty() }?.let(::SyncedLyrics)
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val timestamp = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        return lrc.lineSequence().flatMap { line ->
            val matches = timestamp.findAll(line).toList()
            val text = timestamp.replace(line, "").trim()
            if (matches.isEmpty() || text.isEmpty()) emptySequence()
            else matches.asSequence().map { match ->
                val minutes = match.groupValues[1].toLongOrNull() ?: 0L
                val seconds = match.groupValues[2].toLongOrNull() ?: 0L
                val fraction = match.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                LyricLine((minutes * 60L + seconds) * 1_000L + fraction, text)
            }
        }.sortedBy { it.startMs }.toList()
    }

    private fun cacheKey(song: SongEntity): String {
        val source = listOf(song.title, song.artist, song.album, (song.duration / 1_000L).toString())
            .joinToString("\u0000") { normalize(it) }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val DAY_MS = 86_400_000L
    }

    private sealed interface LookupResult {
        data class Success(val lyrics: Pair<String?, String?>?) : LookupResult
        data object Failure : LookupResult
    }
}
