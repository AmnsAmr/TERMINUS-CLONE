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
    @Json(name = "genres") val genres: GenresResponse? = null,
    @Json(name = "songsByGenre") val songsByGenre: SongsByGenreResponse? = null,
    @Json(name = "lyrics") val lyrics: Any? = null,
    @Json(name = "lyricsList") val lyricsList: Any? = null,
    @Json(name = "playlist") val playlist: PlaylistResponse? = null,
    @Json(name = "playlists") val playlists: PlaylistsResponse? = null,
    @Json(name = "starred2") val starred2: Starred2Response? = null,
    @Json(name = "user") val user: UserResponse? = null
)

@JsonClass(generateAdapter = true)
data class Starred2Response(
    @Json(name = "song") val songs: List<SongItem>? = null
)

@JsonClass(generateAdapter = true)
data class GenresResponse(@Json(name = "genre") val genres: List<GenreItem>? = null)

@JsonClass(generateAdapter = true)
data class GenreItem(
    @Json(name = "value") val value: String,
    @Json(name = "songCount") val songCount: Int? = null,
    @Json(name = "albumCount") val albumCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class SongsByGenreResponse(@Json(name = "song") val songs: List<SongItem>? = null)

@JsonClass(generateAdapter = true)
data class PlaylistResponse(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "songCount") val songCount: Int? = null,
    @Json(name = "owner") val owner: String? = null,
    @Json(name = "public") val isPublic: Boolean? = null,
    @Json(name = "coverArt") val coverArt: String? = null,
    @Json(name = "entry") val entries: List<PlaylistEntryResponse>? = null
)

@JsonClass(generateAdapter = true)
data class UserResponse(
    @Json(name = "username") val username: String? = null,
    @Json(name = "adminRole") val adminRole: Boolean = false
)

@JsonClass(generateAdapter = true)
data class PlaylistsResponse(@Json(name = "playlist") val items: List<PlaylistResponse>? = null)

@JsonClass(generateAdapter = true)
data class PlaylistEntryResponse(
    @Json(name = "id") val id: String,
    @Json(name = "title") val title: String? = null,
    @Json(name = "artist") val artist: String? = null,
    @Json(name = "album") val album: String? = null,
    @Json(name = "albumId") val albumId: String? = null,
    @Json(name = "duration") val duration: Int? = null,
    @Json(name = "track") val track: Int? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "path") val path: String? = null
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
    @Json(name = "albumId") val albumId: String?,
    @Json(name = "genres") val genres: Any? = null
)
