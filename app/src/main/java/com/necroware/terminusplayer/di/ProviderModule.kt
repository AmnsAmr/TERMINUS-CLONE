package com.necroware.terminusplayer.di

import com.necroware.terminusplayer.data.provider.LocalMediaProvider
import com.necroware.terminusplayer.data.provider.MediaProvider
import com.necroware.terminusplayer.data.provider.NavidromeProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey

@Module
@InstallIn(SingletonComponent::class)
interface ProviderModule {

    @Binds
    @IntoMap
    @StringKey("local")
    fun bindLocalMediaProvider(provider: LocalMediaProvider): MediaProvider

    @Binds
    @IntoMap
    @StringKey("navidrome")
    fun bindNavidromeProvider(provider: NavidromeProvider): MediaProvider
}
