package com.necroware.terminusplayer.di

import com.necroware.terminusplayer.util.AudioConverter
import com.necroware.terminusplayer.util.AudioUtils
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class UtilModule {
    @Binds
    abstract fun bindAudioConverter(impl: AudioUtils): AudioConverter
}
