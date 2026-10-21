package com.necroware.terminusplayer.data.api.youtube

import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.prefs.resolveEffectiveMaxBitRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeStreamResolver @Inject constructor(
    private val client: YouTubeInnerTubeClient,
    private val prefsRepo: UserPreferencesRepository
) {
    private data class CachedStream(
        val url: String,
        val format: YouTubeAudioFormat,
        val expiresAtMs: Long
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<String>>()

    suspend fun resolveStreamUrl(videoId: String, forceRefresh: Boolean = false): String {
        val cleanId = videoId.removePrefix("youtube:")
        val now = System.currentTimeMillis()

        if (!forceRefresh) {
            val cached = streamCache[cleanId]
            if (cached != null && cached.expiresAtMs > now + 60_000L) {
                return cached.url
            }
        }

        while (true) {
            if (!forceRefresh) {
                val cached = streamCache[cleanId]
                if (cached != null && cached.expiresAtMs > System.currentTimeMillis() + 60_000L) {
                    return cached.url
                }
            }

            val deferred = CompletableDeferred<String>()
            val existing = inFlightRequests.putIfAbsent(cleanId, deferred)
            if (existing != null) {
                return try {
                    existing.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue
                }
            }

            try {
                val formats = client.getAudioFormats(cleanId)
                val config = prefsRepo.serverConnectionConfig.value.takeIf { it.isLoaded }
                    ?: prefsRepo.awaitServerConnectionConfig()
                val effectiveBitrate = resolveEffectiveMaxBitRate(
                    configuredBitRate = config.maxBitRate,
                    smartBitrateEnabled = false,
                    cellularBitRate = 128,
                    isCellular = false
                )

                val selected = selectAudioFormat(formats, effectiveBitrate)
                // Stream URLs from YouTube usually expire in 6 hours (~21600s).
                // We cache conservatively for 5 hours.
                val expiresAtMs = System.currentTimeMillis() + 5 * 3600 * 1000L
                streamCache[cleanId] = CachedStream(selected.url, selected, expiresAtMs)
                deferred.complete(selected.url)
                return selected.url
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
                throw t
            } finally {
                inFlightRequests.remove(cleanId)
            }
        }
    }

    suspend fun resolveAudioFormat(videoId: String, targetBitRate: Int? = null): YouTubeAudioFormat {
        val cleanId = videoId.removePrefix("youtube:")
        val formats = client.getAudioFormats(cleanId)
        return selectAudioFormat(formats, targetBitRate)
    }

    fun invalidate(videoId: String) {
        val cleanId = videoId.removePrefix("youtube:")
        streamCache.remove(cleanId)
        inFlightRequests.remove(cleanId)
    }

    companion object {
        fun selectAudioFormat(
            formats: List<YouTubeAudioFormat>,
            maxBitRateKbps: Int?
        ): YouTubeAudioFormat {
            if (formats.isEmpty()) throw NoSuchElementException("No audio formats available")

            if (maxBitRateKbps == null) {
                return formats.maxByOrNull { it.bitrate } ?: formats.first()
            }
            val targetBps = maxBitRateKbps * 1000L
            return formats.filter { it.bitrate <= targetBps }
                .maxByOrNull { it.bitrate }
                ?: formats.minByOrNull { it.bitrate }
                ?: formats.first()
        }
    }
}
