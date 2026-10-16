package com.necroware.terminusplayer.data.database

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun playlistOwnershipMigrationKeepsLegacyMixedMembershipLocal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "playlist-ownership-migration-test.db"
        context.deleteDatabase(databaseName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE playlists (id TEXT NOT NULL PRIMARY KEY, serverPlaylistId TEXT)")
                    db.execSQL("CREATE TABLE songs (remoteId TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, navidromeId TEXT)")
                    db.execSQL("CREATE TABLE playlist_songs (playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL, PRIMARY KEY(playlistId, songId))")
                    db.execSQL("INSERT INTO playlists VALUES ('linked-mix', 'server-mix')")
                    db.execSQL("INSERT INTO playlists VALUES ('server-only', 'server-only-id')")
                    db.execSQL("INSERT INTO songs VALUES ('local:phone', 'local', NULL)")
                    db.execSQL("INSERT INTO songs VALUES ('navidrome:42', 'navidrome', NULL)")
                    db.execSQL("INSERT INTO playlist_songs VALUES ('linked-mix', 'local:phone', 0)")
                    db.execSQL("INSERT INTO playlist_songs VALUES ('linked-mix', 'navidrome:42', 1)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            TerminusDatabase.MIGRATION_19_20.migrate(db)
            db.query("SELECT ownership FROM playlists WHERE id = 'linked-mix'").use {
                check(it.moveToFirst())
                assertEquals("LOCAL", it.getString(0))
            }
            db.query("SELECT ownership FROM playlists WHERE id = 'server-only'").use {
                check(it.moveToFirst())
                assertEquals("SERVER", it.getString(0))
            }
            db.query("SELECT songId, position FROM playlist_songs WHERE playlistId = 'linked-mix' ORDER BY position").use {
                check(it.moveToFirst())
                assertEquals("local:phone", it.getString(0))
                assertEquals(0, it.getInt(1))
                check(it.moveToNext())
                assertEquals("navidrome:42", it.getString(0))
                assertEquals(1, it.getInt(1))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun playlistEntryIdentityMigrationPreservesLegacyOrderAndFlags() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "playlist-entry-identity-migration-test.db"
        context.deleteDatabase(databaseName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(20) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE playlist_songs (playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL, syncedToServer INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlistId, songId))")
                    db.execSQL("INSERT INTO playlist_songs VALUES ('legacy', 'navidrome:2', 1, 1)")
                    db.execSQL("INSERT INTO playlist_songs VALUES ('legacy', 'local:1', 0, 0)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            TerminusDatabase.MIGRATION_20_21.migrate(db)
            db.query("SELECT entryId, songId, position, syncedToServer FROM playlist_songs ORDER BY position").use {
                check(it.moveToFirst())
                assertTrue(it.getLong(0) > 0L)
                assertEquals("local:1", it.getString(1))
                assertEquals(0, it.getInt(2))
                assertEquals(0, it.getInt(3))
                check(it.moveToNext())
                assertTrue(it.getLong(0) > 0L)
                assertEquals("navidrome:2", it.getString(1))
                assertEquals(1, it.getInt(2))
                assertEquals(1, it.getInt(3))
                assertEquals(false, it.moveToNext())
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun likedSongMigrationPreservesLocalLikesAndServerIdentity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "liked-song-identity-migration-test.db"
        context.deleteDatabase(databaseName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(21) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE liked_songs (songId TEXT NOT NULL PRIMARY KEY, likedAt INTEGER NOT NULL)")
                    db.execSQL("CREATE TABLE songs (remoteId TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, providerRemoteId TEXT NOT NULL, navidromeId TEXT)")
                    db.execSQL("INSERT INTO songs VALUES ('local:1', 'local', '1', NULL)")
                    db.execSQL("INSERT INTO songs VALUES ('navidrome:2', 'navidrome', '2', NULL)")
                    db.execSQL("INSERT INTO liked_songs VALUES ('local:1', 100)")
                    db.execSQL("INSERT INTO liked_songs VALUES ('navidrome:2', 200)")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = helper.writableDatabase
            TerminusDatabase.MIGRATION_21_22.migrate(db)
            db.query("SELECT isLiked, ownership, remoteSongId, syncPending, updatedAt FROM liked_songs WHERE songId = 'local:1'").use {
                check(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals("LOCAL", it.getString(1))
                assertTrue(it.isNull(2))
                assertEquals(0, it.getInt(3))
                assertEquals(100L, it.getLong(4))
            }
            db.query("SELECT isLiked, ownership, remoteSongId, syncPending FROM liked_songs WHERE songId = 'navidrome:2'").use {
                check(it.moveToFirst())
                assertEquals(1, it.getInt(0))
                assertEquals("SERVER", it.getString(1))
                assertEquals("2", it.getString(2))
                assertEquals(0, it.getInt(3))
            }
        } finally {
            helper.close()
            context.deleteDatabase(databaseName)
        }
    }

    @Test
    fun representativeLargeServerPlaylistFixtureSurvivesMigrationsAndReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "legacy-large-server-playlist-fixture.db"
        context.deleteDatabase(databaseName)
        val config19 = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(19) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE songs (remoteId TEXT NOT NULL PRIMARY KEY, providerId TEXT NOT NULL, providerRemoteId TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, albumId TEXT NOT NULL, duration INTEGER NOT NULL, uriString TEXT NOT NULL, dateAdded INTEGER NOT NULL, trackNumber INTEGER NOT NULL, year INTEGER NOT NULL, folderPath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, navidromeId TEXT, downloadedUri TEXT, genre TEXT NOT NULL DEFAULT '')")
                    db.execSQL("CREATE TABLE playlists (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, serverPlaylistId TEXT, syncPending INTEGER NOT NULL DEFAULT 0, deletePending INTEGER NOT NULL DEFAULT 0, remoteSongCount INTEGER NOT NULL DEFAULT 0, ownerUsername TEXT, remoteContentSignature TEXT, remoteSnapshotSongCount INTEGER NOT NULL DEFAULT 0, remoteSnapshotCheckedAt INTEGER NOT NULL DEFAULT 0)")
                    db.execSQL("CREATE TABLE playlist_songs (playlistId TEXT NOT NULL, songId TEXT NOT NULL, position INTEGER NOT NULL, syncedToServer INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(playlistId, songId))")
                    db.execSQL("CREATE TABLE liked_songs (songId TEXT NOT NULL PRIMARY KEY, likedAt INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO playlists VALUES ('legacy-large', '700 track server list', 1, 'navidrome-list-700', 0, 0, 700, 'listener', NULL, 0, 0)")
                    db.execSQL("INSERT INTO liked_songs VALUES ('local:phone-1', 100)")
                    for (index in 1..700) {
                        val suffix = index.toString().padStart(4, '0')
                        val isLocalMirror = index == 1
                        val provider = if (isLocalMirror) "local" else "navidrome"
                        val remoteId = if (isLocalMirror) "local:phone-1" else "navidrome:remote-$suffix"
                        val providerRemoteId = if (isLocalMirror) "phone-1" else "remote-$suffix"
                        val navidromeId = if (isLocalMirror) "remote-$suffix" else null
                        val uri = if (isLocalMirror) "content://legacy/music/phone-1" else "terminus://remote-$suffix"
                        val downloaded = if (index == 2) "content://legacy/download/remote-0002" else null
                        db.execSQL(
                            "INSERT INTO songs (remoteId, providerId, providerRemoteId, title, artist, album, albumId, duration, uriString, dateAdded, trackNumber, year, folderPath, sizeBytes, navidromeId, downloadedUri, genre) VALUES (?, ?, ?, ?, 'Fixture Artist', 'Fixture Album', 'album-1', 180000, ?, ?, ?, 2020, '/Fixture', 1000, ?, ?, '')",
                            arrayOf(remoteId, provider, providerRemoteId, "Track $suffix", uri, index.toLong(), index, navidromeId, downloaded)
                        )
                        db.execSQL(
                            "INSERT INTO playlist_songs (playlistId, songId, position, syncedToServer) VALUES ('legacy-large', ?, ?, 1)",
                            arrayOf(remoteId, index - 1)
                        )
                    }
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        var helper = FrameworkSQLiteOpenHelperFactory().create(config19)
        try {
            val db = helper.writableDatabase
            TerminusDatabase.MIGRATION_19_20.migrate(db)
            TerminusDatabase.MIGRATION_20_21.migrate(db)
            TerminusDatabase.MIGRATION_21_22.migrate(db)
            TerminusDatabase.MIGRATION_22_23.migrate(db)

            db.query("SELECT ownership, serverPlaylistId FROM playlists WHERE id = 'legacy-large'").use {
                check(it.moveToFirst())
                assertEquals("SERVER", it.getString(0))
                assertEquals("navidrome-list-700", it.getString(1))
            }
            db.query("SELECT COUNT(*), MIN(position), MAX(position) FROM playlist_songs WHERE playlistId = 'legacy-large'").use {
                check(it.moveToFirst())
                assertEquals(700, it.getInt(0))
                assertEquals(0, it.getInt(1))
                assertEquals(699, it.getInt(2))
            }
            db.query(
                """SELECT ps.position, ps.songId, s.providerId, s.providerRemoteId,
                          s.navidromeId, s.uriString, s.downloadedUri
                   FROM playlist_songs ps JOIN songs s ON s.remoteId = ps.songId
                   WHERE ps.playlistId = 'legacy-large' ORDER BY ps.position"""
            ).use { cursor ->
                var index = 1
                while (cursor.moveToNext()) {
                    val suffix = index.toString().padStart(4, '0')
                    val localMirror = index == 1
                    assertEquals(index - 1, cursor.getInt(0))
                    assertEquals(if (localMirror) "local:phone-1" else "navidrome:remote-$suffix", cursor.getString(1))
                    assertEquals(if (localMirror) "local" else "navidrome", cursor.getString(2))
                    assertEquals(if (localMirror) "phone-1" else "remote-$suffix", cursor.getString(3))
                    if (localMirror) {
                        assertEquals("remote-$suffix", cursor.getString(4))
                        assertEquals("content://legacy/music/phone-1", cursor.getString(5))
                    } else {
                        assertTrue(cursor.isNull(4))
                        assertEquals("terminus://remote-$suffix", cursor.getString(5))
                    }
                    if (index == 2) assertEquals("content://legacy/download/remote-0002", cursor.getString(6))
                    else assertTrue(cursor.isNull(6))
                    index++
                }
                assertEquals(701, index)
            }
            db.query("SELECT playlist_songs.songId, songs.providerId, songs.providerRemoteId, songs.navidromeId, songs.uriString FROM playlist_songs JOIN songs ON songs.remoteId = playlist_songs.songId WHERE playlist_songs.playlistId = 'legacy-large' ORDER BY position LIMIT 1").use {
                check(it.moveToFirst())
                assertEquals("local:phone-1", it.getString(0))
                assertEquals("local", it.getString(1))
                assertEquals("phone-1", it.getString(2))
                assertEquals("remote-0001", it.getString(3))
                assertEquals("content://legacy/music/phone-1", it.getString(4))
            }
            db.query("SELECT downloadedUri FROM songs WHERE remoteId = 'navidrome:remote-0002'").use {
                check(it.moveToFirst())
                assertEquals("content://legacy/download/remote-0002", it.getString(0))
            }

            // Duplicate entries were not representable in the old composite-key table.
            // Verify the migrated schema can now preserve them at distinct ordered positions.
            db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position, syncedToServer) VALUES ('legacy-large', 'navidrome:remote-0002', 700, 1)")
            db.execSQL("INSERT INTO playlist_songs (playlistId, songId, position, syncedToServer) VALUES ('legacy-large', 'navidrome:remote-0002', 701, 1)")
            db.version = 23
            helper.close()

            val config23 = SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(databaseName)
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
            helper = FrameworkSQLiteOpenHelperFactory().create(config23)
            val reopened = helper.writableDatabase
            reopened.query("SELECT songId, position FROM playlist_songs WHERE playlistId = 'legacy-large' ORDER BY position DESC LIMIT 2").use {
                check(it.moveToFirst())
                assertEquals("navidrome:remote-0002", it.getString(0))
                assertEquals(701, it.getInt(1))
                check(it.moveToNext())
                assertEquals("navidrome:remote-0002", it.getString(0))
                assertEquals(700, it.getInt(1))
            }
            reopened.query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = 'legacy-large'").use {
                check(it.moveToFirst())
                assertEquals(702, it.getInt(0))
            }
            reopened.query("SELECT songId FROM liked_songs WHERE songId = 'local:phone-1'").use {
                check(it.moveToFirst())
                assertEquals("local:phone-1", it.getString(0))
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

        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
        val migrated = FrameworkSQLiteOpenHelperFactory().create(config)
        try {
            val db = migrated.writableDatabase
            TerminusDatabase.MIGRATION_5_6.migrate(db)
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
