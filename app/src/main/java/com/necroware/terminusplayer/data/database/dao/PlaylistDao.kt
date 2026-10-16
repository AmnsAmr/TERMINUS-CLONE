package com.necroware.terminusplayer.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.necroware.terminusplayer.data.database.entity.PlaylistEntity
import com.necroware.terminusplayer.data.database.entity.PlaylistSongEntity
import com.necroware.terminusplayer.data.database.entity.SongEntity
import kotlinx.coroutines.flow.Flow

data class PlaylistWithCount(
    val id: String,
    val name: String,
    val createdAt: Long,
    val songCount: Int,
    val ownerUsername: String?,
    val serverPlaylistId: String?,
    val ownership: String,
    val hasSyncConflict: Boolean,
    val serverPlaylistMissing: Boolean
)

data class PlaylistSongForSync(
    val entryId: Long,
    val songId: String,
    val position: Int,
    val providerId: String,
    val providerRemoteId: String,
    val serverSongId: String?,
    val syncedToServer: Boolean
)

@Dao
interface PlaylistDao {

    @Query(
        """
        SELECT playlists.id as id, playlists.name as name, playlists.createdAt as createdAt,
               playlists.ownerUsername as ownerUsername, playlists.serverPlaylistId as serverPlaylistId,
               playlists.ownership as ownership,
               (playlists.conflictRemoteSignature IS NOT NULL) as hasSyncConflict,
               (playlists.conflictRemoteSignature = 'SERVER_MISSING') as serverPlaylistMissing,
               CASE 
                   WHEN playlists.ownership = 'SERVER' AND COUNT(playlist_songs.songId) < IFNULL(playlists.remoteSongCount, 0)
                   THEN IFNULL(playlists.remoteSongCount, 0)
                   ELSE COUNT(playlist_songs.songId)
               END as songCount
        FROM playlists
        LEFT JOIN playlist_songs ON playlist_songs.playlistId = playlists.id
        WHERE playlists.deletePending = 0 OR playlists.conflictRemoteSignature IS NOT NULL
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
        ORDER BY playlist_songs.position ASC, playlist_songs.entryId ASC
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

    @Query("SELECT ownership FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistOwnership(playlistId: String): String?

    @Query("SELECT * FROM playlists WHERE serverPlaylistId = :serverId LIMIT 1")
    suspend fun getPlaylistsByServerId(serverId: String): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position ASC, entryId ASC")
    suspend fun getPlaylistMemberships(playlistId: String): List<PlaylistSongEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId)")
    suspend fun containsSong(playlistId: String, songId: String): Boolean

    @Query("UPDATE playlists SET name = :name, remoteSongCount = :songCount, ownerUsername = :ownerUsername WHERE serverPlaylistId = :serverId AND ownership = 'SERVER' AND syncPending = 0 AND deletePending = 0")
    suspend fun updateRemotePlaylist(serverId: String, name: String, songCount: Int, ownerUsername: String?)

    @Query("UPDATE playlists SET name = :name WHERE id = :playlistId AND ownership = 'LOCAL'")
    suspend fun renameLocalPlaylist(playlistId: String, name: String)

    @Query("UPDATE playlists SET name = :name, syncPending = 1 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun renameServerPlaylist(playlistId: String, name: String)

    @Query("UPDATE playlists SET name = :name, serverPlaylistId = :serverId, ownership = 'SERVER', syncPending = 0, deletePending = 0, remoteSongCount = :songCount, ownerUsername = :ownerUsername, remoteContentSignature = :signature, remoteSnapshotSongCount = :songCount, remoteSnapshotCheckedAt = :checkedAt, remoteSnapshotName = :name, conflictRemoteSignature = NULL, conflictRemoteName = NULL, conflictRemoteSongIds = NULL WHERE id = :playlistId AND ownership = 'LOCAL' AND serverPlaylistId IS NULL")
    suspend fun attachCreatedServerPlaylist(
        playlistId: String,
        serverId: String,
        name: String,
        songCount: Int,
        ownerUsername: String?,
        signature: String,
        checkedAt: Long
    ): Int

    @Query("UPDATE playlists SET remoteSongCount = :songCount WHERE id = :playlistId")
    suspend fun updateRemoteSongCount(playlistId: String, songCount: Int)

    @Query("UPDATE playlists SET remoteSongCount = :songCount WHERE id = :playlistId")
    suspend fun updateRemoteSongCountAndInvalidate(playlistId: String, songCount: Int)

    @Query("UPDATE playlists SET remoteSongCount = :songCount, remoteContentSignature = :signature, remoteSnapshotSongCount = :songCount, remoteSnapshotCheckedAt = :checkedAt, remoteSnapshotName = :name WHERE id = :playlistId")
    suspend fun updateRemoteSnapshot(playlistId: String, songCount: Int, signature: String, checkedAt: Long, name: String)

    @Query("UPDATE playlists SET conflictRemoteSignature = :signature, conflictRemoteName = :name, conflictRemoteSongIds = :songIds WHERE id = :playlistId AND ownership = 'SERVER' AND (syncPending = 1 OR deletePending = 1)")
    suspend fun savePlaylistConflict(playlistId: String, signature: String, name: String, songIds: String)

    @Query("UPDATE playlists SET remoteContentSignature = :signature, remoteSnapshotName = :name, remoteSongCount = :songCount, remoteSnapshotSongCount = :songCount, remoteSnapshotCheckedAt = :checkedAt, conflictRemoteSignature = NULL, conflictRemoteName = NULL, conflictRemoteSongIds = NULL, syncPending = 1 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun resolvePlaylistConflictKeepLocal(playlistId: String, signature: String, name: String, songCount: Int, checkedAt: Long)

    @Query("UPDATE playlists SET name = :name, deletePending = 0, remoteContentSignature = :signature, remoteSnapshotName = :name, remoteSongCount = :songCount, remoteSnapshotSongCount = :songCount, remoteSnapshotCheckedAt = :checkedAt, conflictRemoteSignature = NULL, conflictRemoteName = NULL, conflictRemoteSongIds = NULL, syncPending = 0 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun resolvePlaylistConflictUseServer(playlistId: String, name: String, signature: String, songCount: Int, checkedAt: Long)

    @Query("UPDATE playlists SET ownership = 'LOCAL', serverPlaylistId = NULL, syncPending = 0, deletePending = 0, conflictRemoteSignature = NULL, conflictRemoteName = NULL, conflictRemoteSongIds = NULL WHERE id = :playlistId AND ownership = 'SERVER' AND conflictRemoteSignature = 'SERVER_MISSING'")
    suspend fun resolveMissingServerPlaylistAsLocal(playlistId: String): Int

    @Transaction
    suspend fun replaceServerPlaylistSnapshot(
        playlistId: String,
        songs: List<PlaylistSongEntity>,
        songCount: Int,
        signature: String,
    checkedAt: Long,
    name: String
    ): Boolean {
        if (getPlaylistOwnership(playlistId) != PlaylistEntity.OWNERSHIP_SERVER) return false
        purgePlaylistSongs(playlistId)
        songs.chunked(400).forEach { insertPlaylistSongs(it) }
        updateRemoteSnapshot(playlistId, songCount, signature, checkedAt, name)
        return true
    }

    @Transaction
    suspend fun purgeServerPlaylist(playlistId: String): Boolean {
        if (getPlaylistOwnership(playlistId) != PlaylistEntity.OWNERSHIP_SERVER) return false
        purgePlaylistSongs(playlistId)
        purgePlaylist(playlistId)
        return true
    }

    @Query("SELECT playlist_songs.entryId, playlist_songs.songId, playlist_songs.position, songs.providerId, songs.providerRemoteId, CASE WHEN songs.providerId = 'navidrome' THEN songs.providerRemoteId ELSE songs.navidromeId END AS serverSongId, playlist_songs.syncedToServer FROM playlist_songs INNER JOIN songs ON songs.remoteId = playlist_songs.songId WHERE playlist_songs.playlistId = :playlistId ORDER BY playlist_songs.position ASC, playlist_songs.entryId ASC")
    suspend fun getPlaylistSongsForSync(playlistId: String): List<PlaylistSongForSync>

    @Update
    suspend fun updatePlaylistMemberships(memberships: List<PlaylistSongEntity>)

    @Query("UPDATE playlists SET serverPlaylistId = :serverId, syncPending = 1 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun markPlaylistCreated(playlistId: String, serverId: String)

    @Query("UPDATE playlists SET syncPending = 0 WHERE id = :playlistId")
    suspend fun markPlaylistSynced(playlistId: String)

    @Query("UPDATE playlist_songs SET syncedToServer = 1 WHERE playlistId = :playlistId AND entryId IN (:entryIds)")
    suspend fun markPlaylistSongsSynced(playlistId: String, entryIds: List<Long>)

    @Query("UPDATE playlists SET syncPending = 1 WHERE ownership = 'SERVER' AND id IN (SELECT playlistId FROM playlist_songs WHERE songId = :songId)")
    suspend fun markContainingServerPlaylistsPending(songId: String)

    @Query("UPDATE playlist_songs SET syncedToServer = 0 WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun markPlaylistSongPending(playlistId: String, songId: String)

    @Query("UPDATE playlists SET syncPending = 1 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun markPlaylistPending(playlistId: String)

    @Query("UPDATE playlists SET deletePending = 1 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun markPlaylistDeletePending(playlistId: String)

    @Query("UPDATE playlists SET syncPending = 1 WHERE id = :playlistId AND ownership = 'SERVER'")
    suspend fun markPlaylistContentPending(playlistId: String)

    @Query("UPDATE playlists SET syncPending = 0 WHERE id = :playlistId")
    suspend fun clearPlaylistSyncPending(playlistId: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun nextSongPosition(playlistId: String): Int

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun purgePlaylistSongs(playlistId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId IN (:songIds)")
    suspend fun removePlaylistSongs(playlistId: String, songIds: List<String>)

    @Query("DELETE FROM playlist_songs WHERE entryId IN (:entryIds)")
    suspend fun removePlaylistEntries(entryIds: List<Long>)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun purgePlaylist(playlistId: String)
}
