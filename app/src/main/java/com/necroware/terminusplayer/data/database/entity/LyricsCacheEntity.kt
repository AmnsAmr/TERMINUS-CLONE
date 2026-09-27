package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey val cacheKey: String,
    val plainLyrics: String?,
    val syncedLyrics: String?,
    val fetchedAtEpochMs: Long
)
