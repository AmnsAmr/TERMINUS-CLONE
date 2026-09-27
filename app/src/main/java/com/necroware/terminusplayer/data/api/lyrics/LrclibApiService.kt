package com.necroware.terminusplayer.data.api.lyrics

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface LrclibApiService {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String,
        @Query("album_name") albumName: String,
        @Query("duration") durationSeconds: Long
    ): ResponseBody

    @GET("api/search")
    suspend fun searchLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String
    ): ResponseBody
}
