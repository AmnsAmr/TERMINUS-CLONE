package com.necroware.terminusplayer.data.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @Test
    fun lyricsOwnershipMigrationPreservesLegacyCacheRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "lyrics-cache-migration-test.db"
        context.deleteDatabase(databaseName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(18) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE lyrics_cache (cacheKey TEXT NOT NULL PRIMARY KEY, plainLyrics TEXT, syncedLyrics TEXT, fetchedAtEpochMs INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO lyrics_cache VALUES ('legacy-metadata-hash', 'cached words', NULL, 123)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            TerminusDatabase.MIGRATION_18_19.migrate(db)
            db.query("SELECT cacheKey, plainLyrics, fetchedAtEpochMs FROM lyrics_cache").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("legacy-metadata-hash", cursor.getString(0))
                assertEquals("cached words", cursor.getString(1))
                assertEquals(123L, cursor.getLong(2))
            }
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'lyrics_cache_files'").use {
                check(it.moveToFirst())
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun uploadQueueProgressMigrationPreservesExistingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "upload-queue-migration-test.db"
        context.deleteDatabase(databaseName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(14) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE upload_queue (songId TEXT NOT NULL PRIMARY KEY, uriString TEXT NOT NULL, state TEXT NOT NULL, attemptCount INTEGER NOT NULL, addedAtEpochMs INTEGER NOT NULL, title TEXT NOT NULL, errorMessage TEXT DEFAULT NULL, forceConvert INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("INSERT INTO upload_queue (songId, uriString, state, attemptCount, addedAtEpochMs, title) VALUES ('local:track-1', 'content://music/1', 'WAITING', 0, 123, 'My Song.flac')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            TerminusDatabase.MIGRATION_14_15.migrate(db)
            TerminusDatabase.MIGRATION_15_16.migrate(db)
            db.query("SELECT songId, state, title, transferType, bytesTransferred, phase, partialUri FROM upload_queue").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals("local:track-1", cursor.getString(0))
                assertEquals("WAITING", cursor.getString(1))
                assertEquals("My Song.flac", cursor.getString(2))
                assertEquals("UPLOAD", cursor.getString(3))
                assertEquals(0L, cursor.getLong(4))
                assertEquals("QUEUED", cursor.getString(5))
                assertEquals(true, cursor.isNull(6))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun versionFiveRowsAndSongReferencesSurviveNamespacingMigration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "migration-test.db"
        context.deleteDatabase(databaseName)
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()

        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE songs (remoteId TEXT NOT NULL, providerId TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, albumId TEXT NOT NULL, duration INTEGER NOT NULL, uriString TEXT NOT NULL, dateAdded INTEGER NOT NULL, trackNumber INTEGER NOT NULL, year INTEGER NOT NULL, folderPath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, navidromeId TEXT, PRIMARY KEY(remoteId))")
            db.execSQL("CREATE TABLE liked_songs (songId TEXT NOT NULL, likedAt INTEGER NOT NULL, PRIMARY KEY(songId))")
            db.execSQL("CREATE TABLE play_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, songId TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, albumId TEXT NOT NULL, startedAtEpochMs INTEGER NOT NULL, msPlayed INTEGER NOT NULL, completed INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE playlists (id TEXT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE TABLE playlist_songs (playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, songId))")
            db.execSQL("INSERT INTO songs VALUES ('7', 'local', 'Track', 'Artist', ' Album ', 'album-1', 60000, 'content://track/7', 1, 1, 2024, '/Music', 1000, NULL)")
            db.execSQL("INSERT INTO liked_songs VALUES ('7', 10)")
            db.execSQL("INSERT INTO play_events (songId, artist, album, albumId, startedAtEpochMs, msPlayed, completed) VALUES ('7', 'Artist', 'Album', 'album-1', 20, 5000, 0)")
            db.execSQL("INSERT INTO playlists VALUES ('playlist-1', 'Mix', 1)")
            db.execSQL("INSERT INTO playlist_songs VALUES ('playlist-1', '7', 0)")
            db.version = 5
        }

        val migrated = Room.databaseBuilder(context, TerminusDatabase::class.java, databaseName)
            .addMigrations(TerminusDatabase.MIGRATION_5_6)
            .build()
        try {
            val db = migrated.openHelper.writableDatabase
            assertEquals("local:7", db.query("SELECT remoteId FROM songs").use { it.moveToFirst(); it.getString(0) })
            assertEquals("7", db.query("SELECT providerRemoteId FROM songs").use { it.moveToFirst(); it.getString(0) })
            assertEquals("local:7", db.query("SELECT songId FROM liked_songs").use { it.moveToFirst(); it.getString(0) })
            assertEquals("local:7", db.query("SELECT songId FROM playlist_songs").use { it.moveToFirst(); it.getString(0) })
            assertEquals("local:7", db.query("SELECT songId FROM play_events").use { it.moveToFirst(); it.getString(0) })
            assertEquals("Album", db.query("SELECT album FROM songs").use { it.moveToFirst(); it.getString(0) })
        } finally {
            migrated.close()
            context.deleteDatabase(databaseName)
        }
    }
}
