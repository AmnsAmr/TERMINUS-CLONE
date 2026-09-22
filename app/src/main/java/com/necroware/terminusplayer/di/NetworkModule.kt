package com.necroware.terminusplayer.di

import com.necroware.terminusplayer.data.api.subsonic.SubsonicApiService
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideMoshi(): Moshi {
        return Moshi.Builder().build()
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(prefsRepo: UserPreferencesRepository): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request()
                val prefs = try {
                    runBlocking { prefsRepo.preferences.first() }
                } catch (e: InterruptedException) {
                    throw java.io.InterruptedIOException().apply { initCause(e) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw java.io.InterruptedIOException().apply { initCause(e) }
                }
                val rawUrl = prefs.serverUrl.trimEnd('/')
                val username = prefs.username
                val password = prefs.password

                var finalRequest = request
                if (rawUrl.isNotBlank()) {
                    val urlWithScheme = if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                        "http://$rawUrl"
                    } else {
                        rawUrl
                    }
                    val newUrl = urlWithScheme.toHttpUrlOrNull()
                    if (newUrl != null) {
                        val finalUrlBuilder = newUrl.newBuilder()
                            .addEncodedPathSegments(request.url.encodedPath.removePrefix("/"))
                            .encodedQuery(request.url.encodedQuery)
                            
                        // Append Subsonic auth params if missing (required for Coil image requests)
                        if (username.isNotBlank() && password.isNotBlank()) {
                            if (request.url.queryParameter("u") == null) {
                                finalUrlBuilder.addQueryParameter("u", username)
                                finalUrlBuilder.addQueryParameter("p", password)
                            }
                        }
                        
                        val finalUrl = finalUrlBuilder.build()
                        
                        val builder = request.newBuilder().url(finalUrl)
                        if (username.isNotBlank() && password.isNotBlank()) {
                            val credential = okhttp3.Credentials.basic(username, password)
                            builder.header("Authorization", credential)
                        }
                        finalRequest = builder.build()
                    }
                }
                chain.proceed(finalRequest)
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("http://localhost/") // Dummy base URL, replaced by interceptor
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
    }

    @Provides
    @Singleton
    fun provideSubsonicApiService(retrofit: Retrofit): SubsonicApiService {
        return retrofit.create(SubsonicApiService::class.java)
    }

    @Provides
    @Singleton
    fun provideNavidromeNativeApiService(retrofit: Retrofit): com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService {
        return retrofit.create(com.necroware.terminusplayer.data.api.custom.NavidromeNativeApiService::class.java)
    }
}
