package com.necroware.terminusplayer.data.api.subsonic.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SubsonicResponse(
    @Json(name = "subsonic-response") val response: SubsonicResponseData
)

@JsonClass(generateAdapter = true)
data class SubsonicResponseData(
    @Json(name = "status") val status: String,
    @Json(name = "version") val version: String,
    @Json(name = "error") val error: SubsonicError? = null,
    @Json(name = "searchResult3") val searchResult3: SearchResult3? = null,
    @Json(name = "lyrics") val lyrics: Any? = null,
    @Json(name = "lyricsList") val lyricsList: Any? = null
)

@JsonClass(generateAdapter = true)
data class SubsonicError(
    @Json(name = "code") val code: Int,
    @Json(name = "message") val message: String?
)

@JsonClass(generateAdapter = true)
data class SearchResult3(
    @Json(name = "song") val song: List<SongItem>? = null
)

@JsonClass(generateAdapter = true)
data class SongItem(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String,
    @Json(name = "album") val album: String?,
    @Json(name = "artist") val artist: String?,
    @Json(name = "track") val track: Int?,
    @Json(name = "year") val year: Int?,
    @Json(name = "genre") val genre: String?,
    @Json(name = "duration") val duration: Int?,
    @Json(name = "size") val size: Long?,
    @Json(name = "path") val path: String?,
    @Json(name = "albumId") val albumId: String?
)


