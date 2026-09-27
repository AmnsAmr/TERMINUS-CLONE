package com.necroware.terminusplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.necroware.terminusplayer.data.database.entity.PlaylistEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistSongEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    val id: String,
    val name: String,
    val createdAt: Long,
    val songCount: Int
)

data class PlaylistSongForSync(
    val songId: String,
    val providerId: String,
    val providerRemoteId: String,
    val syncedToServer: Boolean
)

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT playlists.id as id, playlists.name as name, playlists.createdAt as createdAt,
               CASE 
                   WHEN COUNT(playlist_songs.songId) > IFNULL(playlists.remoteSongCount, 0) 
                   THEN COUNT(playlist_songs.songId) 
                   ELSE IFNULL(playlists.remoteSongCount, 0) 
               END as songCount
        FROM playlists
        LEFT JOIN playlist_songs ON playlist_songs.playlistId = playlists.id
        WHERE playlists.deletePending = 0
        GROUP BY playlists.id
        ORDER BY playlists.createdAt DESC
        """
    )
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Insert
    suspend fun insertPlaylistSongs(songs: List<PlaylistSongEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistSongIfMissing(song: PlaylistSongEntity)

    @Transaction
    suspend fun insertPlaylistWithSongs(playlist: PlaylistEntity, songs: List<PlaylistSongEntity>) {
        insertPlaylist(playlist)
        songs.chunked(400).forEach { insertPlaylistSongs(it) }
    }

    @Query(
        """
        SELECT songs.* FROM songs
        INNER JOIN playlist_songs ON playlist_songs.songId = songs.remoteId
        WHERE playlist_songs.playlistId = :playlistId
        ORDER BY playlist_songs.position ASC
        """
    )
    suspend fun getSongsForPlaylist(playlistId: String): List<SongEntity>

    @Query("SELECT name FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistName(playlistId: String): String?

    @Query("SELECT remoteSongCount FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getRemoteSongCount(playlistId: String): Int?

    @Query("SELECT * FROM playlists WHERE syncPending = 1 OR deletePending = 1 ORDER BY createdAt ASC")
    suspend fun getPendingPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylist(playlistId: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE serverPlaylistId = :serverId LIMIT 1")
    suspend fun getPlaylistByServerId(serverId: String): PlaylistEntity?

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getPlaylistMemberships(playlistId: String): List<PlaylistSongEntity>

    @Query("UPDATE playlists SET name = :name, remoteSongCount = :songCount WHERE serverPlaylistId = :serverId")
    suspend fun updateRemotePlaylist(serverId: String, name: String, songCount: Int)

    @Query("UPDATE playlists SET remoteSongCount = :songCount WHERE id = :playlistId")
    suspend fun updateRemoteSongCount(playlistId: String, songCount: Int)

    @Query("SELECT playlist_songs.songId, songs.providerId, songs.providerRemoteId, playlist_songs.syncedToServer FROM playlist_songs INNER JOIN songs ON songs.remoteId = playlist_songs.songId WHERE playlist_songs.playlistId = :playlistId ORDER BY playlist_songs.position ASC")
    suspend fun getPlaylistSongsForSync(playlistId: String): List<PlaylistSongForSync>

    @Query("UPDATE playlists SET serverPlaylistId = :serverId, syncPending = 1 WHERE id = :playlistId")
    suspend fun markPlaylistCreated(playlistId: String, serverId: String)

    @Query("UPDATE playlists SET syncPending = 0 WHERE id = :playlistId")
    suspend fun markPlaylistSynced(playlistId: String)

    @Query("UPDATE playlist_songs SET syncedToServer = 1 WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun markPlaylistSongsSynced(playlistId: String, songIds: List<String>)

    @Query("UPDATE playlist_songs SET syncedToServer = 0 WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun markPlaylistSongPending(playlistId: String, songId: String)

    @Query("UPDATE playlists SET syncPending = 1 WHERE id = :playlistId")
    suspend fun markPlaylistPending(playlistId: String)

    @Query("UPDATE playlists SET deletePending = 1 WHERE id = :playlistId")
    suspend fun markPlaylistDeletePending(playlistId: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun nextSongPosition(playlistId: String): Int

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun purgePlaylistSongs(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun purgePlaylist(playlistId: String)
}
