package com.necroware.terminusplayer.data.repository

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.necroware.terminusplayer.data.api.lyrics.LrclibApiService
import com.necroware.terminusplayer.data.database.dao.LyricsCacheDao
import com.necroware.terminusplayer.data.database.entity.LyricsCacheEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import com.necroware.terminusplayer.data.model.LyricLine
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.toLyricsCacheFields
import com.necroware.terminusplayer.data.model.parseLyricsText
import com.necroware.terminusplayer.data.model.LyricsCacheIdentity
import com.necroware.terminusplayer.data.model.lyricsCacheIdentity
import com.necroware.terminusplayer.data.model.lrclibResultMatches
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@JsonClass(generateAdapter = true)
internal data class LrclibRecord(
    @Json(name = "trackName") val trackName: String? = null,
    @Json(name = "artistName") val artistName: String? = null,
    @Json(name = "duration") val duration: Double? = null,
    @Json(name = "plainLyrics") val plainLyrics: String? = null,
    @Json(name = "syncedLyrics") val syncedLyrics: String? = null
)

@Singleton
class LrclibLyricsRepository @Inject constructor(
    private val api: LrclibApiService,
    private val cacheDao: LyricsCacheDao,
    moshi: Moshi,
    @ApplicationContext private val context: Context
) {
    private val recordAdapter = moshi.adapter(LrclibRecord::class.java)
    private val recordsAdapter = moshi.adapter<List<LrclibRecord>>(
        Types.newParameterizedType(List::class.java, LrclibRecord::class.java)
    )

    suspend fun getLyrics(song: SongEntity): SyncedLyrics? {
        if (song.title.isBlank() || song.artist.isBlank()) return null
        val identity = lyricsCacheIdentity(song)
        cachedForSong(song, identity)?.let { return it }
        return fetchFromLrclib(song, identity)
    }

    suspend fun cachedForSong(song: SongEntity, identity: LyricsCacheIdentity = lyricsCacheIdentity(song)): SyncedLyrics? {
        cacheDao.get(identity.cacheKey)?.asLyrics()?.let { return it }
        val legacyKey = legacyCacheKey(song) ?: return null
        val legacyLyrics = cacheDao.get(legacyKey)?.asLyrics() ?: return null
        save(identity, legacyLyrics)
        return legacyLyrics
    }

    suspend fun cached(vararg keys: String): SyncedLyrics? {
        for (key in keys.distinct()) cacheDao.get(key)?.asLyrics()?.let { return it }
        return null
    }

    suspend fun fetchFromLrclib(song: SongEntity, identity: LyricsCacheIdentity): SyncedLyrics? {
        if (song.title.isBlank() || song.artist.isBlank() || !isOnline()) return null
        val previousLookup = cacheDao.get(identity.cacheKey)
        if (previousLookup != null && previousLookup.plainLyrics.isNullOrBlank() && previousLookup.syncedLyrics.isNullOrBlank() &&
            System.currentTimeMillis() - previousLookup.fetchedAtEpochMs < NEGATIVE_CACHE_TTL_MS
        ) return null
        val lookup = withTimeoutOrNull(5_000L) {
            try {
                val exact = try {
                    api.getLyrics(song.title, song.artist, song.album, (song.duration + 500L) / 1_000L)
                        .use { recordAdapter.fromJson(it.string()) }
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

        if (lookup == null || lookup === LookupResult.Failure) return null
        val fetched = (lookup as LookupResult.Success).lyrics
        val lyrics = fetched?.let { (plain, synced) ->
            val timed = parseLyricsText(synced)
            timed ?: parseLyricsText(plain)
        }
        if (lyrics == null) {
            recordMiss(identity)
            return null
        }
        save(identity, lyrics, fetched?.first)
        return lyrics
    }

    private suspend fun recordMiss(identity: LyricsCacheIdentity) {
        val existing = cacheDao.get(identity.cacheKey)
        if (existing?.asLyrics() != null) return
        val miss = LyricsCacheEntity(identity.cacheKey, null, null, System.currentTimeMillis())
        if (identity.localFileIdentity != null) cacheDao.putLocalOnly(miss, identity.localFileIdentity) else cacheDao.put(miss)
    }

    suspend fun save(identity: LyricsCacheIdentity, lyrics: SyncedLyrics, plainFallback: String? = null): SyncedLyrics {
        val fields = lyrics.toLyricsCacheFields()
        val plain = plainFallback?.takeIf(String::isNotBlank) ?: fields.first
        if (plain.isNullOrBlank() && fields.second.isNullOrBlank()) return lyrics
        val existing = cacheDao.get(identity.cacheKey)
        val existingLyrics = existing?.asLyrics()
        val existingRank = existingLyrics?.let(::qualityRank) ?: -1
        val incomingRank = qualityRank(lyrics)
        if (existing == null || incomingRank > existingRank) {
            val entry = LyricsCacheEntity(identity.cacheKey, plain, fields.second, System.currentTimeMillis())
            if (identity.localFileIdentity != null) {
                cacheDao.putLocalOnly(entry, identity.localFileIdentity)
            } else {
                cacheDao.put(entry)
            }
        } else if (identity.localFileIdentity != null) {
            cacheDao.putFileReference(com.necroware.terminusplayer.data.database.entity.LyricsCacheFileEntity(identity.localFileIdentity, identity.cacheKey))
        }
        return existingLyrics?.takeIf { existingRank >= incomingRank } ?: lyrics
    }

    suspend fun cleanupConfirmedLocalFile(fileIdentity: String) {
        cacheDao.cleanupFileReference(fileIdentity)
    }

    private suspend fun findSearchMatch(song: SongEntity): LrclibRecord? {
        val results = api.searchLyrics(song.title, song.artist).use { recordsAdapter.fromJson(it.string()).orEmpty() }
        return results.firstOrNull { matches(it, song) }
    }

    private fun matches(record: LrclibRecord, song: SongEntity): Boolean {
        val resultDurationMs = record.duration?.let { (it * 1_000.0).toLong() }
        return lrclibResultMatches(song, record.trackName.orEmpty(), record.artistName.orEmpty(), resultDurationMs)
    }

    private fun lyricsFields(record: LrclibRecord): Pair<String?, String?> {
        return record.plainLyrics?.takeUnless(String::isBlank) to record.syncedLyrics?.takeUnless(String::isBlank)
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

    fun isOnline(): Boolean {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun qualityRank(lyrics: SyncedLyrics): Int = if (lyrics.lines.any { it.startMs > 0L }) 2 else 1

    private fun legacyCacheKey(song: SongEntity): String? {
        // Older keys stripped parenthesized/bracketed version markers, so they are unsafe for those titles.
        if (legacyNormalize(song.title) != safeNormalize(song.title)) return null
        val source = listOf(song.title, song.artist, song.album, (song.duration / 1_000L).toString())
            .joinToString("\u0000") { legacyNormalize(it) }
        return MessageDigest.getInstance("SHA-256").digest(source.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun legacyNormalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " ")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun safeNormalize(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private companion object {
        const val NEGATIVE_CACHE_TTL_MS = 24L * 60L * 60L * 1_000L
    }

    private sealed interface LookupResult {
        data class Success(val lyrics: Pair<String?, String?>?) : LookupResult
        data object Failure : LookupResult
    }
}
