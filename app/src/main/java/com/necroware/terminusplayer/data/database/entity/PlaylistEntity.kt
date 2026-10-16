package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import androidx.room.Index

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val serverPlaylistId: String? = null,
    @ColumnInfo(defaultValue = "0") val syncPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val deletePending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val remoteSongCount: Int = 0,
    @ColumnInfo(defaultValue = "NULL") val ownerUsername: String? = null,
    @ColumnInfo(defaultValue = "NULL") val remoteContentSignature: String? = null,
    @ColumnInfo(defaultValue = "0") val remoteSnapshotSongCount: Int = 0,
    @ColumnInfo(defaultValue = "0") val remoteSnapshotCheckedAt: Long = 0L,
    @ColumnInfo(defaultValue = "'LOCAL'") val ownership: String = OWNERSHIP_LOCAL,
    @ColumnInfo(defaultValue = "NULL") val remoteSnapshotName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val conflictRemoteSignature: String? = null,
    @ColumnInfo(defaultValue = "NULL") val conflictRemoteName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val conflictRemoteSongIds: String? = null
) {
    companion object {
        const val OWNERSHIP_LOCAL = "LOCAL"
        const val OWNERSHIP_SERVER = "SERVER"
        const val CONFLICT_SERVER_MISSING = "SERVER_MISSING"
    }
}

/**
 * Composite-keyed join row rather than a List<Long> column — keeps the
 * songId FK queryable/indexable and lets [position] preserve the imported
 * playlist's original track order (Room doesn't guarantee list-column
 * ordering the way a real row-per-entry table does).
 */
@Entity(tableName = "playlist_songs", indices = [Index(value = ["playlistId", "position"])])
data class PlaylistSongEntity(
    val playlistId: String,
    val songId: String,
    val position: Int,
    @ColumnInfo(defaultValue = "0") val syncedToServer: Boolean = false,
    @PrimaryKey(autoGenerate = true) val entryId: Long = 0L
)
