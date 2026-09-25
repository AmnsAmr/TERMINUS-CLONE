package com.necroware.terminusplayer.data.database

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
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
