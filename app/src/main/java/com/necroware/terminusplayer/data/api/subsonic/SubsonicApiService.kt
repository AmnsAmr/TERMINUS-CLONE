package com.necroware.terminusplayer.data.api.subsonic

import com.necroware.terminusplayer.data.api.subsonic.model.SubsonicResponse
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.Streaming

interface SubsonicApiService {

    @GET("rest/getPlaylists")
    suspend fun getPlaylists(
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/createPlaylist")
    suspend fun createPlaylist(
        @Query("name") name: String? = null,
        @Query("playlistId") playlistId: String? = null,
        @Query("songId") songIds: List<String> = emptyList(),
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/updatePlaylist")
    suspend fun updatePlaylist(
        @Query("playlistId") playlistId: String,
        @Query("name") name: String? = null,
        @Query("songIdToAdd") songIdsToAdd: List<String> = emptyList(),
        @Query("songIndexToRemove") songIndicesToRemove: List<Int> = emptyList(),
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/getPlaylist")
    suspend fun getPlaylist(
        @Query("id") id: String,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/deletePlaylist")
    suspend fun deletePlaylist(
        @Query("id") id: String,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @Streaming
    @GET("rest/download")
    suspend fun download(
        @Query("id") id: String,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): Response<ResponseBody>

    @GET("rest/ping")
    suspend fun ping(
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/search3")
    suspend fun search(
        @Query("query") query: String,
        @Query("songCount") songCount: Int = 100,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/scrobble")
    suspend fun scrobble(
        @Query("id") id: String,
        @Query("time") time: Long,
        @Query("submission") submission: Boolean,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/star")
    suspend fun star(
        @Query("id") id: String,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/unstar")
    suspend fun unstar(
        @Query("id") id: String,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse

    @GET("rest/getLyricsBySongId")
    suspend fun getLyricsBySongId(
        @Query("id") id: String,
        @Query("u") user: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "Terminus",
        @Query("f") format: String = "json"
    ): SubsonicResponse
}
