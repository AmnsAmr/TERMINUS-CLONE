package com.necroware.terminusplayer.di

import com.necroware.terminusplayer.data.api.subsonic.SubsonicApiService
import com.necroware.terminusplayer.data.prefs.UserPreferencesRepository
import com.necroware.terminusplayer.data.provider.buildSubsonicToken
import com.necroware.terminusplayer.data.provider.newSubsonicSalt
import com.necroware.terminusplayer.data.provider.parseNavidromeBaseUrl
import com.squareup.moshi.Moshi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import javax.inject.Singleton

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
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val config = prefsRepo.serverConnectionConfig.value
                if (!config.isLoaded) {
                    throw java.io.IOException("Server configuration is still loading")
                }
                val serverUrl = parseNavidromeBaseUrl(config.serverUrl)
                    ?: throw java.io.IOException("Navidrome server URL is missing or invalid")
                val username = config.username
                val password = config.password
                val finalUrlBuilder = serverUrl.newBuilder()
                    .addEncodedPathSegments(request.url.encodedPath.removePrefix("/"))
                    .encodedQuery(request.url.encodedQuery)
                // Artwork requests carry no Retrofit arguments, so authorize them
                // with Subsonic's salted token instead of putting the password in
                // the URL. API methods already provide their own token parameters.
                if (finalUrlBuilder.build().queryParameter("u") == null && username.isNotBlank() && password.isNotBlank()) {
                    val salt = newSubsonicSalt()
                    finalUrlBuilder.addQueryParameter("u", username)
                        .addQueryParameter("t", buildSubsonicToken(password, salt))
                        .addQueryParameter("s", salt)
                }
                val builder = request.newBuilder().url(finalUrlBuilder.build())
                // Subsonic REST requests are authenticated by their salted token;
                // avoid sending the reusable password in Basic auth as well.
                if (!request.url.encodedPath.startsWith("/rest/") && username.isNotBlank() && password.isNotBlank()) {
                    builder.header("Authorization", okhttp3.Credentials.basic(username, password))
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, moshi: Moshi): Retrofit {
        return Retrofit.Builder()
            .baseUrl("https://terminus.invalid/") // Unreachable sentinel; interceptor requires a configured host.
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
