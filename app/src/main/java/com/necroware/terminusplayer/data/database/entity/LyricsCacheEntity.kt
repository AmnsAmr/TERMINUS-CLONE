package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val cacheKey: String,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val fetchedAtEpochMs: Long
)

/** Associates local-only lyric cache entries with the files that keep them alive. */
@Entity(
    tableName = "lyrics_cache_files",
    primaryKeys = ["fileIdentity"],
    indices = [Index(value = ["cacheKey"])],
    foreignKeys = [
        ForeignKey(
            entity = LyricsCacheEntity::class,
            parentColumns = ["cacheKey"],
            childColumns = ["cacheKey"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class LyricsCacheFileEntity(
    val fileIdentity: String,
    val cacheKey: String
)
