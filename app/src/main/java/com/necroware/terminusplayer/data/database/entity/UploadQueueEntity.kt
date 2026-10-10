package com.necroware.terminusplayer.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "upload_queue")
data class UploadQueueEntity(
    @PrimaryKey val songId: String,
    val uriString: String,
    val state: String, // "WAITING", "UPLOADING", "SUCCESS", "FAILED", "PERMANENT_FAILURE"
    val attemptCount: Int,
    val addedAtEpochMs: Long,
    val title: String,
    val errorMessage: String? = null,
    val forceConvert: Boolean = false
)
