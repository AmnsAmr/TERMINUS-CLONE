package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(
    tableName = "songs",
    indices = [
        Index(value = ["providerId", "providerRemoteId"], unique = true),
        Index(value = ["artist"]),
        Index(value = ["album"]),
        Index(value = ["folderPath"])
    ]
)
data class SongEntity(
    @PrimaryKey val remoteId: String,
    val providerId: String,
    @ColumnInfo(defaultValue = "''") val providerRemoteId: String = remoteId,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: String,
    val duration: Long,
    val uriString: String,
    val dateAdded: Long,
    val trackNumber: Int = 0,
    val year: Int = 0,
    val folderPath: String = "",
    val sizeBytes: Long = 0L,
    val navidromeId: String? = null
)
