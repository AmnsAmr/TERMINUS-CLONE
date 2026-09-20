package com.necroware.terminusplayer.data.api.subsonic

import com.necroware.terminusplayer.data.api.subsonic.model.SubsonicResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface SubsonicApiService {

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
}
