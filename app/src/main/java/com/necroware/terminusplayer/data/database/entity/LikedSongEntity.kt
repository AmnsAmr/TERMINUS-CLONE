package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val songId: String,
    val likedAt: Long,
    @ColumnInfo(defaultValue = "1") val isLiked: Boolean = true,
    @ColumnInfo(defaultValue = "'LOCAL'") val ownership: String = OWNERSHIP_LOCAL,
    @ColumnInfo(defaultValue = "NULL") val remoteSongId: String? = null,
    @ColumnInfo(defaultValue = "0") val syncPending: Boolean = false,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = likedAt
) {
    companion object {
        const val OWNERSHIP_LOCAL = "LOCAL"
        const val OWNERSHIP_SERVER = "SERVER"
    }
}
