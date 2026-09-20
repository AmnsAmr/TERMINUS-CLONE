package com.necroware.terminusplayer.data.api.custom

import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class UploadQuotaResponse(
    val used: Long = 0,
    val total: Long = 0
)

@JsonClass(generateAdapter = true)
data class GapFinderSettings(
    val enabled: Boolean = false,
    val threshold: Float = 0f
)

@JsonClass(generateAdapter = true)
data class GapFinderStatus(
    val running: Boolean = false,
    val progress: Float = 0f,
    val currentTrack: String? = null
)

interface NavidromeNativeApiService {
    @Multipart
    @POST("api/upload/file")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Query("path") path: String? = null
    ): Response<Void>

    @GET("api/upload/quota")
    suspend fun getUploadQuota(): Response<UploadQuotaResponse>

    @GET("api/gapfinder/settings")
    suspend fun getGapfinderSettings(): Response<GapFinderSettings>

    @POST("api/gapfinder/settings")
    suspend fun updateGapfinderSettings(
        @Body settings: GapFinderSettings
    ): Response<Void>

    @GET("api/gapfinder/status")
    suspend fun getGapfinderStatus(): Response<GapFinderStatus>

    @POST("api/gapfinder/run")
    suspend fun runGapfinder(): Response<Void>
}
