package com.necroware.terminusplayer.data.api.youtube

import com.necroware.terminusplayer.data.model.Song
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

object YouTubeConstants {
    const val SEARCH_FILTER_SONGS = "EgWKAQIIAWoQEAUQCRADEAQQChAQEBUQEQ%3D%3D"
    const val SEARCH_FILTER_VIDEOS = "EgWKAQIQAWoQEAUQCRADEAQQChAQEBUQEQ%3D%3D"
    const val SEARCH_FILTER_ALBUMS = "EgWKAQIYAWoQEAUQCRADEAQQChAQEBUQEQ%3D%3D"
    const val SEARCH_FILTER_ARTISTS = "EgWKAQIgAWoQEAUQCRADEAQQChAQEBUQEQ%3D%3D"
    const val SEARCH_FILTER_PLAYLISTS = "EgeKAQQoAEABahAQBRAJEAMQBBAKEBAQFRAR"

    const val BROWSE_CHARTS = "FEmusic_charts"
    const val BROWSE_CHARTS_GLOBAL_TOP100 = "VLPL4fGSI1pDJn5kI81J1fYWK5eZRl1zJ5kM"
    const val BROWSE_EXPLORE = "FEmusic_explore"
    const val BROWSE_HOME = "FEmusic_home"

    const val CLIENT_NAME_WEB_REMIX = "WEB_REMIX"
    const val CLIENT_VERSION_WEB_REMIX = "1.20240101.01.00"

    const val CLIENT_NAME_VISIONOS = "VISIONOS"
    const val CLIENT_VERSION_VISIONOS = "1.02"
    const val USER_AGENT_VISIONOS = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
}

data class YouTubeFilterChip(
    val title: String,
    val params: String,
    val isSelected: Boolean = false
)

data class YouTubeSearchResult(
    val songs: List<Song>,
    val continuationToken: String? = null,
    val dynamicFilterChips: List<YouTubeFilterChip> = emptyList()
)

data class YouTubePagedResult(
    val songs: List<Song>,
    val continuationToken: String? = null,
    val dynamicFilterChips: List<YouTubeFilterChip> = emptyList()
)

enum class YouTubeSearchFilter(val label: String, val params: String?) {
    ALL("ALL", null),
    SONGS("SONGS", YouTubeConstants.SEARCH_FILTER_SONGS),
    VIDEOS("VIDEOS", YouTubeConstants.SEARCH_FILTER_VIDEOS),
    ALBUMS("ALBUMS", YouTubeConstants.SEARCH_FILTER_ALBUMS),
    ARTISTS("ARTISTS", YouTubeConstants.SEARCH_FILTER_ARTISTS),
    PLAYLISTS("PLAYLISTS", YouTubeConstants.SEARCH_FILTER_PLAYLISTS)
}

enum class YouTubeDiscoveryCategory(val label: String) {
    FEATURED("FEATURED"),
    MOODS("MOODS"),
    GENRES("GENRES")
}

data class YouTubeDiscoveryFilter(
    val id: String,
    val label: String,
    val category: YouTubeDiscoveryCategory,
    val query: String? = null,
    val browseId: String? = null
) {
    companion object {
        val DEFAULT_FILTERS = listOf(
            // FEATURED
            YouTubeDiscoveryFilter("popular", "TOP HITS", YouTubeDiscoveryCategory.FEATURED, query = "top hits popular music"),
            YouTubeDiscoveryFilter("trending", "TRENDING", YouTubeDiscoveryCategory.FEATURED, query = "trending songs viral hits"),
            YouTubeDiscoveryFilter("charts", "TOP 100", YouTubeDiscoveryCategory.FEATURED, browseId = YouTubeConstants.BROWSE_CHARTS_GLOBAL_TOP100),
            YouTubeDiscoveryFilter("new", "NEW RELEASES", YouTubeDiscoveryCategory.FEATURED, query = "new music singles releases"),
            // MOODS
            YouTubeDiscoveryFilter("chill", "CHILL", YouTubeDiscoveryCategory.MOODS, query = "chill lofi relaxing songs"),
            YouTubeDiscoveryFilter("workout", "WORKOUT", YouTubeDiscoveryCategory.MOODS, query = "workout gym motivation music"),
            YouTubeDiscoveryFilter("focus", "FOCUS", YouTubeDiscoveryCategory.MOODS, query = "focus study concentration beats"),
            YouTubeDiscoveryFilter("party", "PARTY", YouTubeDiscoveryCategory.MOODS, query = "party dance club hits"),
            YouTubeDiscoveryFilter("sleep", "SLEEP", YouTubeDiscoveryCategory.MOODS, query = "ambient sleep relaxation music"),
            // GENRES
            YouTubeDiscoveryFilter("hiphop", "HIP-HOP", YouTubeDiscoveryCategory.GENRES, query = "hip hop rap top tracks"),
            YouTubeDiscoveryFilter("rock", "ROCK", YouTubeDiscoveryCategory.GENRES, query = "rock modern classic rock hits"),
            YouTubeDiscoveryFilter("electronic", "ELECTRONIC", YouTubeDiscoveryCategory.GENRES, query = "electronic dance edm synthwave"),
            YouTubeDiscoveryFilter("pop", "POP", YouTubeDiscoveryCategory.GENRES, query = "pop music billboard top hits"),
            YouTubeDiscoveryFilter("metal", "METAL", YouTubeDiscoveryCategory.GENRES, query = "heavy metal rock songs"),
            YouTubeDiscoveryFilter("indie", "INDIE", YouTubeDiscoveryCategory.GENRES, query = "indie alternative bedroom pop"),
            YouTubeDiscoveryFilter("rnb", "R&B", YouTubeDiscoveryCategory.GENRES, query = "r&b soul neo soul hits"),
            YouTubeDiscoveryFilter("jazz", "JAZZ", YouTubeDiscoveryCategory.GENRES, query = "jazz modern classic instrumental")
        )
    }
}

@JsonClass(generateAdapter = true)
data class YouTubeClientContext(
    @Json(name = "clientName") val clientName: String,
    @Json(name = "clientVersion") val clientVersion: String,
    @Json(name = "deviceMake") val deviceMake: String? = null,
    @Json(name = "deviceModel") val deviceModel: String? = null,
    @Json(name = "osName") val osName: String? = null,
    @Json(name = "osVersion") val osVersion: String? = null,
    @Json(name = "hl") val hl: String = "en",
    @Json(name = "gl") val gl: String = "US",
    @Json(name = "visitorData") val visitorData: String? = null,
    @Json(name = "timeZone") val timeZone: String = "UTC",
    @Json(name = "utcOffsetMinutes") val utcOffsetMinutes: Int = 0
)

@JsonClass(generateAdapter = true)
data class YouTubeRequestContext(
    @Json(name = "client") val client: YouTubeClientContext
)

@JsonClass(generateAdapter = true)
data class YouTubeSearchRequest(
    @Json(name = "context") val context: YouTubeRequestContext,
    @Json(name = "query") val query: String,
    @Json(name = "params") val params: String? = null
)

@JsonClass(generateAdapter = true)
data class YouTubeBrowseRequest(
    @Json(name = "context") val context: YouTubeRequestContext,
    @Json(name = "browseId") val browseId: String,
    @Json(name = "params") val params: String? = null
)

@JsonClass(generateAdapter = true)
data class YouTubeNextRequest(
    @Json(name = "context") val context: YouTubeRequestContext,
    @Json(name = "videoId") val videoId: String
)

@JsonClass(generateAdapter = true)
data class YouTubePlayerRequest(
    @Json(name = "context") val context: YouTubeRequestContext,
    @Json(name = "videoId") val videoId: String
)

data class YouTubeTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val artworkUri: String? = null
) {
    fun toSong(): Song {
        return Song(
            id = "youtube:$videoId",
            title = title,
            artist = artist,
            album = album ?: "YouTube Music",
            albumId = album?.let { "yt_album_${it.hashCode()}" } ?: "yt_album_${artist.hashCode()}",
            duration = durationMs,
            uriString = "https://www.youtube.com/watch?v=$videoId",
            providerId = "youtube",
            providerRemoteId = videoId,
            artworkUri = artworkUri
        )
    }
}

data class YouTubeAudioFormat(
    val itag: Int,
    val mimeType: String,
    val bitrate: Long,
    val contentLength: Long?,
    val durationMs: Long?,
    val url: String
)
