package com.necroware.terminusplayer.di

import android.content.Context
import androidx.room.Room
import com.necroware.terminusplayer.data.database.TerminusDatabase
import com.necroware.terminusplayer.BuildConfig
import com.necroware.terminusplayer.data.database.dao.LikedSongDao
import com.necroware.terminusplayer.data.database.dao.PlayEventDao
import com.necroware.terminusplayer.data.database.dao.PlaylistDao
import com.necroware.terminusplayer.data.database.dao.SongDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): TerminusDatabase {
        val builder = Room.databaseBuilder(
            context,
            TerminusDatabase::class.java,
            TerminusDatabase.DATABASE_NAME
        )
            .addMigrations(TerminusDatabase.MIGRATION_5_6)
        if (BuildConfig.DEBUG) builder.fallbackToDestructiveMigration()
        return builder.build()
    }

    @Provides
    fun provideSongDao(db: TerminusDatabase): SongDao = db.songDao()

    @Provides
    fun provideLikedSongDao(db: TerminusDatabase): LikedSongDao = db.likedSongDao()

    @Provides
    fun providePlayEventDao(db: TerminusDatabase): PlayEventDao = db.playEventDao()

    @Provides
    fun providePlaylistDao(db: TerminusDatabase): PlaylistDao = db.playlistDao()
}
