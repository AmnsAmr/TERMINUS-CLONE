package com.necroware.terminusplayer.di

import com.necroware.terminusplayer.data.repository.DownloadTransferRepository
import com.necroware.terminusplayer.data.repository.MusicRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class TransferRepositoryModule {
    @Binds
    @Singleton
    abstract fun bindDownloadTransferRepository(repository: MusicRepository): DownloadTransferRepository
}
