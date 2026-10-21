package com.necroware.terminusplayer.data.api.youtube

import com.necroware.terminusplayer.data.model.Song
import com.necroware.terminusplayer.data.model.SyncedLyrics
import com.necroware.terminusplayer.data.model.parseLyricsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeInnerTubeClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val cachedVisitorData = AtomicReference<String>("CgtIWkRNRXo5bnVSWSjGyNnWBjIKCgJNQRIEGgAgEA%3D%3D")

    suspend fun searchSongsPaged(
        query: String,
        params: String? = YouTubeConstants.SEARCH_FILTER_SONGS,
        continuation: String? = null
    ): YouTubeSearchResult = withContext(Dispatchers.IO) {
        if (!continuation.isNullOrBlank()) {
            return@withContext fetchSearchContinuation(continuation)
        }

        val json = JSONObject().apply {
            put("context", buildWebContext())
            put("query", query)
            if (!params.isNullOrBlank()) {
                put("params", params)
            }
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", YouTubeConstants.CLIENT_VERSION_WEB_REMIX)
            .header("Origin", "https://music.youtube.com")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube search failed with HTTP ${response.code}")
            val body = response.body?.string() ?: return@withContext YouTubeSearchResult(emptyList())
            parseSearchResponseWithPaging(JSONObject(body))
        }
    }

    suspend fun searchSongs(query: String): List<Song> {
        return searchSongsPaged(query).songs
    }

    suspend fun getSearchSuggestions(input: String): List<String> = withContext(Dispatchers.IO) {
        if (input.isBlank()) return@withContext emptyList()
        val json = JSONObject().apply {
            put("context", buildWebContext())
            put("input", input)
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/music/get_search_suggestions")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", YouTubeConstants.CLIENT_VERSION_WEB_REMIX)
            .header("Origin", "https://music.youtube.com")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseSearchSuggestions(JSONObject(body))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun browsePlaylistSongs(browseId: String): List<Song> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("context", buildWebContext())
            put("browseId", browseId)
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            parsePlaylistResponse(JSONObject(body))
        }
    }

    suspend fun getPopularSongs(): List<Song> = withContext(Dispatchers.IO) {
        // Query search with popular hits or charts
        val results = searchSongs("top hits popular music")
        if (results.isNotEmpty()) results else browseExploreSongs()
    }

    suspend fun getTrendingSongs(): List<Song> = withContext(Dispatchers.IO) {
        val results = searchSongs("trending songs new music")
        if (results.isNotEmpty()) results else browseExploreSongs()
    }

    suspend fun getCharts(): List<Song> = withContext(Dispatchers.IO) {
        val songs = browseCharts()
        if (songs.isNotEmpty()) songs else searchSongs("charts billboard top 50")
    }

    suspend fun getRelatedSongs(videoId: String): List<Song> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("context", buildWebContext())
            put("videoId", videoId)
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            parseNextForQueue(JSONObject(body))
        }
    }

    suspend fun getLyrics(videoId: String): SyncedLyrics? = withContext(Dispatchers.IO) {
        val nextJson = JSONObject().apply {
            put("context", buildWebContext())
            put("videoId", videoId)
        }
        val nextRequest = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next")
            .post(nextJson.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .build()

        val lyricsBrowseId = okHttpClient.newCall(nextRequest).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body?.string() ?: return@use null
            extractLyricsBrowseId(JSONObject(body))
        } ?: return@withContext null

        val browseJson = JSONObject().apply {
            put("context", buildWebContext())
            put("browseId", lyricsBrowseId)
        }
        val browseRequest = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse")
            .post(browseJson.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .build()

        okHttpClient.newCall(browseRequest).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val rawText = extractLyricsText(JSONObject(body)) ?: return@withContext null
            parseLyricsText(rawText)
        }
    }

    suspend fun getAudioFormats(videoId: String): List<YouTubeAudioFormat> = withContext(Dispatchers.IO) {
        try {
            fetchAudioFormatsInternal(videoId, cachedVisitorData.get())
        } catch (e: Exception) {
            // If failed (e.g. status != "OK", HTTP error, or stale visitorData), refresh visitor data and retry once
            val freshVisitor = refreshVisitorData()
            fetchAudioFormatsInternal(videoId, freshVisitor)
        }
    }

    private fun fetchAudioFormatsInternal(videoId: String, visitor: String): List<YouTubeAudioFormat> {
        val json = JSONObject().apply {
            put("context", buildVisionOsContext(visitor))
            put("videoId", videoId)
        }
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", YouTubeConstants.USER_AGENT_VISIONOS)
            .header("X-YouTube-Client-Name", "101")
            .header("X-YouTube-Client-Version", YouTubeConstants.CLIENT_VERSION_VISIONOS)
            .header("X-Goog-Visitor-Id", visitor)
            .header("Origin", "https://www.youtube.com")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube player API failed with HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty response from YouTube player API")
            val root = JSONObject(body)
            extractVisitorData(root)

            val status = root.optJSONObject("playabilityStatus")?.optString("status")
            if (status != "OK") {
                val reason = root.optJSONObject("playabilityStatus")?.optString("reason") ?: "Track unavailable"
                throw IOException("YouTube track playback unplayable ($status): $reason")
            }

            val formats = parseAudioFormats(root)
            if (formats.isEmpty()) {
                throw IOException("No playable audio streams found for YouTube track $videoId")
            }
            formats
        }
    }

    private fun refreshVisitorData(): String {
        val json = JSONObject().apply {
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", YouTubeConstants.CLIENT_NAME_WEB_REMIX)
                    put("clientVersion", YouTubeConstants.CLIENT_VERSION_WEB_REMIX)
                    put("hl", "en")
                    put("gl", "US")
                })
            })
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Origin", "https://music.youtube.com")
            .build()
        try {
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val vd = JSONObject(body).optJSONObject("responseContext")?.optString("visitorData")
                        if (!vd.isNullOrBlank()) {
                            cachedVisitorData.set(vd)
                            return vd
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return cachedVisitorData.get()
    }

    private fun buildWebContext(): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", YouTubeConstants.CLIENT_NAME_WEB_REMIX)
                put("clientVersion", YouTubeConstants.CLIENT_VERSION_WEB_REMIX)
                put("hl", "en")
                put("gl", "US")
                cachedVisitorData.get().takeIf { it.isNotBlank() }?.let { put("visitorData", it) }
            })
        }
    }

    private fun buildVisionOsContext(visitor: String): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", YouTubeConstants.CLIENT_NAME_VISIONOS)
                put("clientVersion", YouTubeConstants.CLIENT_VERSION_VISIONOS)
                put("deviceMake", "Apple")
                put("deviceModel", "RealityDevice17,1")
                put("osName", "visionOS")
                put("osVersion", "26.5.23O471")
                put("hl", "en")
                put("timeZone", "UTC")
                put("utcOffsetMinutes", 0)
                if (visitor.isNotBlank()) put("visitorData", visitor)
            })
        }
    }

    private fun extractVisitorData(root: JSONObject) {
        val vd = root.optJSONObject("responseContext")?.optString("visitorData")
        if (!vd.isNullOrBlank()) {
            cachedVisitorData.set(vd)
        }
    }

    private fun fetchSearchContinuation(continuation: String): YouTubeSearchResult {
        val json = JSONObject().apply {
            put("context", buildWebContext())
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?continuation=$continuation")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", YouTubeConstants.CLIENT_VERSION_WEB_REMIX)
            .header("Origin", "https://music.youtube.com")
            .build()

        return okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use YouTubeSearchResult(emptyList())
            val body = response.body?.string() ?: return@use YouTubeSearchResult(emptyList())
            parseContinuationResponse(JSONObject(body))
        }
    }

    private fun parseSearchResponseWithPaging(root: JSONObject): YouTubeSearchResult {
        extractVisitorData(root)
        val songs = mutableListOf<Song>()
        val sectionList = root.optJSONObject("contents")
            ?.optJSONObject("tabbedSearchResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer") ?: return YouTubeSearchResult(emptyList())

        val contents = sectionList.optJSONArray("contents") ?: return YouTubeSearchResult(emptyList())
        var continuationToken: String? = null

        for (i in 0 until contents.length()) {
            val section = contents.optJSONObject(i) ?: continue

            val card = section.optJSONObject("musicCardShelfRenderer")
            if (card != null) {
                parseCardShelf(card)?.let { songs.add(it) }
                val cardItems = card.optJSONArray("contents")
                if (cardItems != null) {
                    for (c in 0 until cardItems.length()) {
                        val cItem = cardItems.optJSONObject(c)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                        parseResponsiveListItem(cItem)?.let { songs.add(it) }
                    }
                }
            }

            val shelf = section.optJSONObject("musicShelfRenderer") ?: continue
            val items = shelf.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                parseResponsiveListItem(item)?.let { songs.add(it) }
            }

            if (continuationToken == null) {
                val shelfConts = shelf.optJSONArray("continuations")
                continuationToken = shelfConts?.optJSONObject(0)
                    ?.optJSONObject("nextContinuationData")?.optString("continuation")
            }
        }

        if (continuationToken == null) {
            val secConts = sectionList.optJSONArray("continuations")
            continuationToken = secConts?.optJSONObject(0)
                ?.optJSONObject("nextContinuationData")?.optString("continuation")
        }

        val chips = mutableListOf<YouTubeFilterChip>()
        val chipArray = sectionList.optJSONObject("header")
            ?.optJSONObject("chipCloudRenderer")
            ?.optJSONArray("chips")
        if (chipArray != null) {
            for (k in 0 until chipArray.length()) {
                val chip = chipArray.optJSONObject(k)?.optJSONObject("chipCloudChipRenderer") ?: continue
                val text = chip.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                val params = chip.optJSONObject("navigationEndpoint")?.optJSONObject("searchEndpoint")?.optString("params")
                val isSelected = chip.optBoolean("isSelected")
                if (!text.isNullOrBlank() && !params.isNullOrBlank()) {
                    chips.add(YouTubeFilterChip(title = text, params = params, isSelected = isSelected))
                }
            }
        }

        return YouTubeSearchResult(
            songs = songs.distinctBy { it.id },
            continuationToken = continuationToken,
            dynamicFilterChips = chips
        )
    }

    private fun parseCardShelf(card: JSONObject): Song? {
        val onTap = card.optJSONObject("onTap")?.optJSONObject("watchEndpoint")
        val videoId = onTap?.optString("videoId")?.takeIf { it.isNotBlank() } ?: return null
        val title = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Track"
        val subtitleRuns = card.optJSONObject("subtitle")?.optJSONArray("runs")
        val artist = subtitleRuns?.optJSONObject(0)?.optString("text") ?: "Artist"
        val thumbnails = card.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val artworkUri = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")

        return Song(
            id = "youtube:$videoId",
            title = title,
            artist = artist,
            album = "YouTube Music",
            albumId = "yt_album_${artist.hashCode()}",
            duration = 0L,
            uriString = "https://www.youtube.com/watch?v=$videoId",
            providerId = "youtube",
            providerRemoteId = videoId,
            artworkUri = artworkUri
        )
    }

    private fun parseContinuationResponse(root: JSONObject): YouTubeSearchResult {
        extractVisitorData(root)
        val continuationContents = root.optJSONObject("continuationContents") ?: return YouTubeSearchResult(emptyList())
        val songs = mutableListOf<Song>()
        var nextToken: String? = null

        val shelfCont = continuationContents.optJSONObject("musicShelfContinuation")
            ?: continuationContents.optJSONObject("musicPlaylistShelfContinuation")
        if (shelfCont != null) {
            val items = shelfCont.optJSONArray("contents")
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                    parseResponsiveListItem(item)?.let { songs.add(it) }
                }
            }
            val conts = shelfCont.optJSONArray("continuations")
            nextToken = conts?.optJSONObject(0)?.optJSONObject("nextContinuationData")?.optString("continuation")
        } else {
            val sectionListCont = continuationContents.optJSONObject("sectionListContinuation")
            if (sectionListCont != null) {
                val sections = sectionListCont.optJSONArray("contents")
                if (sections != null) {
                    for (s in 0 until sections.length()) {
                        val shelf = sections.optJSONObject(s)?.optJSONObject("musicShelfRenderer") ?: continue
                        val items = shelf.optJSONArray("contents") ?: continue
                        for (i in 0 until items.length()) {
                            val item = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
                            parseResponsiveListItem(item)?.let { songs.add(it) }
                        }
                    }
                }
                val conts = sectionListCont.optJSONArray("continuations")
                nextToken = conts?.optJSONObject(0)?.optJSONObject("nextContinuationData")?.optString("continuation")
            }
        }
        return YouTubeSearchResult(songs.distinctBy { it.id }, nextToken)
    }

    private fun parseSearchSuggestions(root: JSONObject): List<String> {
        val list = mutableListOf<String>()
        val contents = root.optJSONArray("contents") ?: return list
        for (i in 0 until contents.length()) {
            val sec = contents.optJSONObject(i)?.optJSONObject("searchSuggestionsSectionRenderer") ?: continue
            val items = sec.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val sr = items.optJSONObject(j)?.optJSONObject("searchSuggestionRenderer") ?: continue
                val runs = sr.optJSONObject("suggestion")?.optJSONArray("runs") ?: continue
                val text = buildString {
                    for (k in 0 until runs.length()) {
                        append(runs.optJSONObject(k)?.optString("text") ?: "")
                    }
                }.trim()
                if (text.isNotBlank()) list.add(text)
            }
        }
        return list.distinct()
    }

    private fun parsePlaylistResponse(root: JSONObject): List<Song> {
        extractVisitorData(root)
        val songs = mutableListOf<Song>()
        val twoCol = root.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
        val sec = twoCol?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        val shelf = sec?.optJSONObject(0)?.optJSONObject("musicPlaylistShelfRenderer")
            ?: sec?.optJSONObject(0)?.optJSONObject("musicShelfRenderer")
            ?: root.optJSONObject("contents")?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")?.optJSONObject(0)?.optJSONObject("musicPlaylistShelfRenderer")

        val items = shelf?.optJSONArray("contents") ?: return emptyList()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i)?.optJSONObject("musicResponsiveListItemRenderer") ?: continue
            parseResponsiveListItem(item)?.let { songs.add(it) }
        }
        return songs.distinctBy { it.id }
    }

    private fun parseSearchResponse(root: JSONObject): List<Song> {
        return parseSearchResponseWithPaging(root).songs
    }

    private suspend fun browseExploreSongs(): List<Song> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("context", buildWebContext())
            put("browseId", YouTubeConstants.BROWSE_EXPLORE)
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            parseBrowseSectionList(JSONObject(body))
        }
    }

    private suspend fun browseCharts(): List<Song> = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("context", buildWebContext())
            put("browseId", YouTubeConstants.BROWSE_CHARTS)
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse")
            .post(json.toString().toRequestBody(jsonMediaType))
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            val body = response.body?.string() ?: return@withContext emptyList()
            parseBrowseSectionList(JSONObject(body))
        }
    }

    private fun parseBrowseSectionList(root: JSONObject): List<Song> {
        extractVisitorData(root)
        val songs = mutableListOf<Song>()
        val sections = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return emptyList()

        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val carousel = section.optJSONObject("musicCarouselShelfRenderer") ?: continue
            val items = carousel.optJSONArray("contents") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                    ?: items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                parseResponsiveListItem(item)?.let { songs.add(it) }
            }
        }
        return songs.distinctBy { it.id }
    }

    private fun parseNextForQueue(root: JSONObject): List<Song> {
        extractVisitorData(root)
        val songs = mutableListOf<Song>()
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()

        for (t in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(t)?.optJSONObject("tabRenderer") ?: continue
            val queue = tab.optJSONObject("content")?.optJSONObject("musicQueueRenderer") ?: continue
            val playlist = queue.optJSONObject("content")?.optJSONObject("playlistPanelRenderer") ?: continue
            val contents = playlist.optJSONArray("contents") ?: continue
            for (i in 0 until contents.length()) {
                val panelItem = contents.optJSONObject(i)?.optJSONObject("playlistPanelVideoRenderer") ?: continue
                val videoId = panelItem.optString("videoId")
                if (videoId.isNullOrBlank()) continue
                val title = panelItem.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Track"
                val runs = panelItem.optJSONObject("longBylineText")?.optJSONArray("runs")
                    ?: panelItem.optJSONObject("shortBylineText")?.optJSONArray("runs")
                val artist = runs?.optJSONObject(0)?.optString("text") ?: "Artist"
                val lengthText = panelItem.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val durationMs = parseDuration(lengthText)
                val thumbs = panelItem.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val artwork = thumbs?.optJSONObject(thumbs.length() - 1)?.optString("url")

                songs.add(
                    Song(
                        id = "youtube:$videoId",
                        title = title,
                        artist = artist,
                        album = "YouTube Music",
                        albumId = "yt_album_${artist.hashCode()}",
                        duration = durationMs,
                        uriString = "https://www.youtube.com/watch?v=$videoId",
                        providerId = "youtube",
                        providerRemoteId = videoId,
                        artworkUri = artwork
                    )
                )
            }
        }
        return songs.distinctBy { it.id }
    }

    private fun parseResponsiveListItem(item: JSONObject): Song? {
        val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
            ?: item.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: item.optString("videoId")
        if (videoId.isNullOrBlank()) return null

        val flexColumns = item.optJSONArray("flexColumns")
        var title = "Track"
        var artist = "YouTube Artist"
        var album: String? = null
        var durationMs = 0L

        if (flexColumns != null && flexColumns.length() > 0) {
            val col0 = flexColumns.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            title = col0?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: title

            if (flexColumns.length() > 1) {
                val col1 = flexColumns.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                val runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                if (runs != null && runs.length() > 0) {
                    val firstRunText = runs.optJSONObject(0)?.optString("text").orEmpty().trim()
                    val isEntityPrefix = firstRunText.equals("Song", ignoreCase = true) ||
                        firstRunText.equals("Video", ignoreCase = true) ||
                        firstRunText.equals("Track", ignoreCase = true)
                    val artistIdx = if (isEntityPrefix && runs.length() > 2) 2 else 0
                    val albumIdx = if (isEntityPrefix) 4 else 2

                    artist = runs.optJSONObject(artistIdx)?.optString("text")?.takeIf { it.isNotBlank() } ?: artist
                    if (runs.length() > albumIdx) {
                        val candAlbum = runs.optJSONObject(albumIdx)?.optString("text")
                        if (!candAlbum.isNullOrBlank() && !candAlbum.contains(":")) {
                            album = candAlbum
                        }
                    }

                    // Scan runs in reverse for duration formatted text (\d+:\d+)
                    for (r in runs.length() - 1 downTo 0) {
                        val runStr = runs.optJSONObject(r)?.optString("text").orEmpty().trim()
                        if (runStr.contains(":") && runStr.all { c -> c.isDigit() || c == ':' }) {
                            val parsed = parseDuration(runStr)
                            if (parsed > 0L) {
                                durationMs = parsed
                                break
                            }
                        }
                    }
                    if (durationMs == 0L) {
                        val lastRun = runs.optJSONObject(runs.length() - 1)?.optString("text") ?: ""
                        durationMs = parseDuration(lastRun)
                    }
                }
            }
        } else {
            title = item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: title
            val runs = item.optJSONObject("subtitle")?.optJSONArray("runs")
            if (runs != null && runs.length() > 0) {
                artist = runs.optJSONObject(0)?.optString("text") ?: artist
            }
        }

        val thumbnails = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?: item.optJSONObject("thumbnailRenderer")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
        val artworkUri = thumbnails?.optJSONObject(thumbnails.length() - 1)?.optString("url")

        return Song(
            id = "youtube:$videoId",
            title = title,
            artist = artist,
            album = album ?: "YouTube Music",
            albumId = album?.hashCode()?.toString() ?: "yt_album_${artist.hashCode()}",
            duration = durationMs,
            uriString = "https://www.youtube.com/watch?v=$videoId",
            providerId = "youtube",
            providerRemoteId = videoId,
            artworkUri = artworkUri
        )
    }

    private fun extractLyricsBrowseId(root: JSONObject): String? {
        val tabs = root.optJSONObject("contents")
            ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
            ?.optJSONObject("tabbedRenderer")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs") ?: return null

        for (i in 0 until tabs.length()) {
            val tab = tabs.optJSONObject(i)?.optJSONObject("tabRenderer") ?: continue
            val title = tab.optString("title")
            val browseId = tab.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            if (title.equals("lyrics", ignoreCase = true) || (browseId != null && browseId.startsWith("MPLY"))) {
                return browseId
            }
        }
        return null
    }

    private fun extractLyricsText(root: JSONObject): String? {
        val contents = root.optJSONObject("contents")
            ?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents") ?: return null

        for (i in 0 until contents.length()) {
            val shelf = contents.optJSONObject(i)?.optJSONObject("musicDescriptionShelfRenderer") ?: continue
            val runs = shelf.optJSONObject("description")?.optJSONArray("runs") ?: continue
            val sb = StringBuilder()
            for (j in 0 until runs.length()) {
                sb.append(runs.optJSONObject(j)?.optString("text") ?: "")
            }
            val text = sb.toString().trim()
            if (text.isNotEmpty()) return text
        }
        return null
    }

    private fun parseAudioFormats(root: JSONObject): List<YouTubeAudioFormat> {
        val sd = root.optJSONObject("streamingData") ?: return emptyList()
        val formats = sd.optJSONArray("adaptiveFormats") ?: return emptyList()
        val list = mutableListOf<YouTubeAudioFormat>()

        for (i in 0 until formats.length()) {
            val f = formats.optJSONObject(i) ?: continue
            val mime = f.optString("mimeType")
            if (!mime.startsWith("audio/")) continue
            val url = f.optString("url")
            if (url.isNullOrBlank()) continue

            val itag = f.optInt("itag")
            val bitrate = f.optLong("bitrate")
            val contentLength = f.optLong("contentLength").takeIf { it > 0 }
            val approxDurationMs = f.optLong("approxDurationMs").takeIf { it > 0 }

            list.add(
                YouTubeAudioFormat(
                    itag = itag,
                    mimeType = mime,
                    bitrate = bitrate,
                    contentLength = contentLength,
                    durationMs = approxDurationMs,
                    url = url
                )
            )
        }
        return list
    }

    private fun parseDuration(durationStr: String): Long {
        if (durationStr.isBlank()) return 0L
        val parts = durationStr.trim().split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> 0L
        }
    }
}
