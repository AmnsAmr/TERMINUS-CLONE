package com.necroware.terminusplayer.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.necroware.terminusplayer.data.database.dao.LikedSongDao
import com.necroware.terminusplayer.data.database.dao.PlayEventDao
import com.necroware.terminusplayer.data.database.dao.PlaylistDao
import com.necroware.terminusplayer.data.database.dao.SongDao
import com.necroware.terminusplayer.data.database.entity.LikedSongEntity
import com.necroware.terminusplayer.data.database.entity.PlayEventEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistSongEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity

@Database(
    entities = [
        SongEntity::class,
        LikedSongEntity::class,
        PlayEventEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class TerminusDatabase : RoomDatabase() {
    abstract fun songDao(): SongDao
    abstract fun likedSongDao(): LikedSongDao
    abstract fun playEventDao(): PlayEventDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val DATABASE_NAME = "terminus.db"

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE song_id_migration (oldId TEXT NOT NULL PRIMARY KEY, newId TEXT NOT NULL)")
                db.execSQL("INSERT INTO song_id_migration (oldId, newId) SELECT remoteId, providerId || ':' || remoteId FROM songs")

                db.execSQL("UPDATE liked_songs SET songId = (SELECT newId FROM song_id_migration WHERE oldId = liked_songs.songId) WHERE songId IN (SELECT oldId FROM song_id_migration)")
                db.execSQL("UPDATE playlist_songs SET songId = (SELECT newId FROM song_id_migration WHERE oldId = playlist_songs.songId) WHERE songId IN (SELECT oldId FROM song_id_migration)")
                db.execSQL("UPDATE play_events SET songId = (SELECT newId FROM song_id_migration WHERE oldId = play_events.songId) WHERE songId IN (SELECT oldId FROM song_id_migration)")

                db.execSQL("CREATE TABLE songs_new (remoteId TEXT NOT NULL, providerId TEXT NOT NULL, providerRemoteId TEXT NOT NULL DEFAULT '', title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, albumId TEXT NOT NULL, duration INTEGER NOT NULL, uriString TEXT NOT NULL, dateAdded INTEGER NOT NULL, trackNumber INTEGER NOT NULL, year INTEGER NOT NULL, folderPath TEXT NOT NULL, sizeBytes INTEGER NOT NULL, navidromeId TEXT, PRIMARY KEY(remoteId))")
                db.execSQL("INSERT INTO songs_new (remoteId, providerId, providerRemoteId, title, artist, album, albumId, duration, uriString, dateAdded, trackNumber, year, folderPath, sizeBytes, navidromeId) SELECT m.newId, s.providerId, s.remoteId, s.title, s.artist, TRIM(s.album), s.albumId, s.duration, s.uriString, s.dateAdded, s.trackNumber, s.year, s.folderPath, s.sizeBytes, s.navidromeId FROM songs s JOIN song_id_migration m ON m.oldId = s.remoteId")
                db.execSQL("DROP TABLE songs")
                db.execSQL("ALTER TABLE songs_new RENAME TO songs")
                db.execSQL("CREATE UNIQUE INDEX index_songs_providerId_providerRemoteId ON songs(providerId, providerRemoteId)")
                db.execSQL("CREATE INDEX index_songs_artist ON songs(artist)")
                db.execSQL("CREATE INDEX index_songs_album ON songs(album)")
                db.execSQL("CREATE INDEX index_songs_folderPath ON songs(folderPath)")
                db.execSQL("CREATE INDEX index_play_events_startedAtEpochMs ON play_events(startedAtEpochMs)")
                db.execSQL("CREATE INDEX index_play_events_songId ON play_events(songId)")
                db.execSQL("DROP TABLE song_id_migration")
            }
        }
    }
}
